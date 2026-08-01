/*
 * SNTelegram - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sntelegram.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import network.somikyy.sntelegram.core.TextSpan;

import java.util.List;

/**
 * Turns Telegram text into chat components and chat components into plain text.
 *
 * <p><b>The security rule of this class, and of the plugin.</b> Text written by a stranger in
 * Telegram is <i>never</i> handed to MiniMessage. MiniMessage is a markup language with tags that
 * do things - {@code <click:run_command:...>} attaches a command to a clickable line - so parsing
 * a stranger's message as MiniMessage would let anyone in the Telegram group hand every player on
 * the server a button that runs a command as them. That is not a theoretical concern: the group
 * is public on most servers, and the bridge is precisely the path in.
 *
 * <p>So MiniMessage parses exactly one thing: the admin's own template from {@code config.yml}.
 * The user's words go in through a tag resolver, as an already-built {@link Component} that is
 * never source text at any point.
 *
 * <p>MiniMessage itself needs no dependency: it has been part of {@code paper-api} since Paper
 * bundled Adventure, so the plugin can use it while still shipping an empty {@code implementation}
 * configuration.
 */
final class TextRender {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private TextRender() {
    }

    /** A component's visible text, for forwarding to Telegram. */
    static String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }

    /**
     * Renders an admin template with the user's text inserted literally.
     *
     * <p>Substitution goes through MiniMessage's own placeholder resolvers rather than string
     * replacement, and that is the difference between safe and not. Building the final string by
     * pasting the user's words into the template and then parsing the result would mean the
     * user's words get parsed too - which is precisely the hole this class exists to close.
     * A resolver hands MiniMessage a finished component for {@code <message>}; there is no point
     * at which the stranger's text is ever source text.
     *
     * @param template  MiniMessage from {@code config.yml} - trusted, parsed
     * @param userName  the Telegram author's display name - untrusted, inserted as plain text
     * @param userText  what they wrote - untrusted, inserted as a pre-built component
     */
    static Component fromTelegram(String template, String userName, List<TextSpan> userText) {
        return MINI.deserialize(template,
                Placeholder.unparsed("user", userName),
                Placeholder.component("message", styled(userText)));
    }

    /**
     * Builds a component from Telegram's styled runs.
     *
     * <p>Decorations map to their Minecraft equivalents where one exists. Two do not and are
     * approximated deliberately: a spoiler becomes obfuscated text, which hides it in the same
     * spirit, and {@code code} becomes grey rather than monospaced, because Minecraft's chat font
     * is already monospaced and colour is the only distinction available.
     */
    static Component styled(List<TextSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return Component.empty();
        }
        // A chain of appends rather than a builder. The builder is the idiomatic choice and it
        // would drag ComponentBuilder and BuildableComponent into the compile-only stubs the
        // offline build needs - both generic, both erasing to surprising descriptors. A message
        // has a handful of runs; the allocations do not matter and the smaller API surface does.
        Component out = Component.empty();
        for (TextSpan span : spans) {
            Component piece = Component.text(span.text());
            if (span.has(TextSpan.Style.BOLD)) {
                piece = piece.decorate(TextDecoration.BOLD);
            }
            if (span.has(TextSpan.Style.ITALIC)) {
                piece = piece.decorate(TextDecoration.ITALIC);
            }
            if (span.has(TextSpan.Style.UNDERLINE)) {
                piece = piece.decorate(TextDecoration.UNDERLINED);
            }
            if (span.has(TextSpan.Style.STRIKETHROUGH)) {
                piece = piece.decorate(TextDecoration.STRIKETHROUGH);
            }
            if (span.has(TextSpan.Style.SPOILER)) {
                piece = piece.decorate(TextDecoration.OBFUSCATED);
            }
            if (span.has(TextSpan.Style.CODE)) {
                piece = piece.color(NamedTextColor.GRAY);
            }
            if (span.has(TextSpan.Style.QUOTE)) {
                piece = Component.text("| ", NamedTextColor.DARK_GRAY).append(piece);
            }
            // Links are shown, not made clickable. A clickable link in chat that came from a
            // stranger in a Telegram group is a phishing vector aimed at every player at once;
            // the URL is right there for anyone who wants to copy it.
            if (span.url() != null) {
                piece = piece.color(NamedTextColor.AQUA);
            }
            out = out.append(piece);
        }
        return out;
    }

    /** Parses an admin-authored MiniMessage template with no substitutions. */
    static Component template(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }
}
