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

import network.somikyy.sntelegram.core.Json;
import network.somikyy.sntelegram.core.Outbox;
import network.somikyy.sntelegram.core.RateLimiter;
import network.somikyy.sntelegram.core.TelegramApi;
import network.somikyy.sntelegram.core.TelegramException;
import network.somikyy.sntelegram.core.TelegramPoller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Drives the real networking core against {@link FakeTelegram} and prints {@code key=value} lines
 * for {@code selftest.sh} to assert on.
 *
 * <p>Everything here goes over a real socket through the real {@link TelegramApi}: the point is
 * to exercise the code that ships, not a mock of it. The only thing replaced is what is on the
 * other end of the connection.
 */
public final class CoreProbe {

    private static final String TOKEN = "7000000001:AAF-testtoken-not-a-real-secret-000000";

    /** Collects log lines so the tests can assert the bridge complains about the right things. */
    private static final List<String> LOG = new CopyOnWriteArrayList<>();

    private static final TelegramPoller.Log LOGGER = new TelegramPoller.Log() {
        @Override
        public void info(String message) {
            LOG.add("INFO " + message);
        }

        @Override
        public void warn(String message) {
            LOG.add("WARN " + message);
        }

        @Override
        public void error(String message) {
            LOG.add("ERROR " + message);
        }
    };

    public static void main(String[] args) throws Exception {
        FakeTelegram fake = new FakeTelegram(TOKEN);
        fake.start();
        try {
            TelegramApi api = new TelegramApi(fake.baseUrl(), TOKEN,
                    Duration.ofSeconds(5), Duration.ofSeconds(10), null, 0);

            tokenIsSentAndNeverPrinted(api, fake);
            errorsAreClassified(api, fake);
            pollingDeliversUpdatesInOrder(api, fake);
            pollingSkipsTheBacklog(api, fake);
            outboxSendsAndSurvivesThrottling(api, fake);
            configErrorsAreExplained(api, fake);
            groupUpgradeIsExplained(api, fake);
            outboxDropsOldestWhenFull(api, fake);
        } finally {
            fake.stop();
        }
        // Runs last and on its own servers: it stops and restarts one, which the cases above
        // share and would not survive.
        survivesAnOutage();
    }

    // ---------------------------------------------------------------- cases

    private static void tokenIsSentAndNeverPrinted(TelegramApi api, FakeTelegram fake) {
        fake.clearCalls();
        Json me = api.call("getMe", Json.map());
        // FakeTelegram answers 401 unless the token is in the path, so reaching here proves it.
        System.out.println("api.token-in-path=" + me.bool("is_bot", false));
        System.out.println("api.getme-username=" + me.str("username", ""));

        String leak = "сбой при обращении к " + api.redact(
                "https://api.telegram.org/bot" + TOKEN + "/sendMessage");
        System.out.println("api.redacts-full-token=" + !leak.contains(TOKEN));
        System.out.println("api.redacts-secret-half="
                + !leak.contains(TOKEN.substring(TOKEN.indexOf(':') + 1)));
        System.out.println("api.redaction-keeps-context=" + leak.contains("sendMessage"));
    }

