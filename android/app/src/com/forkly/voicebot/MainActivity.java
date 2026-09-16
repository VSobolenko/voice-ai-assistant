package com.forkly.voicebot;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Экран приложения: журнал как в консоли, кнопка «Слушать» и поле для вопроса текстом.
 *
 * <p>Задача этого модуля — показать ассистента на телефоне, поэтому весь обмен
 * (вопрос, ответ, вызовы инструментов, задержки) печатается в журнал и дублируется
 * в logcat с меткой {@code VoiceBot}: если что-то не работает, видно, где именно.
 */
public class MainActivity extends Activity implements Speaker.Log, Speaker.Idle, Tools.Log {

    private static final String TAG = "VoiceBot";
    private static final String PREFS = "voicebot";
    private static final String KEY_API = "api_key";
    private static final String KEY_TOOLS = "tools";
    private static final String KEY_DIALOG = "dialog_mode";
    private static final String KEY_VOICE_ONLINE = "voice_online";
    private static final int REQUEST_MIC = 1;
    private static final int MAX_LOG_LINES = 500;
    /** Пауза перед тем, как снова включить микрофон: чтобы не поймать хвост своей же речи. */
    private static final long RESTART_DELAY_MS = 450;
    /** Сколько раз подряд можно не услышать человека, прежде чем выключить разговор. */
    private static final int MAX_MISSES = 3;
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    /** Знаки, которые могут прилипнуть к ссылке справа: «Содержимое https://cbr.ru/…/:» */
    private static final String URL_TAIL = ":.,;!?»)]}";
    /**
     * Слова-прощания. Вместо {@code \b} здесь проверка «после слова не идёт ещё буква»:
     * в Java граница слова {@code \b} определена только для латиницы, и с кириллицей
     * она не срабатывает ни разу — «пока» перестало бы заканчивать разговор.
     */
    private static final Pattern FAREWELL = Pattern.compile(
            "^(пока|до свидания|до связи|стоп|хватит|закончим|выключись|отбой|"
                    + "stop|bye|goodbye|see you)(?![а-яё])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Язык, на котором слушаем микрофон. Один для всех реплик.
     *
     * <p>«Режим учителя» с автопереключением языка пробовали и убрали: он
     * дописывал к русским ответам «разберём английские слова» и сам менял язык
     * микрофона, хотя просили только одно — чтобы английское слово читал
     * английский голос. Произношением занимается {@link Speaker} вместе с
     * {@link Languages}, а уши остаются русскими.
     */
    private static final String STT_LANGUAGE = "ru-RU";

    /** Что можно скопировать: по этим ключам выбирается содержимое. */
    private static final String COPY_ANSWER = "ответ";
    private static final String COPY_SEARCH = "поиск";
    private static final String COPY_ADDRESS = "адрес";
    private static final String COPY_JOURNAL = "журнал";
    private static final Pattern COPY_VERB = Pattern.compile(
            "(скопир|скапир|копир)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BUFFER_WORD = Pattern.compile(
            "(буфер|обмен)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PUT_VERB = Pattern.compile(
            "(полож|закин|скин|добав|отправ|запиш)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** «Что такое буфер обмена?» — это вопрос к модели, а не команда копирования. */
    private static final Pattern QUESTION_START = Pattern.compile(
            "^\\s*(что|как|зачем|почему|где|когда|кто|сколько|расскажи)(?![а-яё])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ADDRESS_WORD = Pattern.compile(
            "(адрес|ссылк|url|ю ар эл)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SEARCH_WORD = Pattern.compile(
            "(поиск|результат|выдач|нашёл|нашел|найден)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern JOURNAL_WORD = Pattern.compile(
            "(журнал|лог|истори|разговор|переписк|диалог|всё|все )",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ScrollView logScroll;
    private TextView logView;
    private TextView statusView;
    private EditText keyField;
    private Button eyeButton;
    private boolean keyVisible;
    private EditText questionField;
    private Button listenButton;
    private CheckBox toolsBox;
    private CheckBox dialogBox;
    private CheckBox voiceBox;
    private Handler handler;

    private Speaker speaker;
    private LlmClient llm;
    private SpeechRecognizer recognizer;

    private boolean listening;
    private boolean busy;
    private boolean uiReady;
    private int logLines;
    /** Идёт непрерывный разговор: после ответа микрофон включается сам. */
    private boolean dialogArmed;
    private boolean armAfterPermission;
    private int misses;
    /**
     * Что можно скопировать по просьбе «скопируй …». Здесь именно произнесённый
     * текст, а не ответ модели: в буфер должно попадать ровно то, что услышали.
     */
    private String lastAnswer = "";
    private String lastSearch = "";
    private String lastAddress = "";

    private long turnStartedAt;
    private long answerDoneAt;
    private long toolStartedAt;
    private long toolSpentMs;
    private int toolCalls;

    // ------------------------------------------------------------------
    // Экран
    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(Looper.getMainLooper());
        buildUi();

        toolsBox.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_TOOLS, true));
        voiceBox.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_VOICE_ONLINE, true));
        dialogBox.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DIALOG, true));

        speaker = new Speaker(this, this);
        speaker.start(this, voiceBox.isChecked());

        String key = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, "");
        keyField.setText(key);

        log("Голосовой ассистент. Скажите вопрос в микрофон или введите его текстом.");
        log("Модель " + LlmClient.MODEL + ", поиск в интернете: "
                + (toolsBox.isChecked() ? "включён" : "выключен"));
        log(dialogBox.isChecked()
                ? "Режим разговора: нажмите «Слушать» один раз — дальше ассистент слушает сам, "
                        + "а после ответа снова включает микрофон. Скажите «пока» или нажмите «Стоп»."
                : "Режим разговора выключен: микрофон включается на одну реплику.");
        log("Скопировать в буфер: «скопируй это», «скопируй адрес», «скопируй результат», "
                + "«скопируй журнал».");
        if (key.trim().isEmpty()) {
            log("[!] Вставьте ключ DeepSeek в поле сверху и нажмите «Сохранить».");
        } else {
            warmUp(key.trim());
        }
        // Только теперь сообщения от элементов экрана имеют смысл
        uiReady = true;
    }

    @Override
    protected void onDestroy() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (speaker != null) {
            speaker.stop();
        }
        if (recognizer != null) {
            recognizer.destroy();
        }
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D1117"));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("VoiceBot — голосовой ассистент");
        title.setTextColor(Color.parseColor("#58A6FF"));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        // Ключ модели
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setPadding(0, dp(8), 0, 0);

        keyField = new EditText(this);
        keyField.setHint("ключ DeepSeek API (sk-...)");
        keyField.setTextSize(12);
        keyField.setTypeface(Typeface.MONOSPACE);
        // Ключ на экране закрыт: приложение показывают людям, а снимок экрана
        // с открытым ключом — это утёкший ключ. Кнопка «Показать» рядом, если
        // нужно проверить, что вставилось.
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyRow.addView(keyField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        eyeButton = new Button(this);
        eyeButton.setText("Показать");
        eyeButton.setContentDescription("Показать или скрыть ключ");
        eyeButton.setOnClickListener(view -> showKey(!keyVisible));
        keyRow.addView(eyeButton);

        Button saveButton = new Button(this);
        saveButton.setText("Сохранить");
        saveButton.setOnClickListener(view -> saveKey());
        keyRow.addView(saveButton);
        root.addView(keyRow);

        // Переключатели
        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        toolsRow.setGravity(Gravity.CENTER_VERTICAL);

        toolsBox = new CheckBox(this);
        toolsBox.setText("Интернет");
        toolsBox.setTextColor(Color.parseColor("#C9D1D9"));
        toolsBox.setTextSize(12);
        toolsBox.setOnCheckedChangeListener((view, checked) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_TOOLS, checked).apply();
            if (llm != null) {
                llm.setToolsEnabled(checked);
            }
            if (uiReady) {
                log("Поиск в интернете " + (checked ? "включён" : "выключен"));
            }
        });
        toolsRow.addView(toolsBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dialogBox = new CheckBox(this);
        dialogBox.setText("Диалог");
        dialogBox.setTextColor(Color.parseColor("#C9D1D9"));
        dialogBox.setTextSize(12);
        dialogBox.setOnCheckedChangeListener((view, checked) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DIALOG, checked).apply();
            if (!checked) {
                dialogArmed = false;
                updateListenButton();
            }
            if (uiReady) {
                log(checked
                        ? "Режим разговора включён: после ответа снова слушаю."
                        : "Режим разговора выключен: одна реплика на нажатие.");
            }
        });
        toolsRow.addView(dialogBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        voiceBox = new CheckBox(this);
        voiceBox.setText("Голос из сети");
        voiceBox.setTextColor(Color.parseColor("#C9D1D9"));
        voiceBox.setTextSize(12);
        voiceBox.setOnCheckedChangeListener((view, checked) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_VOICE_ONLINE, checked).apply();
            if (speaker != null) {
                speaker.setPreferNetwork(checked);
            }
            if (uiReady) {
                log(checked
                        ? "Озвучка: голос из сети (звучит живее, нужен интернет; без сети переключусь сам)."
                        : "Озвучка: голос в телефоне (работает без интернета).");
            }
        });
        toolsRow.addView(voiceBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(toolsRow);

        // Служебные кнопки
        LinearLayout buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER_VERTICAL);

        Button checkButton = new Button(this);
        checkButton.setText("Проверка");
        checkButton.setOnClickListener(view -> selfTest());
        buttonsRow.addView(checkButton);

        Button clearButton = new Button(this);
        clearButton.setText("Очистить");
        clearButton.setOnClickListener(view -> {
            logView.setText("");
            logLines = 0;
            if (llm != null) {
                llm.clear();
            }
            log("Журнал и память диалога очищены.");
        });
        buttonsRow.addView(clearButton);
        root.addView(buttonsRow);

        statusView = new TextView(this);
        statusView.setText("готов");
        statusView.setTextColor(Color.parseColor("#8B949E"));
        statusView.setTextSize(12);
        statusView.setPadding(0, dp(4), 0, dp(4));
        root.addView(statusView);

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#010409"));
        logScroll.setPadding(dp(6), dp(6), dp(6), dp(6));
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.parseColor("#C9D1D9"));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Нижняя строка: микрофон, вопрос текстом, отправка
        LinearLayout askRow = new LinearLayout(this);
        askRow.setOrientation(LinearLayout.HORIZONTAL);
        askRow.setGravity(Gravity.CENTER_VERTICAL);
        askRow.setPadding(0, dp(8), 0, 0);

        listenButton = new Button(this);
        listenButton.setText("Слушать");
        listenButton.setOnClickListener(view -> toggleListening());
        askRow.addView(listenButton);

        questionField = new EditText(this);
        questionField.setHint("или напишите вопрос");
        questionField.setTextSize(13);
        askRow.addView(questionField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button askButton = new Button(this);
        askButton.setText("Спросить");
        askButton.setOnClickListener(view -> {
            String question = questionField.getText().toString().trim();
            if (question.isEmpty()) {
                log("[!] Введите вопрос или нажмите «Слушать».");
                return;
            }
            questionField.setText("");
            ask(question);
        });
        askRow.addView(askButton);
        root.addView(askRow);

        setContentView(root);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // Журнал
    // ------------------------------------------------------------------
    @Override
    public void line(String text) {
        log(text);
    }

    /** Строка журнала: пишем и на экран, и в logcat — так её удобно читать с компьютера. */
    private void log(String text) {
        Log.i(TAG, text);
        runOnUiThread(() -> {
            if (logLines > MAX_LOG_LINES) {
                logView.setText("[старые записи убраны]\n");
                logLines = 1;
            }
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            logView.append("[" + stamp + "] " + text + "\n");
            logLines += 1 + countLines(text);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private static int countLines(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Ключ и прогрев
    // ------------------------------------------------------------------
    private void saveKey() {
        String key = keyField.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_API, key).apply();
        if (key.isEmpty()) {
            log("[!] Ключ пустой — ассистент не сможет отвечать.");
            return;
        }
        log("Ключ сохранён.");
        showKey(false);
        warmUp(key);
    }

    /** Показать ключ на экране или снова закрыть его точками. */
    private void showKey(boolean visible) {
        keyVisible = visible;
        keyField.setInputType(InputType.TYPE_CLASS_TEXT
                | (visible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        keyField.setSelection(keyField.getText().length());
        eyeButton.setText(visible ? "Скрыть" : "Показать");
    }

    private void warmUp(String key) {
        LlmClient client = newClient(key);
        log("[мозг] прогреваю соединение с моделью…");
        new Thread(() -> {
            try {
                long ms = client.ping();
                llm = client;
                log("[мозг] соединение с моделью прогрето за " + seconds(ms));
            } catch (Exception exc) {
                log("[мозг] не удалось связаться с моделью: " + exc.getMessage());
            }
        }, "warmup").start();
    }

    private LlmClient newClient(String key) {
        LlmClient client = new LlmClient(key, this);
        client.setToolsEnabled(toolsBox.isChecked());
        return client;
    }

    // ------------------------------------------------------------------
    // Вопрос и ответ
    // ------------------------------------------------------------------
    private void ask(final String question) {
        if (busy) {
            log("[!] Ещё отвечаю на прошлый вопрос.");
            return;
        }
        if (FAREWELL.matcher(question.trim()).find()) {
            // Прощаться вслух — дело модели, а вот выключать разговор нужно сразу,
            // иначе микрофон включится снова и ассистент будет слушать пустую комнату.
            log("[разговор] услышал прощание — после ответа выключаю микрофон.");
            dialogArmed = false;
            updateListenButton();
        }
        if (copyCommand(question)) {
            // Команду копирования модель не видит: отвечать ей нечего, а ключ и сеть
            // для неё не нужны вовсе.
            return;
        }
        if (llm == null) {
            log("[!] Модель не готова: проверьте ключ и нажмите «Сохранить».");
            return;
        }
        busy = true;
        turnStartedAt = System.currentTimeMillis();
        answerDoneAt = 0;
        toolSpentMs = 0;
        toolCalls = 0;
        speaker.begin();
        log("Ты: " + question);
        statusView.setText("думаю…");

        new Thread(() -> {
            try {
                String answer = llm.ask(question, new LlmClient.Listener() {
                    @Override
                    public void onText(String chunk) {
                        speaker.feed(chunk);
                        runOnUiThread(() -> statusView.setText("говорю…"));
                    }

                    @Override
                    public void onTool(String name, String arguments) {
                        toolCalls++;
                        toolStartedAt = System.currentTimeMillis();
                        log("[инструмент] " + Tools.describeCall(name, arguments));
                        log("[инструмент] " + name + "(" + shorten(arguments, 200) + ")");
                    }

            @Override
            public void onToolResult(String name, String result) {
                toolSpentMs += System.currentTimeMillis() - toolStartedAt;
                log("  [результат] " + shorten(result, 400));
                // То, что нашлось: пригодится, если человек скажет «скопируй результат».
                lastSearch = result;
                String address = firstAddress(result);
                if (!address.isEmpty()) {
                    lastAddress = address;
                }
            }
                });

                answerDoneAt = System.currentTimeMillis();
                speaker.finish();
                log("Бот: " + answer.trim());
                if (!answer.trim().isEmpty()) {
                    // Именно этот текст уйдёт в буфер по команде «скопируй это».
                    lastAnswer = answer.trim();
                }
            } catch (Exception exc) {
                log("[!] Сбой при ответе: " + exc.getMessage());
                runOnUiThread(() -> statusView.setText("ошибка"));
                speaker.finish();
            } finally {
                busy = false;
            }
        }, "answer").start();
    }

    // ------------------------------------------------------------------
    // Копирование в буфер: приложение делает это само, без модели
    // ------------------------------------------------------------------
    /**
     * Выполнить команду копирования, если человек её произнёс.
     *
     * <p>Модель к этому не привлекается намеренно: «скопируй» значит «дословно»,
     * а модель — генератор текста, она вправе переформулировать или оборвать
     * длинный ответ. Нужная строка уже лежит здесь ровно такой, какой прозвучала,
     * поэтому копирование мгновенное и работает даже без ключа.
     *
     * @return {@code true}, если реплика обработана и в модель идти не нужно
     */
    private boolean copyCommand(String phrase) {
        String kind = copyTarget(phrase);
        if (kind == null) {
            return false;
        }
        String name;
        String content;
        if (COPY_ADDRESS.equals(kind)) {
            name = "адрес страницы";
            content = lastAddress;
        } else if (COPY_SEARCH.equals(kind)) {
            name = "результат поиска";
            content = lastSearch;
        } else if (COPY_JOURNAL.equals(kind)) {
            name = "журнал разговора";
            content = logView.getText().toString();
        } else {
            name = "последний ответ";
            content = lastAnswer;
        }

        log("[буфер] копирую: " + name);
        if (content == null || content.trim().isEmpty()) {
            log("[буфер] копировать нечего");
            sayLocally("Пока нечего копировать.");
            return true;
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null) {
            log("[буфер] система не дала доступ к буферу обмена");
            sayLocally("Не получилось скопировать.");
            return true;
        }
        manager.setPrimaryClip(ClipData.newPlainText("VoiceBot", content));
        log("[буфер] готово, " + content.length() + " символов: " + shorten(content, 120));
        sayLocally("Скопировал.");
        return true;
    }

    /**
     * Первый адрес в тексте — без знаков препинания, прилипших к нему справа.
     *
     * <p>Двоеточие тут не придирка: в строке «Содержимое https://cbr.ru/…:» оно стоит
     * вплотную к ссылке, и без чистки в буфер уезжал бы адрес с лишним символом —
     * по такой ссылке браузер уже не откроет страницу.
     */
    private static String firstAddress(String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            int end = raw.length();
            while (end > 0 && URL_TAIL.indexOf(raw.charAt(end - 1)) >= 0) {
                end--;
            }
            if (end > 0) {
                return raw.substring(0, end);
            }
        }
        return "";
    }

    /** Что копировать по этой фразе, или {@code null}, если это не команда. */    private static String copyTarget(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) {
            return null;
        }
        if (QUESTION_START.matcher(phrase).find()) {
            // «Что такое буфер обмена» — вопрос, а не команда.
            return null;
        }
        boolean asks = COPY_VERB.matcher(phrase).find()
                || (BUFFER_WORD.matcher(phrase).find() && PUT_VERB.matcher(phrase).find());
        if (!asks) {
            return null;
        }
        // От конкретного к общему: в «скопируй адрес из результатов поиска»
        // важен именно адрес.
        if (ADDRESS_WORD.matcher(phrase).find()) {
            return COPY_ADDRESS;
        }
        if (SEARCH_WORD.matcher(phrase).find()) {
            return COPY_SEARCH;
        }
        if (JOURNAL_WORD.matcher(phrase).find()) {
            return COPY_JOURNAL;
        }
        return COPY_ANSWER;
    }

    /** Подтвердить вслух то, что не является ответом модели: задержку тут мерить нечем. */
    private void sayLocally(String phrase) {
        turnStartedAt = 0;
        answerDoneAt = 0;
        speaker.say(phrase);
    }

    /** Итог по задержкам — когда речь уже закончилась, иначе «первый звук» ещё не наступил. */
    private void reportLatency(long spokenMs) {
        long firstAudio = speaker.firstAudioDelayMs();
        StringBuilder line = new StringBuilder("[задержка] ");
        line.append(firstAudio >= 0 ? "первый звук " + seconds(firstAudio) : "звука не было");
        line.append(", ответ готов за ").append(seconds(answerDoneAt - turnStartedAt));
        if (toolCalls > 0) {
            line.append(", из них ").append(seconds(toolSpentMs)).append(" на поиск (")
                    .append(toolCalls).append(" вызов.)");
        }
        line.append("; реплик озвучено: ").append(speaker.phrases())
                .append(", проговорено за ").append(seconds(spokenMs));
        log(line.toString());
    }

    @Override
    public void onIdle() {
        runOnUiThread(() -> {
            statusView.setText("готов");
            if (turnStartedAt > 0 && answerDoneAt > 0) {
                long spokenMs = System.currentTimeMillis() - turnStartedAt;
                reportLatency(spokenMs);
                turnStartedAt = 0;
            }
            // Ассистент договорил — в режиме разговора снова включаем микрофон.
            scheduleListening();
        });
    }

    // ------------------------------------------------------------------
    // Голосовой ввод
    // ------------------------------------------------------------------
    private void toggleListening() {
        if (dialogArmed) {
            stopListening("остановил по вашей команде");
            return;
        }
        // Новый разговор начинается с чистого счётчика промахов.
        misses = 0;
        startListening(true);
    }

    /** Включить микрофон. {@code partOfDialog} — это шаг непрерывного разговора. */
    private void startListening(boolean partOfDialog) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Разрешение спросим и продолжим с того же места, а не с начала.
            armAfterPermission = partOfDialog;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        if (listening) {
            return;
        }
        if (busy) {
            log("[!] Сначала дождитесь ответа.");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // На части телефонов эта проверка врёт из-за ограничений видимости
            // пакетов, поэтому не отказываемся сразу, а пробуем запустить движок.
            log("[уши] система не сообщила о сервисе распознавания речи (" + earsHint() + "). Пробую запустить всё равно…");
        }
        if (recognizer == null) {
            recognizer = createRecognizer();
        }
        if (recognizer == null) {
            log("[уши] движок распознавания речи на телефоне не найден — " + earsHint());
            log("[уши] пока говорите текстом: поле ввода внизу работает без голоса.");
            dialogArmed = false;
            updateListenButton();
            return;
        }
        speaker.stop();

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        // Язык распознавания фиксированный: русский. Переключение языка вместе с
        // «режимом занятия» пробовали и убрали — русская речь на слух ничуть не
        // пострадала, а английские слова человек произносит по-русски, и это
        // распознаётся как обычно.
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, STT_LANGUAGE);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // Подсказки распознавателю: не обрывать фразу на первой же паузе
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L);

        dialogArmed = partOfDialog && dialogBox.isChecked();
        listening = true;
        updateListenButton();
        statusView.setText("слушаю…");
        log("[слушаю...]");
        recognizer.startListening(intent);
    }

    /** Договорил — и снова слушаем, без нажатий: так же, как в консольной версии. */
    private void scheduleListening() {
        if (!dialogArmed || !dialogBox.isChecked()) {
            return;
        }
        handler.postDelayed(() -> {
            if (!dialogArmed || listening) {
                return;
            }
            if (busy || speaker.turnBusy()) {
                // Ответ ещё пишется или звучит: включать микрофон сейчас нельзя,
                // он услышит собственный голос ассистента. Вернёмся чуть позже.
                scheduleListening();
                return;
            }
            startListening(true);
        }, RESTART_DELAY_MS);
    }

    private void stopListening(String reason) {
        dialogArmed = false;
        misses = 0;
        listening = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (recognizer != null) {
            recognizer.cancel();
        }
        speaker.stop();
        updateListenButton();
        statusView.setText("готов");
        log("[слушаю] " + reason);
    }

    /** Кнопка показывает, что произойдёт по нажатию: продолжить или остановить разговор. */
    private void updateListenButton() {
        listenButton.setText(dialogArmed || listening ? "Стоп" : "Слушать");
    }

    private SpeechRecognizer createRecognizer() {
        SpeechRecognizer created;
        try {
            created = SpeechRecognizer.createSpeechRecognizer(this);
        } catch (RuntimeException exc) {
            // Часть прошивок бросает исключение вместо того, чтобы вернуть null.
            log("[уши] система не дала движок распознавания: " + exc.getMessage());
            return null;
        }
        if (created == null) {
            return null;
        }
        created.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                statusView.setText("слушаю… говорите");
            }

            @Override
            public void onBeginningOfSpeech() {
                statusView.setText("слушаю…");
            }

            @Override
            public void onRmsChanged(float rms) {
                // уровень громкости не показываем: для демонстрации он не нужен
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
                // промежуточные аудиоданные не используются
            }

            @Override
            public void onEndOfSpeech() {
                statusView.setText("понял, обрабатываю…");
            }

            @Override
            public void onError(int error) {
                listening = false;
                updateListenButton();
                String reason = speechError(error);
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    // Дальше слушать нечем: просим доступ заново и выходим из разговора.
                    dialogArmed = false;
                    updateListenButton();
                    statusView.setText("нет доступа к микрофону");
                    log("[уши] " + reason + " — нажмите «Слушать» и разрешите доступ.");
                    return;
                }
                boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                statusView.setText("готов");
                log(silence ? "[слушаю] тишина — попробуйте ещё раз." : "[уши] не получилось: " + reason);
                if (!dialogArmed) {
                    return;
                }
                misses++;
                if (misses >= MAX_MISSES) {
                    // Вечный цикл «слушаю пустоту» не нужен: выключаемся и говорим об этом.
                    log("[слушаю] " + MAX_MISSES + " раза подряд ничего не разобрал — "
                            + "выключаю разговор. Нажмите «Слушать», чтобы продолжить.");
                    dialogArmed = false;
                    updateListenButton();
                    return;
                }
                scheduleListening();
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                updateListenButton();
                ArrayList<String> variants = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = variants == null || variants.isEmpty() ? "" : variants.get(0).trim();
                if (text.isEmpty()) {
                    statusView.setText("готов");
                    log("[слушаю] не разобрал слова — попробуйте ещё раз.");
                    return;
                }
                misses = 0;
                statusView.setText("готов");
                ask(text);
            }

            @Override
            public void onPartialResults(Bundle partial) {
                ArrayList<String> variants = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (variants != null && !variants.isEmpty()) {
                    statusView.setText("слушаю… " + variants.get(0));
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
                // служебные события движка распознавания не нужны
            }
        });
        return created;
    }

    private static String speechError(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "ошибка записи звука";
            case SpeechRecognizer.ERROR_CLIENT:
                return "движок распознавания не ответил — возможно, он выключен в системе";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "нет разрешения на микрофон";
            case SpeechRecognizer.ERROR_NETWORK:
                return "нет сети для распознавания";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "сеть не ответила";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "не разобрал слова";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "распознаватель занят";
            case SpeechRecognizer.ERROR_SERVER:
                return "сервер распознавания отказал";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "тишина";
            default:
                return "код " + code;
        }
    }

    // ------------------------------------------------------------------
    // Диагностика голосового ввода
    // ------------------------------------------------------------------
    /** Строка для журнала: что именно с ушами на этом телефоне. */
    private String describeEars() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!mic) {
            return "нет разрешения на микрофон — нажмите «Слушать» и разрешите доступ";
        }
        List<String> services = serviceNames();
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            return services.isEmpty() ? "доступно" : "доступно (" + services.get(0) + ")";
        }
        if (services.isEmpty()) {
            return "НЕДОСТУПНО — " + earsHint();
        }
        return "НЕДОСТУПНО, но сервис в системе есть: " + services
                + " — попробуйте кнопку «Слушать», запуск иногда срабатывает";
    }

    /** Что посоветовать человеку, если движка распознавания не нашлось. */
    private String earsHint() {
        if (!serviceNames().isEmpty()) {
            return "сервис есть, но система не отдаёт его приложению";
        }
        return "в системе нет движка распознавания речи. Обычно он живёт в приложении "
                + "«Google»: проверьте, что оно установлено и не выключено "
                + "(Настройки → Приложения → Google). На телефонах без сервисов Google "
                + "голосовой ввод недоступен — там работает только текстовое поле";
    }

    /** Какие сервисы распознавания речи видит система от имени нашего приложения. */
    private List<String> serviceNames() {
        List<String> names = new ArrayList<>();
        List<ResolveInfo> found = getPackageManager()
                .queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        if (found == null) {
            return names;
        }
        for (ResolveInfo info : found) {
            if (info.serviceInfo == null) {
                continue;
            }
            boolean enabled = info.serviceInfo.enabled
                    && (info.serviceInfo.applicationInfo == null || info.serviceInfo.applicationInfo.enabled);
            names.add(info.serviceInfo.packageName + (enabled ? "" : " (выключен)"));
        }
        return names;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_MIC) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            log("[микрофон] разрешение получено.");
            if (armAfterPermission) {
                armAfterPermission = false;
                startListening(true);
            } else {
                startListening(false);
            }
        } else {
            log("[микрофон] без разрешения голосовой ввод не работает — вводите вопрос текстом.");
        }
    }

    // ------------------------------------------------------------------
    // Самопроверка
    // ------------------------------------------------------------------
    private void selfTest() {
        log("=== Проверка ===");
        final String key = keyField.getText().toString().trim();
        new Thread(() -> {
            log("[1/5] распознавание речи: " + describeEars());
            log("[2/5] озвучка: " + speaker.describe());
            log("[2/5] голоса движка: " + speaker.voiceReport());
            if (key.isEmpty()) {
                log("[3/5] ключ не задан — проверка модели пропущена.");
                log("=== Проверка закончена ===");
                return;
            }
            try {
                long ms = newClient(key).ping();
                log("[3/5] модель ответила за " + seconds(ms));
            } catch (Exception exc) {
                log("[3/5] модель недоступна: " + exc.getMessage());
                log("=== Проверка закончена ===");
                return;
            }
            log("[4/5] ищу в интернете «курс доллара к рублю»…");
            List<String> addresses = new ArrayList<>();
            try {
                String results = Tools.search("курс доллара к рублю", 3, this);
                log(shorten(results, 700));
                Matcher matcher = URL.matcher(results);
                while (matcher.find() && addresses.size() < 3) {
                    addresses.add(matcher.group());
                }
            } catch (Exception exc) {
                log("[4/5] поиск не удался: " + exc.getMessage());
            }
            if (addresses.isEmpty()) {
                log("[5/5] ссылки не нашлось — чтение страницы пропущено.");
                log("=== Проверка закончена ===");
                return;
            }
            // Сайты бывают закрыты от роботов: пробуем по очереди, как это делает модель
            for (String address : addresses) {
                log("[5/5] читаю " + address);
                String page = Tools.fetch(address, this);
                log(shorten(page, 400));
                if (!page.startsWith("Не удалось") && !page.startsWith("Страница вернула ошибку")
                        && !page.startsWith("На странице не нашлось")) {
                    break;
                }
            }
            log("=== Проверка закончена ===");
        }, "selftest").start();
    }

    // ------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------
    private static String seconds(long millis) {
        return String.format(Locale.US, "%.1f с", millis / 1000.0);
    }

    private static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
