/*
 * SNTelegram - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sntelegram.bukkit;

import network.somikyy.sntelegram.core.Build;
import network.somikyy.sntelegram.core.Config;
import network.somikyy.sntelegram.core.EventKind;
import network.somikyy.sntelegram.core.MuteBook;
import network.somikyy.sntelegram.core.TelegramPoller;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * The plugin.
 *
 * <p>Its whole job is to assemble the pieces in the right order and take them apart again
 * cleanly. There is no bridge logic here on purpose: everything that decides anything lives in
 * {@code core} and is testable without a server, and everything that touches the server lives in
 * the four classes next to this one.
 */
public final class SNTelegramPlugin extends JavaPlugin {

    private Config config;
    private MuteBook mutes;
    private Bridge bridge;
    private Scheduling scheduling;

    /**
     * Owned here, not by the bridge, so that {@code /sntelegram mute} works even when the bridge
     * does not.
     *
     * <p>Without this the failure mode is genuinely bad: a player muted forever could never be
     * unmuted if the token expired or Telegram became unreachable, because the only path to the
     * unmute command would be through Telegram itself.
     */
    private Moderation moderation;

    @Override
    public void onEnable() {
        this.scheduling = new Scheduling(this);
        this.config = readConfig();

        for (String warning : config.warnings()) {
            getLogger().warning(warning);
        }

        this.mutes = new MuteBook(getDataFolder().toPath().resolve("mutes.txt"));
        try {
            mutes.load();
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Не удалось прочитать список мутов, начинаем с пустого", e);
        }

        this.moderation = new Moderation(mutes, false);

        SNTelegramCommand command = new SNTelegramCommand(this);
        if (getCommand("sntelegram") != null) {
            getCommand("sntelegram").setExecutor(command);
            getCommand("sntelegram").setTabCompleter(command);
        }

        if (!config.usable()) {
            // Enabled but idle, on purpose. Refusing to enable would hide the warnings above
            // behind a stack trace, and an admin who has just installed the plugin needs to
            // read them - they say exactly what is missing.
            getLogger().warning("SNTelegram загружен, но мост не запущен: не хватает настроек. "
                    + "Заполни plugins/SNTelegram/config.yml и выполни /sntelegram reload.");
            return;
        }

        startBridge();
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            bridge.sendEvent(EventKind.SERVER, config.templates().serverStop());
            bridge.stop();
            bridge = null;
        }
        if (mutes != null) {
            try {
                mutes.save();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Не удалось сохранить список мутов", e);
            }
        }
        // Nothing is left in the world to clean up: the bridge creates no entities, no
        // holograms and no scoreboards. Removing the jar removes the plugin entirely.
    }

    // ---------------------------------------------------------------- wiring

    private void startBridge() {
        bridge = new Bridge(config, mutes, moderation, scheduling, logger());
        getServer().getPluginManager().registerEvents(new GameListeners(config, bridge), this);
        bridge.start();
        bridge.sendEvent(EventKind.SERVER, config.templates().serverStart());
        getLogger().info("Мост в Telegram запущен: " + config.topics().size()
                + " тем(ы), опрос раз в " + config.pollSeconds() + " c."
                + (scheduling.isFolia() ? " Обнаружена Folia." : ""));
    }

    /** Rebuilds everything from the file on disk. Used by {@code /sntelegram reload}. */
    void reload() {
        if (bridge != null) {
            bridge.stop();
            org.bukkit.event.HandlerList.unregisterAll(this);
            bridge = null;
        }
        this.config = readConfig();
        for (String warning : config.warnings()) {
            getLogger().warning(warning);
        }
        if (config.usable()) {
            startBridge();
        } else {
            getLogger().warning("Мост не запущен: не хватает настроек в config.yml.");
        }
    }

    Config config() {
        return config;
    }

    Bridge bridge() {
        return bridge;
    }

    MuteBook mutes() {
        return mutes;
    }

    Moderation moderation() {
        return moderation;
    }

    Scheduling scheduling() {
        return scheduling;
    }

    // ---------------------------------------------------------------- config file

    /**
     * Reads {@code config.yml}, writing the bundled default on first run.
     *
     * <p>Done by hand rather than through {@code saveDefaultConfig()} and {@code getConfig()}
     * because the parser lives in {@code core}, which knows nothing about Bukkit. That is what
     * lets the same parsing be exercised by the offline self-test, so the file the admin edits is
     * read by code that has been tested against the file that ships.
     */
    private Config readConfig() {
        Path file = getDataFolder().toPath().resolve("config.yml");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream bundled = getResource("config.yml")) {
                    if (bundled != null) {
                        Files.copy(bundled, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return Config.load(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Не удалось прочитать config.yml, используются "
                    + "настройки по умолчанию", e);
            return Config.load("");
        }
    }

    /** Adapts the plugin logger to what {@code core} expects, so core stays Bukkit-free. */
    private TelegramPoller.Log logger() {
        return new TelegramPoller.Log() {
            @Override
            public void info(String message) {
                getLogger().info(message);
            }

            @Override
            public void warn(String message) {
                getLogger().warning(message);
            }

            @Override
            public void error(String message) {
                getLogger().severe(message);
            }
        };
    }

    static String version() {
        return Build.VERSION;
    }
}
