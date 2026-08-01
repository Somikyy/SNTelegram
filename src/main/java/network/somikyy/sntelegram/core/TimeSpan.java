/*
 * SNTelegram - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sntelegram.core;

import java.util.Locale;

/**
 * Durations as an admin types them on a phone: {@code 10m}, {@code 2ч}, {@code 7d}, {@code навсегда}.
 *
 * <p>Both alphabets, because the person moderating is typing Russian into a Telegram app and
 * switching layouts to write {@code 10m} is exactly the kind of friction that makes people stop
 * using a tool. {@code 10м} must work.
 *
 * <p>Also renders durations back into Russian with correct plural forms - "1 минута", "2 минуты",
 * "5 минут". The rule is not decoration: a moderation bot that announces a ban for "5 минута"
 * reads as amateur work, and this is the text every member of the chat sees.
 */
public final class TimeSpan {

    /** Returned by {@link #parse} for "forever". */
    public static final long PERMANENT = -1L;

    /** Returned by {@link #parse} when the text is not a duration at all. */
    public static final long NOT_A_DURATION = 0L;

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;

    private TimeSpan() {
    }

    /**
     * Parses one duration token.
     *
     * @return milliseconds, {@link #PERMANENT}, or {@link #NOT_A_DURATION}
     */
    public static long parse(String text) {
        if (text == null) {
            return NOT_A_DURATION;
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return NOT_A_DURATION;
        }
        if (s.equals("навсегда") || s.equals("perm") || s.equals("permanent") || s.equals("forever")
                || s.equals("насовсем") || s.equals("∞")) {
            return PERMANENT;
        }

        int digits = 0;
        while (digits < s.length() && Character.isDigit(s.charAt(digits))) {
            digits++;
        }
        if (digits == 0) {
            return NOT_A_DURATION;
        }
        long amount;
        try {
            amount = Long.parseLong(s.substring(0, digits));
        } catch (NumberFormatException e) {
            return NOT_A_DURATION;
        }
        String unit = s.substring(digits).trim();

        long millis = switch (unit) {
            case "s", "sec", "с", "сек" -> amount * SECOND;
            // Bare number means minutes: "/mute 10" is what people actually type, and guessing
            // milliseconds there would be technically defensible and practically useless.
            case "", "m", "min", "м", "мин" -> amount * MINUTE;
            case "h", "hour", "ч", "час" -> amount * HOUR;
            case "d", "day", "д", "дн", "день" -> amount * DAY;
            case "w", "week", "н", "нед" -> amount * WEEK;
            default -> NOT_A_DURATION;
        };
        if (millis == NOT_A_DURATION) {
            return NOT_A_DURATION;
        }
        // A year, capped. Beyond that a "temporary" punishment is a permanent one wearing a
        // disguise, and overflow in the Date the ban API takes is a real possibility.
        return Math.min(millis, 365L * DAY);
    }

    /** True when {@code text} looks like a duration, so a parser can tell it from a reason. */
    public static boolean isDuration(String text) {
        return parse(text) != NOT_A_DURATION;
    }

    /** Renders a duration in Russian with the right plural form; "навсегда" for {@link #PERMANENT}. */
    public static String russian(long millis) {
        if (millis == PERMANENT) {
            return "навсегда";
        }
        if (millis <= 0) {
            return "нисколько";
        }
        if (millis >= WEEK && millis % WEEK == 0) {
            long n = millis / WEEK;
            return n + " " + plural(n, "неделю", "недели", "недель");
        }
        if (millis >= DAY) {
            long n = millis / DAY;
            return n + " " + plural(n, "день", "дня", "дней");
        }
        if (millis >= HOUR) {
            long n = millis / HOUR;
            return n + " " + plural(n, "час", "часа", "часов");
        }
        if (millis >= MINUTE) {
            long n = millis / MINUTE;
            return n + " " + plural(n, "минуту", "минуты", "минут");
        }
        long n = Math.max(1L, millis / SECOND);
        return n + " " + plural(n, "секунду", "секунды", "секунд");
    }

    /**
     * The Russian plural rule.
     *
     * <p>11 to 14 are the exception that catches every naive implementation: they take the
     * "many" form despite ending in 1 to 4. "11 минута" is the classic tell of a machine
     * translation, and it appears in a message the whole chat reads.
     */
    static String plural(long n, String one, String few, String many) {
        long abs = Math.abs(n);
        long lastTwo = abs % 100;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return many;
        }
        long last = abs % 10;
        if (last == 1) {
            return one;
        }
        if (last >= 2 && last <= 4) {
            return few;
        }
        return many;
    }
}
