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
 * The kinds of thing the bridge can carry from the game to Telegram.
 *
 * <p>An enum rather than free-form strings because these names appear in the admin's config file
 * under {@code events:}, and a typo there must produce a clear complaint at load time - not a
 * topic that silently receives nothing and a support conversation three weeks later.
 */
public enum EventKind {

    /** Player chat. */
    CHAT("chat", "чат"),

    /** A player joined. */
    JOIN("join", "вход"),

    /** A player left. */
    QUIT("quit", "выход"),

    /** A player died, with the vanilla death message. */
    DEATH("death", "смерть"),

    /** A player completed an advancement. */
    ADVANCEMENT("advancement", "достижение"),

    /** The server started or stopped. */
    SERVER("server", "сервер"),

    /** A moderation action taken from Telegram, echoed back so the team can see who did what. */
    MODERATION("moderation", "модерация");

    private final String key;
    private final String russian;

    EventKind(String key, String russian) {
        this.key = key;
        this.russian = russian;
    }

    /** The name used in {@code config.yml}. */
    public String key() {
        return key;
    }

    public String russian() {
        return russian;
    }

    /**
     * Parses a config value, accepting the Russian name too.
     *
     * <p>Russian aliases are not decoration: the whole config file is in Russian, and an admin
     * who writes {@code события: [чат, вход]} has done nothing unreasonable.
     *
     * @return {@code null} when the name is not recognised, so the caller can name it in a warning
     */
    public static EventKind parse(String name) {
        if (name == null) {
            return null;
        }
        String normalised = name.trim().toLowerCase(Locale.ROOT);
        for (EventKind kind : values()) {
            if (kind.key.equals(normalised) || kind.russian.equals(normalised)) {
                return kind;
            }
        }
        return null;
    }
}
