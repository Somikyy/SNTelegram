package io.papermc.paper.event.player;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public class AsyncChatEvent {
    public Player getPlayer() { return null; }
    public Component message() { return null; }
    public void setCancelled(boolean cancel) { }
}
