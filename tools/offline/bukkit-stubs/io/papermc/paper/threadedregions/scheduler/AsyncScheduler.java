package io.papermc.paper.threadedregions.scheduler;

import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

public interface AsyncScheduler {
    ScheduledTask runNow(Plugin plugin, Consumer<ScheduledTask> task);
}
