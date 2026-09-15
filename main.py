"""Точка входа.

Запуск один:  python main.py

Всё поведение настраивается в одном месте — файл .env в корне проекта.
Полный список параметров с пояснениями: voicebot/config.py и .env.example.

Например, чтобы набирать вопросы с клавиатуры вместо микрофона,
поставьте в .env строку INPUT_MODE=text.
"""

from __future__ import annotations

import sys

# Русский текст в консоли Windows без этого может падать с UnicodeEncodeError
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")
# Ввод тоже: иначе русский текст из перенаправленного stdin приходит мусором
try:
    sys.stdin.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):  # например, если stdin закрыт
    pass

from voicebot.assistant import VoiceAssistant, self_test  # noqa: E402
from voicebot.config import ROOT, settings  # noqa: E402


def main() -> int:
    # По желанию можно прогонять самопроверку при каждом запуске (STARTUP_CHECK=1)
    if settings.startup_check:
        self_test()

    if not settings.api_key:
        print("Не задан DEEPSEEK_API_KEY — без него мозг работать не может.")
        print(f"Откройте файл {ROOT / '.env'} и впишите ключ в строку DEEPSEEK_API_KEY=...")
        print("Ключ выдаётся здесь: https://platform.deepseek.com/api_keys")
        print("Проверить всё остальное без ключа: python check.py")
        return 1

    assistant = VoiceAssistant()
    if settings.input_mode == "text":
        assistant.run_text()
    else:
        assistant.run_voice()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
