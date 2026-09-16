"""Прослушать голоса и выбрать лучший.

Зачем инструмент. «Звучит роботом» и «читает английский с акцентом» — это оценки
на слух, их нельзя выяснить по коду. Поэтому скрипт рендерит один и тот же текст
разными голосами в файлы mp3, а вы слушаете и выбираете.

    .venv\\Scripts\\python.exe voices.py            все голоса
    .venv\\Scripts\\python.exe voices.py en         только английские
    .venv\\Scripts\\python.exe voices.py multi      только многоязычные

Результат: папка ``samples/``. Для каждого голоса два файла — английский текст и
русский. Слушайте оба: многоязычный голос интересен именно тем, читает ли он
русский так же хорошо, как английский. Если да, он лучше двух отдельных голосов —
одну и ту же фразу с русским словом и английским произносит один человек, а не
два разных.

Выбранное имя вписывается в ``.env``:

    TTS_VOICE=...      голос для русского текста
    TTS_VOICE_EN=...   голос для английского текста
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import edge_tts  # noqa: E402

ROOT = Path(__file__).resolve().parent
SAMPLES = ROOT / "samples"

EN_TEXT = (
    "Hello! I am your English teacher. Repeat after me: the weather is lovely today, "
    "and I would like a cup of coffee."
)
RU_TEXT = (
    "Привет! Я твой учитель английского. Давай заниматься вместе: "
    "сегодня хорошая погода, и я хочу чашку кофе."
)

# Русские и английские голоса плюс многоязычные (Multilingual). Многоязычные
# читают оба языка, но русский у них с английским акцентом — слушайте перед тем,
# как ставить их в TTS_VOICE.
CANDIDATES = [
    "ru-RU-SvetlanaNeural",
    "ru-RU-DmitryNeural",
    # Мужские английские голоса: из них выбирается TTS_VOICE_EN
    "en-US-GuyNeural",
    "en-US-AndrewNeural",
    "en-US-BrianNeural",
    "en-US-ChristopherNeural",
    "en-US-EricNeural",
    "en-US-RogerNeural",
    "en-GB-RyanNeural",
    "en-GB-ThomasNeural",
    "en-AU-WilliamNeural",
    "en-US-AriaNeural",
    "en-US-AvaNeural",
    "en-US-EmmaMultilingualNeural",
    "en-US-AndrewMultilingualNeural",
    "en-US-AvaMultilingualNeural",
    "en-US-BrianMultilingualNeural",
    "en-GB-SoniaNeural",
]

GROUPS = {
    "ru": ("ru-RU",),
    "en": ("en-US", "en-GB", "en-AU"),
    "male": ("Guy", "Andrew", "Brian", "Christopher", "Eric", "Roger", "Ryan",
             "Thomas", "William", "Dmitry"),
    "multi": ("Multilingual",),
}


def chosen(argument: str) -> list[str]:
    if not argument:
        return CANDIDATES
    keys = GROUPS.get(argument.lower())
    if keys is None:
        return [name for name in CANDIDATES if argument.lower() in name.lower()]
    return [name for name in CANDIDATES if any(key in name for key in keys)]


async def render(voice: str, text: str, path: Path) -> int:
    """Озвучить текст и сохранить в файл. Возвращает размер файла в байтах."""
    await edge_tts.Communicate(text, voice).save(str(path))
    return path.stat().st_size


async def main(argument: str) -> int:
    voice_names = chosen(argument)
    if not voice_names:
        print(f"Ничего не нашлось по запросу «{argument}».")
        return 1

    SAMPLES.mkdir(exist_ok=True)
    print(f"Голосов: {len(voice_names)}. Файлы кладу в {SAMPLES}\n")
    print(f"{'голос':38} {'английский':>11} {'русский':>9}")
    print("-" * 62)
    failures = 0
    for voice in voice_names:
        sizes: list[str] = []
        for language, text in (("en", EN_TEXT), ("ru", RU_TEXT)):
            path = SAMPLES / f"{voice}.{language}.mp3"
            try:
                size = await render(voice, text, path)
                sizes.append(f"{size // 1024} КБ")
            except Exception as exc:  # noqa: BLE001 — голос может быть недоступен
                sizes.append("—")
                path.unlink(missing_ok=True)
                failures += 1
                print(f"{voice:38} {language}: не вышло ({type(exc).__name__}: {exc})")
        print(f"{voice:38} {sizes[0]:>11} {sizes[1]:>9}")

    print()
    if len(voice_names) > 1:
        print("Проверьте русский столбец у многоязычных голосов: если файл пустой,")
        print("этот голос русский не читает и годится только для английского.")
    print("Слушайте из папки samples и впишите выбранный голос в .env.")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "")))
