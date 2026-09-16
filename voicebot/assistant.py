"""Главный цикл: слушаем -> понимаем -> думаем -> отвечаем голосом.

Схема ровно такая же, как будет с телефоном. Разница только в том, откуда
берётся звук: сейчас из микрофона, в продакшене — из SIP-потока оператора связи.
Поэтому audio.py и assistant.py разделены: заменить нужно только источник звука.
"""

from __future__ import annotations

import re
import threading
import time

from . import audio, clipboard, tools
from .brain import Brain, BrainError
from .config import settings
from .ears import SttError, transcribe
from .mouth import Mouth, pop_sentences

EXIT_WORDS = {
    "пока", "выход", "выход!", "стоп", "до свидания", "хватит", "закройся", "quit", "exit",
}

# Адрес страницы в тексте: и в выдаче поиска, и в «Содержимое <url>:» от web_fetch
URL = re.compile(r"https?://\S+")
# Знаки, которые могут прилипнуть к ссылке справа: «Содержимое https://cbr.ru/…/:»
URL_TAIL = ":.,;!?»)]}"


def first_address(text: str) -> str:
    """Первый адрес в тексте — без знаков препинания, прилипших к нему справа.

    Двоеточие тут не придирка: в строке «Содержимое https://cbr.ru/…:» оно стоит
    вплотную к ссылке, и без чистки в буфер уезжал бы адрес с хвостом из одного
    символа — по такой ссылке браузер уже не откроет страницу.
    """
    for raw in URL.findall(text):
        address = raw.rstrip(URL_TAIL)
        if address:
            return address
    return ""


GREETING = "Привет! Я голосовой ассистент. Задай любой вопрос голосом, и я отвечу."


