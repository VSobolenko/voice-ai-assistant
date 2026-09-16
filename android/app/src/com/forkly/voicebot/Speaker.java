package com.forkly.voicebot;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Рот ассистента: озвучка через системный TTS.
 *
 * <p>В Python-версии пришлось вручную собирать конвейер «синтез + воспроизведение»
 * в два потока, чтобы между предложениями не было пауз. Android умеет это сам:
 * фразы ставятся в очередь движка и читаются подряд. Поэтому здесь только
 * нарезка ответа на фразы по мере его поступления от модели.
 *
 * <p>Качество голоса целиком определяется движком, который стоит на телефоне,
 * поэтому тут есть две вещи, которые слышно сразу:
 * <ul>
 *   <li>движок Google выбирается явно — на части телефонов по умолчанию стоит
 *       чужой синтезатор, который читает русский «как робот»;</li>
 *   <li>из всех русских голосов движка берётся лучший по качеству, а голос
 *       «из сети» (он звучит живее) включается галочкой на экране. Если сети нет,
 *       голос молчит только первую фразу — ассистент сам переходит на голос
 *       в телефоне и повторяет её.</li>
 * </ul>
 */
final class Speaker {

    interface Log {
        void line(String text);
    }

    /** Позвать, когда ассистент закончил говорить (очередь опустела). */
    interface Idle {
        void onIdle();
    }

    /**
     * Сколько знаков набирать, прежде чем отдать фразу движку. Первую фразу режем
     * раньше (чтобы ответ начинал звучать быстрее), дальше — крупнее: на каждом
     * стыке движок заново выстраивает интонацию, и от частых стыков речь звучит
     * рубленой. Ровно поэтому в Python-версии короткие предложения склеивались.
     */
    private static final int MIN_CHARS_FIRST = 30;
    private static final int MIN_CHARS = 70;
    /** Кусок короче этого числа букв отдельный голос не получает («витамин D»). */
    private static final int MIX_MIN_LETTERS = 2;
    private static final String GOOGLE_ENGINE = "com.google.android.tts";

