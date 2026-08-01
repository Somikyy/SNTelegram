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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers which player each forwarded message was about, so a reply can punish them.
 *
 * <p>This is the small piece of state that makes reply-moderation possible. The bridge sends a
 * chat line to Telegram; Telegram answers with a {@code message_id}; an admin later replies to
 * that message with {@code /mute 10m}. Without this map the bridge sees a reply to message 8417
 * and has no idea who wrote it.
 *
 * <p>Bounded and least-recently-inserted-first. A server running for months would otherwise
 * accumulate an entry per chat line forever - and the useful window is short anyway: nobody
 * moderates a message from three days ago by scrolling back to it.
 *
 * <p>Synchronised because it is written from the sender thread (when Telegram answers) and read
 * from the polling thread (when a reply arrives). The map is small and the operations are
 * hash lookups, so a lock costs nothing measurable and removes a whole class of question.
 */
public final class MessageIndex {

    private final int capacity;
    private final Map<Long, Origin> byMessageId;

    /**
     * Who a forwarded message was about. Named Origin, not Entry: inside the LinkedHashMap
     * subclass below the simple name Entry resolves to Map.Entry and the override silently
     * stops overriding.
     *
     * @param playerName in-game name at the time the message was sent
     * @param telegramUserId the Telegram author, for messages that went the other way; 0 if none
     */
    public record Origin(String playerName, long telegramUserId) {
    }

    public MessageIndex(int capacity) {
        this.capacity = Math.max(16, capacity);
        this.byMessageId = new LinkedHashMap<>(64, 0.75f, false) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Origin> eldest) {
                return size() > MessageIndex.this.capacity;
            }
        };
    }

    /** Records that {@code messageId} in Telegram carries a chat line by {@code playerName}. */
    public synchronized void remember(long messageId, String playerName) {
        if (messageId <= 0 || playerName == null || playerName.isEmpty()) {
            return;
        }
        byMessageId.put(messageId, new Origin(playerName, 0L));
    }

    /** Records that {@code messageId} was written in Telegram by {@code telegramUserId}. */
    public synchronized void rememberTelegram(long messageId, long telegramUserId) {
        if (messageId <= 0 || telegramUserId == 0L) {
            return;
        }
        byMessageId.put(messageId, new Origin("", telegramUserId));
    }

    /** The player a message was about, or {@code null} when it is not in the window any more. */
    public synchronized String playerOf(long messageId) {
        Origin entry = byMessageId.get(messageId);
        if (entry == null || entry.playerName().isEmpty()) {
            return null;
        }
        return entry.playerName();
    }

    public synchronized int size() {
        return byMessageId.size();
    }

    public synchronized void clear() {
        byMessageId.clear();
    }
}
