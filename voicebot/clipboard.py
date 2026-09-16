"""Копирование в буфер обмена по голосовой команде.

Почему это не инструмент для модели. «Скопируй» означает «дословно», а модель —
генератор текста: она вправе переформулировать, сократить «лишнее» или оборвать
длинный ответ. Текст, который надо скопировать, уже лежит в ассистенте в точности
таким, каким его произнесли, поэтому фразу разбирает сам ассистент и модель к
этому делу не привлекается: получается мгновенно, дословно и работает даже без
ключа API.

Инструмент понадобился бы в одном случае — если текст задаёт человек, а не
ассистент («скопируй фразу …»). Здесь такого нет намеренно: городить вызов
модели ради строки, которая и так известна, — лишний круг и лишний риск.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys

# Что именно копируем. Строки-ключи нужны, чтобы одинаково называть это в
# консоли, в приложении и в тестах.
ANSWER = "ответ"
SEARCH = "поиск"
ADDRESS = "адрес"
JOURNAL = "журнал"

TARGET_NAMES = {
    ANSWER: "последний ответ",
    SEARCH: "результат поиска",
    ADDRESS: "адрес страницы",
    JOURNAL: "журнал разговора",
}


class ClipboardError(RuntimeError):
    """Не получилось положить текст в буфер обмена."""


# ---------------------------------------------------------------------------
# Разбор фразы
# ---------------------------------------------------------------------------
# Глаголы «скопировать». «скапир» и «копир» — на случай, если распознавание речи
# услышало слово неточно: на слух «скопируй» и «скапируй» неотличимы.
_COPY_VERB = re.compile(r"(скопир|скапир|копир)", re.IGNORECASE)
# Если глагола нет, но человека явно просят положить что-то в буфер, нужен ещё
# и глагол действия: «положи в буфер», «скинь в буфер обмена».
_BUFFER = re.compile(r"(буфер|обмен)", re.IGNORECASE)
_PUT = re.compile(r"(полож|положи|закин|скин|добав|отправ|запиш)", re.IGNORECASE)
# «Что такое буфер обмена?» — это вопрос к модели, а не команда.
_QUESTION = re.compile(r"^\s*(что|как|зачем|почему|где|когда|кто|сколько|расскажи)\b", re.IGNORECASE)

_ADDRESS = re.compile(r"(адрес|ссылк|url|ю ар эл)", re.IGNORECASE)
_SEARCH = re.compile(r"(поиск|результат|выдач|нашёл|нашел|найден)", re.IGNORECASE)
_JOURNAL = re.compile(r"(журнал|лог|истори|разговор|переписк|диалог|всё|все )", re.IGNORECASE)


def wants_copy(phrase: str) -> bool:
    """Просит ли человек скопировать что-нибудь в буфер обмена."""
    if not phrase or not phrase.strip():
        return False
    if _QUESTION.match(phrase):
        return False
    if _COPY_VERB.search(phrase):
        return True
    return bool(_BUFFER.search(phrase) and _PUT.search(phrase))


def target(phrase: str) -> str:
    """Что копировать, если человек сказал «скопируй …».

    Порядок проверок — от самого конкретного к общему: в «скопируй адрес из
    результатов поиска» важнее именно адрес.
    """
    if _ADDRESS.search(phrase):
        return ADDRESS
    if _SEARCH.search(phrase):
        return SEARCH
    if _JOURNAL.search(phrase):
        return JOURNAL
    return ANSWER


# ---------------------------------------------------------------------------
# Собственно буфер
# ---------------------------------------------------------------------------
def _command() -> tuple[list[str], str]:
    """Утилита системы, которая умеет класть текст в буфер, и его кодировка.

    Кодировка здесь не мелочь: на Windows clip.exe ждёт UTF-16LE. Если отдать
    ему UTF-8, русский текст превращается в «╨Ь╨╕╨╜╤Б╨║» — проверено на живом
    буфере, обратное чтение через GetClipboardData.
    """
    if sys.platform == "win32":
        return ["clip"], "utf-16-le"
    if sys.platform == "darwin":
        return ["pbcopy"], "utf-8"
    if shutil.which("xclip"):
        return ["xclip", "-selection", "clipboard"], "utf-8"
    if shutil.which("xsel"):
        return ["xsel", "--clipboard", "--input"], "utf-8"
    raise ClipboardError("нет утилиты для буфера: поставьте xclip или xsel")


def copy(text: str) -> None:
    """Положить текст в буфер обмена. Ошибку сообщаем исключением.

    На Windows многострочный текст ложится в буфер с переводами строк CRLF —
    это делает сам clip.exe, и это правильный вид для вставки в windows-программы.
    """
    if not text or not text.strip():
        raise ClipboardError("текст пустой — копировать нечего")
    command, encoding = _command()
    try:
        finished = subprocess.run(
            command,
            input=text.encode(encoding),
            capture_output=True,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise ClipboardError(f"не удалось запустить {command[0]}: {exc}") from exc
    if finished.returncode != 0:
        raise ClipboardError(f"{command[0]} вернул код {finished.returncode}")
