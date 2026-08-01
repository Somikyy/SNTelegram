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

import com.destroystokyo.paper.profile.PlayerProfile;

import io.papermc.paper.ban.BanListType;

import net.kyori.adventure.text.Component;

import network.somikyy.sntelegram.core.MuteBook;
import network.somikyy.sntelegram.core.TelegramText;
import network.somikyy.sntelegram.core.TimeSpan;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Carries out a moderation decision that was made in Telegram.
 *
 * <p>Everything here runs on the server thread. That is not caution, it is required: Paper's
 * {@code AsyncCatcher} rejects {@code Player#kick} and {@code Bukkit.dispatchCommand} from any
 * other thread, and the ban list is not safe to touch concurrently either. The caller hands work
 * in through {@link Scheduling}, and each method here answers back with a line of Russian for
 * Telegram - so the admin who typed the command learns what happened even when nothing did.
 *
 * <p>The ban API used is {@code Player#ban(String, Date, String, boolean)}, whose signature is
 * identical on 1.20.1 and on 26.2. That matters for a plugin shipped as one jar: the surrounding
 * ban machinery changed a lot across those versions - {@code BanList.Type} was deprecated in
 * 1.20.4, {@code io.papermc.paper.ban.BanListType} did not exist before 1.20.6 - while this one
 * call did not move.
 */
final class Moderation {

    /** Who bans are attributed to in the ban list, so an admin reading banlist.json can tell. */
    private static final String SOURCE = "SNTelegram";

    private final MuteBook mutes;
    private final boolean showAddresses;

    Moderation(MuteBook mutes, boolean showAddresses) {
        this.mutes = mutes;
        this.showAddresses = showAddresses;
    }

    /**
     * Keys a mute may be stored under: the UUID when the server knows it, the lowercased name
     * otherwise.
     *
     * <p>Both, always, because they identify different things. On a premium server the UUID
     * survives a rename; on an offline-mode server - which is most of the Russian market - the
     * name is the identity and the UUID is derived from it. Checking both costs two hash lookups
     * and removes an entire category of "the mute stopped working".
     */
    static List<String> keysFor(UUID uuid, String name) {
        String byName = "n:" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        return uuid == null ? List.of(byName) : List.of("u:" + uuid, byName);
    }

    // ---------------------------------------------------------------- actions

    /** @param reply receives one line of Russian describing the outcome */
    void mute(String targetName, long durationMillis, String reason, String by, Consumer<String> reply) {
        Target target = resolve(targetName);
        if (target == null) {
            reply.accept("Игрок «" + TelegramText.escapeHtml(targetName) + "» не найден: он ни разу "
                    + "не заходил на сервер, либо имя написано с ошибкой.");
            return;
        }
        long until = durationMillis == TimeSpan.PERMANENT
                ? MuteBook.PERMANENT
                : System.currentTimeMillis() + durationMillis;
        mutes.mute(keysFor(target.uuid(), target.name()).get(0),
                new MuteBook.Mute(until, reason, by, target.name()));

        Player online = target.online();
        if (online != null) {
            online.sendMessage(TextRender.template(
                    "<red>Вам запрещено писать в чат на " + TimeSpan.russian(durationMillis)
                            + (reason.isEmpty() ? "" : ". Причина: " + escapeMini(reason)) + "</red>"));
        }
        reply.accept("🔇 <b>" + TelegramText.escapeHtml(target.name()) + "</b> не сможет писать в чат "
                + TimeSpan.russian(durationMillis)
                + (reason.isEmpty() ? "." : ". Причина: " + TelegramText.escapeHtml(reason)));
    }

    void unmute(String targetName, Consumer<String> reply) {
        Target target = resolve(targetName);
        String name = target == null ? targetName : target.name();
        UUID uuid = target == null ? null : target.uuid();
        if (mutes.unmuteAny(keysFor(uuid, name))) {
            reply.accept("🔊 <b>" + TelegramText.escapeHtml(name) + "</b> снова может писать в чат.");
        } else {
            reply.accept("У игрока <b>" + TelegramText.escapeHtml(name) + "</b> и так нет мута.");
        }
    }

    void kick(String targetName, String reason, String by, Consumer<String> reply) {
        Player online = Bukkit.getPlayerExact(targetName);
        if (online == null) {
            reply.accept("Игрока <b>" + TelegramText.escapeHtml(targetName)
                    + "</b> нет на сервере — кикать некого.");
            return;
        }
        online.kick(Component.text(kickText(reason, by)));
        reply.accept("👢 <b>" + TelegramText.escapeHtml(targetName) + "</b> отключён от сервера"
                + (reason.isEmpty() ? "." : ": " + TelegramText.escapeHtml(reason)));
    }

    void ban(String targetName, long durationMillis, String reason, String by, Consumer<String> reply) {
        Target target = resolve(targetName);
        if (target == null) {
            reply.accept("Игрок «" + TelegramText.escapeHtml(targetName) + "» не найден: он ни разу "
                    + "не заходил на сервер, либо имя написано с ошибкой.");
            return;
        }
        Date expires = durationMillis == TimeSpan.PERMANENT
                ? null // null means forever in the Bukkit ban API
                : new Date(System.currentTimeMillis() + durationMillis);
        String text = reason.isEmpty() ? "Забанен администрацией" : reason;

        Player online = target.online();
        if (online != null) {
            // The overload with the kick flag: banning someone who is standing in the world and
            // leaving them connected is a bug that looks like a working ban until they log out.
            online.ban(text, expires, by, true);
        } else {
            target.offline().ban(text, expires, by);
        }
        reply.accept("⛔ <b>" + TelegramText.escapeHtml(target.name()) + "</b> забанен "
                + TimeSpan.russian(durationMillis)
                + (reason.isEmpty() ? "." : ". Причина: " + TelegramText.escapeHtml(reason)));
    }

    void unban(String targetName, Consumer<String> reply) {
        // Bans made through Player#ban land in the profile list, so that is where the pardon
        // must go. BanListType rather than the deprecated BanList.Type: the plugin targets
        // 1.21+, where the typed version exists and carries no deprecation.
        //
        // The profile type is com.destroystokyo.paper.profile.PlayerProfile, not the
        // org.bukkit.profile one of the same simple name - ProfileBanList is declared over
        // Paper's. Using the wrong one compiles against neither, which is the good outcome;
        // it is the kind of mistake that would otherwise be found on a live server.
        //
        // Held in a variable rather than chained: chaining makes javac infer ProfileBanList and
        // insert a cast, which moves the pardon call's owner and makes the compile-only stubs
        // that much more intricate for no gain.
        BanList<PlayerProfile> bans = Bukkit.getBanList(BanListType.PROFILE);
        bans.pardon(Bukkit.createProfile(targetName));
        reply.accept("✅ Бан с <b>" + TelegramText.escapeHtml(targetName) + "</b> снят.");
    }

    /**
     * The player card.
     *
     * <p>Addresses are omitted unless the admin turned them on. A Telegram group is not
     * necessarily made only of administrators, and printing a player's IP into it on a one-word
     * command is a data leak the plugin would be responsible for.
     */
    void info(String targetName, Consumer<String> reply) {
        Target target = resolve(targetName);
        if (target == null) {
            reply.accept("Игрок «" + TelegramText.escapeHtml(targetName) + "» ни разу не заходил "
                    + "на этот сервер.");
            return;
        }
        OfflinePlayer offline = target.offline();
        Player online = target.online();
        StringBuilder card = new StringBuilder();
        card.append("<b>").append(TelegramText.escapeHtml(target.name())).append("</b>\n");
        card.append(online != null ? "🟢 сейчас на сервере" : "⚪ не в сети").append('\n');

        long playedTicks = safeStatistic(offline);
        if (playedTicks > 0) {
            // The statistic counts ticks, twenty to the second, despite its name.
            card.append("⏱ наиграно: ").append(TimeSpan.russian(playedTicks / 20L * 1000L)).append('\n');
        }
        if (offline.getFirstPlayed() > 0L) {
            card.append("📅 первый вход: ").append(date(offline.getFirstPlayed())).append('\n');
        }
        if (offline.isBanned()) {
            card.append("⛔ забанен\n");
        }
        MuteBook.Mute mute = mutes.findAny(keysFor(target.uuid(), target.name()),
                System.currentTimeMillis());
        if (mute != null) {
            card.append("🔇 мут ещё ").append(TimeSpan.russian(mute.remaining(System.currentTimeMillis())));
            if (!mute.reason().isEmpty()) {
                card.append(" — ").append(TelegramText.escapeHtml(mute.reason()));
            }
            card.append('\n');
        }
        if (showAddresses && online != null && online.getAddress() != null) {
            card.append("🌐 адрес: ")
                    .append(TelegramText.escapeHtml(online.getAddress().getAddress().getHostAddress()))
                    .append('\n');
        }
        reply.accept(card.toString().trim());
    }

    // ---------------------------------------------------------------- helpers

    /** A player the server can act on, online or not. */
    private record Target(UUID uuid, String name, OfflinePlayer offline) {
        Player online() {
            return Bukkit.getPlayerExact(name);
        }
    }

    /**
     * Finds a player without blocking.
     *
     * <p>{@code Bukkit.getOfflinePlayer(String)} is tempting and wrong here: it is not deprecated,
     * but it performs a web request to Mojang to resolve an unknown name, and this runs on the
     * server thread. One moderation command aimed at a typo would freeze the whole server for the
     * length of an HTTP timeout. {@code getOfflinePlayerIfCached} answers from what the server
     * already knows, or not at all - which is the right answer for a name nobody has ever used.
     */
    private static Target resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName(), online);
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null || !cached.hasPlayedBefore()) {
            return null;
        }
        String resolved = cached.getName() == null ? name : cached.getName();
        return new Target(cached.getUniqueId(), resolved, cached);
    }

    private static long safeStatistic(OfflinePlayer player) {
        try {
            return player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // A player whose statistics file was never written throws rather than answering
            // zero. Not worth a line in the log, and certainly not worth failing the card.
            return 0L;
        }
    }

    private static String date(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .toString();
    }

    private static String kickText(String reason, String by) {
        return reason.isEmpty()
                ? "Вы отключены от сервера администратором " + by
                : "Вы отключены от сервера: " + reason;
    }

    /** Escapes a reason before it goes into a MiniMessage template built for the player. */
    private static String escapeMini(String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }
}
