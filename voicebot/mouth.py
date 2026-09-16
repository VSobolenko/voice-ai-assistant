"""Рот: превращает текст в звук (TTS).

Бэкенды (TTS_BACKEND в .env):
  edge — нейросетевые голоса Microsoft Edge: звучит почти как человек, бесплатно,
         без ключа, но нужен интернет; при сбое фраза пропускается;
  sapi — встроенный голос Windows (Microsoft Irina): офлайн, зато «робот»;
  auto — сначала edge, при сбое автоматический откат на sapi.

Главная тонкость — как произносить ответ, который приходит по кускам.
Наивный вариант («синтезировать фразу → проиграть → синтезировать следующую»)
даёт слышимую паузу между предложениями: пока звучит первая фраза, вторая ещё
даже не отправлена в синтез. Поэтому диалог говорит через speak(): фразы
синтезируются на опережение в отдельном потоке, а звук пишется в один
непрерывный поток вывода (см. audio.OutputChannel).

Вторая тонкость — язык текста. Движок читает текст по правилам **своего** языка:
русский голос произносит английские слова русскими фонемами, и получается тот
самый «акцент». Поэтому голос выбирается по языку текста (TTS_VOICE для русского,
TTS_VOICE_EN для английского), а смешанная фраза режется по алфавитам — иначе
русский голос испортил бы именно то английское слово, ради которого фраза сказана.
"""

from __future__ import annotations

import asyncio
import queue
import re
import threading
from contextlib import AbstractContextManager

import numpy as np

from . import language
from .audio import OutputChannel, play_pcm
from .config import settings

# Конец предложения = точка/!/?/… за которым идёт пробел или конец строки.
# Требование пробела нужно, чтобы не разрывать числа вида 2.5
_SENTENCE_RE = re.compile(r"[.!?…]+(?=\s|$)")


def voice_for(language_code: str | None) -> str:
    """Голос под язык текста.

    ``None`` (текст без букв: цифры, знаки) остаётся на русском голосе — цифры
    читаются одинаково на любом языке, и менять голос из-за них незачем.
    """
    if language_code == language.EN:
        return settings.tts_voice_en
    return settings.tts_voice


def parts_for(text: str) -> list[tuple[str | None, str]]:
    """Разложить реплику на куски: ``[(язык, текст), ...]``.

    Если голоса для русского и английского совпадают (например, выбран один
    многоязычный голос), резать фразу незачем: один голос одинаково хорошо
    прочитает оба языка, а лишние стыки синтеза только испортят речь.
    """
    if not settings.tts_mix_voices or settings.tts_voice == settings.tts_voice_en:
        return [(language.detect(text), text)]
    return language.split(text, settings.tts_mix_min_letters)


def log_mixed(parts: list[tuple[str | None, str]]) -> None:
    """Показать в консоли, что фраза ушла к разным голосам.

    Без этой строки «русский акцент» нечем отлаживать на слух: слышно, что
    английское слово прочитано неправильно, а видно только, что реплик стало
    больше. Здесь же написано, какой голос получил какой кусок.
    """
    if len(parts) < 2:
        return
    shown = " | ".join(f"{voice_for(code)}: {chunk}" for code, chunk in parts)
    print(f"[рот] фраза на разные голоса ({len(parts)} куска) → {shown}")


# Тишина по краям синтеза: edge-tts добавляет около секунды «хвоста» в каждой
# реплике. Для одного предложения это просто пауза, но когда фраза режется на
# куски ради разных голосов, тишина приезжает с каждым куском — замерено 3
# секунды провала на фразе длиной 3 секунды. Поэтому края подрезаем, оставляя
# чуть-чуть: внутри фразы меньше, в конце реплики больше (там нужна пауза).
LEAD_KEEP_MS = 20
INNER_TAIL_MS = 90