    private static void errorsAreClassified(TelegramApi api, FakeTelegram fake) {
        fake.failNext("sendMessage", 401,
                "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        System.out.println("err.401-unauthorized=" + classify(api, "sendMessage", e -> e.unauthorized()));

        fake.throttleNext("sendMessage", 7);
        System.out.println("err.429-retryable=" + classify(api, "sendMessage", TelegramException::retryable));

        fake.throttleNext("sendMessage", 7);
        System.out.println("err.429-retry-after-ms="
                + value(api, "sendMessage", TelegramException::retryAfterMillis));

        fake.failNext("sendMessage", 400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}");
        System.out.println("err.400-not-retryable=" + classify(api, "sendMessage", e -> !e.retryable()));

        // A proxy error page instead of JSON: must be a clear refusal, not a parser stack trace.
        fake.failNext("sendMessage", 502, "<html><body>502 Bad Gateway</body></html>");
        System.out.println("err.html-body-handled=" + classify(api, "sendMessage",
                e -> e.getMessage().contains("не является JSON")));
    }

    private static void pollingDeliversUpdatesInOrder(TelegramApi api, FakeTelegram fake)
            throws Exception {
        fake.clearCalls();
        fake.push(update(101, "первое"));
        fake.push(update(102, "второе"));
        fake.push(update(103, "третье"));

        List<String> seen = new CopyOnWriteArrayList<>();
        TelegramPoller poller = new TelegramPoller(api, 1, false,
                u -> seen.add(u.obj("message").str("text", "")), LOGGER);
        poller.start();
        waitUntil(() -> seen.size() >= 3, 5000);
        poller.stop();

        System.out.println("poll.delivered=" + seen.size());
        System.out.println("poll.in-order=" + seen.equals(List.of("первое", "второе", "третье")));
        System.out.println("poll.asks-for-allowed-updates="
                + fake.callsTo("getUpdates").get(0).bodyHas("allowed_updates"));
        System.out.println("poll.limits-update-types="
                + !fake.callsTo("getUpdates").get(0).bodyHas("chat_member"));
    }

    private static void pollingSkipsTheBacklog(TelegramApi api, FakeTelegram fake) throws Exception {
        fake.clearCalls();
        // Three messages queued while the server was down, then one sent after it came up.
        fake.push(update(201, "старое-1"));
        fake.push(update(202, "старое-2"));
        fake.push(update(203, "старое-3"));

        List<String> seen = new CopyOnWriteArrayList<>();
        TelegramPoller poller = new TelegramPoller(api, 1, true,
                u -> seen.add(u.obj("message").str("text", "")), LOGGER);
        poller.start();
        waitUntil(() -> !fake.callsTo("getUpdates").isEmpty(), 3000);
        Thread.sleep(300L);
        fake.push(update(204, "свежее"));
        waitUntil(() -> seen.contains("свежее"), 5000);
        poller.stop();

        System.out.println("backlog.skipped=" + !seen.contains("старое-1"));
        System.out.println("backlog.fresh-delivered=" + seen.contains("свежее"));
        System.out.println("backlog.probe-used-offset-minus-1="
                + fake.callsTo("getUpdates").get(0).bodyHas("\"offset\":-1"));
    }

    private static void outboxSendsAndSurvivesThrottling(TelegramApi api, FakeTelegram fake)
            throws Exception {
        fake.clearCalls();
        RateLimiter limiter = new RateLimiter(1000, 1000, System::nanoTime);
        Outbox outbox = new Outbox(api, limiter, 100, 60, LOGGER, System::nanoTime);
        outbox.start();

        fake.throttleNext("sendMessage", 1);
        outbox.enqueue(-1001234567890123L, "sendMessage", sendParams("после throttling"));
        waitUntil(() -> outbox.sentCount() >= 1, 8000);
        outbox.stop(500);

        System.out.println("outbox.sent-after-429=" + outbox.sentCount());
        System.out.println("outbox.retried=" + (fake.callsTo("sendMessage").size() >= 2));
        System.out.println("outbox.body-has-thread="
                + fake.callsTo("sendMessage").get(fake.callsTo("sendMessage").size() - 1)
                        .bodyHas("message_thread_id"));
        System.out.println("outbox.body-has-html="
                + fake.callsTo("sendMessage").get(0).bodyHas("\"parse_mode\":\"HTML\""));
    }

    /**
     * Configuration mistakes must be reported in words the admin can act on.
     *
     * <p>Every one of these is a real setup step going wrong, and Telegram's own wording names
     * the mechanism rather than the setting: "message thread not found" does not mention
     * config.yml, thread-id, or where the right number comes from. The first live server hit
     * exactly this one, on the very next restart after topics were switched on.
     */
    private static void configErrorsAreExplained(TelegramApi api, FakeTelegram fake) throws Exception {
        RateLimiter fast = new RateLimiter(1000, 1000, System::nanoTime);
        Outbox outbox = new Outbox(api, fast, 100, 60, LOGGER, System::nanoTime);
        outbox.start();

        LOG.clear();
        fake.failNext("sendMessage", 400, "{\"ok\":false,\"error_code\":400,"
                + "\"description\":\"Bad Request: message thread not found\"}");
        outbox.enqueue(-1001234567890123L, "sendMessage", sendParams("в тему 2"));
        waitUntil(() -> logHas("нет темы с номером"), 8000);
        System.out.println("explain.missing-topic=" + logHas("нет темы с номером 2"));
        System.out.println("explain.points-at-config=" + logHas("config.yml"));
        System.out.println("explain.explains-general=" + logHas("General"));

        // The same mistake repeated must not fill the log with identical lines.
        int before = LOG.size();
        fake.failNext("sendMessage", 400, "{\"ok\":false,\"error_code\":400,"
                + "\"description\":\"Bad Request: message thread not found\"}");
        outbox.enqueue(-1001234567890123L, "sendMessage", sendParams("снова в тему 2"));
        Thread.sleep(800L);
        System.out.println("explain.not-repeated=" + (LOG.size() == before));

        LOG.clear();
        fake.failNext("sendMessage", 400, "{\"ok\":false,\"error_code\":400,"
                + "\"description\":\"Bad Request: chat not found\"}");
        outbox.enqueue(-1001234567890123L, "sendMessage", sendParams("никуда"));
        waitUntil(() -> logHas("не знает чат"), 8000);
        System.out.println("explain.chat-not-found=" + logHas("отрицательный"));

        LOG.clear();
        fake.failNext("sendMessage", 400, "{\"ok\":false,\"error_code\":400,"
                + "\"description\":\"Bad Request: not enough rights to send text messages\"}");
        outbox.enqueue(-1001234567890123L, "sendMessage", sendParams("нет прав"));
        waitUntil(() -> logHas("нет прав писать"), 8000);
        System.out.println("explain.no-rights=" + logHas("администратора"));

        outbox.stop(500);
    }

    /**
     * A group being upgraded to a supergroup, which changes its id permanently.
     *
     * <p>Happens the first time an admin turns on topics - so it happens to almost every server
     * that follows the setup guide, once, and then never again. Telegram answers every subsequent
     * send with a 400 that says nothing an admin can use; the new id is tucked into
     * {@code parameters.migrate_to_chat_id}, and without reading it the only symptom is a bridge
     * that stopped working for no visible reason.
     *
     * <p>Impossible to arrange on a live server without destroying the group under test, which is
     * exactly the kind of thing a fake Bot API is for.
     */
    private static void groupUpgradeIsExplained(TelegramApi api, FakeTelegram fake) throws Exception {
        RateLimiter fast = new RateLimiter(1000, 1000, System::nanoTime);
        Outbox outbox = new Outbox(api, fast, 100, 60, LOGGER, System::nanoTime);
        outbox.start();

        LOG.clear();
        fake.failNext("sendMessage", 400, "{\"ok\":false,\"error_code\":400,"
                + "\"description\":\"Bad Request: group chat was upgraded to a supergroup chat\","
                + "\"parameters\":{\"migrate_to_chat_id\":-1009876543210987}}");
        outbox.enqueue(-1001234567890123L, "sendMessage", sendParams("после апгрейда"));
        waitUntil(() -> logHas("супергруппу"), 8000);

        System.out.println("migrate.detected=" + logHas("супергруппу"));
        System.out.println("migrate.gives-new-id=" + logHas("-1009876543210987"));
        System.out.println("migrate.names-the-setting=" + logHas("telegram.chat-id"));
        outbox.stop(500);
    }

    /**
     * Losing the route to Telegram, and getting it back.
     *
     * <p>The promise being tested is the one an admin actually cares about: an outage is invisible
     * to players, quiet in the log, and repairs itself. "Quiet" is the part most easily got wrong -
     * a bridge that logs every failed retry turns a lost hour into an unreadable log file, and on
     * a Russian-hosted server losing the route for an hour is a normal Tuesday.
     *
     * <p>The fake server is stopped and then restarted on the same port, because recovering from
     * an outage means the same address starting to answer again.
     */
    private static void survivesAnOutage() throws Exception {
        FakeTelegram first = new FakeTelegram(TOKEN);
        first.start();
        int port = first.port();
        TelegramApi api = new TelegramApi("http://127.0.0.1:" + port, TOKEN,
                Duration.ofSeconds(2), Duration.ofSeconds(5), null, 0);

        List<String> seen = new CopyOnWriteArrayList<>();
        TelegramPoller poller = new TelegramPoller(api, 1, false,
                u -> seen.add(u.obj("message").str("text", "")), LOGGER);
        LOG.clear();
        poller.start();

        first.push(update(301, "до обрыва"));
        waitUntil(() -> seen.contains("до обрыва"), 8000);
        System.out.println("outage.delivered-before=" + seen.contains("до обрыва"));

        // The network goes away.
        first.stop();
        waitUntil(() -> logHas("нет связи с Telegram"), 8000);
        int firstComplaint = countLog("нет связи с Telegram");
        // Several retries happen in this window; none of them may add another line.
        Thread.sleep(3000L);
        int laterComplaints = countLog("нет связи с Telegram");
        System.out.println("outage.complains-once="
                + (firstComplaint == 1 && laterComplaints == 1));

        // The network comes back at the same address.
        FakeTelegram again = new FakeTelegram(TOKEN, port);
        again.start();
        again.push(update(302, "после обрыва"));
        waitUntil(() -> seen.contains("после обрыва"), 25000);
        poller.stop();
        again.stop();

        System.out.println("outage.recovered-by-itself=" + seen.contains("после обрыва"));
        System.out.println("outage.says-it-recovered=" + logHas("Связь с Telegram восстановлена"));
        System.out.println("outage.nothing-lost=" + (seen.size() == 2));
    }

    private static int countLog(String needle) {
        int n = 0;
        for (String line : LOG) {
            if (line.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    private static boolean logHas(String needle) {
        for (String line : LOG) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void outboxDropsOldestWhenFull(TelegramApi api, FakeTelegram fake) {
        // A limiter that never lets anything through, so the queue can be observed while full.
        RateLimiter frozen = new RateLimiter(0.000001, 0.000001, System::nanoTime);
        Outbox outbox = new Outbox(api, frozen, 16, 60, LOGGER, System::nanoTime);

        boolean everRefused = false;
        for (int i = 0; i < 100; i++) {
            if (!outbox.enqueue(-1001234567890123L, "sendMessage", sendParams("строка " + i))) {
                everRefused = true;
            }
        }
        System.out.println("outbox.bounded=" + (outbox.pending() <= 16));
        System.out.println("outbox.reports-overflow=" + everRefused);
        System.out.println("outbox.counts-drops=" + (outbox.droppedCount() > 0));
        System.out.println("outbox.never-throws=true");
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> sendParams(String text) {
        Map<String, Object> params = Json.map();
        params.put("chat_id", -1001234567890123L);
        params.put("message_thread_id", 2);
        params.put("text", text);
        params.put("parse_mode", "HTML");
        return params;
    }

    private static String update(int id, String text) {
        return "{\"update_id\":" + id + ",\"message\":{\"message_id\":" + id
                + ",\"message_thread_id\":2,\"date\":1785000000,"
                + "\"from\":{\"id\":555000111,\"is_bot\":false,\"first_name\":\"Сомик\","
                + "\"username\":\"somikyy\"},"
                + "\"chat\":{\"id\":-1001234567890123,\"type\":\"supergroup\",\"is_forum\":true},"
                + "\"text\":\"" + text + "\"}}";
    }

    private interface Check {
        boolean test(TelegramException e);
    }

    private interface Value {
        long of(TelegramException e);
    }

    private static boolean classify(TelegramApi api, String method, Check check) {
        try {
            api.call(method, sendParams("проверка"));
            return false;
        } catch (TelegramException e) {
            return check.test(e);
        }
    }

    private static long value(TelegramApi api, String method, Value value) {
        try {
            api.call(method, sendParams("проверка"));
            return -1L;
        } catch (TelegramException e) {
            return value.of(e);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long millis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
    }

    /** Unused today, kept so a future case can assert on what the bridge logged. */
    static List<String> log() {
        return new ArrayList<>(LOG);
    }
}
