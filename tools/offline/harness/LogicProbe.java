/*
 * SNTelegram - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package harness;

import network.somikyy.sntelegram.core.Config;
import network.somikyy.sntelegram.core.EventKind;
import network.somikyy.sntelegram.core.IncomingMessage;
import network.somikyy.sntelegram.core.Json;
import network.somikyy.sntelegram.core.MessageIndex;
import network.somikyy.sntelegram.core.ModerationCommand;
import network.somikyy.sntelegram.core.TelegramText;
import network.somikyy.sntelegram.core.TextSpan;
import network.somikyy.sntelegram.core.TimeSpan;
import network.somikyy.sntelegram.core.Topic;

import java.util.List;

/**
 * Exercises the pure logic - no sockets, no server - and prints {@code key=value} lines for
 * {@code selftest.sh}.
 *
 * <p>Every case here corresponds to something the Bot API documentation says and a naive
 * implementation gets wrong. They are regression tests for research, not for code: the code is
 * easy once the behaviour is known, and the behaviour took reading the Bot API server source.
 */
public final class LogicProbe {

    public static void main(String[] args) {
        forumTopicTraps();
        moderationParsing();
        durations();
        configLoading();
        replyIndex();
        formatting();
        consoleCommands();
    }

    // ---------------------------------------------------------------- forum topics

    private static void forumTopicTraps() {
        // An ordinary line typed into topic 47. Telegram attaches reply_to_message pointing at
        // the topic-creation service message, whose message_id equals the thread id.
        IncomingMessage implicit = IncomingMessage.from(Json.parse(message(
                "\"message_thread_id\":47,\"is_topic_message\":true,"
                        + "\"reply_to_message\":{\"message_id\":47,\"forum_topic_created\":"
                        + "{\"name\":\"Админка\",\"icon_color\":7322096}},"
                        + "\"text\":\"обычная строка\"")));
        System.out.println("topic.implicit-reply-ignored=" + !implicit.isRealReply());
        System.out.println("topic.thread-read=" + implicit.threadId());
        System.out.println("topic.text-kept=" + implicit.text());

        // A genuine reply inside the same topic: the replied-to id is not the thread id.
        IncomingMessage real = IncomingMessage.from(Json.parse(message(
                "\"message_thread_id\":47,\"is_topic_message\":true,"
                        + "\"reply_to_message\":{\"message_id\":8123,\"text\":\"<Steve> привет\"},"
                        + "\"text\":\"/mute 10m флуд\"")));
        System.out.println("topic.real-reply-detected=" + real.isRealReply());
        System.out.println("topic.real-reply-target=" + real.replyToMessageId());

        // The service message announcing the topic itself must never reach the game.
        IncomingMessage service = IncomingMessage.from(Json.parse(message(
                "\"message_thread_id\":47,\"forum_topic_created\":{\"name\":\"Логи\","
                        + "\"icon_color\":7322096}")));
        System.out.println("topic.service-ignored=" + service.empty());

        // General topic: no message_thread_id at all.
        IncomingMessage general = IncomingMessage.from(Json.parse(message("\"text\":\"в General\"")));
        System.out.println("topic.general-has-no-thread=" + (general.threadId() == null));

        // And the Topic record must omit the parameter rather than send 1.
        Topic generalTopic = new Topic("основной", Topic.GENERAL, java.util.Set.of(EventKind.CHAT),
                true, "[TG]");
        System.out.println("topic.general-omits-parameter=" + (generalTopic.threadParameter() == null));
        System.out.println("topic.general-id-is-zero=" + (Topic.GENERAL == 0));

        // Media with no text still has to be announced, not dropped silently.
        IncomingMessage photo = IncomingMessage.from(Json.parse(message(
                "\"photo\":[{\"file_id\":\"abc\",\"width\":90,\"height\":90}]")));
        System.out.println("media.kind=" + photo.mediaKind());
        System.out.println("media.not-empty=" + !photo.empty());

        // A reply that came from another chat or topic arrives in external_reply, not
        // reply_to_message - it must not be mistaken for a local reply.
        IncomingMessage external = IncomingMessage.from(Json.parse(message(
                "\"message_thread_id\":47,\"external_reply\":{\"origin\":{\"type\":\"user\"},"
                        + "\"chat\":{\"id\":-100999,\"type\":\"supergroup\"},\"message_id\":5}, "
                        + "\"text\":\"привет\"")));
        System.out.println("topic.external-reply-not-local=" + !external.isRealReply());
    }

