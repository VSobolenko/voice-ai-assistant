"""Уши: превращают звук в текст (STT / ASR).

Бэкенды переключаются в .env переменной STT_BACKEND:
  google  — бесплатный веб-распознаватель Google, нужен интернет, хорошо знает русский;
  vosk    — офлайн и быстро, качество ниже (нужен пакет vosk + модель, см. README);
  whisper — офлайн и точно, но тяжелее (нужен faster-whisper).

Концепт: LLM звука не слышит. Мы обязаны сначала получить текст, и уже его
отдавать мозгу. Именно поэтому качество распознавания напрямую определяет,
насколько «умным» выглядит ассистент.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from .config import ROOT, settings

_vosk_model = None
_whisper_model = None


class SttError(RuntimeError):
    """Понятная человеку ошибка распознавания."""


# ---------------------------------------------------------------------------
# Google — бесплатно, без ключа, нужен интернет
# ---------------------------------------------------------------------------
def _google(pcm: np.ndarray, language: str) -> str:
    import speech_recognition as sr

    recognizer = sr.Recognizer()
    audio = sr.AudioData(pcm.tobytes(), 16000, 2)  # 2 байта на сэмпл = int16
    try:
        return recognizer.recognize_google(audio, language=language)
    except sr.UnknownValueError:
        return ""  # разобрали не всё/шум — это нормально, просто попросим повторить
    except sr.RequestError as exc:
        raise SttError(
            f"Не достучались до сервиса распознавания Google ({exc}).\n"
            "Проверьте интернет, VPN/блокировки, либо переключитесь на офлайн-уши:\n"
            "  в .env поставьте STT_BACKEND=vosk (см. README, раздел про офлайн)"
        ) from exc


# ---------------------------------------------------------------------------
# Vosk — офлайн
# ---------------------------------------------------------------------------
def _vosk(pcm: np.ndarray, language: str) -> str:
    global _vosk_model
    try:
        from vosk import KaldiRecognizer, Model, SetLogLevel
    except ImportError as exc:
        raise SttError("Нет пакета vosk. Установите: pip install vosk") from exc

    if _vosk_model is None:
        SetLogLevel(-1)
        candidates = [ROOT / "models" / "vosk-model-small-ru-0.22", Path("D:/Python/TestVoice/models/vosk-model-small-ru-0.22")]
        model_dir = next((p for p in candidates if p.exists()), None)
        if model_dir is None:
            raise SttError(
                "Не нашёл модель Vosk. Скачайте vosk-model-small-ru-0.22 с https://alphacephei.com/vosk/models "
                "и распакуйте в папку models в корне проекта."
            )
        _vosk_model = Model(str(model_dir))

    # Модель Vosk привязана к языку: русской моделью английскую речь не разобрать.
    # Об этом честно предупреждаем, иначе будет казаться, что «уши сломались».
    if language.split("-")[0] != "ru":
        raise SttError(
            f"Офлайн-модель Vosk загружена только для русского, а нужен {language}.\n"
            "Для занятий английским поставьте STT_BACKEND=google или whisper."
        )

    recognizer = KaldiRecognizer(_vosk_model, 16000)
    recognizer.AcceptWaveform(pcm.tobytes())
    return json.loads(recognizer.FinalResult()).get("text", "").strip()


# ---------------------------------------------------------------------------
# faster-whisper — офлайн, лучшее качество
# ---------------------------------------------------------------------------
def _whisper(pcm: np.ndarray, language: str) -> str:
    global _whisper_model
    try:
        from faster_whisper import WhisperModel
    except ImportError as exc:
        raise SttError("Нет пакета faster-whisper. Установите: pip install faster-whisper") from exc

    if _whisper_model is None:
        print("[уши] загружаю модель Whisper (первый раз скачивается ~250 МБ)...")
        _whisper_model = WhisperModel("small", device="cpu", compute_type="int8")

    segments, _info = _whisper_model.transcribe(
        pcm.astype(np.float32) / 32768.0,
        language=language.split("-")[0],
        vad_filter=True,
    )
    return " ".join(segment.text.strip() for segment in segments).strip()


_BACKENDS = {"google": _google, "vosk": _vosk, "whisper": _whisper}


def transcribe(pcm: np.ndarray, language: str | None = None) -> str:
    """Звук (моно int16 @16 кГц) -> текст.

    Язык передаётся явно: в занятии английским распознавание переключается на
    ходу, а ``settings.stt_language`` читается только один раз при импорте.
    """
    backend = _BACKENDS.get(settings.stt_backend)
    if backend is None:
        raise SttError(f"Неизвестный STT_BACKEND={settings.stt_backend!r}. Доступно: {', '.join(_BACKENDS)}")
    return backend(pcm, language or settings.stt_language)
