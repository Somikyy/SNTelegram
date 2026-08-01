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

import java.util.List;
import java.util.Locale;

/**
 * A moderation command typed into Telegram, parsed but not yet executed.
 *
 * <p>This is the feature the plugin exists for. A survey of every Telegram bridge for Paper that
 * supports 26.x - tgbridge, FlectonePulse, ConnectMe, SyncShield - found none that lets an admin
 * reply to a forwarded chat line and punish its author. The nearest offer a raw
 * {@code /rcon <command>} passthrough, which means handing whoever is in the Telegram group the
 * ability to run anything on the server, guarded by a blacklist.
 *
 * <p>So commands here are <b>structured, not passthrough</b>: a closed set of verbs with typed
 * arguments. The bridge can therefore check permission per verb, and there is no
 * {@code /rcon op me} to blacklist because there is no {@code /rcon}.
 *
 * <p><b>Reply targeting.</b> When the command is a reply to a message the bridge itself sent,
 * the target is whoever wrote that line - the admin types {@code /mute 10m} and nothing else.
 * That is the whole ergonomic argument for moderating from a phone, and it is why
 * {@link #needsTarget()} distinguishes "no target given" from "target came from the reply".
 */
public record ModerationCommand(Action action, String targetName, long durationMillis,
                                String reason, String argument) {

    /** The closed set of verbs. Anything else is {@link Action#UNKNOWN}. */
    public enum Action {
        /** Silence a player in the game chat for a while. */
        MUTE(true, true),
        UNMUTE(true, false),
        KICK(true, false),
        BAN(true, true),
        UNBAN(true, false),
        /** The player card: online, playtime, address, punishment history. */
        INFO(true, false),
        /** Who is online. */
        LIST(false, false),
        /** Server health in one line. */
        STATUS(false, false),
        /** Say something in the game chat as the server, not as a Telegram user. */
        SAY(false, false),
        HELP(false, false),
        UNKNOWN(false, false);

        private final boolean needsTarget;
        private final boolean takesDuration;

        Action(boolean needsTarget, boolean takesDuration) {
            this.needsTarget = needsTarget;
            this.takesDuration = takesDuration;
        }

        public boolean needsTarget() {
            return needsTarget;
        }

        public boolean takesDuration() {
            return takesDuration;
        }
    }

    /** True when the command names a player and no reply supplied one. */
    public boolean needsTarget() {
        return action.needsTarget() && (targetName == null || targetName.isEmpty());
    }

    public boolean known() {
        return action != Action.UNKNOWN;
    }

    /**
     * Parses a Telegram message into a command.
     *
     * @param text          the message text
     * @param replyTarget   the player named by the message being replied to, or {@code null}
     * @return a command; {@link Action#UNKNOWN} when the text is not one
     */
    public static ModerationCommand parse(String text, String replyTarget) {
        if (text == null) {
            return unknown();
        }
        String trimmed = text.trim();
        // "!" as well as "/" because Telegram renders a leading "/" as a tappable command and
        // offers autocomplete for commands the bot has not registered - which looks broken.
        // Admins in mixed chats tend to reach for "!" anyway.
        if (trimmed.length() < 2 || (trimmed.charAt(0) != '/' && trimmed.charAt(0) != '!')) {
            return unknown();
        }

        List<String> parts = List.of(trimmed.substring(1).split("\\s+"));
        String verb = parts.get(0).toLowerCase(Locale.ROOT);
        // "/mute@my_bot" - Telegram appends the bot username in group chats, always.
        int at = verb.indexOf('@');
        if (at > 0) {
            verb = verb.substring(0, at);
        }

        Action action = actionOf(verb);
        if (action == Action.UNKNOWN) {
            return unknown();
        }

        List<String> args = parts.subList(1, parts.size());
        if (action == Action.SAY) {
            // Everything after the verb, verbatim - splitting and rejoining would eat runs of
            // spaces the author meant to keep.
            String rest = trimmed.substring(1 + parts.get(0).length()).trim();
            return new ModerationCommand(action, null, 0L, rest, rest);
        }

        int i = 0;
        String target = replyTarget;
        if (action.needsTarget() && (target == null || target.isEmpty())) {
            // Do not swallow a duration as a name. Without this guard, "/mute 10m" typed with no
            // reply mutes a player called "10m" for the default ten minutes - silently, because
            // the command parses perfectly. The admin sees nothing happen and no error.
            boolean looksLikeDuration = action.takesDuration()
                    && i < args.size() && TimeSpan.isDuration(args.get(i));
            if (i < args.size() && !looksLikeDuration) {
                target = args.get(i++);
            }
        }

        long duration = 0L;
        if (action.takesDuration() && i < args.size() && TimeSpan.isDuration(args.get(i))) {
            duration = TimeSpan.parse(args.get(i));
            i++;
        }
        // A ban with no duration given is permanent; a mute with none is a short one. Different
        // defaults because the cost of getting them wrong is asymmetric: a too-long mute is
        // annoying, a too-long ban loses a player.
        if (action.takesDuration() && duration == 0L) {
            duration = action == Action.BAN ? TimeSpan.PERMANENT : TimeSpan.parse("10m");
        }

        String reason = String.join(" ", args.subList(Math.min(i, args.size()), args.size())).trim();
        return new ModerationCommand(action, target, duration, reason, String.join(" ", args));
    }

    private static ModerationCommand unknown() {
        return new ModerationCommand(Action.UNKNOWN, null, 0L, "", "");
    }

    /**
     * Verb table, Russian included.
     *
     * <p>The Russian aliases are the point of the whole plugin's positioning: the admin reading
     * this chat thinks in Russian, and {@code /бан} must work as well as {@code /ban}.
     */
    private static Action actionOf(String verb) {
        return switch (verb) {
            case "mute", "мут", "замутить", "молчать" -> Action.MUTE;
            case "unmute", "размут", "размутить" -> Action.UNMUTE;
            case "kick", "кик", "кикнуть" -> Action.KICK;
            case "ban", "бан", "забанить" -> Action.BAN;
            case "unban", "разбан", "разбанить", "pardon" -> Action.UNBAN;
            case "info", "инфо", "кто", "who", "player" -> Action.INFO;
            case "list", "online", "онлайн", "список", "кто_онлайн" -> Action.LIST;
            case "status", "статус", "tps", "тпс" -> Action.STATUS;
            case "say", "сказать", "объявить" -> Action.SAY;
            case "help", "помощь", "start", "команды" -> Action.HELP;
            default -> Action.UNKNOWN;
        };
    }
}