    // ---------------------------------------------------------------- moderation

    private static void moderationParsing() {
        // The headline case: reply to a player's line, type nothing but the verb and a duration.
        ModerationCommand muted = ModerationCommand.parse("/mute 10m мат в чате", "Steve");
        System.out.println("mod.reply-target=" + muted.targetName());
        System.out.println("mod.action=" + muted.action());
        System.out.println("mod.duration=" + muted.durationMillis());
        System.out.println("mod.reason=" + muted.reason());

        // Same thing entirely in Russian, which is how it will actually be typed.
        ModerationCommand russian = ModerationCommand.parse("/бан 7д гриферство", "Alex");
        System.out.println("mod.ru-action=" + russian.action());
        System.out.println("mod.ru-duration-days=" + (russian.durationMillis() / 86_400_000L));
        System.out.println("mod.ru-reason=" + russian.reason());

        // Telegram appends the bot username to commands in groups. Always.
        System.out.println("mod.bot-suffix-stripped="
                + (ModerationCommand.parse("/kick@sn_bot грубость", "Steve").action()
                        == ModerationCommand.Action.KICK));

        // No reply: the first argument is the target.
        ModerationCommand named = ModerationCommand.parse("/ban Notch 2d чит", null);
        System.out.println("mod.named-target=" + named.targetName());
        System.out.println("mod.named-duration-hours=" + (named.durationMillis() / 3_600_000L));
        System.out.println("mod.named-reason=" + named.reason());

        // No reply and no name: the bridge must say so, not punish someone at random.
        System.out.println("mod.missing-target-detected="
                + ModerationCommand.parse("/mute 10m", null).needsTarget());

        // Defaults differ on purpose: a ban with no duration is permanent, a mute is short.
        System.out.println("mod.ban-defaults-permanent="
                + (ModerationCommand.parse("/ban Notch", null).durationMillis() == TimeSpan.PERMANENT));
        System.out.println("mod.mute-default-minutes="
                + (ModerationCommand.parse("/mute", "Steve").durationMillis() / 60_000L));

        // Ordinary chat must not be mistaken for a command.
        System.out.println("mod.plain-text-not-command="
                + !ModerationCommand.parse("а давайте забаним Стива", "Steve").known());
        System.out.println("mod.unknown-verb-not-command="
                + !ModerationCommand.parse("/варкрафт", null).known());

        // There is no passthrough verb: this is the whole security argument against /rcon.
        System.out.println("mod.no-rcon-verb="
                + !ModerationCommand.parse("/rcon op somikyy", null).known());
        System.out.println("mod.no-console-verb="
                + !ModerationCommand.parse("/console op somikyy", null).known());

        // /say keeps the rest of the line verbatim.
        System.out.println("mod.say-keeps-text="
                + ModerationCommand.parse("/say  сервер   уходит на  вайп", null).reason());
    }

