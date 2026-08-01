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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader, writer and navigator - the whole of SNTelegram's Telegram wire format.
 *
 * <p>Written by hand rather than shaded from Gson or Jackson because the suite ships zero
 * runtime dependencies: a shaded JSON library is the single most common source of "works on my
 * server, explodes on yours", and the Bot API needs about two hundred lines of parser.
 *
 * <p>Two decisions here are not stylistic:
 *
 * <ul>
 *   <li><b>Whole numbers become {@code Long}, never {@code Double}.</b> Telegram chat and user ids
 *       are signed 64-bit and supergroup ids look like {@code -1001234567890}. A parser that
 *       hands back a double silently rounds ids past 2^53 - and the failure shows up as messages
 *       going to the wrong chat, which is unforgivable and nearly undebuggable.</li>
 *   <li><b>Navigation never returns {@code null}.</b> A missing object yields an empty {@code Json}
 *       whose accessors return the caller's fallback, so reading a deeply optional field like
 *       {@code reply_to_message.from.username} needs no null checks. The Bot API marks most
 *       fields optional; defending against that at every call site is how bridges end up one
 *       NullPointerException away from dropping a chat message.</li>
 * </ul>
 *
 * <p>Instances are immutable views over parsed data and safe to read from several threads.
 */
public final class Json {

    /**
     * Nesting limit for parsing.
     *
     * <p>The parser is recursive, and the bytes on the socket are not necessarily Telegram's:
     * the plugin can be pointed at a self-hosted Bot API server. A deep-nesting payload would
     * turn into StackOverflowError inside the polling thread, so depth is capped well above
     * anything the Bot API produces (an Update nests about six levels).
     */
    private static final int MAX_DEPTH = 64;

    /** The empty view returned for every absent object or array element. */
    private static final Json NONE = new Json(null);

    private final Object value;

    private Json(Object value) {
        this.value = value;
    }

    // ---------------------------------------------------------------- entry points