    /**
     * Предпочитать мужской английский голос. Пол в API озвучки не передаётся — его
     * можно только угадать по имени голоса (движок Google добавляет «#male_1»,
     * «#female_1») или по набору свойств, поэтому выбор такой: если мужской голос
     * в телефоне есть, берём его; если нет — остаётся прежнее правило «по стране,
     * затем по качеству». Только для английского: русский читает выбранный
     * женский голос, и менять его на ходу незачем.
     */
    private static final boolean PREFER_MALE_EN = true;

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?…]+[\"'»”’)]*(\\s|$)");
    private static final Pattern LINK = Pattern.compile("https?://\\S+");
    private static final Pattern MARKUP = Pattern.compile("[*_`#>|~]+");

    private final Log log;
    private final Idle idle;
    private final List<String[]> queuedBeforeReady = new ArrayList<>();
    private final Map<String, String[]> pending = new LinkedHashMap<>();

    private Context context;
    private TextToSpeech engine;
    private Voice voice;
    /** Голоса по языкам: английский текст нельзя читать русским голосом. */
    private Voice voiceRu;
    private Voice voiceEn;
    private boolean preferNetwork = true;
    private boolean googleTried;
    private boolean dead;
    private boolean ready;
    private boolean languageOk;
    private boolean englishOk;
    /** Выбранный английский голос — мужской (см. {@link #PREFER_MALE_EN}). */
    private boolean englishMale;
    private boolean speaking;
    private boolean turnActive;
    private boolean turnClosed;
    private boolean turnCancelled;
    private boolean networkRetried;
    private String buffer = "";
    private int spoken;
    private int finished;
    private int utteranceSeq;
    private long turnStartedAt;
    private long firstAudioAt;
    private long firstAudioDelayMs = -1;

    Speaker(Log log, Idle idle) {
        this.log = log;
        this.idle = idle;
    }

    void start(Context context, boolean networkVoice) {
        this.context = context.getApplicationContext();
        this.preferNetwork = networkVoice;
        openEngine(true);
    }

    /** Переключить «голос из сети» / «голос в телефоне» на ходу. */
    void setPreferNetwork(boolean value) {
        if (preferNetwork == value) {
            return;
        }
        preferNetwork = value;
        if (ready) {
            applyVoice();
            log.line("[рот] " + describe());
        }
    }

    /** Поднять движок: сначала пробуем Google, при неудаче — системный по умолчанию. */
    private void openEngine(boolean google) {
        googleTried = google;
        engine = google
                ? new TextToSpeech(context, this::onInit, GOOGLE_ENGINE)
                : new TextToSpeech(context, this::onInit);
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                if (turnActive && firstAudioAt == 0) {
                    firstAudioAt = System.currentTimeMillis();
                    firstAudioDelayMs = firstAudioAt - turnStartedAt;
                }
            }

            @Override
            public void onDone(String utteranceId) {
                pending.remove(utteranceId);
                finished++;
                // Опустевшая очередь ещё не значит «ответ закончился»: перед поиском
                // бот произносит одну фразу, и без этой проверки разговор считался бы
                // завершённым посреди ответа.
                if (turnActive && turnClosed && finished >= spoken) {
                    closeTurn();
                }
            }

            @Override
            public void onError(String utteranceId) {
                String[] item = pending.remove(utteranceId);
                finished++;
                if (item != null && voice != null && voice.isNetworkConnectionRequired() && !networkRetried) {
                    // Голос из сети не доехал (обрезался интернет) — молчание в ответ
                    // хуже плохого голоса: переходим на голос в телефоне и повторяем.
                    networkRetried = true;
                    preferNetwork = false;
                    log.line("[рот] голос из сети не сработал — перехожу на голос в телефоне");
                    applyVoice();
                    utterRaw(item[0], item[1]);
                } else {
                    log.line("[рот] не смог озвучить фразу (" + utteranceId + ")");
                }
                if (turnActive && turnClosed && finished >= spoken) {
                    closeTurn();
                }
            }
        });
    }

    private void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || engine == null) {
            if (googleTried) {
                log.line("[рот] движок Google не запустился — беру системный");
                if (engine != null) {
                    engine.shutdown();
                }
                openEngine(false);
                return;
            }
            log.line("[рот] движок озвучки не запустился — отвечать буду только текстом");
            dead = true;
            queuedBeforeReady.clear();
            if (turnActive && turnClosed) {
                closeTurn();
            }
            return;
        }
        engine.setPitch(1f);
        engine.setSpeechRate(1f);
        applyVoice();
        ready = true;
        for (String[] item : queuedBeforeReady) {
            utterRaw(item[0], item[1]);
        }
        queuedBeforeReady.clear();
        log.line("[рот] " + describe());
    }

    /** Выбрать лучшие голоса для обоих языков: русского и английского. */
    private void applyVoice() {
        languageOk = setLanguage("ru") && selectVoice(true);
        // Английский голос нужен для английских слов: русский голос читает их
        // русскими фонемами, и получается тот самый «акцент».
        englishOk = setLanguage("en") && selectVoice(false);
        englishMale = englishOk && voiceEn != null && looksMale(voiceEn);
        // Оставляем движок на русском: русской речи в занятии больше, а язык
        // каждой фразы всё равно выставляется перед её отправкой (utterRaw).
        setLanguage("ru");
        if (languageOk && voiceRu != null) {
            engine.setVoice(voiceRu);
            voice = voiceRu;
        }
    }

    /** Переключить язык движка. Возвращает false, если языка нет в телефоне. */
    private boolean setLanguage(String code) {
        Locale locale = Locale.forLanguageTag(code.equals(Languages.EN) ? "en-US" : "ru-RU");
        int available = engine.setLanguage(locale);
        return available != TextToSpeech.LANG_MISSING_DATA
                && available != TextToSpeech.LANG_NOT_SUPPORTED;
    }

    /** Выбрать лучший голос текущего языка движка. */
    private boolean selectVoice(boolean russian) {
        Voice best = chooseVoice(russian ? Languages.RU : Languages.EN);
        if (best != null && engine.setVoice(best) == TextToSpeech.SUCCESS) {
            voice = best;
            if (russian) {
                voiceRu = best;
            } else {
                voiceEn = best;
            }
            return true;
        }
        return false;
    }

    private Voice chooseVoice(String language) {
        Set<Voice> voices = engine.getVoices();
        if (voices == null) {
            return null;
        }
        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Voice candidate : voices) {
            Locale locale = candidate.getLocale();
            if (locale == null || !language.equals(locale.getLanguage())) {
                continue;
            }
            boolean network = candidate.isNetworkConnectionRequired();
            // Локальный голос без загруженных данных просто промолчит — не берём.
            if (!network && notInstalled(candidate)) {
                continue;
            }
            // Страна важнее «из сети или в телефоне»: для занятий английским
            // американский голос в телефоне лучше индийского из сети. Без этого
            // движок выбирал en-in — английский с индийским акцентом. Пол стоит
            // между страной и качеством: мужской английский голос с обычным
            // качеством звучит для занятий лучше женского с очень высоким.
            int score = countryScore(language, locale.getCountry()) * 1000
                    + (maleWanted(language) && looksMale(candidate) ? 100 : 0)
                    + qualityScore(candidate.getQuality())
                    + (network == preferNetwork ? 8 : 0);
            if (score > bestScore || (score == bestScore && best != null
                    && candidate.getName().compareTo(best.getName()) < 0)) {
                // При равном счёте берём имя по алфавиту: выбор должен быть
                // одинаковым на одном устройстве, иначе голос «прыгает».
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /** Насколько голос подходит по стране: для английского лучше США, затем Британия. */
    private static int countryScore(String language, String country) {
        if (country == null) {
            return 0;
        }
        if (Languages.RU.equals(language)) {
            return "RU".equalsIgnoreCase(country) ? 2 : 0;
        }
        if ("US".equalsIgnoreCase(country)) {
            return 2;
        }
        return "GB".equalsIgnoreCase(country) ? 1 : 0;
    }

    /** Хотим ли для этого языка мужской голос. */
    private static boolean maleWanted(String language) {
        return PREFER_MALE_EN && Languages.EN.equals(language);
    }

    /**
     * Похож ли голос на мужской. Официального признака пола в Android нет, поэтому
     * смотрим на то, что движок всё-таки сообщает: набор свойств иногда содержит
     * «male», а имя голоса Google умеет выглядеть как «en-us-x-sfg#male_1-local».
     * Проверка намеренно осторожная — лучше не узнать мужской голос, чем взять
     * женский и решить, что это он.
     */
    private static boolean looksMale(Voice candidate) {
        Set<String> features = candidate.getFeatures();
        if (features != null && features.contains("male") && !features.contains("female")) {
            return true;
        }
        String name = candidate.getName();
        if (name == null) {
            return false;
        }
        String lowered = name.toLowerCase(Locale.US);
        return lowered.contains("#male") || lowered.contains("_male") || lowered.contains("-male");
    }

    private static boolean notInstalled(Voice candidate) {
        Set<String> features = candidate.getFeatures();
        return features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    private static int qualityScore(int quality) {
        switch (quality) {
            case Voice.QUALITY_VERY_HIGH:
                return 40;
            case Voice.QUALITY_HIGH:
                return 30;
            case Voice.QUALITY_NORMAL:
                return 20;
            case Voice.QUALITY_LOW:
                return 10;
            default:
                return 0;
        }
    }

    private static String qualityName(int quality) {
        switch (quality) {
            case Voice.QUALITY_VERY_HIGH:
                return "очень высокое";
            case Voice.QUALITY_HIGH:
                return "высокое";
            case Voice.QUALITY_NORMAL:
                return "обычное";
            case Voice.QUALITY_LOW:
                return "низкое";
            default:
                return "неизвестное";
        }
    }

    private String engineName() {
        return googleTried ? "Google TTS" : "системный TTS";
    }

    String describe() {
        if (engine == null) {
            return "не создан";
        }
        if (dead) {
            return "недоступна: движок озвучки не запустился";
        }
        if (!ready) {
            return "инициализируется";
        }
        if (!languageOk) {
            return engineName() + ": русского голоса нет — говорю голосом по умолчанию "
                    + "(поставьте «Речевые сервисы Google» и русский язык)";
        }
        String russian = voice == null
                ? "русский голос есть, но выбрать лучший не удалось"
                : voice.getName() + " (качество " + qualityName(voice.getQuality())
                        + ", " + (voice.isNetworkConnectionRequired() ? "из сети" : "в телефоне") + ")";
        // Про английский голос пишем отдельно и заметно: без него английские слова
        // читаются русскими фонемами, и это ровно тот «акцент», на который жалуются.
        String english = englishOk && voiceEn != null
                ? voiceEn.getName() + (englishMale ? " (мужской)" : "")
                        + (maleWanted(Languages.EN) && !englishMale
                                ? " — мужского английского голоса в телефоне нет" : "")
                : "НЕТ — английские слова читаю русским голосом, с акцентом";
        return engineName() + " — русский: " + russian + "; английский: " + english;
    }

    /**
     * Голоса, из которых движок реально выбирает: русский и американский либо
     * британский английский.
     *
     * <p>Полный список голосов занимает тысячи символов и обрезается в логе, а
     * смотреть в нём не на что: остальное — английский других стран (Индия,
     * Австралия, Нигерия), который для занятий не подходит.
     */
    String voiceReport() {
        if (engine == null || !ready) {
            return "движок не готов";
        }
        Set<Voice> voices = engine.getVoices();
        if (voices == null) {
            return "движок не отдал список голосов";
        }
        List<String> russian = new ArrayList<>();
        List<String> english = new ArrayList<>();
        int skipped = 0;
        for (Voice candidate : voices) {
            Locale locale = candidate.getLocale();
            if (locale == null) {
                continue;
            }
            String language = locale.getLanguage();
            if ((!Languages.RU.equals(language) && !Languages.EN.equals(language))
                    || countryScore(language, locale.getCountry()) == 0) {
                skipped++;
                continue;
            }
            String line = candidate.getName() + " (качество " + qualityName(candidate.getQuality())
                    + ", " + (candidate.isNetworkConnectionRequired() ? "из сети" : "в телефоне")
                    + (looksMale(candidate) ? ", мужской" : "")
                    + (notInstalled(candidate) ? ", данные не загружены" : "") + ")";
            (Languages.RU.equals(language) ? russian : english).add(line);
        }
        Collections.sort(russian);
        Collections.sort(english);
        String ru = russian.isEmpty() ? "нет" : String.join("; ", russian);
        String en = english.isEmpty() ? "нет" : String.join("; ", english);
        return "годные — русские: " + ru + " | английские: " + en
                + " | прочих голосов пропущено: " + skipped;
    }

    /** Начать новую реплику: обнуляем счётчики, но не трогаем то, что уже звучит. */
    void begin() {
        buffer = "";
        spoken = 0;
        finished = 0;
        firstAudioAt = 0;
        firstAudioDelayMs = -1;
        turnStartedAt = System.currentTimeMillis();
        turnActive = true;
        turnClosed = false;
        turnCancelled = false;
        networkRetried = false;
    }

    /** Принять очередной кусок ответа от модели и озвучить то, что уже сложилось во фразу. */
    void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        buffer += chunk;
        int cut = readyPrefix(buffer, spoken == 0 ? MIN_CHARS_FIRST : MIN_CHARS);
        if (cut <= 0) {
            return;
        }
        String phrase = tidy(buffer.substring(0, cut));
        buffer = buffer.substring(cut);
        if (!phrase.isEmpty()) {
            utter(phrase);
        }
    }

    /** Ответ закончился — озвучиваем остаток. */
    void finish() {
        String phrase = tidy(buffer);
        buffer = "";
        if (!phrase.isEmpty()) {
            utter(phrase);
        }
        turnClosed = true;
        if (dead) {
            // Озвучки нет вовсе — ждать её бессмысленно, иначе разговор встанет.
            if (turnActive) {
                closeTurn();
            }
            return;
        }
        if (turnActive && finished >= spoken) {
            closeTurn();
        }
    }

    /**
     * Произнести одну служебную фразу («Скопировал») — это не ответ модели.
     * Идёт через тот же конвейер, поэтому после неё разговор продолжается сам.
     */
    void say(String phrase) {
        begin();
        feed(phrase);
        finish();
    }

    /** Замолчать немедленно: человек начал говорить. */
    void stop() {
        if (engine != null && engine.isSpeaking()) {
            engine.stop();
            log.line("[рот] замолчал: вы начали говорить");
        }
        if (turnActive) {
            // Прерванную реплику движок не доиграет и onDone по ней не позовёт:
            // закрываем её сами, иначе разговор застрянет на «говорю…».
            turnCancelled = true;
            closeTurn();
        }
    }

    long firstAudioDelayMs() {
        return firstAudioDelayMs;
    }

    int phrases() {
        return spoken;
    }

    /**
     * Реплика ещё не закончена: модель пишет ответ или движок его проговаривает.
     * По этому признаку микрофон не включают — иначе он услышит сам ассистента.
     */
    boolean turnBusy() {
        return turnActive || !queuedBeforeReady.isEmpty();
    }

    /**
     * Отправить фразу на озвучку. Смешанную фразу («Слово apple значит яблоко»)
     * режем по алфавитам: иначе русский голос прочитает английское слово
     * русскими фонемами — а оно в такой фразе самое важное.
     */
    private void utter(String phrase) {
        List<String[]> parts = Languages.split(phrase, MIX_MIN_LETTERS);
        if (parts.isEmpty()) {
            return;
        }
        for (String[] part : parts) {
            spoken++;
            utterRaw(part[0], part[1]);
        }
    }

    /** Отправить кусок в очередь движка: до готовности TTS куски копятся. */
    private void utterRaw(String language, String phrase) {
        if (engine == null || !ready) {
            queuedBeforeReady.add(new String[]{language, phrase});
            return;
        }
        if (turnCancelled) {
            return;
        }
        // Язык выставляем перед каждой фразой: движок читает текст по правилам
        // того языка, который стоит в этот момент, а куски в очереди — разные.
        if (Languages.EN.equals(language) && englishOk) {
            setLanguage(Languages.EN);
            if (voiceEn != null) {
                engine.setVoice(voiceEn);
            }
        } else if (languageOk) {
            setLanguage(Languages.RU);
            if (voiceRu != null) {
                engine.setVoice(voiceRu);
            }
        }
        String id = "фраза-" + (++utteranceSeq);
        pending.put(id, new String[]{language, phrase});
        speaking = true;
        engine.speak(phrase, TextToSpeech.QUEUE_ADD, new Bundle(), id);
    }

    private void closeTurn() {
        turnActive = false;
        speaking = false;
        idle.onIdle();
    }

    private static int readyPrefix(String text, int minChars) {
        Matcher matcher = SENTENCE_END.matcher(text);
        int cut = 0;
        int start = 0;
        while (matcher.find()) {
            if (matcher.end() - start >= minChars) {
                cut = matcher.end();
                start = matcher.end();
            }
        }
        return cut;
    }

    /** Убрать разметку и ссылки: вслух они звучат мусором. */
    private static String tidy(String phrase) {
        if (phrase == null || phrase.isEmpty()) {
            return "";
        }
        String text = LINK.matcher(phrase).replaceAll("ссылка");
        text = MARKUP.matcher(text).replaceAll(" ");
        return text.replaceAll("\\s+", " ").trim();
    }
}