    private static void durations() {
        System.out.println("time.10m=" + TimeSpan.parse("10m"));
        System.out.println("time.10м-cyrillic=" + TimeSpan.parse("10м"));
        System.out.println("time.bare-number-is-minutes=" + TimeSpan.parse("30"));
        System.out.println("time.permanent=" + (TimeSpan.parse("навсегда") == TimeSpan.PERMANENT));
        System.out.println("time.not-a-duration=" + (TimeSpan.parse("флуд") == TimeSpan.NOT_A_DURATION));
        System.out.println("time.capped-at-a-year=" + (TimeSpan.parse("9999d") == 365L * 86_400_000L));

        // The Russian plural rule, including the 11-14 exception every naive version gets wrong.
        System.out.println("plural.1=" + TimeSpan.russian(60_000L));
        System.out.println("plural.2=" + TimeSpan.russian(2 * 60_000L));
        System.out.println("plural.5=" + TimeSpan.russian(5 * 60_000L));
        System.out.println("plural.11=" + TimeSpan.russian(11 * 60_000L));
        System.out.println("plural.21=" + TimeSpan.russian(21 * 60_000L));
        System.out.println("plural.permanent=" + TimeSpan.russian(TimeSpan.PERMANENT));
    }

    // ---------------------------------------------------------------- config

    private static void configLoading() {
        Config empty = Config.load("");
        System.out.println("config.empty-not-usable=" + !empty.usable());
        System.out.println("config.empty-warns=" + (empty.warnings().size() >= 2));
        System.out.println("config.empty-has-default-topic=" + (empty.topics().size() == 1));

        Config good = Config.load(String.join("\n",
                "telegram:",
                "  token: '7000000001:AAF-not-a-real-secret'",
                "  chat-id: -100_1234567890123",
                "  poll-timeout: 999",
                "topics:",
                "  основной:",
                "    thread-id: 0",
                "    events: [chat, join, quit]",
                "    from-telegram: true",
                "  админка:",
                "    thread-id: 47",
                "    events: [модерация, сервер]",
                "    from-telegram: true",
                "  логи:",
                "    thread-id: 91",
                "    events: [смерть, достижение]",
                "    from-telegram: false",
                "moderation:",
                "  enabled: true",
                "  admins: [111222333]"));
        System.out.println("config.usable=" + good.usable());
        System.out.println("config.chat-id-exact=" + (good.chatId() == -1001234567890123L));
        System.out.println("config.poll-clamped=" + good.pollSeconds());
        System.out.println("config.topics=" + good.topics().size());
        System.out.println("config.ru-event-names=" + good.topicFor(EventKind.MODERATION).name());
        System.out.println("config.routes-death=" + good.topicFor(EventKind.DEATH).threadId());
        System.out.println("config.by-thread=" + good.topicByThread(47).name());
        System.out.println("config.by-thread-general=" + good.topicByThread(null).name());
        System.out.println("config.no-warnings=" + good.warnings().isEmpty());
        System.out.println("config.admins=" + good.admins().size());

        Config broken = Config.load(String.join("\n",
                "telegram:",
                "  token: 'мусор'",
                "  chat-id: 555111",
                "topics:",
                "  a:",
                "    thread-id: 5",
                "    events: [chat, нетакого]",
                "  b:",
                "    thread-id: 5",
                "    from-telegram: false"));
        System.out.println("config.warns-bad-token=" + anyContains(broken.warnings(), "не похоже на токен"));
        System.out.println("config.warns-positive-chat=" + anyContains(broken.warnings(), "личный чат"));
        System.out.println("config.warns-unknown-event=" + anyContains(broken.warnings(), "нетакого"));
        System.out.println("config.warns-duplicate-thread=" + anyContains(broken.warnings(), "уже занят"));
    }

    // ---------------------------------------------------------------- reply index

    private static void replyIndex() {
        MessageIndex index = new MessageIndex(32);
        index.remember(8123, "Steve");
        System.out.println("index.recalls=" + index.playerOf(8123));
        System.out.println("index.unknown-is-null=" + (index.playerOf(9999) == null));

        for (int i = 0; i < 100; i++) {
            index.remember(10_000 + i, "Игрок" + i);
        }
        System.out.println("index.bounded=" + (index.size() <= 32));
        System.out.println("index.keeps-newest=" + (index.playerOf(10_099) != null));
        System.out.println("index.drops-oldest=" + (index.playerOf(8123) == null));
    }

    // ---------------------------------------------------------------- text

