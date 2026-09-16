"""Диагностика: python check.py

Ничего не настраивает и не запускает диалог — только проверяет, что всё работает,
и подсказывает, какие значения нужно поправить в .env.

Полезно запускать первым делом после заполнения .env: это дешевле и быстрее,
чем искать причину по молчащему микрофону.
"""

from __future__ import annotations

import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from voicebot import audio  # noqa: E402
from voicebot.assistant import self_test  # noqa: E402
from voicebot.mouth import list_edge_voices  # noqa: E402


def main() -> int:
    code = self_test()

    print("\n[дополнительно] доступные голоса синтеза речи")
    for prefix, setting, note in (
        ("ru", "TTS_VOICE", "ими читается русский текст; здесь нужен русский голос"),
        ("en", "TTS_VOICE_EN", "ими читается английский текст. Голоса Multilingual "
                               "читают и русский, но с английским акцентом"),
    ):
        print(f"  {setting} — {note}:")
        try:
            for voice in list_edge_voices(prefix):
                print("   ", voice)
        except Exception as exc:  # noqa: BLE001
            print(f"    не удалось получить список: {type(exc).__name__}: {exc}")
    print("    выбранные голоса должны быть из этих списков; послушать их: python voices.py")

    print("\n[дополнительно] звуковые устройства")
    try:
        print(audio.list_devices())
        print('    микрофон по умолчанию: ' + audio.default_device_name("input"))
        print('    динамики по умолчанию: ' + audio.default_device_name("output"))
        print("    эти же устройства считаются 'по умолчанию', когда INPUT_DEVICE и OUTPUT_DEVICE пусты")
    except Exception as exc:  # noqa: BLE001
        print(f"    не удалось получить список: {type(exc).__name__}: {exc}")

    print("\nПроверка завершена. Запуск диалога: python main.py")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
