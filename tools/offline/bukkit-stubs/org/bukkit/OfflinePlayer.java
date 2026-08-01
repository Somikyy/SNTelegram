package org.bukkit;

import com.destroystokyo.paper.profile.PlayerProfile;

import java.util.Date;
import java.util.UUID;

public interface OfflinePlayer {
    BanEntry<PlayerProfile> ban(String reason, Date expires, String source);
    long getFirstPlayed();
    String getName();
    int getStatistic(Statistic statistic);
    UUID getUniqueId();
    boolean hasPlayedBefore();
    boolean isBanned();
}