    private static void formatting() {
        // A Telegram user typing MiniMessage must never have it interpreted. The core hands back
        // literal spans; nothing here can become a clickable run_command in game.
        List<TextSpan> hostile = TelegramText.spans(
                "<click:run_command:'/op somikyy'>жми</click>", List.of());
        System.out.println("safety.single-literal-span=" + (hostile.size() == 1));
        System.out.println("safety.text-unchanged="
                + hostile.get(0).text().equals("<click:run_command:'/op somikyy'>жми</click>"));
        System.out.println("safety.no-styles=" + hostile.get(0).styles().isEmpty());

        // Outbound escaping of the same hostility, in the other direction.
        System.out.println("safety.html-escaped="
                + TelegramText.escapeHtml("<b>Steve</b> & <script>").equals(
                        "&lt;b&gt;Steve&lt;/b&gt; &amp; &lt;script&gt;"));

        // The console prints the same sentence Telegram gets, undressed. Building it twice would
        // let the two wordings drift, and the console one is used when something is already wrong.
        System.out.println("console.strips-tags="
                + TelegramText.stripHtml("🔇 <b>Steve</b> не сможет писать в чат 10 минут."));
        System.out.println("console.unescapes="
                + TelegramText.stripHtml("&lt;тег&gt; &amp; текст").equals("<тег> & текст"));
        // Round trip: escaping then stripping must give back exactly the original.
        String tricky = "<b>Steve</b> & <script> \"кавычки\"";
        System.out.println("console.roundtrip="
                + TelegramText.stripHtml(TelegramText.escapeHtml(tricky)).equals(tricky));
    }

    // ---------------------------------------------------------------- console path

    /**
     * The server console rebuilds its arguments into the line the Telegram parser expects, so both
     * paths share every rule about durations, aliases and targets. These assert that the rebuild
     * really does produce identical results.
     */
    private static void consoleCommands() {
        ModerationCommand console = fromConsole("mute", "Steve", "10m", "флуд", "в", "чате");
        System.out.println("console.action=" + console.action());
        System.out.println("console.target=" + console.targetName());
        System.out.println("console.duration=" + console.durationMillis());
        System.out.println("console.reason=" + console.reason());

        // Identical to what the same command typed in Telegram produces.
        ModerationCommand telegram = ModerationCommand.parse("/mute Steve 10m флуд в чате", null);
        System.out.println("console.matches-telegram=" + console.equals(telegram));

        // Russian works from the console too.
        System.out.println("console.ru=" + (fromConsole("бан", "Steve", "7д", "гриф").action()
                == ModerationCommand.Action.BAN));

        // From the console there is nothing to reply to, so a missing nickname must be reported
        // rather than guessed - and a duration must still not be mistaken for one.
        System.out.println("console.needs-target=" + fromConsole("mute").needsTarget());
        System.out.println("console.duration-not-a-name=" + fromConsole("mute", "10m").needsTarget());

        // The plugin's own verbs must not be swallowed by the moderation parser.
        System.out.println("console.reload-not-moderation=" + !fromConsole("reload").known());
        System.out.println("console.import-not-moderation=" + !fromConsole("import").known());
        // ...and a typo must be reported, not silently treated as something else.
        System.out.println("console.typo-unknown=" + !fromConsole("relaod").known());
    }

    /** Rebuilds console arguments exactly the way SNTelegramCommand does. */
    private static ModerationCommand fromConsole(String... args) {
        return ModerationCommand.parse("/" + String.join(" ", args), null);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean anyContains(List<String> lines, String needle) {
        for (String line : lines) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Wraps message-specific JSON in the fields every Bot API Message carries. */
    private static String message(String extra) {
        return "{\"message_id\":8500,\"date\":1785000000,"
                + "\"from\":{\"id\":555000111,\"is_bot\":false,\"first_name\":\"Сомик\","
                + "\"username\":\"somikyy\"},"
                + "\"chat\":{\"id\":-1001234567890123,\"type\":\"supergroup\",\"is_forum\":true},"
                + extra + "}";
    }
}