    /** Parses a JSON document. Throws {@link JsonException} on malformed input. */
    public static Json parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonException("лишние символы после конца документа, позиция " + p.pos);
        }
        return new Json(v);
    }

    /** Wraps an already-parsed value ({@code Map}, {@code List}, {@code String}, number, boolean). */
    public static Json of(Object raw) {
        return raw == null ? NONE : new Json(raw);
    }

    /** The empty view: every accessor returns its fallback. */
    public static Json none() {
        return NONE;
    }

    // ---------------------------------------------------------------- navigation

    /**
     * The child object at {@code key}, or an empty view when the key is missing or not an object.
     *
     * <p>Chains safely: {@code json.obj("message").obj("from").str("username", "")}.
     */
    public Json obj(String key) {
        Object child = rawGet(key);
        return child == null ? NONE : new Json(child);
    }

    /** The array at {@code key} as a list of views; an empty list when absent. */
    public List<Json> arr(String key) {
        Object child = rawGet(key);
        if (!(child instanceof List<?> list)) {
            return List.of();
        }
        List<Json> out = new ArrayList<>(list.size());
        for (Object element : list) {
            out.add(new Json(element));
        }
        return Collections.unmodifiableList(out);
    }

    /** This view as a list, when it is itself an array. */
    public List<Json> arr() {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Json> out = new ArrayList<>(list.size());
        for (Object element : list) {
            out.add(new Json(element));
        }
        return Collections.unmodifiableList(out);
    }

    public String str(String key, String fallback) {
        Object child = rawGet(key);
        return child instanceof String s ? s : fallback;
    }

    /** This view as a string. */
    public String str(String fallback) {
        return value instanceof String s ? s : fallback;
    }

    /**
     * A 64-bit field. Doubles are accepted and truncated so that a Bot API server which writes
     * {@code 1.0} for an id does not break the bridge.
     */
    public long num(String key, long fallback) {
        Object child = rawGet(key);
        if (child instanceof Long l) {
            return l;
        }
        if (child instanceof Double d) {
            return (long) (double) d;
        }
        return fallback;
    }

    public double dec(String key, double fallback) {
        Object child = rawGet(key);
        if (child instanceof Long l) {
            return l;
        }
        if (child instanceof Double d) {
            return d;
        }
        return fallback;
    }

    public boolean bool(String key, boolean fallback) {
        Object child = rawGet(key);
        return child instanceof Boolean b ? b : fallback;
    }

    /** True when the key is present and not JSON {@code null}. */
    public boolean has(String key) {
        return rawGet(key) != null;
    }

    /** True for the empty view and for a JSON {@code null}. */
    public boolean isNull() {
        return value == null;
    }

    public boolean isObject() {
        return value instanceof Map<?, ?>;
    }

    /** The underlying parsed value; {@code null} for the empty view. */
    public Object raw() {
        return value;
    }

    private Object rawGet(String key) {
        if (value instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    @Override
    public String toString() {
        return write(value);
    }

    // ---------------------------------------------------------------- writing

    /**
     * Serialises maps, lists, strings, numbers, booleans and {@code null}.
     *
     * <p>Non-ASCII characters are written as themselves rather than as {@code \\uXXXX}: the body
     * goes out as UTF-8, which the Bot API requires anyway, and escaping Cyrillic would triple
     * the size of every Russian chat message for no benefit.
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeTo(out, value);
        return out.toString();
    }

    private static void writeTo(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(out, s);
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d)) {
                // JSON has no NaN or Infinity, and silently writing one produces a body the
                // Bot API rejects with a message that names nothing useful.
                throw new JsonException("нечисловое значение нельзя записать в JSON: " + d);
            }
            out.append(d);
        } else if (value instanceof Number n) {
            out.append(n.longValue());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() == null) {
                    // Optional Bot API parameters are omitted, not sent as null: some methods
                    // treat an explicit null as a value and answer 400.
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(e.getKey()));
                out.append(':');
                writeTo(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeTo(out, element);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Convenience for building request bodies: an insertion-ordered map. */
    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    // ---------------------------------------------------------------- parser

    /** Thrown for malformed JSON. Unchecked: callers wrap whole request cycles, not single fields. */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {

        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("слишком глубокая вложенность JSON (более " + MAX_DEPTH + ")");
            }
            skipWhitespace();
            if (atEnd()) {
                throw new JsonException("документ оборвался");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject(int depth) {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || src.charAt(pos) != '"') {
                    throw new JsonException("ожидалось имя поля, позиция " + pos);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("объект не закрыт");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new JsonException("ожидалась ',' или '}', позиция " + pos);
                }
            }
        }

        private List<Object> parseArray(int depth) {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("массив не закрыт");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new JsonException("ожидалась ',' или ']', позиция " + pos);
                }
            }
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("строка не закрыта");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("строка обрывается на экранировании");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new JsonException("оборванная \\u-последовательность");
                        }
                        // Surrogate pairs are appended as two units and never recombined: Java
                        // strings are UTF-16, so an emoji written as 😀 lands correctly
                        // by doing nothing special. Telegram sends plenty of them.
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new JsonException("неизвестное экранирование \\" + esc);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (!atEnd() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
                pos++;
            }
            boolean fractional = false;
            while (!atEnd()) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fractional = true;
                    pos++;
                } else {
                    break;
                }
            }
            String text = src.substring(start, pos);
            if (text.isEmpty() || text.equals("-")) {
                throw new JsonException("ожидалось число, позиция " + start);
            }
            try {
                // See the class comment: whole numbers stay long so that chat ids survive.
                return fractional ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new JsonException("некорректное число: " + text);
            }
        }

        private Object parseLiteral(String literal, Object result) {
            if (!src.startsWith(literal, pos)) {
                throw new JsonException("ожидалось " + literal + ", позиция " + pos);
            }
            pos += literal.length();
            return result;
        }

        private void expect(char c) {
            if (atEnd() || src.charAt(pos) != c) {
                throw new JsonException("ожидался символ '" + c + "', позиция " + pos);
            }
            pos++;
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }
    }
}
