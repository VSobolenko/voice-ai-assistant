package com.forkly.voicebot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Мозг ассистента: DeepSeek через OpenAI-совместимый API.
 *
 * <p>Всё, что можно настроить, собрано в константах ниже — это единственное место,
 * которое нужно править (как .env и config.py в Python-версии).
 */
final class LlmClient {

    /** Куда обращаться и какая модель. */
    static final String BASE_URL = "https://api.deepseek.com";
    static final String MODEL = "deepseek-chat";

    /** Сколько раундов поиска разрешено и сколько секунд на них отведено. */
    private static final int MAX_ROUNDS = 2;
    private static final int BUDGET_SECONDS = 25;
    private static final int MAX_TOKENS = 500;
    private static final int SEARCH_RESULTS = 5;

    interface Listener {
        void onText(String chunk);

        void onTool(String name, String arguments);

        void onToolResult(String name, String result);
    }

    private static final String SYSTEM_PROMPT = """
            Ты — голосовой ассистент. Твой ответ читают вслух, поэтому:
            - Говори естественно, как в разговоре, без разметки, списков и заголовков.
            - Отвечай коротко: одно-два предложения, максимум пять. Это речь, а не статья.
            - Не используй символы форматирования, не перечисляй ссылки и адреса.
            - Не пересказывай вопрос и не повторяй предыдущий ответ целиком.
            - Если чего-то не знаешь, честно скажи об этом, не выдумывай факты.
            - Отвечай на языке собеседника.
            """;

    /**
     * Правила про инструменты. Правил «про английский» здесь нет намеренно: режим
     * учителя пробовали и убрали — модель начинала дописывать к русским ответам
     * «разберём английские слова». За произношение отвечает не промпт, а
     * маршрутизация текста по голосам (см. {@link Languages} и {@link Speaker}).
     */
    private static final String TOOL_RULES = """

            У тебя есть инструменты web_search и web_fetch для свежей информации из интернета.
            Пользуйся ими, когда вопрос про текущие события, курсы, погоду, цены, новости, или когда
            не уверен в актуальности своих знаний. Не ищи то, что и так знаешь: каждый поиск
            заставляет человека ждать.

            Когда вызываешь инструмент, ОБЯЗАТЕЛЬНО сначала скажи одну короткую фразу вроде
            «Секунду, уточню в интернете.» — человеку нужно понимать, почему наступила пауза.
            Скажи эту фразу только один раз, в самом начале поиска.

            Как искать: максимум два шага. Сначала web_search, затем web_fetch по самой надёжной
            ссылке. Предпочитай официальные источники и крупные СМИ.

            Если страница не открылась или в ней нет текста, не сдавайся и не говори «доступ не
            сработал»: фрагменты выдачи почти всегда уже содержат ответ. Ответь по ним и коротко
            скажи, что детали могут быть неполными. Просить человека повторить вопрос — плохой ответ.

            Если точной цифры найти не удалось, назови то, что нашёл, и честно предупреди о неточности.
            """;

    /** Инструменты недоступны на этом шаге: заметка идёт в запрос, но не сохраняется в истории. */
    private static final String NO_TOOLS_NOTE =
            "Инструменты сейчас недоступны. Не вызывай их и не обещай поискать в интернете. "
                    + "Ответь прямо сейчас, коротко и по существу.";

    /** Время на поиск истекло — надо отвечать тем, что уже есть. */
    private static final String BUDGET_NOTICE =
            "Инструменты отключены: превышен лимит времени на поиск. Ответь сейчас, используя уже "
                    + "полученную информацию. Если точных данных нет, назови то, что знаешь, и коротко "
                    + "предупреди о неточности.";

    private final String apiKey;
    private final Tools.Log log;
    private JSONArray history = new JSONArray();
    private boolean toolsEnabled = true;

    LlmClient(String apiKey, Tools.Log log) {
        this.apiKey = apiKey;
        this.log = log;
        clear();
    }

    void setToolsEnabled(boolean enabled) {
        this.toolsEnabled = enabled;
    }

    /** Итоговая инструкция: правила речи плюс правила про инструменты. */
    private String systemPrompt() {
        return SYSTEM_PROMPT + TOOL_RULES;
    }

    /** Начать разговор заново: история обнуляется, инструкция ставится первой. */
    void clear() {
        JSONArray fresh = new JSONArray();
        try {
            fresh.put(new JSONObject().put("role", "system").put("content", systemPrompt()));
        } catch (Exception exc) {
            log.line("[мозг] не удалось задать инструкцию: " + exc.getMessage());
        }
        history = fresh;
    }

    int historySize() {
        return history.length();
    }

    /**
     * Прогрев соединения: один крошечный запрос, чтобы первый настоящий ответ не ждал
     * установки TLS и прогрева модели. Заодно проверяет ключ.
     */
    long ping() throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", "Привет"));
        body.put("messages", messages);
        body.put("max_tokens", 1);
        body.put("stream", false);

