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
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Converts between Telegram's wire text format and the bridge's own.
 *
 * <p>Two directions, and they are not symmetric.
 *
 * <p><b>Telegram to Minecraft</b> goes through {@code entities}, not through HTML. Telegram sends
 * the raw text plus a list of ranges, and rebuilding formatting from those ranges is exact -
 * whereas re-parsing HTML would mean writing an HTML parser and getting the escaping wrong in
 * both directions. Offsets are given "in UTF-16 code units"
 * (<a href="https://core.telegram.org/bots/api#messageentity">MessageEntity</a>), which is
 * precisely what a Java {@code String} is indexed in, so no conversion is needed - an emoji in
 * the middle of a sentence lands correctly by doing nothing.
 *
 * <p><b>Minecraft to Telegram</b> produces HTML with only three characters escaped, per the
 * Bot API: "All &lt;, &gt; and &amp; symbols that are not a part of a tag or an HTML entity must
 * be replaced with the corresponding HTML entities"
 * (<a href="https://core.telegram.org/bots/api#html-style">HTML style</a>).
 */
public final class TelegramText {

    /**
     * Bot API limit for {@code sendMessage}: "Text of the message to be sent, 1-4096 characters
     * after entities parsing" (<a href="https://core.telegram.org/bots/api#sendmessage">sendMessage</a>).
     *
     * <p>Counted after parsing, so the escaping expansion of {@code &amp;} does not count against
     * it - but the bridge truncates before escaping anyway, which is the conservative direction.
     */
    public static final int MAX_MESSAGE_CHARS = 4096;

    private TelegramText() {
    }

    // ---------------------------------------------------------------- Telegram -> Minecraft

    /**
     * Splits {@code text} into styled runs using Telegram's entity ranges.
     *
     * <p>Entities may nest, and the Bot API guarantees the nesting is well-formed: "If two
     * entities have common characters, then one of them is fully contained inside another"
     * (<a href="https://core.telegram.org/bots/api#formatting-options">Formatting options</a>).
     * That guarantee is what lets a stack work here. It is still not trusted blindly: ranges are
     * clamped to the text and inverted ones dropped, because the bytes may come from a
     * self-hosted Bot API server rather than from Telegram.
     *
     * @param text     message text, may be {@code null} for a message that has none
     * @param entities the {@code entities} array of the same message
     */
    public static List<TextSpan> spans(String text, List<Json> entities) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Range> ranges = ranges(text, entities);
        if (ranges.isEmpty()) {
            return List.of(TextSpan.plain(text));
        }

        // Cut the text at every entity edge, then ask each piece which entities cover it.
        //
        // The obvious implementation is a stack pushed on open and popped on close, and it is
        // wrong: it relies on the nesting guarantee holding. Two ranges that merely overlap
        // (0-4 and 2-6) leave the first one on the stack forever, and its styling leaks to the
        // end of the message. Scanning boundaries has no such invariant to violate - the entity
        // count per message is single digits, so the cost of asking every range about every
        // piece is not worth a cleverer structure.
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        cuts.add(text.length());
        for (Range r : ranges) {
            cuts.add(r.start);
            cuts.add(r.end);
        }

