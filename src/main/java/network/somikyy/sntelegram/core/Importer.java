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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a competing plugin's configuration and writes the equivalent SNTelegram one.
 *
 * <p>Migration is a required feature of every plugin in the suite, and for a bridge it is the
 * only barrier that actually matters. An admin already running one has a bot token, a chat id and
 * a set of topic ids scattered across three screens of somebody else's YAML; the difference
 * between "paste four values" and "find four values again" is the difference between switching
 * and not bothering.
 *
 * <p>Only formats that were read from the source are supported. Every field mapped here comes
 * from the other plugin's own configuration file or its model classes - nothing is inferred from
 * a screenshot or a forum post, because an importer that quietly gets a token wrong is worse than
 * no importer at all.
 *
 * <p>The result is written to a separate file for the admin to check, never over the live
 * config: one of the values being moved is a secret, and silently overwriting it is not
 * something a plugin gets to do.
 */
public final class Importer {

    private Importer() {
    }

    /** What the importer can read. Drives the command's tab completion and its help text. */
    public static List<String> sources() {
        return List.of("flectonepulse", "mctgbridge", "discordsrv");
    }

    /**
     * @param found whether the source plugin's config was located at all
     * @param yaml  a complete SNTelegram config.yml
     * @param moved how many settings were carried across
     * @param note  why nothing was found, when {@code found} is false
     * @param notes things the admin must check by hand
     */
    public record Result(boolean found, String yaml, int moved, String note, List<String> notes) {

        static Result missing(String note) {
            return new Result(false, "", 0, note, List.of());
        }
    }

    public static Result importFrom(String source, Path pluginsDir) throws IOException {
        return switch (source.toLowerCase(Locale.ROOT)) {
            case "flectonepulse", "flectone" -> fromFlectonePulse(pluginsDir);
            case "mctgbridge", "minecraft-telegram-bridge" -> fromMctgBridge(pluginsDir);
            case "discordsrv" -> fromDiscordSrv(pluginsDir);
            default -> Result.missing("Импорт из «" + source + "» не поддерживается. Доступно: "
                    + String.join(", ", sources()) + ".");
        };
    }

    // ---------------------------------------------------------------- FlectonePulse

    /**
     * FlectonePulse keeps its Telegram settings in {@code integration.yml}, not {@code config.yml}.
     *
     * <p>Its chat routing is a map from an internal message-type constant to a list of chat ids,
     * so the closest honest translation is: take the id used for global chat, and leave the topic
     * layout to the admin - FlectonePulse has no notion of an admin topic to carry across.
     */
    private static Result fromFlectonePulse(Path pluginsDir) throws IOException {
        Path file = pluginsDir.resolve("FlectonePulse").resolve("integration.yml");
        if (!Files.exists(file)) {
            return Result.missing("Ожидался файл plugins/FlectonePulse/integration.yml — "
                    + "именно там FlectonePulse хранит настройки Telegram, а не в config.yml.");
        }
        MiniYaml y = MiniYaml.parse(Files.readString(file, StandardCharsets.UTF_8));
        List<String> notes = new ArrayList<>();
        int moved = 0;

        String token = y.get("telegram.token", "");
        if (!token.isEmpty()) {
            moved++;
        }
        long chatId = 0L;
        for (String key : List.of("telegram.message_channel.MESSAGE_CHAT_GLOBAL",
                "telegram.message_channel.INTEGRATION_TELEGRAM")) {
            for (String raw : y.getList(key)) {
                try {
                    chatId = Long.parseLong(raw.trim());
                    break;
                } catch (NumberFormatException ignored) {
                    // Not a bare id: FlectonePulse also accepts other forms here. Skip it and
                    // let the admin fill the value in - a wrong chat id is worse than a blank.
                }
            }
            if (chatId != 0L) {
                moved++;
                break;
            }
        }
        if (chatId == 0L) {
            notes.add("Не удалось однозначно определить chat-id: в FlectonePulse он лежит в "
                    + "telegram.message_channel и может быть задан не числом. Впиши вручную.");
        }

        String proxyHost = "";
        int proxyPort = 0;
        if (!"DIRECT".equalsIgnoreCase(y.get("telegram.proxy.type", "DIRECT"))) {
            proxyHost = y.get("telegram.proxy.host", "");
            proxyPort = y.getInt("telegram.proxy.port", 0, 0, 65535);
            if (!proxyHost.isEmpty()) {
                moved++;
                notes.add("Прокси перенесён как HTTP. FlectonePulse умеет SOCKS — если у тебя "
                        + "SOCKS-прокси, SNTelegram его не поддерживает, и поле нужно очистить.");
            }
        }
        if (!y.getBoolean("telegram.enable", false)) {
            notes.add("В FlectonePulse интеграция с Telegram была выключена (enable: false). "
                    + "Проверь, что токен ещё действителен.");
        }
        notes.add("Модерации из Telegram у FlectonePulse нет — переносить было нечего. "
                + "Список админов задаётся в moderation.admins или берётся из админов группы.");

        return new Result(true, render(token, chatId, proxyHost, proxyPort, defaultTopics(), null),
                moved, "", notes);
    }

