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

import java.util.Set;

/**
 * A run of Telegram text carrying one set of styles - the bridge's only representation of
 * formatted incoming text.
 *
 * <p>Deliberately NOT a MiniMessage string. This is the security boundary of the whole plugin:
 * a stranger in a Telegram group can type anything, and if their text reached the server as
 * MiniMessage source, {@code <click:run_command:'/op them'>} typed into a public chat would
 * become a clickable message in-game. Telegram formatting is therefore decoded into this flat,
 * closed list of styles, and the Bukkit layer builds the component from it directly - user text
 * is only ever inserted as a literal, never parsed.
 *
 * <p>{@code url} is set for {@code text_link} entities and for auto-detected {@code url} and
 * {@code email} entities. It is a link the Bukkit layer may choose to attach, after its own
 * checks - core does not decide policy.
 *
 * @param text   the literal characters of this run, never {@code null}
 * @param styles decorations covering the whole run
 * @param url    target of a link covering the whole run, or {@code null}
 */
public record TextSpan(String text, Set<Style> styles, String url) {

    /**
     * Telegram decorations the bridge can render in Minecraft.
     *
     * <p>Deliberately smaller than Telegram's entity list. Everything not listed here (hashtags,
     * cashtags, phone numbers, custom emoji, date-time) carries no visual meaning worth
     * reproducing in a chat line and arrives as plain text.
     */
    public enum Style {
        BOLD,
        ITALIC,
        UNDERLINE,
        STRIKETHROUGH,
        /** Rendered as obfuscated text: the closest honest equivalent Minecraft has. */
        SPOILER,
        /** {@code code} and {@code pre}: shown in a distinct colour, not a distinct font. */
        CODE,
        /** {@code blockquote}: the Bukkit layer prefixes the line rather than styling it. */
        QUOTE,
    }

    public TextSpan {
        text = text == null ? "" : text;
        styles = styles == null ? Set.of() : Set.copyOf(styles);
    }

    public boolean has(Style style) {
        return styles.contains(style);
    }

    public static TextSpan plain(String text) {
        return new TextSpan(text, Set.of(), null);
    }
}
