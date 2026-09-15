"""Мозг: диалог с LLM + вызов инструментов (function calling).

Три важных приёма:

1. stream=True — не ждём ответ целиком, а получаем текст по токенам. Это половина
   всей борьбы за «живость» голосового бота.

2. Инструменты. Модель не ходит в интернет сама: она возвращает структурированную
   заявку вида {"name": "web_search", "arguments": {"query": "курс доллара"}},
   наш код её выполняет, а результат возвращается модели новым сообщением с ролью
   "tool". Такой обмен может повторяться несколько раундов: поиск, потом чтение
   страницы, и только потом ответ человеку.

3. Защита от «служебного мусора». Если инструменты в запросе недоступны (например,
   кончился бюджет времени), модель иногда пишет вызов прямо в тексте:
   <tool_call>{"name": "web_fetch"}... Озвучивать это нельзя, поэтому поток
   проходит через фильтр _ServiceMarkupFilter, который вырезает такие блоки.

Наружу мы отдаём поток событий, а не просто текст, потому что о паузе на поиск
надо честно сказать вслух:

    ("text",  "кусок ответа")     — озвучить
    ("search", "ищу в интернете") — сейчас будет пауза, человека надо предупредить
"""

from __future__ import annotations

import json
import time
from typing import Iterator

from . import tools
from .config import settings

Event = tuple[str, str]

# Что сообщаем модели, когда время на поиск закончилось: она обязана ответить
BUDGET_NOTICE = (
    "Инструменты отключены: превышен лимит времени на поиск. "
    "Ответь сейчас, используя уже полученную информацию. "
    "Если точных данных нет, назови то, что знаешь, и коротко предупреди о неточности."
)

# Приписывается к запросу на время, когда инструменты недоступны.
# Это НЕ сохраняется в истории: заметка нужна только для текущего ответа.
NO_TOOLS_NOTE = (
    "Инструменты сейчас недоступны. Не вызывай их, не пиши служебную разметку "
    "и не обещай поискать в интернете. Ответь прямо сейчас, коротко и по существу."
)


class BrainError(RuntimeError):
    """Модель недоступна или вернула ошибку."""


