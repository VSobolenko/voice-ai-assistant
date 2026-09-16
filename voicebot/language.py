"""Определение языка реплики и деление её по алфавитам.

Зачем это нужно. Голосовой движок читает текст по правилам **своего** языка:
русский голос произносит английские слова русскими фонемами, отсюда и «акцент
робота». Никакая инструкция для модели это не лечит — лечится только тем, чтобы
английский текст попадал к английскому голосу, а русский к русскому.

Как определяется язык. По алфавиту: русский и английский пишутся разными
буквами, поэтому для этой пары языков не нужен никакой угадывающий детектор —
достаточно посчитать буквы. Это работает даже на одной фразе и не ошибается.

Отдельная тонкость — фразы, где языки перемешаны: «Слово apple значит яблоко».
Такую фразу нельзя отдать одному голосу: русский голос испортит именно то слово,
ради которого фраза и сказана. Поэтому фраза режется по алфавиту на куски, и
каждый кусок читает свой голос.
"""

from __future__ import annotations

import re

RU = "ru"
EN = "en"

_CYRILLIC = re.compile(r"[А-Яа-яЁё]")
_LATIN = re.compile(r"[A-Za-z]")
# Куски текста: буквы одного алфавита либо всё остальное (пробелы, цифры, знаки)
_TOKEN = re.compile(r"[A-Za-z]+|[А-Яа-яЁё]+|[^A-Za-zА-Яа-яЁё]+")


def count(text: str) -> tuple[int, int]:
    """Сколько в тексте букв кириллицы и латиницы."""
    return len(_CYRILLIC.findall(text)), len(_LATIN.findall(text))


def detect(text: str) -> str | None:
    """Язык текста: :data:`RU`, :data:`EN` или ``None``, если букв нет вовсе.

    ``None`` — это не ошибка: фраза из одних цифр или знаков («42!») одинаково
    звучит на любом языке, и голос для неё можно не менять.
    """
    cyrillic, latin = count(text)
    if cyrillic == 0 and latin == 0:
        return None
    return RU if cyrillic >= latin else EN


def split(text: str, min_letters: int = 2) -> list[tuple[str, str]]:
    """Разбить фразу на куски по алфавиту: ``[(язык, текст), ...]``.

    ``min_letters`` защищает от дёрганья голосом на каждой мелочи: кусок с одной
    латинской буквой («витамин D») отдельным голосом не читается, он остаётся
    с соседним куском. Кусок, который не удалось присоединить (текст вообще без
    букв), возвращается как ``(RU, ...)`` — язык тут ни на что не влияет.
    """
    parts: list[list] = []  # [язык, текст, сколько букв]
    for token in _TOKEN.findall(text):
        language = detect(token)
        if not parts:
            parts.append([language or RU, token, _letters(token)])
            continue
        if language is None or language == parts[-1][0]:
            # Ни цифры, ни знаки не повод менять голос посреди фразы
            parts[-1][1] += token
            parts[-1][2] += _letters(token)
            continue
        parts.append([language, token, _letters(token)])

    merged = _merge_short(parts, max(1, min_letters))
    return [(language, chunk.strip()) for language, chunk, _ in merged if chunk.strip()]


def _letters(token: str) -> int:
    cyrillic, latin = count(token)
    return cyrillic + latin


def _merge_short(parts: list[list], min_letters: int) -> list[list]:
    """Приклеить слишком короткие куски к соседу, чтобы голос не дёргался зря."""
    result: list[list] = []
    for part in parts:
        if result and part[2] < min_letters:
            result[-1][1] += part[1]
            result[-1][2] += part[2]
            continue
        result.append(part)
    # Короткий первый кусок мог остаться в одиночестве: отдаём его следующему
    if len(result) > 1 and result[0][2] < min_letters:
        first = result.pop(0)
        result[0][1] = first[1] + result[0][1]
        result[0][2] += first[2]

    # Склейка коротких кусков могла снова поставить рядом два куска одного языка
    # («витамин D полезен»). Голос у них один и тот же, и дробить фразу незачем:
    # каждый лишний кусок — это отдельный вызов синтеза и лишний стык.
    glued: list[list] = []
    for part in result:
        if glued and glued[-1][0] == part[0]:
            glued[-1][1] += part[1]
            glued[-1][2] += part[2]
            continue
        glued.append(part)
    return glued
