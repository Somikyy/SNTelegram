package org.bukkit;

public interface BanList<T> {
    void pardon(T target);
}
