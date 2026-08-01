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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The long-polling loop: asks Telegram for updates forever, hands each one to a consumer.
 *
 * <p>Polling rather than webhooks, and not as a fallback - as the only mode. A webhook needs a
 * public HTTPS endpoint with a valid certificate on a port Telegram can reach, which a Minecraft
 * server admin usually does not have and should not have to arrange. Polling needs nothing but
 * an outbound connection, which is the whole setup story: paste a token, restart.
 *
 * <p>Runs on its own thread. It never touches the game - it only calls the consumer, which is
 * responsible for getting onto whatever thread it needs.
 *
 * <p>Three failure modes are handled explicitly, because all three happen in production:
 * <ul>
 *   <li><b>No route to Telegram.</b> Retries with growing backoff and stays quiet in the log
 *       after the first complaint. Losing the route for an hour must not produce an hour of log.</li>
 *   <li><b>Another instance polling the same bot</b> (HTTP 409). Happens the moment someone
 *       copies a config to a second server. Detected and reported as that, in those words.</li>
 *   <li><b>A bad token</b> (HTTP 401). Stops permanently: retrying cannot help, and the admin
 *       needs to see one clear line, not a scrolling wall.</li>
 * </ul>
 */
public final class TelegramPoller implements Runnable {

    /**
     * Update types the bridge asks for.
     *
     * <p>Listing them is not an optimisation. {@code allowed_updates} is a filter Telegram applies
     * on its side, so anything not listed is never delivered and never has to be trusted, parsed
     * or logged. A bridge that receives only messages cannot be surprised by a payment update.
     *
     * <p>Just {@code message}, because that is all the bridge acts on. Asking for
     * {@code edited_message} would mean receiving edits it has no answer for - an edited
     * moderation command cannot un-ban anyone - and every extra type is one more shape of JSON
     * arriving from outside.
     *
     * <p>A caution from the Bot API server's own source: unrecognised names in this list are
     * silently ignored, and if <i>none</i> of them are recognised the server falls back to its
     * defaults - so a typo would quietly subscribe the bot to everything. These are constants in
     * code and never come from the config for exactly that reason.
     */
    private static final List<String> ALLOWED_UPDATES = List.of("message");

    private final TelegramApi api;
    private final Consumer<Json> onUpdate;
    private final Log log;

    /** Seconds Telegram holds the request open when there is nothing to report. */
    private final int pollSeconds;

    /** Whether to throw away updates that queued while the server was down. */
    private final boolean dropBacklog;

    private volatile boolean running;
    private volatile Thread thread;

    /** Next {@code offset}: acknowledges everything below it. Owned by the polling thread. */
    private long offset;

    public TelegramPoller(TelegramApi api, int pollSeconds, boolean dropBacklog,
                          Consumer<Json> onUpdate, Log log) {
        this.api = api;
        this.pollSeconds = Math.max(1, Math.min(50, pollSeconds));
        this.dropBacklog = dropBacklog;
        this.onUpdate = onUpdate;
        this.log = log;
    }

    /** Somewhere for the loop to complain to, without core knowing what a server logger is. */
    public interface Log {
        void info(String message);

        void warn(String message);

        void error(String message);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this, "SNTelegram-poller");
        // Daemon: a stuck poll must never be the reason a server cannot finish shutting down.
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /**
     * Stops the loop and waits briefly for the thread to notice.
     *
     * <p>The wait is bounded and short: the thread is almost certainly parked inside a poll that
     * can last most of a minute, and holding up server shutdown for it would be worse than
     * leaving a daemon thread to die on its own.
     */
    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        if (dropBacklog && !skipBacklog()) {
            return;
        }