    // ---------------------------------------------------------------- minecraft-telegram-bridge

    /**
     * The archived {@code ntoneee/minecraft-telegram-bridge}: the direct ancestor of this plugin.
     *
     * <p>Archived on 21 July 2024 with its author pointing elsewhere, Russian-localised, and built
     * around exactly the idea SNTelegram is built around - a public chat plus a separate admin
     * chat. Its users are the ones with nowhere to go, so its config is the one worth reading
     * most carefully. Its two chats become two topics.
     */
    private static Result fromMctgBridge(Path pluginsDir) throws IOException {
        Path file = null;
        for (String folder : List.of("Minecraft-Telegram_Bridge", "Minecraft-Telegram-Bridge",
                "MinecraftTelegramBridge", "mctgbridge")) {
            Path candidate = pluginsDir.resolve(folder).resolve("config.yml");
            if (Files.exists(candidate)) {
                file = candidate;
                break;
            }
        }
        if (file == null) {
            return Result.missing("Не найдена папка Minecraft-Telegram_Bridge в plugins/.");
        }
        MiniYaml y = MiniYaml.parse(Files.readString(file, StandardCharsets.UTF_8));
        List<String> notes = new ArrayList<>();
        int moved = 0;

        String token = y.get("telegram-token", "");
        if (!token.isEmpty()) {
            moved++;
        }
        long chatId = y.getId("telegram-chat-id", 0L);
        if (chatId != 0L) {
            moved++;
        }
        long adminChatId = y.getId("telegram-admin-chat-id", 0L);

        // Its switches map one to one onto our event kinds.
        StringBuilder events = new StringBuilder("chat");
        if (y.getBoolean("bridge-to-telegram.join-leave", true)) {
            events.append(", join, quit");
            moved++;
        }
        if (y.getBoolean("bridge-to-telegram.death", true)) {
            events.append(", death");
            moved++;
        }
        if (y.getBoolean("bridge-to-telegram.advancements.goal", true)
                || y.getBoolean("bridge-to-telegram.advancements.task", true)
                || y.getBoolean("bridge-to-telegram.advancements.challenge", true)) {
            events.append(", advancement");
            moved++;
        }
        if (y.getBoolean("bridge-to-telegram.server-state.enable", true)) {
            events.append(", server");
            moved++;
        }

        String topics = "  основной:\n"
                + "    thread-id: 0\n"
                + "    events: [" + events + "]\n"
                + "    from-telegram: true\n"
                + "    prefix: '[TG]'\n";
        if (adminChatId != 0L && adminChatId != chatId) {
            // Its admin chat was a separate chat, not a topic. One bot can only poll one chat at
            // a time in this design, so this becomes a topic and the admin is told to move it.
            notes.add("У тебя был отдельный админ-чат (" + adminChatId + "). SNTelegram работает "
                    + "с одной группой и раскладывает потоки по темам форума, поэтому админский "
                    + "поток стал темой: включи в группе темы, создай тему для админов и впиши "
                    + "её thread-id вместо 2 в разделе topics.админка.");
            topics += "  админка:\n"
                    + "    thread-id: 2\n"
                    + "    events: [moderation, server]\n"
                    + "    from-telegram: true\n"
                    + "    prefix: '[админ]'\n";
            moved++;
        }
        notes.add("Автообновляемое сообщение со списком игроков (telegram-list-message-id) "
                + "SNTelegram не поддерживает — вместо него команда /онлайн в чате.");

        return new Result(true, render(token, chatId, "", 0, topics, null), moved, "", notes);
    }

    // ---------------------------------------------------------------- DiscordSRV

