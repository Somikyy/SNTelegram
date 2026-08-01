/*
 * SNTelegram - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Bot API server good enough to prove the bridge works, and small enough to read in a sitting.
 *
 * <p>The self-test must run with no network - Maven Central and api.telegram.org are both
 * routinely unreachable from a Russian box, and a test suite that only passes in CI is not a
 * gate. So the bridge is pointed at this, on localhost, via the same {@code base-url} setting a
 * real admin would use for a self-hosted Bot API server. That is the point: the code path under
 * test is the production one, down to the JSON on the socket.
 *
 * <p>Built on {@code com.sun.net.httpserver}, which ships inside the JDK. No dependency, nothing
 * to download, and it exists on every JDK the plugin is built with.
 *
 * <p>What it can do, because the tests need it:
 * <ul>
 *   <li>queue updates for {@code getUpdates} to hand out, honouring {@code offset}</li>
 *   <li>record every call made to it, so a test can assert on what the bridge sent</li>
 *   <li>fail on demand - 429 with {@code retry_after}, 401, 409, malformed JSON - because the
 *       error paths are the ones worth testing and the real Telegram will not produce them to
 *       order</li>
 * </ul>
 */
public final class FakeTelegram {

    private final HttpServer server;

    /**
     * Held so it can be shut down.
     *
     * <p>{@code HttpServer.stop()} does not touch an executor the caller supplied, and the
     * default thread pool is not made of daemon threads - so without this the self-test JVM
     * prints every assertion, passes, and then hangs forever instead of exiting. Which it did.
     */
    private final ExecutorService workers;

    private final String token;

    /** Updates waiting to be handed out, oldest first. */
    private final Deque<String> pending = new ArrayDeque<>();

    /** Every call the bridge made: method name and raw body. */
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    /** Per-method canned failures, consumed one per call. */
    private final Map<String, Deque<Failure>> failures = new ConcurrentHashMap<>();

    private final AtomicLong nextMessageId = new AtomicLong(1000);

    public record Call(String method, String body) {
        public boolean bodyHas(String needle) {
            return body.contains(needle);
        }
    }

    private record Failure(int httpStatus, String json) {
    }

    public FakeTelegram(String token) throws IOException {
        this(token, 0);
    }

    /**
     * @param port {@code 0} to let the OS pick, or a specific port to reoccupy
     *
     * <p>Reoccupying a port is how the outage test works: the server is stopped to break the
     * connection, then a new one is started on the same port to represent the network coming
     * back. Without that the bridge would be pointed at a different address after the outage,
     * which is not what recovering from an outage means.
     */
    public FakeTelegram(String token, int port) throws IOException {
        this.token = token;
        // Port 0 by default: the OS picks a free one. A fixed port makes the suite fail on a
        // developer machine that happens to be using it, which is a false failure and erodes
        // trust in the whole gate.
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/", this::handle);
        this.workers = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "FakeTelegram-worker");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(workers);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        workers.shutdownNow();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ---------------------------------------------------------------- test control

    /** Makes an update available to the next {@code getUpdates}. */
    public synchronized void push(String updateJson) {
        pending.addLast(updateJson);
    }

    /** Makes the next call to {@code method} fail with this status and envelope. */
    public void failNext(String method, int httpStatus, String json) {
        failures.computeIfAbsent(method, k -> new ArrayDeque<>()).addLast(new Failure(httpStatus, json));
    }

    /** Makes the next call to {@code method} answer 429 asking for {@code seconds}. */
    public void throttleNext(String method, int seconds) {
        failNext(method, 429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                + "\"parameters\":{\"retry_after\":" + seconds + "}}");
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> callsTo(String method) {
        List<Call> out = new ArrayList<>();
        for (Call c : calls) {
            if (c.method().equals(method)) {
                out.add(c);
            }
        }
        return out;
    }

    public void clearCalls() {
        calls.clear();
    }

    // ---------------------------------------------------------------- server

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        // The bridge must send the token in the path, exactly as the Bot API requires. Checking
        // it here means a regression that drops the token fails the test instead of silently
        // working against a fake that never looked.
        String expectedPrefix = "/bot" + token + "/";
        if (!path.startsWith(expectedPrefix)) {
            respond(exchange, 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
            return;
        }
        String method = path.substring(expectedPrefix.length());
        calls.add(new Call(method, body));

        Deque<Failure> queued = failures.get(method);
        if (queued != null) {
            Failure failure = queued.pollFirst();
            if (failure != null) {
                respond(exchange, failure.httpStatus(), failure.json());
                return;
            }
        }

        respond(exchange, 200, switch (method.toLowerCase(Locale.ROOT)) {
            case "getupdates" -> getUpdates(body);
            case "getme" -> "{\"ok\":true,\"result\":{\"id\":7000000001,\"is_bot\":true,"
                    + "\"first_name\":\"SNTelegram Test\",\"username\":\"sntelegram_test_bot\","
                    + "\"can_join_groups\":true,\"can_read_all_group_messages\":true}}";
            case "getchat" -> "{\"ok\":true,\"result\":{\"id\":-1001234567890123,\"type\":\"supergroup\","
                    + "\"title\":\"Тестовый сервер\",\"is_forum\":true}}";
            case "sendmessage" -> "{\"ok\":true,\"result\":{\"message_id\":" + nextMessageId.incrementAndGet()
                    + ",\"date\":1785000000,\"chat\":{\"id\":-1001234567890123,\"type\":\"supergroup\"}}}";
            default -> "{\"ok\":true,\"result\":true}";
        });
    }

    private synchronized String getUpdates(String body) {
        // offset acknowledges everything below it. Parsed with a crude scan rather than a JSON
        // reader on purpose: the harness must not share code with the thing it is testing, or a
        // bug in the parser would hide itself.
        long offset = longField(body, "offset");
        if (offset == -1L) {
            // The backlog-skip probe. Answer with only the newest, or nothing at all.
            String last = pending.peekLast();
            return envelope(last == null ? List.of() : List.of(last));
        }
        List<String> out = new ArrayList<>();
        while (!pending.isEmpty()) {
            String update = pending.peekFirst();
            if (offset > 0 && longField(update, "update_id") < offset) {
                pending.pollFirst();
                continue;
            }
            out.add(pending.pollFirst());
        }
        return envelope(out);
    }

    private static String envelope(List<String> updates) {
        return "{\"ok\":true,\"result\":[" + String.join(",", updates) + "]}";
    }

    private static long longField(String json, String name) {
        int at = json.indexOf('"' + name + '"');
        if (at < 0) {
            return 0L;
        }
        int i = json.indexOf(':', at) + 1;
        while (i < json.length() && json.charAt(i) == ' ') {
            i++;
        }
        int start = i;
        while (i < json.length() && (json.charAt(i) == '-' || Character.isDigit(json.charAt(i)))) {
            i++;
        }
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