class VoiceAssistant:
    def __init__(self) -> None:
        self.brain = Brain()
        self.mouth = Mouth()
        # Что можно потом скопировать по просьбе «скопируй это». Здесь именно
        # произнесённый текст, а не ответ модели: вслух и в буфер попадает одно и то же.
        self.last_answer = ""
        self.last_search = ""
        self.last_address = ""
        # Прогрев запускаем сразу: первый запрос к модели в новом процессе
        # стоит около 10 секунд, и это время лучше потратить пока бот здоровается.
        self._warmup_thread: threading.Thread | None = self._start_warmup()

    def _start_warmup(self) -> threading.Thread:
        def work() -> None:
            try:
                seconds = self.brain.warmup()
                print(f"[мозг] соединение с моделью прогрето за {seconds:.1f} с")
            except Exception as exc:  # noqa: BLE001 — прогрев не обязателен
                print(f"[мозг] прогрев не удался: {type(exc).__name__}: {exc}")

        thread = threading.Thread(target=work, daemon=True)
        thread.start()
        return thread

    def _wait_warmup(self, timeout: float = 15.0) -> None:
        """Дождаться прогрева, но не дольше таймаута (чтобы не задерживать старт)."""
        thread, self._warmup_thread = self._warmup_thread, None
        if thread is not None and thread.is_alive():
            thread.join(timeout)

    # ------------------------------------------------------------------
    # Одна реплика: стримим ответ и озвучиваем по предложениям
    # ------------------------------------------------------------------
    def answer(self, user_text: str) -> str:
        started = time.perf_counter()
        first_audio: float | None = None
        buffer = ""
        spoken: list[str] = []
        # Текст, сказанный ПОСЛЕ последнего обращения к инструменту.
        # Именно он считается ответом: реплики до поиска — это только обещания.
        tail_parts: list[str] = []
        tool_seconds = 0.0
        searches = 0
        phrases = 0

        def mark_started() -> None:
            """Сработает в момент, когда звук реально пошёл в динамики."""
            nonlocal first_audio
            if first_audio is None:
                first_audio = time.perf_counter() - started

        print("Бот: ", end="", flush=True)
        # speak() отдаёт озвучку отдельным потокам: пока звучит одна фраза,
        # следующая уже синтезируется, поэтому между ними нет паузы
        speech = self.mouth.speak(on_start=mark_started)
        try:
            with speech:

                def say(text: str) -> None:
                    nonlocal phrases
                    if not text.strip():
                        return
                    phrases += 1
                    speech.say(text)

                for kind, payload in self.brain.stream(user_text):
                    if kind == "search":
                        # Модель ушла за данными. Пока она ищет, надо честно
                        # предупредить о паузе — иначе решат, что связь пропала.
                        searches += 1
                        tail_parts.clear()
                        print(f"\n[инструмент] {payload}")
                        if not buffer.strip() and not spoken:
                            say(settings.tool_filler)
                        continue

                    if kind == "tool_time":
                        tool_seconds += float(payload)
                        continue

                    if kind == "tool_result":
                        # Имя инструмента здесь не нужно: и выдача поиска, и текст
                        # прочитанной страницы — это «то, что нашли».
                        _, result = payload
                        self._remember_result(result)
                        continue

                    print(payload, end="", flush=True)
                    buffer += payload
                    tail_parts.append(payload)
                    # Первую фразу отдаём как можно раньше, остальные укрупняем
                    min_chars = settings.tts_min_chars if spoken else settings.tts_min_chars_first
                    ready, buffer = pop_sentences(buffer, min_chars)
                    for sentence in ready:
                        spoken.append(sentence)
                        say(sentence)

                # Хвост без точки на конце тоже надо произнести — но внутри блока,
                # пока рот ещё открыт
                if buffer.strip():
                    spoken.append(buffer.strip())
                    say(buffer)
        except BrainError as exc:
            print(f"\n[мозг] ошибка: {exc}")
            self.mouth.say("Извини, я не смог получить ответ. Проверь связь и повтори вопрос.")
            return ""

        # Модель могла только пообещать поискать и не сказать ничего по существу.
        # Человек не должен остаться в тишине — говорим об этом вслух сами.
        meaningful = "".join(tail_parts).strip(" \n\t.,!?…—-")
        if not meaningful:
            if searches:
                self.mouth.say("Не получилось найти точный ответ в интернете. Спроси, пожалуйста, иначе.")
            else:
                self.mouth.say("Не понял вопрос. Повтори, пожалуйста, или переформулируй.")
            print("[ответ] содержательного ответа не было — сказал об этом вслух")
        print()

        total = time.perf_counter() - started
        if first_audio is not None:
            detail = f", из них {tool_seconds:.1f} с на поиск ({searches} вызов.)" if searches else ""
            print(
                f"[задержка] первый звук {first_audio:.1f} с, ответ целиком {total:.1f} с"
                f"{detail}; реплик озвучено: {phrases}"
            )
        result = " ".join(spoken)
        if result.strip():
            # Запоминаем именно то, что услышал человек: это и уйдёт в буфер.
            self.last_answer = result
        return result

    # ------------------------------------------------------------------
    # Служебные реплики: ассистент выполняет их сам, без модели
    # ------------------------------------------------------------------
    def _remember_result(self, result: str) -> None:
        """Запомнить, что нашли, — чтобы потом ответить на «скопируй результат»."""
        self.last_search = result
        address = first_address(result)
        if address:
            # Первая ссылка в выдаче — обычно та, по которой стоит вернуться.
            self.last_address = address

    def copy_command(self, text: str) -> bool:
        """Выполнить команду копирования, если человек её произнёс.

        Возвращает True, если реплика обработана и в модель идти уже не нужно.
        Копирование делает сам ассистент: он единственный держит текст ровно в том
        виде, в каком тот прозвучал, и ему для этого не нужен ни ключ, ни сеть.
        В историю разговора команда не попадает — модели нечего на неё отвечать.
        """
        if not clipboard.wants_copy(text):
            return False

        kind = clipboard.target(text)
        content = {
            clipboard.ANSWER: self.last_answer,
            clipboard.SEARCH: self.last_search,
            clipboard.ADDRESS: self.last_address,
            clipboard.JOURNAL: self.brain.transcript(),
        }[kind]

        print(f"[буфер] копирую: {clipboard.TARGET_NAMES[kind]}")
        if not content.strip():
            print("[буфер] копировать нечего")
            self.mouth.say("Пока нечего копировать.")
            return True
        try:
            clipboard.copy(content)
        except clipboard.ClipboardError as exc:
            print(f"[буфер] не получилось: {exc}")
            self.mouth.say("Не получилось скопировать.")
            return True

        print(f"[буфер] готово, {len(content)} символов")
        self.mouth.say("Скопировал.")
        return True

    # ------------------------------------------------------------------
    # Режим 1: разговор голосом (бесконечный цикл)
    # ------------------------------------------------------------------
    def run_voice(self) -> None:
        print("=" * 66)
        print("Голосовой ассистент. Просто говорите в микрофон.")
        print("Бот отвечает, когда вы замолкаете. Скажите «пока» или нажмите Ctrl+C.")
        print("=" * 66)
        self.mouth.say(GREETING)
        # Пока звучало приветствие, соединение с моделью успело прогреться
        self._wait_warmup()

        while True:
            try:
                audio.beep()
                print("\n[слушаю...]")
                pcm = audio.record_question()
                if pcm is None:
                    print("[слушаю] тишина — попробуй ещё раз.")
                    continue

                try:
                    text = transcribe(pcm, settings.stt_language)
                except SttError as exc:
                    print(f"\n[уши] {exc}\n")
                    continue

                if not text:
                    print("[уши] не разобрал слова, повтори громче.")
                    continue

                print(f"Ты: {text}")
                if text.strip().lower().strip("!.,?") in EXIT_WORDS:
                    self.mouth.say("Пока! Хорошего дня.")
                    print("[бот] завершаю работу.")
                    return

                if self.copy_command(text):
                    audio.cooldown()
                    continue

                self.answer(text)
                audio.cooldown()
            except KeyboardInterrupt:
                print("\n[бот] остановлено вручную.")
                return

    # ------------------------------------------------------------------
    # Режим 2: тот же цикл, но текст с клавиатуры (для отладки без микрофона)
    # ------------------------------------------------------------------
    def run_text(self) -> None:
        print("=" * 66)
        print("Текстовый режим: печатайте вопросы, отвечать бот всё равно будет голосом.")
        print("Пустая строка или Ctrl+C — выход.")
        print("=" * 66)
        # Здесь не ждём долго: пока человек набирает вопрос, прогрев успеет закончиться
        self._wait_warmup(timeout=5.0)
        while True:
            try:
                text = input("\nТы: ").strip()
            except (EOFError, KeyboardInterrupt):
                print("\n[бот] остановлено.")
                return
            if not text:
                return
            if text.lower() in EXIT_WORDS:
                self.mouth.say("Пока!")
                return
            if self.copy_command(text):
                continue
            self.answer(text)


