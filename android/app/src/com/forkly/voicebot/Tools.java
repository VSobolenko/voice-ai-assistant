package com.forkly.voicebot;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Инструменты ассистента: web_search и web_fetch.
 *
 * <p>Логика намеренно повторяет Python-версию (voicebot/tools.py), потому что ошибки
 * там были одни и те же:
 *
 * <ul>
 *   <li>брали первый попавшийся {@code <main>} и вместо статьи отдавали меню сайта —
 *       поэтому текст собирается из абзацев, а таблицы идут отдельным случаем;
 *   <li>обрезали текст строго по символу и превращали курс «84,2569» в «84,2» —
 *       поэтому обрезка идёт по границе слова;
 *   <li>выбрасывали уже скачанное, если сервер оборвал соединение на середине;
 *   <li>ходили по любым адресам, включая внутренние, — поэтому есть проверка адреса.
 * </ul>
 */
final class Tools {

    /** Куда писать диагностику: приложение показывает её прямо на экране. */
    interface Log {
        void line(String text);
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_BYTES = 600_000;
    private static final int FETCH_MAX_CHARS = 2_500;
    private static final int SNIPPET_CHARS = 320;
    /** Ниже этого объёма считаем, что описание ещё не набрано. */
    private static final int SNIPPET_MIN = 30;
    private static final int MIN_CONTENT_CHARS = 200;
    private static final int TABLE_ROWS_HINT = 20;
    private static final double MAX_LINK_RATIO = 0.5;
    private static final String[] COOKIE_MARKERS = {"cookie", "куки", "согласие на обработку", "consent"};

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern DROP_BLOCK = Pattern.compile(
            "<(script|style|noscript|svg|iframe|form|nav|footer|header|aside|template)\\b[^>]*>.*?</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CONTENT_BLOCK = Pattern.compile(
            "<(p|h1|h2|h3|blockquote)\\b[^>]*>(.*?)</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile(
            "<(td|th)\\b[^>]*>(.*?)</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b[^>]*href\\s*=\\s*\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    /** Обрывок тега на границе прочитанного куска: «<a class="result__url"» без «>». */
    private static final Pattern UNCLOSED_TAG = Pattern.compile("<[^>]*$");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0]+");
    private static final Pattern ENTITY = Pattern.compile("&(#?\\w+);");
    private static final Pattern CHARSET_META = Pattern.compile("charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEARCH_ENGINE = Pattern.compile(
            "(^|\\.)(duckduckgo|mojeek|bing|google|yandex|yahoo|baidu)\\.[a-z]{2,}$", Pattern.CASE_INSENSITIVE);
    /** Адрес сайта внутри текста: «cbr.ru/currency_base», «ru.investing.com» и подобное. */
    private static final Pattern ADDRESS = Pattern.compile(
            "\\b[\\w-]+(\\.[\\w-]+)*\\.(ru|com|by|org|net|info|io|ua|kz|edu|gov)(/\\S*)?",
            Pattern.CASE_INSENSITIVE);

    private static final String[][] ENTITIES = {
            {"nbsp", " "}, {"amp", "&"}, {"lt", "<"}, {"gt", ">"}, {"quot", "\""},
            {"apos", "'"}, {"laquo", "«"}, {"raquo", "»"}, {"mdash", "—"}, {"ndash", "–"},
            {"hellip", "…"}, {"copy", "©"}, {"deg", "°"}, {"euro", "€"}, {"pound", "£"},
            {"rsquo", "’"}, {"lsquo", "‘"}, {"ldquo", "“"}, {"rdquo", "”"}, {"middot", "·"},
            {"bull", "•"}, {"trade", "™"}, {"sect", "§"}, {"times", "×"}, {"minus", "−"},
    };

    private Tools() {
    }

    // ------------------------------------------------------------------
    // Поиск
    // ------------------------------------------------------------------
    /** Поисковики перебираются по очереди: если один недоступен из сети — работает другой. */
    private static final String[][] PROVIDERS = {
            {"DuckDuckGo", "https://html.duckduckgo.com/html/?q="},
            {"DuckDuckGo lite", "https://lite.duckduckgo.com/lite/?q="},
            {"Mojeek", "https://www.mojeek.com/search?q="},
    };

