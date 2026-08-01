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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is muted and until when.
 *
 * <p>Minecraft has no mute. Neither Bukkit nor Paper offers one in any version - a check of the
 * whole {@code io.papermc.paper.event.player} package and of {@code org.bukkit.entity.Player} in
 * 26.2 turns up nothing of the kind, and 26.1 and 26.2 added no chat-moderation API either. The
 * only mechanism the server provides is cancelling the chat event, which means the list of muted
 * players has to live here.
 *
 * <p>Persisted to a plain text file, one record per line, rewritten atomically. Not a database:
 * the file holds tens of entries on a busy server, an admin can read and fix it by hand, and
 * "the plugin ships no dependencies" is a promise this class is not going to be the one to break.
 *
 * <p>Keys are opaque strings so the Bukkit layer can choose: a UUID for a player the server can
 * identify, a lowercased name for one it cannot. Core does not need to know which it got.
 */
public final class MuteBook {

    /** Value meaning "until unmuted". */
    public static final long PERMANENT = Long.MAX_VALUE;

    private final Path file;
    private final Map<String, Mute> mutes = new LinkedHashMap<>();
    private volatile boolean dirty;

    /**
     * One mute.
     *
     * @param until       epoch millis, or {@link #PERMANENT}
     * @param reason      shown to the player each time they try to speak
     * @param by          who did it, for the record and for the player card
     * @param displayName last known in-game name, so the file is readable by a human
     */
    public record Mute(long until, String reason, String by, String displayName) {

        public boolean expired(long now) {
            return until != PERMANENT && now >= until;
        }

        public long remaining(long now) {
            return until == PERMANENT ? TimeSpan.PERMANENT : Math.max(0L, until - now);
        }
    }

    public MuteBook(Path file) {
        this.file = file;
    }

    /** The active mute for a key, or {@code null}. Expired entries are dropped as they are found. */
    public synchronized Mute find(String key, long now) {
        if (key == null) {
            return null;
        }
        Mute mute = mutes.get(key);
        if (mute == null) {
            return null;
        }
        if (mute.expired(now)) {
            mutes.remove(key);
            dirty = true;
            return null;
        }
        return mute;
    }

    /** The first active mute among several keys - a player has both a UUID and a name. */
    public synchronized Mute findAny(List<String> keys, long now) {
        for (String key : keys) {
            Mute mute = find(key, now);
            if (mute != null) {
                return mute;
            }
        }
        return null;
    }

    public synchronized void mute(String key, Mute mute) {
        mutes.put(key, mute);
        dirty = true;
    }

    /** @return true when something was actually removed */
    public synchronized boolean unmute(String key) {
        boolean removed = mutes.remove(key) != null;
        dirty |= removed;
        return removed;
    }

    public synchronized boolean unmuteAny(List<String> keys) {
        boolean removed = false;
        for (String key : keys) {
            removed |= unmute(key);
        }
        return removed;
    }

    public synchronized int size() {
        return mutes.size();
    }

    // ---------------------------------------------------------------- storage

    /**
     * Reads the file, ignoring anything it cannot parse.
     *
     * <p>Ignoring rather than failing: a corrupt line must cost one mute, not every mute and the
     * plugin's startup. The file is hand-editable by design, so it will be hand-edited.
     */
    public synchronized void load() throws IOException {
        mutes.clear();
        dirty = false;
        if (!Files.exists(file)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // key<TAB>until<TAB>by<TAB>displayName<TAB>reason - tab-separated because a reason
            // may contain anything a moderator felt like typing, including every other separator.
            String[] parts = trimmed.split("\t", 5);
            if (parts.length < 4) {
                continue;
            }
            long until;
            try {
                until = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            Mute mute = new Mute(until, parts.length >= 5 ? parts[4] : "", parts[2], parts[3]);
            if (mute.expired(now)) {
                dirty = true; // it will simply not be written back
                continue;
            }
            mutes.put(parts[0], mute);
        }
    }

    /**
     * Writes the file if anything changed, replacing it atomically.
     *
     * <p>Atomic because this is called on shutdown, and a server killed mid-write would otherwise
     * leave a truncated file - which on next start silently unmutes whoever was after the cut.
     */
    public synchronized void save() throws IOException {
        if (!dirty) {
            return;
        }
        List<String> lines = new ArrayList<>(mutes.size() + 3);
        lines.add("# SNTelegram: список мутов. Формат строки, поля через табуляцию:");
        lines.add("# ключ<TAB>до-когда<TAB>кто выдал<TAB>имя игрока<TAB>причина");
        lines.add("# «до-когда» — время в миллисекундах epoch; " + PERMANENT + " означает «навсегда».");
        for (Map.Entry<String, Mute> e : mutes.entrySet()) {
            Mute m = e.getValue();
            lines.add(String.join("\t", e.getKey(), Long.toString(m.until()),
                    clean(m.by()), clean(m.displayName()), clean(m.reason())));
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // Some Windows setups and network shares refuse ATOMIC_MOVE. A plain replace is
            // still far better than writing in place.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    /** Strips the two characters that would break the line format. */
    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }
}
