package io.papermc.paper.threadedregions.scheduler;

import org.bukkit.plugin.Plugin;

public interface GlobalRegionScheduler {
    void execute(Plugin plugin, Runnable run);
}
