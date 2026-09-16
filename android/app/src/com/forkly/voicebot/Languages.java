package com.forkly.voicebot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Определение языка реплики по алфавиту.
 *
 * <p>Зачем это нужно. Голосовой движок читает текст по правилам <b>своего</b> языка:
 * русский голос произносит английские слова русскими фонемами, отсюда и «акцент».
 * Никакая инструкция для модели это не исправляет — исправляется только тем, что
 * английский текст уходит к английскому голосу.
 *
 * <p>Язык определяется по алфавиту: русский и английский пишутся разными буквами,
 * поэтому для этой пары языков не нужен угадывающий детектор — достаточно посчитать
 * буквы. Работает даже внутри одной фразы и не ошибается.
 *
 * <p>Отдельный случай — смешанная фраза: «Слово apple значит яблоко». Её нельзя
 * отдать одному голосу: русский испортит именно то слово, ради которого фраза
 * сказана. Поэтому фраза режется по алфавитам, и каждый кусок читает свой голос.
 *
 * <p>Здесь только статические методы без обращения к Android, поэтому проверяются
 * обычной виртуальной машиной Java (см. probe-скрипт в корне проекта).
 */
final class Languages {

    static final String RU = "ru";
    static final String EN = "en";

    /** Кусок текста: буквы одного алфавита либо всё остальное (пробелы, цифры, знаки). */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z]+|[А-Яа-яЁё]+|[^A-Za-zА-Яа-яЁё]+");
    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");

    private Languages() {
    }

    /**
     * Язык текста: {@link #RU}, {@link #EN} или {@code null}, если букв нет вовсе.
     *
     * <p>{@code null} — не ошибка: фраза из одних цифр («42!») звучит одинаково на
     * любом языке, и голос для неё можно не менять.
     */
    static String detect(String text) {
        if (text == null) {
            return null;
        }
        int cyrillic = count(CYRILLIC, text);
        int latin = count(LATIN, text);
        if (cyrillic == 0 && latin == 0) {
            return null;
        }
        return cyrillic >= latin ? RU : EN;
    }

    /**
     * Разбить фразу по алфавитам: список пар {язык, текст}.
     *
     * <p>{@code minLetters} защищает от дёрганья голосом на мелочи: кусок с одной
     * латинской буквой («витамин D») отдельным голосом не читается, он остаётся
     * с соседним куском. Кусок без букв вовсе отдаётся русскому голосу — язык на
     * его звучание не влияет.
     */
    static List<String[]> split(String text, int minLetters) {
        List<Part> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            String language = detect(token);
            if (parts.isEmpty()) {
                parts.add(new Part(language == null ? RU : language, token));
                continue;
            }
            Part last = parts.get(parts.size() - 1);
            if (language == null || language.equals(last.language)) {
                // Ни цифры, ни знаки не повод менять голос посреди фразы
                last.text.append(token);
                last.letters += letters(token);
                continue;
            }
            parts.add(new Part(language, token));
        }

        List<String[]> result = new ArrayList<>();
        for (Part part : merge(parts, Math.max(1, minLetters))) {
            String chunk = part.text.toString().trim();
            if (!chunk.isEmpty()) {
                result.add(new String[]{part.language, chunk});
            }
        }
        return result;
    }

    /** Приклеить слишком короткие куски к соседу, чтобы голос не дёргался зря. */
    private static List<Part> merge(List<Part> parts, int minLetters) {
        List<Part> result = new ArrayList<>();
        for (Part part : parts) {
            if (!result.isEmpty() && part.letters < minLetters) {
                Part last = result.get(result.size() - 1);
                last.text.append(part.text);
                last.letters += part.letters;
                continue;
            }
            result.add(part);
        }
        // Короткий первый кусок мог остаться в одиночестве: отдаём его следующему
        if (result.size() > 1 && result.get(0).letters < minLetters) {
            Part first = result.remove(0);
            result.get(0).text.insert(0, first.text);
            result.get(0).letters += first.letters;
        }

        // Склейка коротких кусков могла снова поставить рядом два куска одного языка
        // («витамин D полезен»). Голос у них один и тот же, и дробить фразу незачем:
        // каждый лишний кусок — это отдельный вызов синтеза и лишний стык.
        List<Part> glued = new ArrayList<>();
        for (Part part : result) {
            if (!glued.isEmpty() && glued.get(glued.size() - 1).language.equals(part.language)) {
                Part last = glued.get(glued.size() - 1);
                last.text.append(part.text);
                last.letters += part.letters;
                continue;
            }
            glued.add(part);
        }
        return glued;
    }

    private static int letters(String token) {
        return count(CYRILLIC, token) + count(LATIN, token);
    }

    private static int count(Pattern pattern, String text) {
        int total = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            total++;
        }
        return total;
    }

    /** Кусок фразы: язык, накопленный текст и число букв (для склейки коротких кусков). */
    private static final class Part {
        private final String language;
        private final StringBuilder text;
        private int letters;

        private Part(String language, String start) {
            this.language = language;
            this.text = new StringBuilder(start);
            this.letters = Languages.letters(start);
        }
    }
}
