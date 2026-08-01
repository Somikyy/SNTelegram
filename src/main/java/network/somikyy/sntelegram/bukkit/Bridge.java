/*
 * SNTelegram - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sntelegram.bukkit;

import net.kyori.adventure.text.Component;

import network.somikyy.sntelegram.core.AdminRoster;
import network.somikyy.sntelegram.core.Config;
import network.somikyy.sntelegram.core.EventKind;
import network.somikyy.sntelegram.core.IncomingMessage;
import network.somikyy.sntelegram.core.Json;
import network.somikyy.sntelegram.core.MessageIndex;
import network.somikyy.sntelegram.core.ModerationCommand;
import network.somikyy.sntelegram.core.MuteBook;
import network.somikyy.sntelegram.core.Outbox;
import network.somikyy.sntelegram.core.RateLimiter;
import network.somikyy.sntelegram.core.TelegramApi;
import network.somikyy.sntelegram.core.TelegramPoller;
import network.somikyy.sntelegram.core.TelegramText;
import network.somikyy.sntelegram.core.Topic;

import org.bukkit.Bukkit;

import java.time.Duration;
import java.util.Map;

/**
 * Everything that has to know about both sides at once.
 *
 * <p>Deliberately the only class that does. The game side below it knows about events and
 * components; the Telegram side knows about JSON and HTTP; neither imports the other. This class
 * is where a chat line becomes an HTML message and where {@code /mute 10m} becomes a call on the
 * server thread - and it is small enough to read in one sitting, which is the point of keeping
 * the two halves apart.
 *
 * <p>Thread ownership, because it is the thing most likely to be got wrong later:
 * <ul>
 *   <li>{@link #handle} runs on the polling thread. It may block on the network. It must never
 *       touch the world, so anything that does goes through {@link Scheduling}.</li>
 *   <li>The {@code send*} methods are called from server threads. They only enqueue, so they
 *       never block.</li>
 * </ul>
 */
final class Bridge {

    private final Config config;
    private final TelegramApi api;
    private final Outbox outbox;
    private final TelegramPoller poller;
    private final AdminRoster admins;
    private final MessageIndex index;
    private final MuteBook mutes;
    private final Moderation moderation;
    private final Scheduling scheduling;
    private final TelegramPoller.Log log;

    Bridge(Config config, MuteBook mutes, Scheduling scheduling, TelegramPoller.Log log) {
        this.config = config;
        this.mutes = mutes;
        this.scheduling = scheduling;
        this.log = log;

        this.api = new TelegramApi(config.baseUrl(), config.token(),
                Duration.ofSeconds(10), Duration.ofSeconds(30),
                config.proxyHost(), config.proxyPort());
        RateLimiter limiter = new RateLimiter(config.sendsPerSecond(),
                config.sendsPerChatPerMinute(), System::nanoTime);
        this.outbox = new Outbox(api, limiter, config.queueSize(), config.queueMaxAgeSeconds(),
                log, System::nanoTime);
        this.poller = new TelegramPoller(api, config.pollSeconds(), config.dropBacklog(),
                this::handle, log);
        this.admins = new AdminRoster(api, config.chatId(), config.admins(),
                config.trustChatAdmins(), 60_000L);
        // Roughly an hour of chat on a busy server: enough that replying to something from
        // earlier in the evening still works, small enough to be invisible in memory.
        this.index = new MessageIndex(2000);
        this.moderation = new Moderation(mutes, false);
    }

    void start() {
        outbox.start();
        poller.start();
    }

    void stop() {
        poller.stop();
        // Long enough for the "server stopped" line to actually leave, short enough that nobody
        // notices the shutdown taking longer.
        outbox.stop(1500L);
    }

    Outbox outbox() {
        return outbox;
    }

    TelegramPoller poller() {
        return poller;
    }

    MuteBook mutes() {
        return mutes;
    }

    AdminRoster admins() {
        return admins;
    }

    // ---------------------------------------------------------------- game to Telegram

    /** Sends an event line to whichever topic carries that kind. Does nothing if none does. */
    void sendEvent(EventKind kind, String html) {
        Topic topic = config.topicFor(kind);
        if (topic == null) {
            return;
        }
        outbox.enqueue(config.chatId(), "sendMessage", sendParams(topic, html));
    }

