package org.bukkit.command;

public final class PluginCommand extends Command {
    private PluginCommand() { }
    public void setExecutor(CommandExecutor executor) { }
    public void setTabCompleter(TabCompleter completer) { }
}
