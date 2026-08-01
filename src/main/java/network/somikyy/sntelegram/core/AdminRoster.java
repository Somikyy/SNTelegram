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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides who is allowed to moderate the server from Telegram.
 *
 * <p>Two sources, and both exist for a reason.
 *
 * <p><b>The explicit list</b> from {@code config.yml} is the one that cannot be taken away by
 * anything happening inside Telegram. It is the answer to "someone was promoted to group admin
 * and now they can ban players".
 *
 * <p><b>The chat's own administrators</b>, asked for through {@code getChatAdministrators}, is
 * the one that makes the plugin usable: on a real server the moderation team already exists as
 * Telegram admins, and making the owner copy a dozen numeric ids into a YAML file before anything
 * works is how a plugin gets uninstalled during setup.
 *
 * <p>Cached with a deliberately short life. The list is asked for at most once a minute, so a
 * demoted moderator loses access within a minute - fast enough to matter, slow enough that a
 * flood of commands cannot turn into a flood of API calls.
 */
public final class AdminRoster {

    /** Telegram statuses that count as administration. Anything else does not. */
    private static final Set<String> ADMIN_STATUSES = Set.of("creator", "administrator");

    private final TelegramApi api;
    private final long chatId;
    private final Set<Long> configured;
    private final boolean trustChatAdmins;
    private final long ttlMillis;

    private volatile Set<Long> cached = Set.of();
    private volatile long fetchedAt;

    public AdminRoster(TelegramApi api, long chatId, Set<Long> configured, boolean trustChatAdmins,
                       long ttlMillis) {
        this.api = api;
        this.chatId = chatId;
        this.configured = Set.copyOf(configured);
        this.trustChatAdmins = trustChatAdmins;
        this.ttlMillis = Math.max(5_000L, ttlMillis);
    }

    /**
     * Whether {@code userId} may issue moderation commands.
     *
     * <p>Called from the polling thread, and may perform one network request - which is safe
     * there and nowhere else. It must never be called from the server thread.
     */
    public boolean isAdmin(long userId) {
        if (configured.contains(userId)) {
            return true;
        }
        if (!trustChatAdmins) {
            return false;
        }
        return chatAdmins().contains(userId);
    }

    /** The configured ids alone - used when reporting configuration to the owner. */
    public Set<Long> configured() {
        return configured;
    }

    private Set<Long> chatAdmins() {
        long now = System.currentTimeMillis();
        Set<Long> snapshot = cached;
        if (now - fetchedAt < ttlMillis) {
            return snapshot;
        }
        try {
            Map<String, Object> params = Json.map();
            params.put("chat_id", chatId);
            List<Json> members = api.call("getChatAdministrators", params).arr();
            Set<Long> fresh = new LinkedHashSet<>();
            for (Json member : members) {
                if (!ADMIN_STATUSES.contains(member.str("status", ""))) {
                    continue;
                }
                Json user = member.obj("user");
                // Other bots being administrators is common and they are not moderators.
                if (user.bool("is_bot", false)) {
                    continue;
                }
                fresh.add(user.num("id", 0L));
            }
            cached = Set.copyOf(fresh);
            fetchedAt = now;
            return cached;
        } catch (TelegramException e) {
            // Keep serving the previous answer rather than locking the whole team out because
            // the network blinked. The stamp is not advanced, so the next call retries.
            return snapshot;
        }
    }

    /** Drops the cache, so the next check asks Telegram again. Used by {@code /sntelegram reload}. */
    public void invalidate() {
        fetchedAt = 0L;
    }
}