class Brain:
    def __init__(self) -> None:
        from openai import OpenAI

        self._client = OpenAI(api_key=settings.require_api_key(), base_url=settings.base_url)
        self.history: list[dict] = [{"role": "system", "content": settings.system_prompt}]

    # ------------------------------------------------------------------
    # Публичный интерфейс
    # ------------------------------------------------------------------
    def stream(self, user_text: str) -> Iterator[Event]:
        """Отправить реплику пользователя и стримить ответ событиями."""
        self.history.append({"role": "user", "content": user_text})
        self._trim()

        spoken: list[str] = []
        deadline = time.monotonic() + settings.tool_budget_seconds
        known_tool_calls: set[tuple[str, str]] = set()

        for round_index in range(settings.tool_max_rounds + 1):
            # Инструменты доступны, пока не исчерпаны раунды и бюджет времени
            allow_tools = (
                settings.enable_tools
                and round_index < settings.tool_max_rounds
                and time.monotonic() < deadline
            )

            text_parts: list[str] = []
            tool_calls: dict = {}
            cleaner = _ServiceMarkupFilter()

            try:
                for chunk in self._request(allow_tools):
                    if not chunk.choices:
                        continue
                    delta = chunk.choices[0].delta
                    if delta is None:
                        continue
                    if delta.content:
                        visible = cleaner.feed(delta.content)
                        if visible:
                            text_parts.append(visible)
                            spoken.append(visible)
                            yield ("text", visible)
                    if delta.tool_calls:
                        _collect_tool_calls(tool_calls, delta.tool_calls)
            except Exception as exc:  # noqa: BLE001
                raise BrainError(f"{type(exc).__name__}: {exc}") from exc

            tail = cleaner.flush()
            if tail:
                text_parts.append(tail)
                spoken.append(tail)
                yield ("text", tail)

            # Некоторые модели повторяют тот же самый вызов — не даём зациклиться.
            # Сравниваем имя вместе с аргументами: повтор с другим запросом
            # (например, после неудачного поиска) — это нормально и полезно.
            fresh = {
                key: call
                for key, call in tool_calls.items()
                if (call["name"], call["arguments"]) not in known_tool_calls
            }
            if not tool_calls:
                break
            if not fresh:
                print("  [модель повторила тот же вызов — отвечаю по найденному]")
                break
            tool_calls = fresh

            # Модель попросила инструменты: сохраняем её заявку в историю...
            self.history.append(
                {
                    "role": "assistant",
                    "content": "".join(text_parts) or None,
                    "tool_calls": [
                        {
                            "id": call["id"],
                            "type": "function",
                            "function": {"name": call["name"], "arguments": call["arguments"]},
                        }
                        for call in tool_calls.values()
                    ],
                }
            )

            # ...и отвечаем результатами на каждый запрос.
            # На каждый вызов обязан быть ответ с тем же id, иначе API откажет.
            out_of_time = time.monotonic() >= deadline
            for call in tool_calls.values():
                known_tool_calls.add((call["name"], call["arguments"]))
                if out_of_time:
                    result = BUDGET_NOTICE
                    print("  [время на поиск истекло — отвечаю по найденному]")
                else:
                    yield ("search", tools.describe_call(call["name"], _parse_arguments(call["arguments"])))
                    tool_started = time.perf_counter()
                    result = tools.execute(call["name"], call["arguments"])
                    # Отдельное событие с чистым временем работы инструмента:
                    # иначе в метрику попадёт ещё и время ответа модели
                    yield ("tool_time", f"{time.perf_counter() - tool_started:.1f}")
                    print(indent_block(f"[результат] {result}"))
                self.history.append({"role": "tool", "tool_call_id": call["id"], "content": result})

        if spoken:
            self.history.append({"role": "assistant", "content": "".join(spoken)})

    def _request(self, allow_tools: bool):
        kwargs: dict = {
            "model": settings.model,
            "messages": self._messages(allow_tools),
            "stream": True,
            "temperature": 0.6,
            "max_tokens": settings.max_tokens,
        }
        if allow_tools:
            kwargs["tools"] = tools.TOOL_SCHEMAS
            kwargs["tool_choice"] = "auto"
        return self._client.chat.completions.create(**kwargs)

    def _messages(self, allow_tools: bool) -> list[dict]:
        """История плюс, при необходимости, заметка «инструменты недоступны»."""
        if allow_tools:
            return list(self.history)
        return [*self.history, {"role": "system", "content": NO_TOOLS_NOTE}]

    # ------------------------------------------------------------------
    def _trim(self) -> None:
        """Не даём истории расти бесконечно (иначе ответы дорожают и замедляются).

        Важно: нельзя обрывать историю так, чтобы сообщение с ролью "tool"
        оказалось первым — API такое отвергает, потому что вызов инструмента
        обязан идти вместе с его результатом.
        """
        limit = settings.history_limit
        if len(self.history) <= limit + 1:
            return
        tail = self.history[-limit:]
        while tail and tail[0].get("role") == "tool":
            tail.pop(0)
        self.history = [self.history[0], *tail]

    def reset(self) -> None:
        """Начать разговор заново (сбрасывает и историю, и системную инструкцию)."""
        self.history = [{"role": "system", "content": settings.system_prompt}]

    def ping(self) -> str:
        """Быстрая проверка, что ключ и сеть работают."""
        response = self._client.chat.completions.create(
            model=settings.model,
            messages=[{"role": "user", "content": "Ответь одним словом: работаешь?"}],
            max_tokens=10,
        )
        return response.choices[0].message.content or ""

    def warmup(self) -> float:
        """Прогреть соединение с моделью и вернуть, сколько это заняло.

        Первый запрос в новом процессе стоит около 10 секунд: установка TLS,
        DNS, прогрев на стороне модели. Все следующие — меньше секунды.
        Поэтому такой «холостой» запрос полезно сделать заранее, пока бот
        здоровается: иначе первый ответ человеку придёт через десять секунд.
        """
        started = time.perf_counter()
        self._client.chat.completions.create(
            model=settings.model,
            messages=[{"role": "user", "content": "ok"}],
            max_tokens=1,
        )
        return time.perf_counter() - started