    /**
     * DiscordSRV, for servers leaving Discord after the block.
     *
     * <p>Nothing secret transfers: a Discord bot token is useless to Telegram, so the admin has
     * to visit BotFather regardless. What does transfer is the shape of their setup - which
     * in-game channels they had, whether chat flowed both ways, and their proxy - and that is the
     * part that takes an afternoon to reconstruct by hand.
     *
     * <p>The channel map is the awkward one: DiscordSRV writes it as inline JSON inside YAML,
     * {@code Channels: {"global": "000000000000000000"}}, so a YAML reader hands back a string
     * that then has to be parsed as JSON. Which is exactly what happens below.
     */
    private static Result fromDiscordSrv(Path pluginsDir) throws IOException {
        Path file = pluginsDir.resolve("DiscordSRV").resolve("config.yml");
        if (!Files.exists(file)) {
            return Result.missing("Не найден файл plugins/DiscordSRV/config.yml.");
        }
        MiniYaml y = MiniYaml.parse(Files.readString(file, StandardCharsets.UTF_8));
        List<String> notes = new ArrayList<>();
        int moved = 0;

        boolean toGame = y.getBoolean("DiscordChatChannelDiscordToMinecraft", true);
        boolean toChat = y.getBoolean("DiscordChatChannelMinecraftToDiscord", true);

        StringBuilder topics = new StringBuilder();
        String channels = y.get("Channels", "");
        int threadId = 0;
        if (!channels.isBlank()) {
            try {
                Object parsed = Json.parse(channels).raw();
                if (parsed instanceof Map<?, ?> map) {
                    for (Object key : map.keySet()) {
                        String name = String.valueOf(key);
                        topics.append("  ").append(safeName(name)).append(":\n")
                                .append("    thread-id: ").append(threadId).append('\n')
                                .append("    events: [")
                                .append(threadId == 0 ? "chat, join, quit, death, server" : "chat")
                                .append("]\n")
                                .append("    from-telegram: ").append(toGame).append('\n')
                                .append("    prefix: '[TG]'\n");
                        // Only the first can be General; the rest need real topics the admin
                        // has to create, so they get placeholders to fill in.
                        threadId = threadId == 0 ? 2 : threadId + 1;
                        moved++;
                    }
                }
            } catch (Json.JsonException e) {
                notes.add("Список каналов Channels не удалось разобрать — темы созданы по "
                        + "умолчанию, проверь раздел topics.");
            }
        }
        if (topics.length() == 0) {
            topics.append(defaultTopics());
        } else if (threadId > 2) {
            notes.add("Каналов было несколько. Темы с thread-id 2 и дальше — заглушки: создай "
                    + "темы в группе Telegram и впиши их настоящие id.");
        }
        if (!toChat) {
            notes.add("В DiscordSRV пересылка из игры была выключена. Проверь события в topics.");
        }

        String proxyHost = y.get("ProxyHost", "");
        int proxyPort = y.getInt("ProxyPort", 0, 0, 65535);
        if (!proxyHost.isEmpty()) {
            moved++;
        }
        notes.add("Токен и chat-id не переносятся: токен Discord в Telegram не работает. "
                + "Создай бота у @BotFather, добавь его в группу и впиши telegram.token и "
                + "telegram.chat-id вручную.");
        notes.add("Привязки аккаунтов (accounts.aof или linkedaccounts.json) не переносятся: "
                + "они связывают Minecraft с Discord-аккаунтами, а не с Telegram.");

        return new Result(true, render("", 0L, proxyHost, proxyPort, topics.toString(), null),
                moved, "", notes);
    }

    // ---------------------------------------------------------------- rendering

    private static String defaultTopics() {
        return "  основной:\n"
                + "    thread-id: 0\n"
                + "    events: [chat, join, quit, death, server, moderation]\n"
                + "    from-telegram: true\n"
                + "    prefix: '[TG]'\n";
    }

    /** A YAML key that is safe unquoted: Latin, Cyrillic, digits, dash and underscore. */
    private static String safeName(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            out.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '-');
        }
        String cleaned = out.toString();
        return cleaned.isEmpty() ? "канал" : cleaned;
    }

    private static String render(String token, long chatId, String proxyHost, int proxyPort,
                                 String topics, String unused) {
        return "# Файл создан командой /sntelegram import. Проверь значения, переименуй в\n"
                + "# config.yml и выполни /sntelegram reload.\n"
                + "config-version: 1\n"
                + "\n"
                + "general:\n"
                + "  language: ru\n"
                + "  show-telegram-names: true\n"
                + "  update-check: true\n"
                + "\n"
                + "telegram:\n"
                + "  token: '" + token.replace("'", "''") + "'\n"
                + "  chat-id: " + chatId + "\n"
                + "  base-url: '" + TelegramApi.DEFAULT_BASE_URL + "'\n"
                + "  poll-timeout: 30\n"
                + "  drop-backlog: true\n"
                + "  proxy:\n"
                + "    host: '" + proxyHost.replace("'", "''") + "'\n"
                + "    port: " + proxyPort + "\n"
                + "\n"
                + "topics:\n"
                + topics
                + "\n"
                + "moderation:\n"
                + "  enabled: true\n"
                + "  trust-chat-admins: true\n"
                + "  admins: []\n";
    }
}