    /**
     * Sends a player's chat line and remembers which Telegram message it became.
     *
     * <p>The remembering is what makes reply-moderation work: an admin replying to this message
     * an hour later is replying to a number, and this is the only moment the bridge can learn
     * that the number means this player.
     */
    void sendChat(String playerName, String html) {
        Topic topic = config.topicFor(EventKind.CHAT);
        if (topic == null) {
            return;
        }
        outbox.enqueue(config.chatId(), "sendMessage", sendParams(topic, html),
                result -> index.remember(result.num("message_id", 0L), playerName));
    }

    /** Answers inside a specific topic - used to reply to a command where it was typed. */
    void reply(Integer threadId, String html) {
        Map<String, Object> params = Json.map();
        params.put("chat_id", config.chatId());
        params.put("message_thread_id", threadId);
        params.put("text", TelegramText.fit(html));
        params.put("parse_mode", "HTML");
        params.put("link_preview_options", Map.of("is_disabled", true));
        outbox.enqueue(config.chatId(), "sendMessage", params);
    }

    private Map<String, Object> sendParams(Topic topic, String html) {
        Map<String, Object> params = Json.map();
        params.put("chat_id", config.chatId());
        // Null for the General topic, and Json.write omits null keys. Sending 1 here - the
        // number every tutorial claims General is - answers 400.
        params.put("message_thread_id", topic.threadParameter());
        params.put("text", TelegramText.fit(html));
        params.put("parse_mode", "HTML");
        // Chat is full of links players paste at each other; a preview card under every one of
        // them turns the Telegram topic into an unreadable wall.
        params.put("link_preview_options", Map.of("is_disabled", true));
        return params;
    }

    // ---------------------------------------------------------------- Telegram to game

    /** Called by the poller for every update. Runs off the server thread. */
    private void handle(Json update) {
        Json raw = update.obj("message");
        if (raw.isNull()) {
            return;
        }
        IncomingMessage message = IncomingMessage.from(raw);

        // Only the configured chat. A bot can be added to any group by anyone who has its
        // username, and without this check that person's group would be able to run moderation
        // commands against this server.
        if (message.chatId() != config.chatId()) {
            return;
        }
        if (message.empty()) {
            return;
        }
        if (raw.obj("from").bool("is_bot", false)) {
            // Two bridges in one group would otherwise echo each other forever.
            return;
        }

        Topic topic = config.topicByThread(message.threadId());
        if (topic == null) {
            return; // a topic the admin did not configure: not ours to touch
        }

        if (config.moderationEnabled() && tryModeration(message, topic)) {
            return;
        }
        if (!topic.fromTelegram()) {
            return;
        }
        showInGame(topic, message);
    }

    /** @return true when the message was a command and has been dealt with */
    private boolean tryModeration(IncomingMessage message, Topic topic) {
        String replyTarget = message.isRealReply() ? index.playerOf(message.replyToMessageId()) : null;
        ModerationCommand command = ModerationCommand.parse(message.text(), replyTarget);
        if (!command.known()) {
            return false;
        }
        // The admin check needs the network, which is fine here and would not be on the server
        // thread - one more reason the hop happens after this point and not before.
        if (!admins.isAdmin(message.fromUserId())) {
            reply(message.threadId(), "У вас нет прав на управление сервером. Права выдаёт "
                    + "владелец через moderation.admins в config.yml или через статус "
                    + "администратора этой группы.");
            return true;
        }
        if (command.needsTarget()) {
            reply(message.threadId(), "Не понятно, к кому применить. Либо ответьте этой командой "
                    + "на сообщение игрока, либо укажите ник: <code>/"
                    + command.action().name().toLowerCase(java.util.Locale.ROOT) + " Ник</code>");
            return true;
        }
        String by = message.authorName();
        Integer thread = message.threadId();
        scheduling.onServerThread(() -> execute(command, by, thread));
        return true;
    }