# ----------------------------------------------------------------------
# Самопроверка: проверяет всё, кроме микрофона
# ----------------------------------------------------------------------
def self_test(tools_only: bool = False) -> int:
    import numpy as np

    if not tools_only:
        print("[1/5] настройки (меняются в файле .env)")
        print(f"      модель      : {settings.model} @ {settings.base_url}")
        print(f"      уши (STT)   : {settings.stt_backend} / {settings.stt_language}")
        print(f"      рот (TTS)   : {settings.tts_backend}")
        print(f"      голос RU    : {settings.tts_voice}")
        print(f"      голос EN    : {settings.tts_voice_en}")
        if settings.tts_voice == settings.tts_voice_en:
            print("      ВНИМАНИЕ    : голоса совпадают — английский будет читаться")
            print("                    русским голосом (или наоборот), это и есть «акцент»")
        print(f"      режим ввода : {settings.input_mode}")
        state = "включены" if settings.enable_tools else "выключены (ENABLE_TOOLS=0)"
        budget = f", бюджет {settings.tool_budget_seconds:.0f} с" if settings.enable_tools else ""
        print(f"      инструменты : {state} (максимум {settings.tool_max_rounds} раунда{budget})")
        key_state = "задан" if settings.api_key else "НЕ ЗАДАН -> впишите в .env"
        print(f"      ключ API    : {key_state}")
        try:
            print(f"      микрофон    : {audio.default_device_name('input')}")
            print(f"      динамики    : {audio.default_device_name('output')}")
        except Exception as exc:  # noqa: BLE001
            print(f"      звук        : недоступен ({type(exc).__name__}: {exc})")

        print("[2/5] синтез речи (mp3 -> динамики)")
        # Два голоса, а не один: английский текст должен звучать английским
        # голосом, иначе получится тот самый «русский акцент».
        checks = [
            ("по-русски", "Раз, два, три. Проверка русского голоса.", settings.tts_voice),
            ("по-английски", "Hello! This is the English voice. Nice to meet you.", settings.tts_voice_en),
        ]
        mouth = Mouth()
        for title, phrase, voice in checks:
            print(f"      {title}: {voice}")
            audio.beep()
            mouth.say(phrase)
        if settings.tts_voice == settings.tts_voice_en:
            print("      ок, обе фразы прозвучали одним и тем же (многоязычным) голосом")
        else:
            print("      ок, вы должны были услышать две фразы разными голосами")

        print("[3/5] распознавание речи (mp3 -> текст, микрофон не нужен)")
        try:
            import asyncio

            import edge_tts
            import miniaudio

            # Для распознавания берём русскую фразу: язык распознавания задан
            # в STT_LANGUAGE, и проверяем именно его.
            phrase = "Раз, два, три. Проверка распознавания речи."

            async def synth() -> bytes:
                communicate = edge_tts.Communicate(phrase, settings.tts_voice)
                data = bytearray()
                async for chunk in communicate.stream():
                    if chunk["type"] == "audio":
                        data += chunk["data"]
                return bytes(data)

            decoded = miniaudio.decode(asyncio.run(synth()), output_format=miniaudio.SampleFormat.SIGNED16)
            samples = np.frombuffer(decoded.samples, dtype=np.int16)
            if decoded.nchannels > 1:
                samples = samples.reshape(-1, decoded.nchannels).mean(axis=1).astype(np.int16)
            samples = audio.resample(samples, decoded.sample_rate, audio.TARGET_RATE)
            recognized = transcribe(samples)
            print(f"      услышали: {recognized!r}")
            if not recognized:
                print("      ВНИМАНИЕ: распознавание вернуло пусто — проверьте STT_BACKEND")
        except Exception as exc:  # noqa: BLE001
            print(f"      не удалось: {type(exc).__name__}: {exc}")

    print("[4/5] инструменты (поиск и чтение страниц, без участия LLM)")
    tools.self_check()

    if tools_only:
        return 0

    print("[5/5] мозг (запрос к LLM)")
    try:
        print(f"      ответ модели: {Brain().ping()!r}")
    except SystemExit as exc:
        print(f"      пропущено: {exc}")
    except Exception as exc:  # noqa: BLE001
        print(f"      ошибка: {type(exc).__name__}: {exc}")

    print("\nГотово. Запускайте:  python main.py")
    return 0
