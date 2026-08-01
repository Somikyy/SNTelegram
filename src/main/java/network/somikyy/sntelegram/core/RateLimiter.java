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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Two token buckets - one global, one per chat - that decide how long a send must wait.
 *
 * <p>Telegram throttles bots, and a bridge is exactly the shape of client that trips it: a busy
 * evening on a hundred-player server produces far more chat lines per second than any chat app
 * expects. Hitting the limit is not a soft failure - the reply is HTTP 429 with a
 * {@code retry_after}, and a bridge that ignores it spends the evening being throttled harder.
 *
 * <p>So the limiter is not a nicety, it is the difference between a bridge that survives peak
 * hour and one that stops delivering at exactly the moment anyone is watching. It is deliberately
 * pessimistic: it would rather delay a line by a second than have Telegram refuse it.
 *
 * <p>Not thread-safe by design - it lives inside the single sender thread, where locking would
 * buy nothing. Time comes from an injected supplier so the self-test can drive a whole minute of
 * traffic without waiting a minute.
 */
public final class RateLimiter {

    private final LongSupplier nanos;

    /** Global budget, refilled continuously. */
    private final Bucket global;

    private final double perChatCapacity;
    private final double perChatRefillPerNano;

    /**
     * Per-chat buckets, created on demand.
     *
     * <p>Pruned in {@link #forget(long)} and by {@link #sweep()}: a server that renames topics
     * over months would otherwise accumulate one entry per chat id ever seen. Small, but this
     * object lives for the uptime of the server.
     */
    private final Map<Long, Bucket> perChat = new HashMap<>();

    /**
     * @param globalPerSecond   sends per second across all chats
     * @param perChatPerMinute  sends per minute into any single chat
     * @param nanos             monotonic clock, normally {@code System::nanoTime}
     */
    public RateLimiter(double globalPerSecond, double perChatPerMinute, LongSupplier nanos) {
        this.nanos = nanos;
        long now = nanos.getAsLong();
        this.global = new Bucket(globalPerSecond, globalPerSecond / 1_000_000_000.0d, now);
        this.perChatCapacity = perChatPerMinute;
        this.perChatRefillPerNano = perChatPerMinute / 60_000_000_000.0d;
    }

    /**
     * Nanoseconds the caller must sleep before sending to {@code chatId}; zero when it may send now.
     *
     * <p>Asking does not consume anything - {@link #consume(long)} does. Split in two so the
     * sender can decide to give up on a message instead of sleeping for it.
     */
    public long waitNanos(long chatId) {
        long now = nanos.getAsLong();
        return Math.max(global.waitNanos(now), chatBucket(chatId).waitNanos(now));
    }

    /** Records one send to {@code chatId}. Call only after {@link #waitNanos} returned zero. */
    public void consume(long chatId) {
        long now = nanos.getAsLong();
        global.take(now);
        chatBucket(chatId).take(now);
    }

    /**
     * Absorbs a Telegram {@code retry_after}: blocks the chat, and the whole bot, for that long.
     *
     * <p>Both, not just the chat. A 429 means the bot as a whole is being told to slow down, and
     * continuing to hammer other chats at full speed is how a short pause becomes a long one.
     */
    public void penalise(long chatId, long millis) {
        long until = nanos.getAsLong() + millis * 1_000_000L;
        global.blockUntil(until);
        chatBucket(chatId).blockUntil(until);
    }

    /** Drops the bucket for a chat that is no longer configured. */
    public void forget(long chatId) {
        perChat.remove(chatId);
    }

    /** Drops per-chat buckets that are full and idle - nothing is lost by recreating them. */
    public void sweep() {
        long now = nanos.getAsLong();
        for (Iterator<Map.Entry<Long, Bucket>> it = perChat.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().isIdle(now)) {
                it.remove();
            }
        }
    }

    private Bucket chatBucket(long chatId) {
        return perChat.computeIfAbsent(chatId,
                id -> new Bucket(perChatCapacity, perChatRefillPerNano, nanos.getAsLong()));
    }

    /** A leaky bucket that also honours an externally imposed pause. */
    private static final class Bucket {

        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long updated;
        private long blockedUntil;

        Bucket(double capacity, double refillPerNano, long now) {
            this.capacity = capacity;
            this.refillPerNano = refillPerNano;
            this.tokens = capacity;
            this.updated = now;
            this.blockedUntil = now;
        }

        long waitNanos(long now) {
            refill(now);
            long penalty = Math.max(0L, blockedUntil - now);
            if (tokens >= 1.0d) {
                return penalty;
            }
            // Time until one whole token exists. Rounded up, because sleeping for the exact
            // amount lands on the boundary and half the time comes back still short.
            long need = (long) Math.ceil((1.0d - tokens) / refillPerNano);
            return Math.max(need, penalty);
        }

        void take(long now) {
            refill(now);
            tokens = Math.max(0.0d, tokens - 1.0d);
        }

        void blockUntil(long until) {
            if (until > blockedUntil) {
                blockedUntil = until;
            }
        }

        boolean isIdle(long now) {
            refill(now);
            return tokens >= capacity && now >= blockedUntil;
        }

        private void refill(long now) {
            if (now <= updated) {
                // A non-monotonic reading would otherwise mint tokens out of negative elapsed
                // time. nanoTime is monotonic, but the injected clock in tests need not be.
                updated = now;
                return;
            }
            tokens = Math.min(capacity, tokens + (now - updated) * refillPerNano);
            updated = now;
        }
    }
}