# ---------------------------------------------------------------------------
# Фильтр служебной разметки
# ---------------------------------------------------------------------------
def _first_index(text: str, markers: tuple[str, ...]) -> int | None:
    found = [text.find(marker) for marker in markers]
    found = [index for index in found if index != -1]
    return min(found) if found else None


class _ServiceMarkupFilter:
    """Вырезает из потока служебную разметку вызова инструментов.

    Зачем: модель — это генератор текста, и если инструменты недоступны, она может
    напечатать вызов как обычный текст. Такой «мусор» нельзя ни читать, ни тем
    более озвучивать.

    Фильтр работает по потоку, а не по целому ответу, потому что мы стримим токены.
    Поэтому короткий «хвост» всегда придерживается: маркер может быть разрезан
    между двумя чанками (например, "<tool" и "_call>").
    """

    OPEN = ("<tool_call", "<tool_calls", "<function_call", "<|tool", "<|function", "｜tool")
    CLOSE = ("</tool_call", "</tool_calls", "</function_call", "<|/tool", "｜tool▁calls▁end｜")
    CARRY = 16  # сколько символов придерживать для склейки разрезанного маркера

    def __init__(self) -> None:
        self._carry = ""
        self._inside = False

    def feed(self, chunk: str) -> str:
        data = self._carry + chunk
        self._carry = ""
        produced: list[str] = []

        while data:
            if self._inside:
                # Внутри служебного блока: выбрасываем всё до закрывающего маркера
                index = _first_index(data, self.CLOSE)
                if index is None:
                    self._carry = data[-self.CARRY :]
                    break
                end = data.find(">", index)
                data = data[end + 1 :] if end != -1 else data[index + 1 :]
                self._inside = False
                continue

            index = _first_index(data, self.OPEN)
            if index is None:
                if len(data) > self.CARRY:
                    produced.append(data[: -self.CARRY])
                    self._carry = data[-self.CARRY :]
                else:
                    self._carry = data
                break

            produced.append(data[:index])
            data = data[index:]
            self._inside = True

        return "".join(produced)

    def flush(self) -> str:
        """Отдать остаток в конце ответа, если это не оборванный служебный блок."""
        text = "" if self._inside else self._carry
        self._carry = ""
        self._inside = False
        return text


# ---------------------------------------------------------------------------
# Разбор заявок инструментов из потока
# ---------------------------------------------------------------------------
def _collect_tool_calls(store: dict, deltas) -> None:
    """Собрать запросы инструментов: они приходят кусками, сгруппированными по индексу."""
    for delta in deltas:
        call = store.setdefault(delta.index, {"id": "", "name": "", "arguments": ""})
        if delta.id:
            call["id"] = delta.id
        if delta.function is not None:
            if delta.function.name:
                call["name"] += delta.function.name
            if delta.function.arguments:
                call["arguments"] += delta.function.arguments


def _parse_arguments(raw: str) -> dict:
    try:
        parsed = json.loads(raw) if raw.strip() else {}
        return parsed if isinstance(parsed, dict) else {"value": parsed}
    except json.JSONDecodeError:
        return {}


def indent_block(text: str, limit: int = 500) -> str:
    """Печатать длинный результат удобно, но не засоряя консоль целиком."""
    if len(text) > limit:
        text = text[:limit] + "…"
    return "  " + text.replace("\n", "\n  ")
