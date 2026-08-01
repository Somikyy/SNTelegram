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

/**
 * A refusal from the Bot API, carrying enough for the caller to decide what to do next.
 *
 * <p>The distinction that matters is {@link #retryable()}: a 429 or a network blip means "try
 * again shortly", while a 401 means the token is wrong and retrying forever only fills the log.
 * The bridge treats those two completely differently - the first is invisible to the admin, the
 * second stops the bridge and says so in plain Russian.
 */
public final class TelegramException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP status, or {@code 0} when the request never got an answer. */
    private final int code;

    /** Milliseconds Telegram asked us to wait, from {@code parameters.retry_after}; {@code 0} if none. */
    private final long retryAfterMillis;

    /**
     * The chat's new id, from {@code parameters.migrate_to_chat_id}; {@code 0} if none.
     *
     * <p>Sent once, when a plain group is upgraded to a supergroup - which happens the moment an
     * admin turns on topics, so it happens to almost every server that follows the setup guide.
     * The old id stops working permanently and every send fails with a 400 that says nothing
     * useful on its own. Carrying the new id lets the bridge tell the admin exactly what to
     * paste into the config instead of leaving them to work it out.
     */
    private final long migrateToChatId;

    private final String method;

    public TelegramException(String method, int code, String description, long retryAfterMillis,
                             long migrateToChatId) {
        super(message(method, code, description));
        this.method = method;
        this.code = code;
        this.retryAfterMillis = retryAfterMillis;
        this.migrateToChatId = migrateToChatId;
    }

    public TelegramException(String method, String description, Throwable cause) {
        super(message(method, 0, description), cause);
        this.method = method;
        this.code = 0;
        this.retryAfterMillis = 0L;
        this.migrateToChatId = 0L;
    }

    private static String message(String method, int code, String description) {
        StringBuilder sb = new StringBuilder("Telegram отклонил запрос ").append(method);
        if (code != 0) {
            sb.append(" (код ").append(code).append(')');
        }
        if (description != null && !description.isEmpty()) {
            sb.append(": ").append(description);
        }
        return sb.toString();
    }

    public int code() {
        return code;
    }

    public String method() {
        return method;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public long migrateToChatId() {
        return migrateToChatId;
    }

    /**
     * True when trying the same request again can plausibly succeed.
     *
     * <p>{@code 0} covers connection failures - the single most common state on a Russian host,
     * where a route to Telegram may come and go. {@code 429} is explicit throttling. {@code 5xx}
     * is Telegram's own trouble. Everything else is our mistake and repeating it will not fix it.
     */
    public boolean retryable() {
        return code == 0 || code == 429 || code >= 500;
    }

    /** True when the token itself is rejected: the one error worth stopping the bridge for. */
    public boolean unauthorized() {
        return code == 401;
    }
}
