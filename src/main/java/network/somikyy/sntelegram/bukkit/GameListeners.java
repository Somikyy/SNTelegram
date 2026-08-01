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

import io.papermc.paper.event.player.AsyncChatEvent;

import network.somikyy.sntelegram.core.Config;
import network.somikyy.sntelegram.core.EventKind;
import network.somikyy.sntelegram.core.MuteBook;
import network.somikyy.sntelegram.core.TelegramText;
import network.somikyy.sntelegram.core.TimeSpan;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The game half of the bridge: what happens in the world, on its way to Telegram.
 *
 * <p>Uses {@code io.papermc.paper.event.player.AsyncChatEvent} rather than the old
 * {@code AsyncPlayerChatEvent}, which has been deprecated for years and carries a
 * {@code String} instead of a component - meaning it cannot represent what modern chat actually
 * is. The Paper event has been available since long before the oldest version this plugin
 * supports.
 *
 * <p>Every handler here obeys one rule: <b>be invisible when Telegram is broken</b>. Not one of
 * them can block, throw into the server, or delay a chat message. The worst that a completely
 * dead Telegram can do to a player is nothing at all.
 */
final class GameListeners implements Listener {

    private final Config config;
    private final Bridge bridge;

    GameListeners(Config config, Bridge bridge) {
        this.config = config;
        this.bridge = bridge;
    }

    /**
     * Enforces mutes.
     *
     * <p>Separate handler at {@code HIGHEST} so the decision to cancel is made before the
     * forwarding handler at {@code MONITOR} looks at the event - a muted player's line must not
     * reach Telegram either. {@code ignoreCancelled} is deliberately false here and true there.
     *
     * <p>Minecraft has no mute of its own in any version, on Bukkit or Paper, so cancelling the
     * chat event is not a shortcut - it is the only mechanism the server offers.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChatMute(AsyncChatEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        MuteBook.Mute mute = bridge.mutes()
                .findAny(Moderation.keysFor(player.getUniqueId(), player.getName()), now);
        if (mute == null) {
            return;
        }
        event.setCancelled(true);
        // Telling them is not optional. A cancelled message with no explanation looks like the
        // server ate it, and the player says it again, louder.
        player.sendMessage(TextRender.template("<red>Вам запрещено писать в чат ещё "
                + TimeSpan.russian(mute.remaining(now))
                + (mute.reason().isEmpty() ? "" : ". Причина: " + escape(mute.reason()))
                + "</red>"));
    }

    /** Forwards chat. {@code MONITOR} so a mute or another plugin's veto is already applied. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String name = event.getPlayer().getName();
        String text = TextRender.plain(event.message());
        bridge.sendChat(name, config.templates().chat()
                .replace("{player}", TelegramText.escapeHtml(name))
                .replace("{message}", TelegramText.escapeHtml(text)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        bridge.sendEvent(EventKind.JOIN, config.templates().join()
                .replace("{player}", TelegramText.escapeHtml(event.getPlayer().getName())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        bridge.sendEvent(EventKind.QUIT, config.templates().quit()
                .replace("{player}", TelegramText.escapeHtml(event.getPlayer().getName())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        // The vanilla death message, which already names the player and what killed them.
        // Rewriting it would lose the translations the server has and gain nothing.
        String message = TextRender.plain(event.deathMessage());
        if (message.isBlank()) {
            return; // keepDeathMessage off, or another plugin cleared it
        }
        bridge.sendEvent(EventKind.DEATH, config.templates().death()
                .replace("{player}", TelegramText.escapeHtml(event.getEntity().getName()))
                .replace("{message}", TelegramText.escapeHtml(message)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        // Paper's message() is null for advancements the server does not announce - recipe
        // unlocks, mostly, of which there are hundreds. Announcing those would flood the topic.
        var announcement = event.message();
        if (announcement == null) {
            return;
        }
        bridge.sendEvent(EventKind.ADVANCEMENT, config.templates().advancement()
                .replace("{player}", TelegramText.escapeHtml(event.getPlayer().getName()))
                .replace("{advancement}", TelegramText.escapeHtml(TextRender.plain(announcement))));
    }

    /** Escapes a moderator's free text before it goes into a MiniMessage template. */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }
}