        long started = System.currentTimeMillis();
        HttpURLConnection connection = open(body);
        try {
            int code = connection.getResponseCode();
            String response = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code >= 400) {
                throw new IllegalStateException("модель ответила " + code + ": " + shorten(response, 200));
            }
            return System.currentTimeMillis() - started;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Отправить реплику и вернуть ответ целиком.
     *
     * <p>Модель может по ходу дела попросить инструменты — тогда запрос повторяется
     * с их результатами. События отдаются в {@link Listener}, чтобы приложение успевало
     * показывать и озвучивать ответ по мере генерации.
     */
    String ask(String userText, Listener listener) throws Exception {
        history.put(new JSONObject().put("role", "user").put("content", userText));

        StringBuilder answer = new StringBuilder();
        List<String> tried = new ArrayList<>();
        long deadline = System.currentTimeMillis() + BUDGET_SECONDS * 1000L;

        for (int round = 0; round <= MAX_ROUNDS; round++) {
            boolean withTools = toolsEnabled
                    && round < MAX_ROUNDS
                    && System.currentTimeMillis() < deadline;

            JSONObject message = request(withTools, listener, answer);
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) {
                break;
            }

            // Модель иногда повторяет ровно тот же вызов — не даём зациклиться
            List<JSONObject> fresh = new ArrayList<>();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i);
                JSONObject function = call.optJSONObject("function");
                String key = str(function, "name", "") + str(function, "arguments", "{}");
                if (!tried.contains(key)) {
                    fresh.add(call);
                }
            }
            if (fresh.isEmpty()) {
                log.line("  [модель повторила тот же вызов — отвечаю по найденному]");
                break;
            }

            history.put(message);
            boolean outOfTime = System.currentTimeMillis() >= deadline;
            for (JSONObject call : fresh) {
                JSONObject function = call.getJSONObject("function");
                String name = str(function, "name", "");
                String arguments = str(function, "arguments", "{}");
                tried.add(name + arguments);
                listener.onTool(name, arguments);
                if (outOfTime) {
                    log.line("  [время на поиск истекло — отвечаю по найденному]");
                }
                String result = outOfTime ? BUDGET_NOTICE : Tools.execute(name, arguments, SEARCH_RESULTS, log);
                listener.onToolResult(name, result);
                history.put(new JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", str(call, "id", ""))
                        .put("content", result));
            }
        }
        return answer.toString();
    }

    // ------------------------------------------------------------------
    // Запрос со стримингом
    // ------------------------------------------------------------------
    private JSONObject request(boolean withTools, Listener listener, StringBuilder answer) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("messages", messagesFor(withTools));
        body.put("stream", true);
        body.put("temperature", 0.6);
        body.put("max_tokens", MAX_TOKENS);
        if (withTools) {
            body.put("tools", new JSONArray(Tools.schemas()));
            body.put("tool_choice", "auto");
        }

        HttpURLConnection connection = open(body);
        StringBuilder content = new StringBuilder();
        JSONArray toolCalls = new JSONArray();
        try {
            int code = connection.getResponseCode();
            if (code >= 400) {
                throw new IllegalStateException("модель ответила " + code + ": "
                        + shorten(readAll(connection.getErrorStream()), 200));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JSONObject event = new JSONObject(payload);
                    JSONArray choices = event.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) {
                        continue;
                    }
                    String piece = str(delta, "content", "");
                    if (!piece.isEmpty()) {
                        content.append(piece);
                        answer.append(piece);
                        listener.onText(piece);
                    }
                    JSONArray deltaCalls = delta.optJSONArray("tool_calls");
                    if (deltaCalls != null) {
                        mergeToolCalls(toolCalls, deltaCalls);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }

        JSONObject message = new JSONObject().put("role", "assistant");
        // Пустая строка вместо null недопустима рядом с tool_calls — как и в Python-версии
        message.put("content", content.length() == 0 ? JSONObject.NULL : content.toString());
        if (toolCalls.length() > 0) {
            message.put("tool_calls", toolCalls);
        }
        return message;
    }

    /**
     * Собрать вызовы инструментов из потока: модель присылает их по кусочкам,
     * поэтому имя и аргументы приходится склеивать.
     */
    private static void mergeToolCalls(JSONArray target, JSONArray deltas) throws Exception {
        for (int i = 0; i < deltas.length(); i++) {
            JSONObject delta = deltas.getJSONObject(i);
            int index = delta.optInt("index", target.length());
            while (target.length() <= index) {
                target.put(new JSONObject()
                        .put("id", "")
                        .put("type", "function")
                        .put("function", new JSONObject().put("name", "").put("arguments", "")));
            }
            JSONObject call = target.getJSONObject(index);
            String id = str(delta, "id", "");
            if (!id.isEmpty()) {
                call.put("id", id);
            }
            JSONObject function = delta.optJSONObject("function");
            if (function == null) {
                continue;
            }
            JSONObject current = call.getJSONObject("function");
            current.put("name", current.optString("name", "") + str(function, "name", ""));
            current.put("arguments", current.optString("arguments", "") + str(function, "arguments", ""));
        }
    }

    private JSONArray messagesFor(boolean withTools) throws Exception {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < history.length(); i++) {
            messages.put(history.getJSONObject(i));
        }
        if (!withTools) {
            messages.put(new JSONObject().put("role", "system").put("content", NO_TOOLS_NOTE));
        }
        return messages;
    }

    private HttpURLConnection open(JSONObject body) throws Exception {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + "/chat/completions").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        connection.setFixedLengthStreamingMode(payload.length);
        connection.connect();
        OutputStream out = connection.getOutputStream();
        out.write(payload);
        out.flush();
        out.close();
        return connection;
    }

    // ------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------
    /** В JSON значение может быть null — optString в этом случае вернул бы строку "null". */
    private static String str(JSONObject json, String key, String fallback) {
        Object value = json == null ? null : json.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return fallback;
        }
        String text = value.toString();
        return text.isEmpty() ? fallback : text;
    }

    private static String readAll(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            StringBuilder builder = new StringBuilder();
            byte[] chunk = new byte[8_192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                builder.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
            }
            return builder.toString();
        } catch (Exception exc) {
            return "";
        }
    }

    private static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