        long backoffMillis = 1000L;
        boolean complainedAboutNetwork = false;

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                List<Json> updates = fetch();
                if (complainedAboutNetwork) {
                    log.info("Связь с Telegram восстановлена.");
                    complainedAboutNetwork = false;
                }
                backoffMillis = 1000L;
                for (Json update : updates) {
                    if (!running) {
                        // Deliberately does not advance the offset for the rest of the batch:
                        // on the next start Telegram redelivers them, which is the right trade -
                        // a duplicated message is recoverable, a silently dropped one is not.
                        return;
                    }
                    offset = Math.max(offset, update.num("update_id", -1) + 1);
                    deliver(update);
                }
            } catch (TelegramException e) {
                if (e.unauthorized()) {
                    log.error("Telegram не принял токен бота. Мост остановлен: проверь "
                            + "telegram.token в config.yml и перезапусти сервер.");
                    running = false;
                    return;
                }
                if (e.code() == 409) {
                    // Two pollers on one token: each steals the other's updates and neither
                    // works. Naming it exactly saves an evening of "messages arrive sometimes".
                    log.error("Этого бота уже опрашивает кто-то ещё — обычно это второй сервер "
                            + "с тем же токеном или незакрытый webhook. Мост будет получать "
                            + "сообщения через раз, пока это не исправлено.");
                }
                if (!complainedAboutNetwork) {
                    log.warn(e.getMessage());
                    complainedAboutNetwork = true;
                }
                long pause = e.retryAfterMillis() > 0 ? e.retryAfterMillis() : backoffMillis;
                if (!sleep(pause)) {
                    return;
                }
                // Cap at a minute: long enough not to spam a dead endpoint, short enough that
                // the bridge comes back on its own within a minute of the route returning.
                backoffMillis = Math.min(backoffMillis * 2, 60_000L);
            } catch (RuntimeException e) {
                // A parse failure or a consumer that threw. Neither is a reason for the bridge
                // to die - the next update is usually fine.
                log.warn("Сбой при обработке обновления из Telegram: " + api.redact(String.valueOf(e.getMessage())));
                if (!sleep(1000L)) {
                    return;
                }
            }
        }
    }

    private void deliver(Json update) {
        try {
            onUpdate.accept(update);
        } catch (RuntimeException e) {
            // One malformed message must not stop the bridge for everyone else.
            log.warn("Сообщение из Telegram не удалось обработать: " + api.redact(String.valueOf(e.getMessage())));
        }
    }

    private List<Json> fetch() {
        Map<String, Object> params = Json.map();
        params.put("offset", offset);
        params.put("timeout", pollSeconds);
        params.put("allowed_updates", ALLOWED_UPDATES);
        // The HTTP timeout must outlast the long poll itself, or every single poll ends in a
        // client-side timeout and the bridge looks broken while being perfectly healthy.
        return api.call("getUpdates", params, Duration.ofSeconds(pollSeconds + 15L)).arr();
    }

    /**
     * Throws away whatever queued while the server was down.
     *
     * <p>Telegram holds undelivered updates for up to 24 hours. Without this, a server that was
     * off overnight comes back and replays the entire night of Telegram chat into the game at
     * once - which reads as a flood of stale messages and looks exactly like a broken plugin.
     *
     * <p>{@code offset: -1} asks for the single most recent update; acknowledging it clears
     * everything before it.
     *
     * @return false when the bridge must not continue
     */
    private boolean skipBacklog() {
        try {
            Map<String, Object> params = Json.map();
            params.put("offset", -1L);
            params.put("timeout", 0);
            params.put("allowed_updates", ALLOWED_UPDATES);
            List<Json> last = api.call("getUpdates", params, Duration.ofSeconds(20L)).arr();
            for (Json update : last) {
                offset = Math.max(offset, update.num("update_id", -1) + 1);
            }
            return true;
        } catch (TelegramException e) {
            if (e.unauthorized()) {
                log.error("Telegram не принял токен бота. Мост остановлен: проверь "
                        + "telegram.token в config.yml и перезапусти сервер.");
                running = false;
                return false;
            }
            // Anything else: start polling from zero. Worst case the admin sees a few old
            // messages once, which is far better than not starting at all.
            log.warn("Не удалось пропустить накопившиеся сообщения: " + e.getMessage());
            return true;
        }
    }

    /** @return false if interrupted, meaning the caller must return */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
