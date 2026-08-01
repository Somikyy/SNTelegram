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

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Getting back onto a thread the server will accept, on both Paper and Folia.
 *
 * <p>The bridge does almost everything on its own threads, but three operations refuse to run
 * there: kicking a player, banning one, and dispatching a console command all pass through
 * Paper's {@code AsyncCatcher} and throw if called from anywhere but the server thread. So every
 * moderation action arriving from Telegram has to hop.
 *
 * <p>Folia has no single server thread to hop to, and its {@code BukkitScheduler} throws on every
 * scheduling call rather than silently misbehaving. The replacement is
 * {@code GlobalRegionScheduler}, and the useful surprise is that it is part of ordinary
 * {@code paper-api} and implemented by ordinary Paper as well - so one call site works on both
 * and no reflection is needed.
 *
 * <p>It is still resolved defensively at load time. A server that predates the API, or a fork
 * that removed it, must degrade to the classic scheduler rather than fail to load the plugin -
 * an admin whose bridge will not start learns nothing from a NoSuchMethodError.
 */
final class Scheduling {

    private final Plugin plugin;

    /** True when {@code GlobalRegionScheduler} is usable; decided once, at enable. */
    private final boolean modern;

    /** True when this really is Folia, which changes what is safe and what is merely allowed. */
    private final boolean folia;

    Scheduling(Plugin plugin) {
        this.plugin = plugin;
        this.folia = classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        this.modern = classPresent("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
    }

    boolean isFolia() {
        return folia;
    }

    /**
     * Runs {@code work} where the server API is legal to call.
     *
     * <p>Always schedules, even when already on the right thread. Running inline would be faster
     * and would also mean the same code sometimes runs inside an event handler and sometimes
     * not - and a kick issued from inside a chat event is a different thing from a kick issued
     * a tick later. One behaviour is worth a tick.
     */
    void onServerThread(Runnable work) {
        if (modern) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, work);
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
        }
    }

    /** Runs {@code work} off the server thread - for anything that touches disk. */
    void async(Runnable work) {
        if (modern) {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> work.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