        List<TextSpan> out = new ArrayList<>();
        int from = -1;
        for (int cut : cuts) {
            if (from >= 0 && cut > from) {
                out.add(pieceAt(text, from, cut, ranges));
            }
            from = cut;
        }
        return compact(out);
    }

    /** The styles and link covering {@code [from, to)} - every range that fully contains it. */
    private static TextSpan pieceAt(String text, int from, int to, List<Range> ranges) {
        Set<TextSpan.Style> styles = EnumSet.noneOf(TextSpan.Style.class);
        String url = null;
        int narrowest = Integer.MAX_VALUE;
        for (Range r : ranges) {
            if (r.start > from || r.end < to) {
                continue;
            }
            if (r.style != null) {
                styles.add(r.style);
            }
            // The innermost link wins, which is what a Telegram client shows when a text_link
            // sits inside an auto-detected url.
            if (r.url != null && r.end - r.start < narrowest) {
                url = r.url;
                narrowest = r.end - r.start;
            }
        }
        return new TextSpan(text.substring(from, to), styles, url);
    }

    /** Drops empty spans and merges neighbours that ended up with identical styling. */
    private static List<TextSpan> compact(List<TextSpan> spans) {
        List<TextSpan> out = new ArrayList<>(spans.size());
        for (TextSpan span : spans) {
            if (span.text().isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                TextSpan last = out.get(out.size() - 1);
                if (last.styles().equals(span.styles())
                        && Objects.equals(last.url(), span.url())) {
                    out.set(out.size() - 1,
                            new TextSpan(last.text() + span.text(), last.styles(), last.url()));
                    continue;
                }
            }
            out.add(span);
        }
        return List.copyOf(out);
    }

    private static List<Range> ranges(String text, List<Json> entities) {
        List<Range> out = new ArrayList<>();
        if (entities == null) {
            return out;
        }
        for (Json e : entities) {
            String type = e.str("type", "");
            int start = (int) Math.max(0, Math.min(text.length(), e.num("offset", -1)));
            int length = (int) Math.max(0, e.num("length", 0));
            int end = (int) Math.min(text.length(), (long) start + length);
            if (end <= start) {
                continue;
            }
            TextSpan.Style style = styleOf(type);
            String url = urlOf(type, e, text, start, end);
            if (style == null && url == null) {
                continue; // an entity with nothing to show: hashtag, bot_command, custom_emoji
            }
            out.add(new Range(start, end, style, url));
        }
        return out;
    }

    private static TextSpan.Style styleOf(String type) {
        return switch (type) {
            case "bold" -> TextSpan.Style.BOLD;
            case "italic" -> TextSpan.Style.ITALIC;
            case "underline" -> TextSpan.Style.UNDERLINE;
            case "strikethrough" -> TextSpan.Style.STRIKETHROUGH;
            case "spoiler" -> TextSpan.Style.SPOILER;
            case "code", "pre" -> TextSpan.Style.CODE;
            case "blockquote", "expandable_blockquote" -> TextSpan.Style.QUOTE;
            default -> null;
        };
    }

    private static String urlOf(String type, Json entity, String text, int start, int end) {
        return switch (type) {
            // text_link carries the target separately; url and email are auto-detected and the
            // target is the text itself.
            case "text_link" -> entity.str("url", null);
            case "url" -> text.substring(start, end);
            case "email" -> "mailto:" + text.substring(start, end);
            default -> null;
        };
    }

    private record Range(int start, int end, TextSpan.Style style, String url) {
    }

    // ---------------------------------------------------------------- Minecraft -> Telegram

    /**
     * Escapes the three characters the Bot API requires escaping in {@code parse_mode=HTML}.
     *
     * <p>Order matters: {@code &} must go first, or the ampersands introduced by the other two
     * replacements get escaped a second time and the admin sees {@code &amp;lt;} in Telegram.
     */
    public static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Turns a Telegram HTML message back into plain text.
     *
     * <p>The moderation code produces one sentence and sends it to Telegram as HTML. The same
     * sentence has to appear in the server console when the same command is typed there, and
     * nobody wants to read {@code &lt;b&gt;Steve&lt;/b&gt;} in a log. Rather than have every
     * message built twice, it is built once and undressed here.
     *
     * <p>Not a general HTML parser and must never become one: it strips tags and undoes the
     * three entities {@link #escapeHtml} creates, which is exactly the inverse of what the bridge
     * itself produces.
     */
    public static String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(html.length());
        boolean inTag = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inTag) {
                inTag = c != '>';
                continue;
            }
            if (c == '<') {
                inTag = true;
                continue;
            }
            out.append(c);
        }
        // Order matters and is the reverse of escaping: &amp; goes last, or "&amp;lt;" would
        // become "<" instead of "&lt;".
        return out.toString()
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /**
     * Cuts text to fit {@link #MAX_MESSAGE_CHARS}, never splitting a surrogate pair.
     *
     * <p>Splitting one produces a lone half-character, which Telegram rejects outright - so a
     * single long message ending in an emoji would silently fail to bridge. The ellipsis is part
     * of the budget so the result is always within the limit.
     */
    public static String fit(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        int cut = Math.max(0, limit - 1);
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + "…";
    }

    public static String fit(String text) {
        return fit(text, MAX_MESSAGE_CHARS);
    }
}
