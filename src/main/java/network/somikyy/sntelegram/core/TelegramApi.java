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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The whole of SNTelegram's Bot API client: one HTTP call, one response envelope.
 *
 * <p>Hand-written on {@code java.net.http.HttpClient} rather than built on the
 * {@code telegrambots} library, because the suite ships zero runtime dependencies - and because
 * the bridge uses about eight of the Bot API's two hundred methods. The library would be
 * two megabytes of shaded classes on someone else's classpath to save a hundred lines here.
 *
 * <p>Every Bot API method is the same shape - {@code POST /bot<token>/<method>} with a JSON body,
 * answering a JSON envelope of {@code ok} plus {@code result} or {@code description} - so there
 * is exactly one method here and the callers pass a map. Adding support for another Bot API
 * method costs one line at the call site and nothing here.
 *
 * <p><b>The token never appears in any message this class produces.</b> It sits in the URL path,
 * which means the naive thing - logging the failing URL - publishes full control of the bot to
 * the server log, and server logs get pasted into support chats. {@link #redact(String)} exists
 * for that reason and is applied to everything that escapes.
 */
public final class TelegramApi {

    /** Public Bot API. Overridable because a self-hosted Bot API server is a real deployment. */
    public static final String DEFAULT_BASE_URL = "https://api.telegram.org";

    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    /** Applied to ordinary calls. Long polling passes its own, longer, timeout. */
    private final Duration requestTimeout;

    public TelegramApi(String baseUrl, String token, Duration connectTimeout, Duration requestTimeout,
                       String proxyHost, int proxyPort) {
        this.baseUrl = trimSlash(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl);
        this.token = token == null ? "" : token.trim();
        this.requestTimeout = requestTimeout;

        HttpClient.Builder builder = HttpClient.newBuilder()
                // HTTP/1.1 on purpose. Long polling holds a request open for up to a minute, and
                // that pattern meets HTTP/2 idle handling badly on intermediaries; there is also
                // nothing to multiplex, since the bridge has one poll in flight at a time.
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(connectTimeout);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            // Present because the target market is Russian hosting, where a direct route to
            // api.telegram.org is not something to assume.
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost.trim(), proxyPort)));
        }
        this.http = builder.build();
    }

    /** True when a token is configured at all - checked before the bridge tries to start. */
    public boolean hasToken() {
        return !token.isEmpty();
    }

    /**
     * Calls a Bot API method and returns its {@code result}.
     *
     * @param method Bot API method name, for example {@code sendMessage}
     * @param params request body; {@code null} values are omitted by {@link Json#write}
     * @param timeout how long to wait for the answer; long polling passes poll timeout plus slack
     * @throws TelegramException on any refusal, including transport failure
     */
    public Json call(String method, Map<String, Object> params, Duration timeout) {
        String body = Json.write(params == null ? Map.of() : params);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/bot" + token + "/" + method))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            // A malformed base URL from the config lands here. The exception text would contain
            // the whole URI, token included.
            throw new TelegramException(method, "адрес Bot API не разобрать: " + redact(e.getMessage()), e);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TelegramException(method, "нет связи с Telegram: " + redact(describe(e)), e);
        } catch (InterruptedException e) {
            // Shutdown. Restore the flag so the polling loop sees it and stops, and do not
            // dress this up as a Telegram failure.
            Thread.currentThread().interrupt();
            throw new TelegramException(method, "запрос прерван при остановке", e);
        }

        Json envelope;
        try {
            envelope = Json.parse(response.body());
        } catch (Json.JsonException e) {
            // A captive portal or a proxy error page: HTML where JSON was expected. Says so
            // rather than dumping the page into the log.
            throw new TelegramException(method, response.statusCode(),
                    "ответ не является JSON (HTTP " + response.statusCode() + ")", 0L, 0L);
        }

        if (!envelope.bool("ok", false)) {
            Json parameters = envelope.obj("parameters");
            // retry_after is documented as seconds: "the number of seconds left to wait before
            // the request can be repeated". Everything inside the bridge is milliseconds.
            long retryAfter = parameters.num("retry_after", 0L);
            throw new TelegramException(method,
                    (int) envelope.num("error_code", response.statusCode()),
                    redact(envelope.str("description", "без объяснения")),
                    retryAfter * 1000L,
                    parameters.num("migrate_to_chat_id", 0L));
        }
        return envelope.obj("result");
    }

    public Json call(String method, Map<String, Object> params) {
        return call(method, params, requestTimeout);
    }

    /**
     * Replaces the bot token wherever it appears.
     *
     * <p>Not defence in depth - defence at the only depth there is. The token is a URL path
     * segment, so it turns up in connection errors, redirect messages and proxy replies, all of
     * which are printed. Anyone holding it can read every message in every chat the bot is in.
     */
    public String redact(String text) {
        if (text == null) {
            return "";
        }
        if (token.isEmpty()) {
            return text;
        }
        String cleaned = text.replace(token, "<токен скрыт>");
        // Also catch the case where only the secret half of the token leaks: the part after the
        // colon is what actually authenticates, and the numeric bot id before it is public.
        int colon = token.indexOf(':');
        if (colon > 0 && colon + 1 < token.length()) {
            cleaned = cleaned.replace(token.substring(colon + 1), "<токен скрыт>");
        }
        return cleaned;
    }

    /** Exception text that names the cause without a stack trace - this goes to a server log. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    private static String trimSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