    static String search(String query, int maxResults, Log log) {
        if (query == null || query.trim().isEmpty()) {
            return "Пустой поисковый запрос.";
        }
        List<String> problems = new ArrayList<>();
        for (String[] provider : PROVIDERS) {
            try {
                String address = provider[1] + URLEncoder.encode(query, "UTF-8");
                Page page = httpGet(address, log);
                List<String[]> hits = hits(new String(page.body, "UTF-8"), maxResults);
                if (hits.isEmpty()) {
                    problems.add(provider[0] + ": ничего не разобрал");
                    continue;
                }
                if (log != null) {
                    log.line("  [поиск] источник: " + provider[0] + ", найдено: " + hits.size());
                }
                return render(query, hits);
            } catch (Exception exc) {
                problems.add(provider[0] + ": " + describe(exc));
            }
        }
        return "Не удалось выполнить поиск (" + join(problems)
                + "). Ответь по своим знаниям и предупреди, что данные могут быть неактуальны.";
    }

    private static String render(String query, List<String[]> hits) {
        // Склеиваем именно переводами строк: это текст для модели, а не список ошибок
        StringBuilder out = new StringBuilder("Результаты поиска по запросу «").append(query).append("»:");
        for (int i = 0; i < hits.size(); i++) {
            String[] hit = hits.get(i);
            out.append('\n').append(i + 1).append(". ").append(hit[0])
                    .append("\n   ").append(hit[1])
                    .append("\n   ").append(hit[2]);
        }
        out.append("\n\nФрагменты могут быть устаревшими. Если нужна точная цифра, открой страницу через web_fetch. ")
                .append("Если страница не откроется — этих фрагментов достаточно для ответа.");
        return out.toString();
    }

    /**
     * Разбор выдачи без привязки к конкретному поисковику: берём все ссылки,
     * отбрасываем служебные и склеиваем заголовок со следующим за ним текстом.
     * Так разметка может меняться — разбор продолжит работать.
     */
    private static List<String[]> hits(String page, int maxResults) {
        List<String[]> found = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher matcher = ANCHOR.matcher(page);
        while (matcher.find() && found.size() < maxResults) {
            String href = realAddress(matcher.group(1));
            String title = plainText(matcher.group(2));
            if (href == null || title.length() < 12) {
                continue;
            }
            if (!seen.add(href)) {
                continue;
            }
            String snippet = snippetAfter(page, matcher.end());
            found.add(new String[]{title, href, snippet});
        }
        return found;
    }

    /**
     * Фрагмент текста, который идёт сразу за заголовком ссылки.
     *
     * <p>В выдаче DuckDuckGo сразу после заголовка стоит показной адрес сайта, и только
     * потом описание. Поэтому берём тексты следующих ссылок, пропуская адреса, пока не
     * наберём осмысленный фрагмент. Если описания в ссылках нет (так делает Mojeek,
     * где оно лежит в отдельном абзаце), берём текст сразу за заголовком.
     *
     * <p>Без этого модель получала либо пустое описание, либо мусор вида
     * «&lt;a class="result__url" href=…».
     */
    private static String snippetAfter(String page, int from) {
        StringBuilder collected = new StringBuilder();
        Matcher next = ANCHOR.matcher(page);
        int cursor = from;
        while (collected.length() < SNIPPET_MIN && next.find(cursor)) {
            String text = plainText(next.group(2));
            cursor = next.end();
            if (text.length() < 15 || isAddress(text)) {
                continue;
            }
            collected.append(text).append(' ');
        }

        if (collected.length() < SNIPPET_MIN) {
            int until = Math.min(page.length(), from + 600);
            String window = plainText(page.substring(from, until));
            window = SPACES.matcher(ADDRESS.matcher(window).replaceAll(" ")).replaceAll(" ").trim();
            if (window.length() > collected.length()) {
                collected.setLength(0);
                collected.append(window);
            }
        }

        String snippet = collected.toString().trim();
        return snippet.length() > SNIPPET_CHARS
                ? snippet.substring(0, SNIPPET_CHARS).trim() + "…"
                : snippet;
    }

    /** Показной адрес сайта в выдаче: без пробелов, с точкой, короткий. */
    private static boolean isAddress(String text) {
        return !text.contains(" ") && text.contains(".") && text.length() < 80;
    }

