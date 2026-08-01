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

import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The one place messages leave for Telegram: a bounded queue drained by a single thread under a
 * rate limit.
 *
 * <p>This class exists to keep one promise: <b>the game never waits for Telegram</b>. A chat
 * listener runs on the server's chat thread, and an HTTPS round trip to Telegram takes tens of
 * milliseconds on a good day and thirty seconds on a bad one. Sending inline would mean every
 * player's message is held hostage by the network quality of a server in another country. So the
 * listener enqueues and returns, and everything slow happens here.
 *
 * <p>The queue is bounded and drops the <i>oldest</i> entry when full. Both halves are
 * deliberate. Unbounded would turn a Telegram outage during peak hour into an
 * OutOfMemoryError - the plugin would kill the server it was supposed to serve. Dropping the
 * oldest keeps the most recent chat, which is the part anyone reading Telegram actually wants.
 */
public final class Outbox implements Runnable {

    private final TelegramApi api;
    private final RateLimiter limiter;
    private final TelegramPoller.Log log;
    private final LongSupplier nanos;

    private final LinkedBlockingDeque<Task> queue;
    private final int capacity;

    /** Entries older than this are thrown away unsent - stale chat is worse than no chat. */
    private final long maxAgeNanos;

    private volatile boolean running;
    private volatile Thread thread;

    /** Counters for {@code /sntelegram status}: what the admin needs to judge the bridge's health. */
    private volatile long sent;
    private volatile long dropped;
    private volatile long failed;

    public Outbox(TelegramApi api, RateLimiter limiter, int capacity, long maxAgeSeconds,
                  TelegramPoller.Log log, LongSupplier nanos) {
        this.api = api;
        this.limiter = limiter;
        this.capacity = Math.max(16, capacity);
        this.queue = new LinkedBlockingDeque<>(this.capacity);
        this.maxAgeNanos = Math.max(1L, maxAgeSeconds) * 1_000_000_000L;
        this.log = log;
        this.nanos = nanos;
    }

    /**
     * A queued Bot API call. {@code chatId} is what the rate limiter buckets by.
     *
     * <p>{@code onSent} receives the {@code result} of a successful call and may be {@code null}.
     * It exists for one feature and pays for itself there: reply-moderation needs to remember
     * which Telegram message carried which player's chat line, and the only place that mapping
     * exists is the {@code message_id} in the answer to {@code sendMessage}. It runs on the
     * sender thread, so it must not block.
     */
    private record Task(long chatId, String method, Map<String, Object> params, long queuedAt,
                        int attempt, Consumer<Json> onSent) {
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this, "SNTelegram-sender");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /**
     * Stops the sender, giving the queue a moment to flush.
     *
     * <p>The flush matters for exactly one message: the "server is stopping" line. An admin
     * watching Telegram should see the server go down, and that message is enqueued microseconds
     * before shutdown - without a grace period it would be the one message that never arrives.
     */
    public void stop(long graceMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, graceMillis);
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    /**
     * Queues a Bot API call. Returns immediately and never throws - callers are event handlers.
     *
     * @return false when the entry displaced an older one because the queue was full
     */
    public boolean enqueue(long chatId, String method, Map<String, Object> params) {
        return enqueue(chatId, method, params, null);
    }

    /**
     * Queues a call and hands the {@code result} to {@code onSent} if it succeeds.
     *
     * @param onSent runs on the sender thread; must not block and must not throw
     */
    public boolean enqueue(long chatId, String method, Map<String, Object> params, Consumer<Json> onSent) {
        Task task = new Task(chatId, method, params, nanos.getAsLong(), 0, onSent);
        if (queue.offerLast(task)) {
            return true;
        }
        // Full. Make room by discarding the oldest, then retry once. If that also fails the
        // drain thread is competing for the same slot, and losing this one entry is fine.
        Task discarded = queue.pollFirst();
        if (discarded != null) {
            dropped++;
        }
        boolean queued = queue.offerLast(task);
        if (!queued) {
            dropped++;
        }
        return false;
    }

    public long sentCount() {
        return sent;
    }

    public long droppedCount() {
        return dropped;
    }

    public long failedCount() {
        return failed;
    }

    public int pending() {
        return queue.size();
    }

    @Override
    public void run() {
        boolean warnedAboutDrops = false;
        while (running && !Thread.currentThread().isInterrupted()) {
            Task task;
            try {
                task = queue.pollFirst(500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) {
                // Idle: a good moment to let per-chat buckets go.
                limiter.sweep();
                if (dropped > 0 && !warnedAboutDrops) {
                    log.warn("Часть сообщений не доехала до Telegram: очередь переполнялась "
                            + dropped + " раз(а). Обычно это значит, что связь с Telegram "
                            + "медленнее, чем поток чата.");
                    warnedAboutDrops = true;
                }
                continue;
            }

            if (nanos.getAsLong() - task.queuedAt() > maxAgeNanos) {
                dropped++;
                continue;
            }

            long wait = limiter.waitNanos(task.chatId());
            if (wait > 0) {
                // Put it back at the head so order is preserved, then wait. Sleeping while
                // holding the task would reorder it behind anything enqueued meanwhile.
                queue.offerFirst(task);
                if (!sleepNanos(wait)) {
                    return;
                }
                continue;
            }

            limiter.consume(task.chatId());
            try {
                Json result = api.call(task.method(), task.params());
                sent++;
                if (task.onSent() != null) {
                    try {
                        task.onSent().accept(result);
                    } catch (RuntimeException e) {
                        // A bookkeeping callback must never take the sender thread down with it.
                        log.warn("Ошибка после отправки в Telegram: "
                                + api.redact(String.valueOf(e.getMessage())));
                    }
                }
            } catch (TelegramException e) {
                handleFailure(task, e);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Не удалось отправить в Telegram: " + api.redact(String.valueOf(e.getMessage())));
            }
        }
    }

    private void handleFailure(Task task, TelegramException e) {
        if (e.retryAfterMillis() > 0) {
            limiter.penalise(task.chatId(), e.retryAfterMillis());
        }
        // Two retries, then give up. A chat line is worth a couple of attempts and no more:
        // retrying forever means a permanently misconfigured chat id blocks the queue for
        // every other chat behind it.
        if (e.retryable() && task.attempt() < 2) {
            queue.offerFirst(new Task(task.chatId(), task.method(), task.params(),
                    task.queuedAt(), task.attempt() + 1, task.onSent()));
            return;
        }
        failed++;
        if (e.migrateToChatId() != 0L) {
            // Happens the first time an admin turns on topics: Telegram upgrades the group to a
            // supergroup and the old id dies for good. Without this line the admin sees only
            // "Bad Request" forever and has no way to guess what changed.
            log.error("Группа превращена в супергруппу, и старый telegram.chat-id больше не "
                    + "работает. Новый id: " + e.migrateToChatId() + " — впиши его в config.yml "
                    + "и выполни /sntelegram reload.");
            return;
        }
        if (e.code() == 400 || e.code() == 403) {
            // These are configuration errors, not weather: a wrong chat id, a bot that was
            // removed from the group, a topic that no longer exists. Worth one clear line.
            log.warn(e.getMessage() + " (чат " + task.chatId() + ")");
        }
    }

    /** @return false if interrupted, meaning the caller must return */
    private boolean sleepNanos(long wait) {
        try {
            // Capped: waking up periodically is what lets stop() take effect promptly even when
            // the limiter has told us to wait out a long penalty.
            TimeUnit.NANOSECONDS.sleep(Math.min(wait, 250_000_000L));
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
