package org.bukkit.command;

import net.kyori.adventure.text.Component;

public interface CommandSender {
    String getName();
    boolean hasPermission(String permission);
    void sendMessage(Component message);
}
