package org.bukkit;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.Collection;

/** Compile-only stub. The server supplies the real class; this one is never packed into the jar. */
public final class Bukkit {
    private Bukkit() { }
    public static int broadcast(Component message) { return 0; }
    public static PlayerProfile createProfile(String name) { return null; }
    public static AsyncScheduler getAsyncScheduler() { return null; }
    public static <T> BanList<T> getBanList(BanListType<T> type) { return null; }
    public static GlobalRegionScheduler getGlobalRegionScheduler() { return null; }
    public static OfflinePlayer getOfflinePlayerIfCached(String name) { return null; }
    public static Collection<? extends Player> getOnlinePlayers() { return null; }
    public static Player getPlayerExact(String name) { return null; }
    public static BukkitScheduler getScheduler() { return null; }
    public static double[] getTPS() { return null; }
}