    /** Привести ссылку выдачи к настоящему адресу и отбросить служебные. */
    private static String realAddress(String href) {
        if (href == null) {
            return null;
        }
        String value = decodeEntities(href).trim();
        if (value.startsWith("//")) {
            value = "https:" + value;
        }
        int marker = value.indexOf("uddg=");
        if (marker >= 0) {
            int end = value.indexOf('&', marker);
            String encoded = value.substring(marker + 5, end < 0 ? value.length() : end);
            try {
                value = URLDecoder.decode(encoded, "UTF-8");
            } catch (Exception ignored) {
                return null;
            }
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null;
        }
        try {
            String host = new URL(value).getHost();
            if (host == null || SEARCH_ENGINE.matcher(host).find()) {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Чтение страницы
    // ------------------------------------------------------------------
    static String fetch(String url, Log log) {
        if (url == null || url.trim().isEmpty()) {
            return "Пустой адрес.";
        }
        try {
            Page page = httpGet(url.trim(), log);
            if (page.contentType != null
                    && !page.contentType.startsWith("text/html")
                    && !page.contentType.startsWith("text/plain")
                    && !page.contentType.startsWith("application/xhtml")) {
                return "Страница не текстовая (тип " + page.contentType + "), читать её не умею.";
            }
            String html = decode(page.body, page.charset);
            String body = page.contentType.startsWith("text/plain") ? html : htmlToText(html);
            if (body.length() < MIN_CONTENT_CHARS) {
                return "На странице не нашлось читаемого текста: похоже, он рисуется скриптами "
                        + "(сайт-приложение) или закрыт от роботов. Ответь по фрагментам из поиска.";
            }
            String clipped = clip(body, FETCH_MAX_CHARS);
            String note = clipped.length() < body.length()
                    ? "\n\n[текст обрезан, показано начало страницы]" : "";
            return "Содержимое " + page.url + ":\n" + clipped + note;
        } catch (HttpError exc) {
            return "Страница вернула ошибку " + exc.code + ". Ответь по фрагментам из поиска "
                    + "и не открывай этот сайт снова.";
        } catch (Exception exc) {
            return "Не удалось загрузить страницу (" + describe(exc) + "). Ответь по фрагментам из поиска "
                    + "и не открывай этот сайт снова.";
        }
    }

    /**
     * Собрать читаемый текст страницы.
     *
     * <p>Главное отличие от «наивного» варианта: содержание берётся из абзацев, а не из
     * первого контейнера. Именно из-за первого контейнера в ответ модели уезжало меню
     * («Новости Прямой эфир») вместо самой новости.
     */
    static String htmlToText(String html) {
        String page = DROP_BLOCK.matcher(COMMENT.matcher(html).replaceAll(" ")).replaceAll(" ");

        StringBuilder paragraphs = new StringBuilder();
        Matcher block = CONTENT_BLOCK.matcher(page);
        while (block.find()) {
            String chunk = plainText(block.group(2));
            if (!chunk.isEmpty()) {
                paragraphs.append(chunk).append(' ');
            }
        }
        String content = paragraphs.toString().trim();

        // Согласие на cookie часто длиннее самой статьи — такую «простыню» не берём
        if (content.length() < 600 && hasCookieMarker(content)) {
            content = "";
        }

        // Табличная страница (курсы валют, каталог, расписание): смысл лежит в ячейках
        if (countRows(page) >= TABLE_ROWS_HINT) {
            StringBuilder cells = new StringBuilder();
            Matcher cell = CELL.matcher(page);
            while (cell.find()) {
                String chunk = plainText(cell.group(2));
                if (!chunk.isEmpty()) {
                    cells.append(chunk).append(' ');
                }
            }
            if (cells.length() > content.length()) {
                content = (content + " " + cells).trim();
            }
        }

        // Абзацев не нашлось — берём текст страницы, если он не состоит из одних ссылок
        if (content.length() < MIN_CONTENT_CHARS) {
            String whole = plainText(page);
            if (whole.length() > content.length() && linkRatio(page, whole) < MAX_LINK_RATIO) {
                content = whole;
            }
        }
        return content;
    }

    /** Доля текста, лежащая внутри ссылок: если её много, это меню, а не статья. */
    private static double linkRatio(String page, String whole) {
        if (whole.isEmpty()) {
            return 0;
        }
        int linked = 0;
        Matcher matcher = ANCHOR.matcher(page);
        while (matcher.find()) {
            linked += plainText(matcher.group(2)).length();
        }
        return (double) linked / whole.length();
    }

    private static boolean hasCookieMarker(String content) {
        String lower = content.toLowerCase();
        for (String marker : COOKIE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static int countRows(String page) {
        int rows = 0;
        Matcher matcher = ROW.matcher(page);
        while (matcher.find()) {
            rows++;
        }
        return rows;
    }

    /**
     * Обрезать текст по границе слова, не разрывая числа.
     *
     * <p>Обрезка строго по символу превращала «840 USD 1 Доллар США 84,2569» в «84,2» —
     * это ложный факт, который хуже пары лишних символов.
     */
    static String clip(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int boundary = text.lastIndexOf(' ', limit);
        if (boundary < limit * 0.7) {
            return text.substring(0, limit).trim();
        }
        int tailEnd = text.indexOf(' ', boundary + 1);
        int end = tailEnd > 0 && tailEnd - boundary <= 40 ? tailEnd : boundary;
        return text.substring(0, end).trim();
    }

    // ------------------------------------------------------------------
    // Разбор служебных данных модели
    // ------------------------------------------------------------------
    /** Человеческое описание вызова: именно это пишется в лог на экране. */
    static String describeCall(String name, String arguments) {
        try {
            JSONObject json = new JSONObject(arguments);
            if ("web_search".equals(name)) {
                return "ищу в интернете: " + json.optString("query", "");
            }
            if ("web_fetch".equals(name)) {
                return "открываю страницу: " + json.optString("url", "");
            }
        } catch (Exception ignored) {
            // аргументы могут прийти неполными — тогда покажем хотя бы имя инструмента
        }
        return name;
    }

    /** Выполнить вызов инструмента, который запросила модель. */
    static String execute(String name, String arguments, int maxResults, Log log) {
        try {
            JSONObject json = new JSONObject(arguments == null || arguments.trim().isEmpty() ? "{}" : arguments);
            if ("web_search".equals(name)) {
                int limit = json.optInt("max_results", maxResults);
                if (limit < 1 || limit > 10) {
                    limit = maxResults;
                }
                return search(json.optString("query", ""), limit, log);
            }
            if ("web_fetch".equals(name)) {
                Object url = json.opt("url");
                if (url == null || url == JSONObject.NULL) {
                    return "Не передан адрес страницы.";
                }
                return fetch(url.toString(), log);
            }
            return "Инструмент " + name + " неизвестен.";
        } catch (Exception exc) {
            return "Не удалось разобрать аргументы вызова " + name + ": " + describe(exc);
        }
    }

    /** Описание инструментов для модели — совпадает с Python-версией. */
    static String schemas() {
        return "[{\"type\":\"function\",\"function\":{"
                + "\"name\":\"web_search\","
                + "\"description\":\"Найти в интернете актуальную информацию. Используй, когда вопрос про "
                + "текущие события, курсы валют, погоду, цены, новости, расписания, или когда не уверен "
                + "в свежих данных. Возвращает заголовки, ссылки и короткие фрагменты текста.\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"Поисковый запрос. Формулируй как в поисковой строке.\"},"
                + "\"max_results\":{\"type\":\"integer\",\"description\":\"Сколько результатов вернуть, от 1 до 10. По умолчанию 5.\"}"
                + "},\"required\":[\"query\"]}}},"
                + "{\"type\":\"function\",\"function\":{"
                + "\"name\":\"web_fetch\","
                + "\"description\":\"Прочитать содержимое страницы по ссылке целиком. Используй после web_search, "
                + "когда во фрагментах нет нужной цифры или факта, а страница выглядит источником с точными данными.\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{"
                + "\"url\":{\"type\":\"string\",\"description\":\"Полный адрес страницы, начиная с http:// или https://\"}"
                + "},\"required\":[\"url\"]}}}]";
    }

    // ------------------------------------------------------------------
    // Сеть
    // ------------------------------------------------------------------
    static final class HttpError extends Exception {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static final class Page {
        byte[] body = new byte[0];
        String url = "";
        String contentType = "";
        String charset;
    }

    private static Page httpGet(String address, Log log) throws Exception {
        String current = address;
        for (int hop = 0; hop < 5; hop++) {
            requirePublicAddress(current);
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "ru,en;q=0.8");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("перенаправление без адреса");
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (code >= 400) {
                    throw new HttpError(code);
                }
                Page page = new Page();
                page.url = connection.getURL().toString();
                String header = connection.getContentType();
                page.contentType = header == null ? "" : header.toLowerCase();
                page.charset = charsetFrom(header);
                readBody(connection, page, log);
                return page;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("слишком много перенаправлений");
    }

    private static void readBody(HttpURLConnection connection, Page page, Log log) throws IOException {
        InputStream stream = connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[16_384];
            int read;
            while (buffer.size() < MAX_BYTES && (read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
        } catch (IOException exc) {
            // Сервер оборвал соединение на середине: уже скачанное всё равно полезно
            if (buffer.size() == 0) {
                throw exc;
            }
            if (log != null) {
                log.line("  [сеть] соединение оборвалось, беру то, что успело прийти");
            }
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // закрытие уже сломанного потока ни на что не влияет
            }
        }
        page.body = buffer.toByteArray();
    }

    /** Не пускаем модель во внутреннюю сеть (SSRF): только публичные http/https адреса. */
    private static void requirePublicAddress(String address) throws Exception {
        URL parsed = new URL(address);
        String scheme = parsed.getProtocol();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException("поддерживаются только http и https");
        }
        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("в адресе нет домена");
        }
        for (InetAddress resolved : InetAddress.getAllByName(host)) {
            if (resolved.isLoopbackAddress() || resolved.isSiteLocalAddress()
                    || resolved.isLinkLocalAddress() || resolved.isAnyLocalAddress()
                    || resolved.isMulticastAddress()) {
                throw new IOException("адрес ведёт во внутреннюю сеть");
            }
        }
    }

    /** Кодировка из заголовка Content-Type: `text/html; charset=windows-1251`. */
    private static String charsetFrom(String contentTypeHeader) {
        if (contentTypeHeader == null) {
            return null;
        }
        Matcher matcher = CHARSET_META.matcher(contentTypeHeader);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String decode(byte[] raw, String charset) {
        List<String> candidates = new ArrayList<>();
        if (charset != null && !charset.isEmpty()) {
            candidates.add(charset);
        }
        String head = new String(raw, 0, Math.min(raw.length, 3_000), java.nio.charset.StandardCharsets.ISO_8859_1);
        Matcher meta = CHARSET_META.matcher(head);
        if (meta.find()) {
            candidates.add(meta.group(1));
        }
        candidates.add("UTF-8");
        candidates.add("windows-1251");
        for (String candidate : candidates) {
            try {
                return new String(raw, candidate);
            } catch (Exception ignored) {
                // неизвестная кодировка — пробуем следующую
            }
        }
        return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Текст
    // ------------------------------------------------------------------
    private static String plainText(String fragment) {
        if (fragment == null) {
            return "";
        }
        String withoutTags = TAG.matcher(fragment).replaceAll(" ");
        // Окно текста может оборваться на середине тега: остаётся «<a class="..."» без
        // закрывающей скобки, и он уезжает в ответ модели как обычный текст
        withoutTags = UNCLOSED_TAG.matcher(withoutTags).replaceAll(" ");
        return SPACES.matcher(decodeEntities(withoutTags)).replaceAll(" ").trim();
    }

    private static String decodeEntities(String value) {
        StringBuilder out = new StringBuilder(value.length());
        Matcher matcher = ENTITY.matcher(value);
        int last = 0;
        while (matcher.find()) {
            out.append(value, last, matcher.start());
            String name = matcher.group(1);
            String replacement = null;
            if (name.startsWith("#")) {
                try {
                    boolean hex = name.length() > 2 && (name.charAt(1) == 'x' || name.charAt(1) == 'X');
                    int code = hex ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                    replacement = new String(Character.toChars(code));
                } catch (RuntimeException ignored) {
                    // битая сущность — оставим как есть
                }
            } else {
                for (String[] pair : ENTITIES) {
                    if (pair[0].equalsIgnoreCase(name)) {
                        replacement = pair[1];
                        break;
                    }
                }
            }
            out.append(replacement != null ? replacement : matcher.group());
            last = matcher.end();
        }
        out.append(value, last, value.length());
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------
    private static String describe(Exception exc) {
        String message = exc.getMessage();
        return message == null || message.isEmpty() ? exc.getClass().getSimpleName() : message;
    }

    private static String join(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(part);
        }
        return builder.toString();
    }
}