def trim_silence(samples: np.ndarray, rate: int, tail_ms: int) -> np.ndarray:
    """Убрать тишину по краям, оставив небольшую паузу в конце.

    Порог берём от пика, а не абсолютный: громкая запись и тихая должны
    обрабатываться одинаково. Порог низкий, чтобы не срезать тихие согласные
    на конце слова («…s», «…f»).
    """
    if samples.size == 0:
        return samples
    peak = int(np.abs(samples).max())
    if peak == 0:
        return samples
    loud = np.where(np.abs(samples) > max(120, peak // 200))[0]
    if loud.size == 0:
        return samples
    start = max(0, int(loud[0]) - int(rate * LEAD_KEEP_MS / 1000))
    stop = min(samples.size, int(loud[-1]) + int(rate * tail_ms / 1000))
    return samples[start:stop]


class TtsError(RuntimeError):
    """Не удалось синтезировать речь."""


# ---------------------------------------------------------------------------
# Разбиение текста на реплики
# ---------------------------------------------------------------------------
def pop_sentences(buffer: str, min_chars: int = 16) -> tuple[list[str], str]:
    """Вытащить из буфера реплики, которые уже можно озвучивать.

    Это нужно, чтобы начать говорить, не дожидаясь конца ответа целиком:
    LLM стримит токены, мы озвучиваем их по мере появления готовых фраз.

    Две тонкости, обе влияют на то, слышно ли паузы в разговоре:

    * min_chars — короткие предложения склеиваются с соседними. Каждый вызов
      синтеза — это отдельный стык и сброс интонации, поэтому лучше отдать
      одну длинную реплику, чем три обрывка.
    * реплика отдаётся, только если позади неё уже есть текст. Иначе последняя
      фраза ответа превращалась бы в отдельный вызов синтеза ради двух слов.
    """
    ready: list[str] = []
    rest = buffer.lstrip()

    while True:
        match = _SENTENCE_RE.search(rest)
        if match is None:
            break

        # Достраиваем кандидата до минимальной длины: несколько коротких предложений
        # лучше произнести одним куском — так меньше стыков и ровнее интонация
        while len(rest[: match.end()]) < min_chars:
            nxt = _SENTENCE_RE.search(rest, match.end())
            if nxt is None:
                break
            match = nxt

        candidate = rest[: match.end()].strip()
        tail = rest[match.end() :].strip()

        if not tail:
            if len(candidate) >= min_chars:
                ready.append(candidate)
                rest = ""
            break

        if len(candidate) < min_chars and len(tail) < min_chars:
            # И сама фраза короткая, и продолжения пока мало — подождём ещё текста
            break

        ready.append(candidate)
        rest = tail

    return ready, rest


def list_edge_voices(locale_prefix: str = "ru") -> list[str]:
    """Доступные нейросетевые голоса edge-tts."""
    import edge_tts

    async def _all():
        return await edge_tts.list_voices()

    voices = asyncio.run(_all())
    return sorted(v["ShortName"] for v in voices if v["Locale"].startswith(locale_prefix))


# ---------------------------------------------------------------------------
# Рот
# ---------------------------------------------------------------------------
class Mouth:
    """Текст -> звук. Помнит состояние голоса (например, что edge недоступен)."""

    def __init__(self) -> None:
        self.backend = settings.tts_backend
        # Причина, по которой нейросетевой голос отключён (обычно нет интернета)
        self._edge_problem: str | None = None
        self._sapi_warned = False

    # -- состояние ------------------------------------------------------
    @property
    def edge_available(self) -> bool:
        return self.backend != "sapi" and self._edge_problem is None

    def note_edge_failure(self, exc: Exception) -> None:
        """Запомнить сбой нейросетевого голоса и сказать об этом один раз."""
        if self._edge_problem is not None:
            return
        self._edge_problem = f"{type(exc).__name__}: {exc}"
        print(f"\n[рот] нейросетевой голос недоступен: {self._edge_problem}")
        if self.backend == "auto":
            print("[рот] переключаюсь на системный голос Windows — он звучит роботом.\n")
        else:
            print("[рот] режим TTS_BACKEND=edge: фразы, которые не удалось озвучить, будут пропущены.\n")

    # -- синтез ---------------------------------------------------------
    def synthesize(
        self, text: str, voice: str | None = None, tail_ms: int | None = None
    ) -> tuple[np.ndarray, int]:
        """Текст -> моно int16 и частота дискретизации. Только edge-tts.

        Отдаём именно сэмплы, а не играем сразу: это позволяет синтезировать
        следующую фразу, пока звучит предыдущая. Голос выбирается по языку
        текста — иначе русский движок прочитает английский русскими фонемами.
        """
        import edge_tts
        import miniaudio

        if self._edge_problem is not None:
            raise TtsError(self._edge_problem)

        async def _synth() -> bytes:
            communicate = edge_tts.Communicate(
                text, voice or settings.tts_voice, rate=settings.tts_rate
            )
            buffer = bytearray()
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    buffer += chunk["data"]
            return bytes(buffer)

        data = asyncio.run(_synth())
        if not data:
            raise TtsError("пустой ответ от сервиса синтеза")

        decoded = miniaudio.decode(data, output_format=miniaudio.SampleFormat.SIGNED16)
        samples = np.frombuffer(decoded.samples, dtype=np.int16)
        if decoded.nchannels > 1:
            samples = samples.reshape(-1, decoded.nchannels).mean(axis=1).astype(np.int16)
        wanted = settings.tts_tail_ms if tail_ms is None else tail_ms
        return trim_silence(samples, decoded.sample_rate, wanted), decoded.sample_rate

    # -- разовая фраза --------------------------------------------------
    def say(self, text: str, on_start=None) -> None:
        """Озвучить фразу целиком: приветствие, подсказка, сообщение об ошибке."""
        text = text.strip()
        if not text:
            return
        parts = parts_for(text)
        log_mixed(parts)
        for index, (language_code, chunk) in enumerate(parts):
            # Внутри разрезанной фразы пауза должна быть короче, чем в конце реплики,
            # иначе речь распадается на отдельные слова.
            tail = settings.tts_tail_ms if index == len(parts) - 1 else INNER_TAIL_MS
            if self.edge_available:
                try:
                    samples, rate = self.synthesize(chunk, voice_for(language_code), tail)
                    play_pcm(samples, rate, on_start)
                    continue
                except Exception as exc:  # noqa: BLE001 — сеть, сервис, что угодно
                    self.note_edge_failure(exc)
                    if self.backend == "edge":
                        return  # строгий режим: чужой голос не подставляем
            # Нейросетевой голос отказал — договариваем системным, чтобы фраза
            # не пропала целиком посреди предложения.
            self.say_with_sapi(chunk, on_start, language_code)

    def say_with_sapi(self, text: str, on_start=None, language_code: str | None = None) -> None:
        """Озвучить системным голосом Windows (офлайн, но робот)."""
        import pyttsx3

        if not self._sapi_warned:
            print("[рот] использую системный голос Windows (SAPI) — офлайн-запас.")
            self._sapi_warned = True
        # Язык текста важен и здесь: английский голос Windows читает английский
        # правильно, а русский на английском тексте даёт тот самый «акцент».
        want = "en" if language_code == language.EN else "ru"
        expected = "english" if want == "en" else "russian"
        engine = pyttsx3.init()
        target = None
        for voice in engine.getProperty("voices") or []:
            languages = [str(item).lower() for item in (voice.languages or [])]
            name = str(voice.name).lower()
            if any(want in item for item in languages) or expected in name:
                target = voice.id
                break
        if target:
            engine.setProperty("voice", target)
        elif want == "ru":
            print("[рот] ВНИМАНИЕ: русский голос не найден — речь будет с английским акцентом.")
        engine.setProperty("rate", self._sapi_rate())
        if on_start is not None:
            on_start()
        engine.say(text)
        engine.runAndWait()
        engine.stop()

    # -- диалог ---------------------------------------------------------
    def speak(self, on_start=None) -> AbstractContextManager:
        """Озвучка потока фраз без пауз между ними.

        Используется так:

            with mouth.speak(on_start=mark) as speech:
                speech.say("первая фраза")
                speech.say("вторая фраза")

        Синтез идёт на опережение в фоновом потоке, поэтому вторая фраза успевает
        приготовиться, пока звучит первая. При выходе из блока рот дожидается
        окончания речи.
        """
        if not self.edge_available:
            return _SequentialSpeech(self, on_start)
        try:
            return _PipelineSpeech(self, on_start)
        except Exception as exc:  # noqa: BLE001 — например, не открылось устройство вывода
            print(f"[рот] непрерывный вывод звука недоступен ({type(exc).__name__}: {exc}).")
            print("[рот] говорю по фразам: между предложениями возможны паузы.")
        return _SequentialSpeech(self, on_start)

    @staticmethod
    def _sapi_rate() -> int:
        """' +20%' -> 240 слов в минуту (база 200)."""
        try:
            percent = int(settings.tts_rate.strip().rstrip("%"))
        except ValueError:
            percent = 0
        return max(80, min(400, 200 + int(200 * percent / 100)))


# ---------------------------------------------------------------------------
# Запасной путь: SAPI говорит сам, фразу за фразой
# ---------------------------------------------------------------------------
class _SequentialSpeech(AbstractContextManager):
    """Используется, когда нейросетевой голос недоступен.

    Очереди здесь невозможны: системный синтез сам пишет в устройство. Поэтому
    фразы звучат по одной, а паузы между ними неизбежны.
    """

    def __init__(self, mouth: Mouth, on_start=None) -> None:
        self._mouth = mouth
        self._on_start = on_start
        self.speaking = False

    def say(self, text: str) -> None:
        if not text.strip():
            return
        for language_code, chunk in parts_for(text):
            self.speaking = True
            self._mouth.say_with_sapi(chunk, self._on_start, language_code)

    def __exit__(self, *exc_info) -> bool:
        return False


# ---------------------------------------------------------------------------
# Основной путь: синтез на опережение + непрерывный вывод
# ---------------------------------------------------------------------------
class _PipelineSpeech(AbstractContextManager):
    """Озвучка без пауз между фразами.

    Два потока:

      tts-synth — берёт фразу, синтезирует её в сэмплы и кладёт в очередь звука.
                  Работает на опережение: пока звучит текущая фраза, следующая
                  уже готова, поэтому слышимой паузы не возникает;
      tts-play  — забирает сэмплы и пишет их в непрерывный поток вывода.

    Очереди ограничены по размеру (TTS_PREFETCH): это тормоз для модели —
    не даём ей убежать вперёд и не копим минуты звука в памяти.
    """

    def __init__(self, mouth: Mouth, on_start=None) -> None:
        self._mouth = mouth
        self._on_start = on_start
        self.speaking = False
        self._announced = False
        self._closed = False
        self._stopped = threading.Event()

        depth = max(1, settings.tts_prefetch)
        # Очередь текста просторнее звуковой: пока звучит речь (это медленно),
        # модель успевает наговорить несколько фраз, и тормозить её незачем
        self._texts: queue.Queue = queue.Queue(maxsize=max(4, depth * 2))
        self._audio: queue.Queue = queue.Queue(maxsize=depth)
        self._channel = OutputChannel()

        self._synth = threading.Thread(target=self._synth_loop, name="tts-synth", daemon=True)
        self._play = threading.Thread(target=self._play_loop, name="tts-play", daemon=True)
        self._synth.start()
        self._play.start()

    # -- внешний интерфейс ----------------------------------------------
    def say(self, text: str) -> None:
        text = text.strip()
        if not text or self._closed or self._stopped.is_set():
            return
        self.speaking = True
        # Смешанную фразу кладём в очередь несколькими кусками: каждый уйдёт к
        # своему голосу, а очередь сохранит порядок, поэтому стыка не слышно.
        parts = parts_for(text)
        log_mixed(parts)
        for index, (language_code, chunk) in enumerate(parts):
            tail = settings.tts_tail_ms if index == len(parts) - 1 else INNER_TAIL_MS
            if not self._put(self._texts, (chunk, language_code, tail), self._synth):
                print("[рот] синтез остановился — фраза пропущена")
                return

    def __exit__(self, exc_type, exc, tb) -> bool:
        # При исключении (например, Ctrl+C) не доигрываем очередь, а обрываем речь
        self._close(abort=exc_type is not None)
        return False

    def _close(self, abort: bool = False) -> None:
        if self._closed:
            return
        self._closed = True
        if abort:
            self._stopped.set()
            _drain_queue(self._texts)
            _drain_queue(self._audio)
        self._put(self._texts, None, self._synth, timeout=30)
        self._synth.join(timeout=30)
        self._put(self._audio, None, self._play, timeout=30)
        self._play.join(timeout=60)
        if not abort:
            self._channel.drain()  # дать доиграть последний слог
        self._channel.close()

    # -- потоки ----------------------------------------------------------
    def _synth_loop(self) -> None:
        while True:
            item = self._texts.get()
            if item is None or self._stopped.is_set():
                return
            text, language_code, tail_ms = item
            try:
                samples, rate = self._mouth.synthesize(text, voice_for(language_code), tail_ms)
            except Exception as exc:  # noqa: BLE001 — сеть, сервис, что угодно
                self._mouth.note_edge_failure(exc)
                if self._mouth.backend == "edge":
                    continue  # строгий режим: чужой голос не подставляем
                item = ("sapi", text, language_code)
            else:
                item = ("pcm", samples, rate)
            if not self._put(self._audio, item, self._play):
                return

    def _play_loop(self) -> None:
        while True:
            item = self._audio.get()
            if item is None:
                return
            if self._stopped.is_set():
                continue
            self._announce()
            if item[0] == "sapi":
                # Синтез отказал: даём доиграть буферу и говорим системным голосом,
                # чтобы порядок фраз не сломался
                self._channel.drain()
                self._mouth.say_with_sapi(item[1], None, item[2])
            else:
                self._channel.write(item[1], item[2])

    def _announce(self) -> None:
        """Сообщить наружу, что звук реально пошёл в динамики (для метрики задержки)."""
        if self._announced:
            return
        self._announced = True
        if self._on_start is not None:
            self._on_start()

    # -- вспомогательное -------------------------------------------------
    @staticmethod
    def _put(target: queue.Queue, item, thread: threading.Thread, timeout: float = 5.0) -> bool:
        """Положить в очередь, но не зависнуть, если поток-получатель уже умер."""
        while thread.is_alive():
            try:
                target.put(item, timeout=timeout)
                return True
            except queue.Full:
                continue
        return False


def _drain_queue(target: queue.Queue) -> None:
    """Опустошить очередь, не дожидаясь обработки."""
    while True:
        try:
            target.get_nowait()
        except queue.Empty:
            return
