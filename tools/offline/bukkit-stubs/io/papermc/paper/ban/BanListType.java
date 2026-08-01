package io.papermc.paper.ban;

import com.destroystokyo.paper.profile.PlayerProfile;

import java.net.InetAddress;

public final class BanListType<T> {
    public static final BanListType<InetAddress> IP = new BanListType<>();
    public static final BanListType<PlayerProfile> PROFILE = new BanListType<>();
    private BanListType() { }
}
