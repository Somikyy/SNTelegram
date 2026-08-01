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

import java.util.Set;

/**
 * One configured destination: a Telegram forum topic and the rules for what crosses it.
 *
 * <p>Topics are what make this a bridge rather than a firehose. A server has an admin channel, a
 * public chat and a log, and pouring all three into one Telegram group is what makes admins turn
 * bridges off. Each topic here declares which event kinds it receives and whether messages typed
 * into it reach the game.
 *
 * @param name        the admin's own name for this topic, used in commands and messages
 * @param threadId    Telegram {@code message_thread_id}, or {@link #GENERAL} for the General topic
 * @param toTelegram  event kinds forwarded from the game into this topic
 * @param fromTelegram whether messages written here are shown in the game
 * @param prefix      shown in-game before messages from this topic, so players can see the source
 */
public record Topic(String name, int threadId, Set<EventKind> toTelegram, boolean fromTelegram,
                    String prefix) {

    /**
     * The General topic of a forum, and the only sane value for a non-forum group.
     *
     * <p>Zero, not one. The obvious guess is that General is thread 1 - it is, at the MTProto
     * level, and every tutorial repeats it. The Bot API is a different id space: it filters
     * {@code 1} out on the way in and refuses it on the way out, so passing
     * {@code message_thread_id: 1} to {@code sendMessage} answers 400. The correct way to address
     * General is to omit the parameter entirely, which is what {@link #hasThread()} controls.
     */
    public static final int GENERAL = 0;

    public Topic {
        name = name == null ? "" : name.trim();
        toTelegram = toTelegram == null ? Set.of() : Set.copyOf(toTelegram);
        prefix = prefix == null ? "" : prefix;
        if (threadId < 0) {
            threadId = GENERAL;
        }
    }

    /** True when {@code message_thread_id} must be sent; false for General and plain groups. */
    public boolean hasThread() {
        return threadId != GENERAL;
    }

    /** The value to put in a request, or {@code null} to omit the parameter - see {@link #GENERAL}. */
    public Integer threadParameter() {
        return hasThread() ? threadId : null;
    }

    public boolean carries(EventKind kind) {
        return toTelegram.contains(kind);
    }
}