    /** Runs on the server thread. Every branch answers into Telegram, including the failures. */
    private void execute(ModerationCommand command, String by, Integer thread) {
        // Answers go twice: once where the command was typed, and once into the moderation log
        // topic if the admin configured one. Both, because the person who typed it needs the
        // outcome immediately and the rest of the team needs the record.
        java.util.function.Consumer<String> answer = html -> {
            reply(thread, html);
            Topic log = config.topicFor(EventKind.MODERATION);
            if (log != null && (thread == null ? log.hasThread() : log.threadId() != thread)) {
                sendEvent(EventKind.MODERATION,
                        config.templates().moderation().replace("{message}", html));
            }
        };
        switch (command.action()) {
            case MUTE -> moderation.mute(command.targetName(), command.durationMillis(),
                    command.reason(), by, answer);
            case UNMUTE -> moderation.unmute(command.targetName(), answer);
            case KICK -> moderation.kick(command.targetName(), command.reason(), by, answer);
            case BAN -> moderation.ban(command.targetName(), command.durationMillis(),
                    command.reason(), by, answer);
            case UNBAN -> moderation.unban(command.targetName(), answer);
            // The read-only verbs answer only where they were asked; echoing "who is online"
            // into the moderation log would bury the entries that matter.
            case INFO -> moderation.info(command.targetName(), html -> reply(thread, html));
            case LIST -> reply(thread, onlineList());
            case STATUS -> reply(thread, status());
            case SAY -> say(command.reason(), by, thread);
            case HELP -> reply(thread, help());
            default -> {
            }
        }
    }

    private void say(String text, String by, Integer thread) {
        if (text.isBlank()) {
            reply(thread, "После /say нужен текст объявления.");
            return;
        }
        Bukkit.broadcast(Component.text("[" + by + "] " + text));
        reply(thread, "📢 Объявление отправлено в игру.");
    }

    private void showInGame(Topic topic, IncomingMessage message) {
        String author = config.showTelegramNamesInGame()
                ? message.authorName()
                : "Telegram";
        Component line = message.mediaKind() != null && message.text().isBlank()
                ? TextRender.fromTelegram(config.templates().fromTelegram(), author,
                        java.util.List.of(network.somikyy.sntelegram.core.TextSpan
                                .plain("[" + message.mediaKind() + "]")))
                : TextRender.fromTelegram(config.templates().fromTelegram(), author, message.spans());
        // Broadcasting is safe from any thread on Paper and on Folia; the moderation actions
        // below are the ones that are not, and they hop.
        Bukkit.broadcast(line);
    }

    // ---------------------------------------------------------------- read-only answers

    private String onlineList() {
        var players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            return "На сервере сейчас никого нет.";
        }
        StringBuilder sb = new StringBuilder("👥 Онлайн (" + players.size() + "):\n");
        for (var player : players) {
            sb.append("• ").append(TelegramText.escapeHtml(player.getName())).append('\n');
        }
        return sb.toString().trim();
    }

    private String status() {
        double[] tps = Bukkit.getTPS();
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                / (1024 * 1024);
        long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        return "📊 <b>Состояние сервера</b>\n"
                + "TPS: " + String.format(java.util.Locale.ROOT, "%.2f", tps[0]) + "\n"
                + "Онлайн: " + Bukkit.getOnlinePlayers().size() + "\n"
                + "Память: " + usedMb + " из " + maxMb + " МБ\n"
                + "Очередь в Telegram: " + outbox.pending()
                + " (отправлено " + outbox.sentCount()
                + ", потеряно " + outbox.droppedCount() + ")";
    }

    private static String help() {
        return "<b>SNTelegram — команды</b>\n\n"
                + "Ответьте на сообщение игрока и напишите:\n"
                + "<code>/мут 10м причина</code> — запретить писать в чат\n"
                + "<code>/размут</code> — снять запрет\n"
                + "<code>/кик причина</code> — отключить от сервера\n"
                + "<code>/бан 7д причина</code> — забанить\n"
                + "<code>/разбан Ник</code> — снять бан\n"
                + "<code>/инфо</code> — карточка игрока\n\n"
                + "Без ответа на сообщение ник указывается первым: <code>/бан Ник 7д причина</code>\n\n"
                + "Без цели:\n"
                + "<code>/онлайн</code> — кто на сервере\n"
                + "<code>/статус</code> — TPS, память, очередь\n"
                + "<code>/сказать текст</code> — объявление в игровой чат\n\n"
                + "Длительность: <code>10м</code>, <code>2ч</code>, <code>7д</code>, "
                + "<code>навсегда</code>. Английские команды тоже работают.";
    }
}
