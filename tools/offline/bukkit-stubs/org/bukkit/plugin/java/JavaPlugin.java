package org.bukkit.plugin.java;

import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.util.logging.Logger;

public abstract class JavaPlugin implements Plugin {
    public PluginCommand getCommand(String name) { return null; }
    public File getDataFolder() { return null; }
    public Logger getLogger() { return null; }
    public InputStream getResource(String filename) { return null; }
    public Server getServer() { return null; }
    public void onDisable() { }
    public void onEnable() { }
}
