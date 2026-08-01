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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything from {@code config.yml}, validated once and immutable afterwards.
 *
 * <p>Two rules shape this class.
 *
 * <p><b>Clamp, do not refuse.</b> Every numeric setting is pulled into a range that works. A
 * bridge that will not start because someone typed {@code poll-timeout: 300} has helped nobody;
 * one that starts with 50 and says so in the log has.
 *
 * <p><b>Complain in one place.</b> Problems found while loading go into {@link #warnings()} and
 * are printed together at startup, in Russian, naming the setting. Config mistakes are the single
 * most common support case for a plugin whose whole job is talking to an external service, and
 * the admin should learn about all of them at once rather than one restart at a time.
 */
public final class Config {

    // ---------------------------------------------------------------- connection

    private final String token;
    private final long chatId;
    private final String baseUrl;
    private final String proxyHost;
    private final int proxyPort;
    private final int pollSeconds;
    private final boolean dropBacklog;

    // ---------------------------------------------------------------- pacing

    private final double sendsPerSecond;
    private final double sendsPerChatPerMinute;
    private final int queueSize;
    private final long queueMaxAgeSeconds;

    // ---------------------------------------------------------------- behaviour

    private final List<Topic> topics;
    private final boolean moderationEnabled;
    private final Set<Long> admins;
    private final boolean trustChatAdmins;
    private final boolean showTelegramNamesInGame;
    private final boolean updateCheck;
    private final Templates templates;

    private final List<String> warnings;

    private Config(Builder b) {
        this.token = b.token;
        this.chatId = b.chatId;
        this.baseUrl = b.baseUrl;
        this.proxyHost = b.proxyHost;
        this.proxyPort = b.proxyPort;
        this.pollSeconds = b.pollSeconds;
        this.dropBacklog = b.dropBacklog;
        this.sendsPerSecond = b.sendsPerSecond;
        this.sendsPerChatPerMinute = b.sendsPerChatPerMinute;
        this.queueSize = b.queueSize;
        this.queueMaxAgeSeconds = b.queueMaxAgeSeconds;
        this.topics = List.copyOf(b.topics);
        this.moderationEnabled = b.moderationEnabled;
        this.admins = Set.copyOf(b.admins);
        this.trustChatAdmins = b.trustChatAdmins;
        this.showTelegramNamesInGame = b.showTelegramNamesInGame;
        this.updateCheck = b.updateCheck;
        this.templates = b.templates;
        this.warnings = List.copyOf(b.warnings);
    }

    /** Message templates, kept together so the format section can be replaced as a unit. */
    public record Templates(String chat, String join, String quit, String death, String advancement,
                            String serverStart, String serverStop, String moderation,
                            String fromTelegram) {
    }

    // ---------------------------------------------------------------- loading

    public static Config load(String yaml) {
        MiniYaml y = MiniYaml.parse(yaml == null ? "" : yaml);
        Builder b = new Builder();

        b.token = y.get("telegram.token", "").trim();
        b.chatId = y.getId("telegram.chat-id", 0L);
        b.baseUrl = y.get("telegram.base-url", TelegramApi.DEFAULT_BASE_URL).trim();
        b.proxyHost = y.get("telegram.proxy.host", "").trim();
        b.proxyPort = y.getInt("telegram.proxy.port", 0, 0, 65535);
        // 50 is the Bot API's own ceiling for getUpdates; 1 keeps a debugging admin from
        // accidentally configuring a busy-loop against Telegram.
        b.pollSeconds = y.getInt("telegram.poll-timeout", 30, 1, 50);
        b.dropBacklog = y.getBoolean("telegram.drop-backlog", true);

        // Defaults sit below Telegram's documented ceilings on purpose: the limits are not
        // contractual and the cost of being slightly under is a few hundred milliseconds,
        // while the cost of being over is a throttled bot.
        b.sendsPerSecond = y.getInt("limits.per-second", 25, 1, 30);
        b.sendsPerChatPerMinute = y.getInt("limits.per-chat-per-minute", 18, 1, 60);
        b.queueSize = y.getInt("limits.queue-size", 500, 16, 20_000);
        b.queueMaxAgeSeconds = y.getLong("limits.max-age-seconds", 120L, 5L, 3600L);

        b.moderationEnabled = y.getBoolean("moderation.enabled", true);
        b.trustChatAdmins = y.getBoolean("moderation.trust-chat-admins", true);
        for (String raw : y.getList("moderation.admins")) {
            try {
                b.admins.add(Long.parseLong(raw.replace("_", "").trim()));
            } catch (NumberFormatException e) {
                b.warnings.add("В moderation.admins значение «" + raw + "» — это не числовой "
                        + "Telegram-id. Узнать свой id можно у бота @userinfobot.");
            }
        }

        b.showTelegramNamesInGame = y.getBoolean("general.show-telegram-names", true);
        b.updateCheck = y.getBoolean("general.update-check", true);

        // Two template languages live here, and mixing them up produces output that looks
        // almost right. Everything going TO Telegram is HTML with {braces} substituted as plain
        // strings; the one template going INTO the game is MiniMessage with <angle> placeholders
        // resolved by MiniMessage itself. Braces cannot be used for the latter, because
        // substituting into MiniMessage source is exactly the injection this plugin avoids.
        b.templates = new Templates(
                y.get("format.chat", "<b>{player}</b>: {message}"),
                y.get("format.join", "▸ <b>{player}</b> зашёл на сервер"),
                y.get("format.quit", "◂ <b>{player}</b> вышел"),
                y.get("format.death", "☠ {message}"),
                y.get("format.advancement", "★ <b>{player}</b> получил достижение «{advancement}»"),
                y.get("format.server-start", "🟢 Сервер запущен"),
                y.get("format.server-stop", "🔴 Сервер остановлен"),
                // {message} is the sentence the moderation code already produced - it knows the
                // verb, the target and the duration, and no template could reassemble those
                // without duplicating the Russian grammar that produced them.
                y.get("format.moderation", "⚖ {message}"),
                y.get("format.from-telegram", "<gray>[TG]</gray> <white><user></white><gray>:</gray> <message>"));

        loadTopics(y, b);
        validate(b);
        return new Config(b);
    }

    private static void loadTopics(MiniYaml y, Builder b) {
        List<String> names = y.childrenOf("topics");
        if (names.isEmpty()) {
            // A config with no topics is a fresh install that has not been filled in. Give it a
            // working default rather than nothing: one topic, the General one, carrying chat and
            // the join/quit lines, in both directions. That is the setup 90% of servers want.
            b.topics.add(new Topic("основной", Topic.GENERAL,
                    EnumSet.of(EventKind.CHAT, EventKind.JOIN, EventKind.QUIT, EventKind.DEATH,
                            EventKind.SERVER, EventKind.MODERATION),
                    true, "[TG]"));
            return;
        }
        Set<Integer> usedThreads = new LinkedHashSet<>();
        for (String name : names) {
            String path = "topics." + name;
            int threadId = y.getInt(path + ".thread-id", Topic.GENERAL, 0, Integer.MAX_VALUE);
            if (threadId == 1) {
                // 1 is never a valid thread id in the Bot API. It is General's id at the MTProto
                // level - which is what every tutorial repeats - and the Bot API filters that
                // constant out on the way in and on the way out, so sending it answers 400
                // "message thread not found". An admin who wrote 1 wanted General and there is no
                // other thing they could have wanted, so take it as General and say so: the
                // alternative is a main chat topic where nothing works at all.
                b.warnings.add("В теме «" + name + "» указан thread-id: 1. Это распространённая "
                        + "ошибка: у главной темы (General) в Bot API нет номера, и значение 1 "
                        + "Telegram отвергает. Читаю как General; впиши 0, чтобы предупреждение "
                        + "не повторялось.");
                threadId = Topic.GENERAL;
            }
            Set<EventKind> kinds = EnumSet.noneOf(EventKind.class);
            List<String> declared = y.getList(path + ".events");
            if (declared.isEmpty()) {
                kinds.add(EventKind.CHAT);
            }
            for (String raw : declared) {
                if (raw.equalsIgnoreCase("all") || raw.equalsIgnoreCase("всё") || raw.equals("*")) {
                    kinds.addAll(EnumSet.allOf(EventKind.class));
                    continue;
                }
                EventKind kind = EventKind.parse(raw);
                if (kind == null) {
                    b.warnings.add("В topics." + name + ".events указано «" + raw
                            + "» — такого события нет. Доступны: chat, join, quit, death, "
                            + "advancement, server, moderation.");
                } else {
                    kinds.add(kind);
                }
            }
            if (!usedThreads.add(threadId)) {
                b.warnings.add("Тема «" + name + "» использует thread-id " + threadId
                        + ", который уже занят другой темой. Сообщения из неё будет невозможно "
                        + "отличить от сообщений соседней темы.");
            }
            b.topics.add(new Topic(name, threadId, kinds,
                    y.getBoolean(path + ".from-telegram", true),
                    y.get(path + ".prefix", "[TG]")));
        }
    }

    private static void validate(Builder b) {
        if (b.token.isEmpty()) {
            b.warnings.add("Не указан telegram.token — мост не запустится. Токен выдаёт "
                    + "@BotFather командой /newbot.");
        } else if (b.token.indexOf(':') <= 0) {
            b.warnings.add("Значение telegram.token не похоже на токен: настоящий выглядит как "
                    + "123456789:AA... Проверь, что скопирован он целиком.");
        }
        if (b.chatId == 0L) {
            b.warnings.add("Не указан telegram.chat-id — мост не запустится. Для группы это "
                    + "отрицательное число; узнать его можно, добавив в группу @getmyid_bot.");
        } else if (b.chatId > 0L) {
            // A positive id is a private chat with one person. Legal, and occasionally what an
            // admin wants, but nine times out of ten it means they pasted their own id instead
            // of the group's - and then wonder why nobody else sees anything.
            b.warnings.add("telegram.chat-id положительный — это личный чат, а не группа. "
                    + "Если мост должен работать в группе, id должен начинаться с минуса.");
        }
        if (b.moderationEnabled && b.admins.isEmpty() && !b.trustChatAdmins) {
            b.warnings.add("Модерация включена, но список moderation.admins пуст и "
                    + "moderation.trust-chat-admins выключен — модерировать не сможет никто.");
        }
        boolean anyInbound = false;
        for (Topic t : b.topics) {
            anyInbound |= t.fromTelegram();
        }
        if (!anyInbound) {
            b.warnings.add("Ни одна тема не принимает сообщения из Telegram "
                    + "(from-telegram: false везде) — мост работает только в одну сторону.");
        }
    }

    // ---------------------------------------------------------------- accessors

    public String token() {
        return token;
    }

    public long chatId() {
        return chatId;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String proxyHost() {
        return proxyHost;
    }

    public int proxyPort() {
        return proxyPort;
    }

    public int pollSeconds() {
        return pollSeconds;
    }

    public boolean dropBacklog() {
        return dropBacklog;
    }

    public double sendsPerSecond() {
        return sendsPerSecond;
    }

    public double sendsPerChatPerMinute() {
        return sendsPerChatPerMinute;
    }

    public int queueSize() {
        return queueSize;
    }

    public long queueMaxAgeSeconds() {
        return queueMaxAgeSeconds;
    }

    public List<Topic> topics() {
        return topics;
    }

    /** The topic a given event kind goes to, or {@code null} when nothing carries it. */
    public Topic topicFor(EventKind kind) {
        for (Topic t : topics) {
            if (t.carries(kind)) {
                return t;
            }
        }
        return null;
    }

    /** The topic a Telegram message arrived in, matched by thread id. */
    public Topic topicByThread(Integer threadId) {
        int id = threadId == null ? Topic.GENERAL : threadId;
        for (Topic t : topics) {
            if (t.threadId() == id) {
                return t;
            }
        }
        return null;
    }

    public boolean moderationEnabled() {
        return moderationEnabled;
    }

    public Set<Long> admins() {
        return admins;
    }

    public boolean trustChatAdmins() {
        return trustChatAdmins;
    }

    public boolean showTelegramNamesInGame() {
        return showTelegramNamesInGame;
    }

    public boolean updateCheck() {
        return updateCheck;
    }

    public Templates templates() {
        return templates;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** True when there is enough to attempt a connection at all. */
    public boolean usable() {
        return !token.isEmpty() && chatId != 0L;
    }

    private static final class Builder {
        String token = "";
        long chatId;
        String baseUrl = TelegramApi.DEFAULT_BASE_URL;
        String proxyHost = "";
        int proxyPort;
        int pollSeconds = 30;
        boolean dropBacklog = true;
        double sendsPerSecond = 25;
        double sendsPerChatPerMinute = 18;
        int queueSize = 500;
        long queueMaxAgeSeconds = 120L;
        final List<Topic> topics = new ArrayList<>();
        boolean moderationEnabled = true;
        final Set<Long> admins = new LinkedHashSet<>();
        boolean trustChatAdmins = true;
        boolean showTelegramNamesInGame = true;
        boolean updateCheck = true;
        Templates templates;
        final List<String> warnings = new ArrayList<>();
    }
}
