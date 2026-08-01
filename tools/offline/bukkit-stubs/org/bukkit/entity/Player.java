package org.bukkit.entity;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.BanEntry;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.net.InetSocketAddress;
import java.util.Date;
import java.util.UUID;

public interface Player extends OfflinePlayer, CommandSender {
    BanEntry<PlayerProfile> ban(String reason, Date expires, String source, boolean kickPlayer);
    InetSocketAddress getAddress();
    @Override String getName();
    @Override UUID getUniqueId();
    void kick(Component reason);
    @Override void sendMessage(Component message);
}
