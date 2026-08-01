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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deliberately small YAML reader for {@code config.yml}.
 *
 * <p>Bukkit ships SnakeYAML and the plugin could simply call {@code getConfig()}. It does not,
 * for one reason: {@code core} must not import {@code org.bukkit} - that is what lets the whole
 * bridge be compiled and self-tested on a machine with no server jar and no network. The config
 * is read by the same parser in the plugin and in the offline test, so a file that passes the
 * test is a file the server will read identically.
 *
 * <p>Grown from the same parser as SNDoctor's, with two additions the bridge needs:
 * {@link #childrenOf(String)} to enumerate user-named sections (the topic map is keyed by names
 * the admin invents), and clamped numeric getters so nothing downstream has to defend against a
 * hand-edited file.
 *
 * <p>Supported subset:
 * <ul>
 *   <li>{@code key: value} scalars, nested maps flattened to dotted keys</li>
 *   <li>block lists ({@code key:} followed by indented {@code - item}) and inline {@code [a, b]}</li>
 *   <li>{@code #} comments, quoted scalars, blank lines</li>
 * </ul>
 * Anything more exotic is skipped rather than treated as an error: one unusual line must not cost
 * the admin every other setting in the file.
 */
public final class MiniYaml {

    private final Map<String, String> scalars = new LinkedHashMap<>();
    private final Map<String, List<String>> lists = new LinkedHashMap<>();

    /** Every dotted key ever seen, sections included, in file order - the basis of {@link #childrenOf}. */
    private final Set<String> paths = new LinkedHashSet<>();

    private MiniYaml() {
    }

    public static MiniYaml parse(String text) {
        MiniYaml yaml = new MiniYaml();
        yaml.doParse(text);
        return yaml;
    }

    // ---------------------------------------------------------------- reading

    public String get(String key, String fallback) {
        String v = scalars.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String v = scalars.get(key);
        if (v == null) {
            return fallback;
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1")) {
            return true;
        }
        if (v.equalsIgnoreCase("false") || v.equalsIgnoreCase("no") || v.equals("0")) {
            return false;
        }
        return fallback;
    }

    /**
     * A number clamped into {@code [min, max]}.
     *
     * <p>Clamping rather than rejecting: a poll timeout of 9999 seconds is a typo, and refusing
     * to start over a typo helps nobody. The value is silently pulled into the range that works,
     * which is also what the config comments promise.
     */
    public long getLong(String key, long fallback, long min, long max) {
        String v = scalars.get(key);
        if (v == null || v.isEmpty()) {
            return clamp(fallback, min, max);
        }
        try {
            // Underscores are allowed in the file because chat ids are long and unreadable:
            // -100_284_317_2655 is a legitimate thing for an admin to write.
            return clamp(Long.parseLong(v.replace("_", "").trim()), min, max);
        } catch (NumberFormatException e) {
            return clamp(fallback, min, max);
        }
    }

    public int getInt(String key, int fallback, int min, int max) {
        return (int) getLong(key, fallback, min, max);
    }

    /**
     * A chat id: a long with no clamping, because valid ids span the whole signed 64-bit range
     * and a supergroup id is a large negative number. Returns {@code fallback} when absent or
     * unparseable.
     */
    public long getId(String key, long fallback) {
        String v = scalars.get(key);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.replace("_", "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public List<String> getList(String key) {
        List<String> l = lists.get(key);
        if (l != null) {
            return List.copyOf(l);
        }
        String s = scalars.get(key);
        if (s != null && !s.isEmpty()) {
            return List.of(s);
        }
        return List.of();
    }

    public boolean has(String key) {
        return paths.contains(key);
    }

    /**
     * The immediate child names of a section, in file order.
     *
     * <p>Needed because the topic map is keyed by names the admin chooses: the code cannot know
     * that {@code topics.admin} exists without asking the file what is under {@code topics}.
     */
    public List<String> childrenOf(String prefix) {
        String head = prefix.isEmpty() ? "" : prefix + ".";
        Set<String> children = new LinkedHashSet<>();
        for (String path : paths) {
            if (!path.startsWith(head) || path.length() == head.length()) {
                continue;
            }
            String rest = path.substring(head.length());
            int dot = rest.indexOf('.');
            children.add(dot < 0 ? rest : rest.substring(0, dot));
        }
        return List.copyOf(children);
    }

    private static long clamp(long v, long min, long max) {
        return v < min ? min : Math.min(v, max);
    }

    // ---------------------------------------------------------------- parsing

    private void doParse(String text) {
        // path[i] holds the key owning indentation level i
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        String listOwner = null;
        int listIndent = -1;

        for (String rawLine : text.split("\r?\n", -1)) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = indentOf(line);
            String trimmed = line.trim();

            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                if (listOwner != null && indent >= listIndent) {
                    String item = unquote(trimmed.length() > 1 ? trimmed.substring(1).trim() : "");
                    if (!item.isEmpty()) {
                        lists.computeIfAbsent(listOwner, k -> new ArrayList<>()).add(item);
                    }
                }
                continue;
            }

            int colon = findKeyColon(trimmed);
            if (colon < 0) {
                continue; // not a mapping line we understand
            }
            String key = unquote(trimmed.substring(0, colon).trim());
            String value = trimmed.substring(colon + 1).trim();
            if (key.isEmpty()) {
                continue;
            }

            // pop deeper-or-equal levels off the path
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            String fullKey = path.isEmpty() ? key : String.join(".", path) + "." + key;
            paths.add(fullKey);

            if (value.isEmpty()) {
                // either a nested map or the header of a block list - it is not knowable yet
                path.add(key);
                indents.add(indent);
                listOwner = fullKey;
                listIndent = indent;
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = new ArrayList<>();
                String inner = value.substring(1, value.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        String item = unquote(part.trim());
                        if (!item.isEmpty()) {
                            items.add(item);
                        }
                    }
                }
                lists.put(fullKey, items);
                listOwner = null;
            } else {
                scalars.put(fullKey, unquote(value));
                listOwner = null;
            }
        }
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** Finds the mapping colon, ignoring colons inside quotes. */
    private static int findKeyColon(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':') {
                // "key:" or "key: value" - a colon inside a bare scalar (a URL, a time) has no
                // trailing space and is not at end of line
                if (i == s.length() - 1 || s.charAt(i + 1) == ' ') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
