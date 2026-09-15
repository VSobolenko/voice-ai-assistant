"""Микрофон и динамики.

Здесь живёт вся «физика»: запись с определением конца фразы (VAD по громкости)
и воспроизведение звука. Уши и рот работают только с числами, поэтому
телефонию потом можно подставить вместо этого файла.
"""

from __future__ import annotations

import time

import numpy as np
import sounddevice as sd

from .config import settings

# Частота, которую удобно отдавать в распознавание речи
TARGET_RATE = 16000
BLOCK_MS = 30


# ---------------------------------------------------------------------------
# Воспроизведение
# ---------------------------------------------------------------------------
def resample(samples: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    """Простая линейная передискретизация — для голоса качества достаточно."""
    if src_rate == dst_rate or samples.size == 0:
        return samples
    count = max(1, int(round(samples.size * dst_rate / src_rate)))
    src_idx = np.arange(samples.size, dtype=np.float64)
    dst_idx = np.linspace(0, samples.size - 1, count)
    return np.interp(dst_idx, src_idx, samples.astype(np.float64)).astype(np.int16)


def _out_rate() -> int:
    return int(sd.query_devices(settings.output_device, kind="output")["default_samplerate"])


def play_pcm(samples: np.ndarray, rate: int, on_start=None) -> None:
    """Проиграть моно int16 и дождаться окончания.

    on_start вызывается в момент, когда звук реально пошёл в динамики —
    это позволяет честно измерить «время до первого звука».
    """
    out = _out_rate()
    prepared = resample(samples, rate, out)
    if on_start is not None:
        on_start()
    sd.play(prepared, out, device=settings.output_device)
    sd.wait()


def play_mp3(data: bytes, on_start=None) -> None:
    """Декодировать mp3 (так отдаёт edge-tts) и проиграть."""
    import miniaudio

    decoded = miniaudio.decode(data, output_format=miniaudio.SampleFormat.SIGNED16)
    samples = np.frombuffer(decoded.samples, dtype=np.int16)
    if decoded.nchannels > 1:
        samples = samples.reshape(-1, decoded.nchannels).mean(axis=1).astype(np.int16)
    play_pcm(samples, decoded.sample_rate, on_start)


class OutputChannel:
    """Непрерывный поток вывода звука.

    Обычный sd.play() включает и выключает устройство на каждый фрагмент, поэтому
    между фразами слышны провалы и щелчки. Здесь устройство открыто один раз, а
    фразы просто дописываются в него подряд: стыки звучат как обычная пауза между
    предложениями, а не как обрыв связи.

    Используется только рот (см. voicebot/mouth.py). Для разовых звуков вроде
    beep() хватает sd.play().
    """

    def __init__(self) -> None:
        self.rate = _out_rate()
        self._stream = sd.OutputStream(
            samplerate=self.rate,
            channels=1,
            dtype="int16",
            device=settings.output_device,
            latency="low",
        )
        self._stream.start()

    def write(self, samples: np.ndarray, rate: int) -> None:
        """Дописать фрагмент. Метод блокирующий: он же задаёт темп проигрывания."""
        if self._stream is None or samples.size == 0:
            return
        prepared = resample(samples, rate, self.rate)
        # shape (кадры, каналы) — так формат не зависит от версии sounddevice
        self._stream.write(prepared.reshape(-1, 1))

    def drain(self) -> None:
        """Дать устройству доиграть то, что уже лежит в его буфере.

        Без этого последний слог обрезается: write() возвращает управление раньше,
        чем звук реально дошёл до динамиков.
        """
        if self._stream is None:
            return
        try:
            latency = self._stream.latency
            if isinstance(latency, (tuple, list)):
                latency = max(latency)
            latency = float(latency)
        except Exception:  # noqa: BLE001 — не критично, берём оценку сверху
            latency = 0.3
        time.sleep(min(2.0, max(0.15, latency)))

    def close(self) -> None:
        if self._stream is None:
            return
        try:
            self._stream.stop()
            self._stream.close()
        finally:
            self._stream = None


def beep(freq: int = 880, ms: int = 80, volume: float = 0.2) -> None:
    """Короткий сигнал «слушаю тебя»."""
    out = _out_rate()
    t = np.linspace(0, ms / 1000, int(out * ms / 1000), endpoint=False)
    tone = (np.sin(2 * np.pi * freq * t) * volume * 32767).astype(np.int16)
    fade = min(len(tone) // 4, out // 100)  # сглаживаем щелчок на краях
    if fade > 0:
        tone[:fade] = (tone[:fade] * np.linspace(0, 1, fade)).astype(np.int16)
        tone[-fade:] = (tone[-fade:] * np.linspace(1, 0, fade)).astype(np.int16)
    sd.play(tone, out, device=settings.output_device)
    sd.wait()


# ---------------------------------------------------------------------------
# Запись
# ---------------------------------------------------------------------------
class Microphone:
    """Поток с микрофона. Частота — 16 кГц, если железо её поддерживает."""

    def __init__(self, block_ms: int = BLOCK_MS) -> None:
        self._block_ms = block_ms
        self._stream: sd.InputStream | None = None
        self.rate = TARGET_RATE
        self.blocksize = 0

    def __enter__(self) -> "Microphone":
        try:
            self.rate = TARGET_RATE
            self.blocksize = max(1, int(self.rate * self._block_ms / 1000))
            self._stream = sd.InputStream(
                samplerate=self.rate,
                channels=1,
                dtype="int16",
                blocksize=self.blocksize,
                device=settings.input_device,
            )
            self._stream.start()
        except Exception:
            # Микрофон не умеет 16 кГц — пишем на его родной частоте
            self.rate = int(sd.query_devices(settings.input_device, kind="input")["default_samplerate"])
            self.blocksize = max(1, int(self.rate * self._block_ms / 1000))
            self._stream = sd.InputStream(
                samplerate=self.rate,
                channels=1,
                dtype="int16",
                blocksize=self.blocksize,
                device=settings.input_device,
            )
            self._stream.start()
        return self

    def __exit__(self, *exc_info) -> None:
        if self._stream is not None:
            self._stream.stop()
            self._stream.close()
            self._stream = None

    def read(self) -> np.ndarray:
        """Один блок сэмплов (моно int16)."""
        assert self._stream is not None, "Микрофон не открыт"
        data, _overflow = self._stream.read(self.blocksize)
        return data.reshape(-1)


def _rms(block: np.ndarray) -> float:
    return float(np.sqrt(np.mean(block.astype(np.float64) ** 2))) if block.size else 0.0


def record_question(
    start_timeout: float | None = None,
    silence_seconds: float | None = None,
    max_seconds: float = 20.0,
    calibration_seconds: float = 0.6,
) -> np.ndarray | None:
    """Ждать речь и записать её до паузы.

    Возвращает моно int16 на 16 кГц или None, если за start_timeout никто не заговорил.

    Логика простая, и это важный концепт: «уши» не знают, когда человек закончил.
    Мы считаем громкость блока и решаем, что тишина длиной silence_seconds = конец фразы.
    В продакшене вместо этого ставят VAD-модель и семантический endpointing.

    Три тонкости — все три взяты из практики «бот обрывает на полуслове»:

    1. Порогов два. Начать запись нужно отчётливым звуком (start_threshold), а
       продолжаться она может и более тихим (hold_threshold): человек затихает
       к концу фразы, и если считать это паузой, последнее слово теряется.
    2. Терпение зависит от длины сказанного. Короткая фраза («сколько ног у»)
       почти наверняка недослушана — там паузу ждём дольше.
    3. Хвост обрезается с запасом (vad_tail_seconds). Обрезать сразу за последним
       громким блоком нельзя: у слова «паука» конец тихий, и «ка» пропало бы.
    """
    # Значения по умолчанию берём из .env: см. voicebot/config.py
    if start_timeout is None:
        start_timeout = settings.vad_start_timeout
    if silence_seconds is None:
        silence_seconds = settings.vad_silence_seconds
    block_seconds = BLOCK_MS / 1000

    with Microphone() as mic:
        # 1. Замеряем шум комнаты: нужен, чтобы порог подстроился под ваш микрофон
        noise: list[float] = []
        for _ in range(max(1, int(calibration_seconds / block_seconds))):
            noise.append(_rms(mic.read()))
        floor = float(np.percentile(noise, 90)) if noise else 0.0
        start_threshold = max(floor * 3.0, float(settings.vad_min_threshold))
        # Продолжать запись можно звуком в полтора раза тише стартового порога
        hold_threshold = max(floor * 2.0, start_threshold * 0.6)

        # 2. Ждём начало речи, сохраняя блоки «до», чтобы не срезать первый слог
        pre_buffer: list[np.ndarray] = []
        waited = 0.0
        speaking = False
        while not speaking:
            block = mic.read()
            waited += block_seconds
            if waited > start_timeout:
                return None
            pre_buffer.append(block)
            if len(pre_buffer) > 8:
                pre_buffer.pop(0)
            loud_run = sum(1 for b in pre_buffer[-3:] if _rms(b) > start_threshold)
            speaking = loud_run >= 2

        # 3. Пишем, пока не наступит пауза
        collected: list[np.ndarray] = list(pre_buffer)
        speech_seconds = 0.0
        silent_for = 0.0
        recorded = 0.0
        while True:
            block = mic.read()
            collected.append(block)
            recorded += block_seconds
            if _rms(block) > hold_threshold:
                silent_for = 0.0
                speech_seconds += block_seconds
            else:
                silent_for += block_seconds

            # Если сказано мало, человек, скорее всего, ещё думает: ждём дольше
            needed = silence_seconds
            if speech_seconds < settings.vad_short_speech_seconds:
                needed = max(needed, settings.vad_silence_after_short)
            if silent_for >= needed or recorded >= max_seconds:
                break
        rate = mic.rate

    # Обрезаем хвостовую тишину, но с запасом: последнее слово часто затихает
    last_loud = 0
    for index, block in enumerate(collected):
        if _rms(block) > hold_threshold:
            last_loud = index
    tail_blocks = max(3, int(settings.vad_tail_seconds / block_seconds))
    collected = collected[: min(len(collected), last_loud + tail_blocks)] or collected

    return resample(np.concatenate(collected), rate, TARGET_RATE)


def list_devices() -> str:
    """Текстовый список устройств — чтобы выбрать номер для .env."""
    return str(sd.query_devices())


def default_device_name(kind: str) -> str:
    """Имя устройства по умолчанию. kind: 'input' или 'output'."""
    return str(sd.query_devices(kind=kind)["name"])


def cooldown(seconds: float = 0.35) -> None:
    """Пауза после ответа, чтобы микрофон не услышал хвост собственного голоса."""
    time.sleep(seconds)
