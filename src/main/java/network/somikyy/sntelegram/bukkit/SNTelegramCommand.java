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
import network.somikyy.sntelegram.core.Importer;
import network.somikyy.sntelegram.core.ModerationCommand;
import network.somikyy.sntelegram.core.TelegramText;
import network.somikyy.sntelegram.core.Topic;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The in-game side: {@code /sntelegram}.
 *
 * <p>Small on purpose. Everything an admin does day to day happens in Telegram; this command
 * exists for the three moments when they are at the console instead - checking whether the bridge
 * is actually alive, reloading after editing the config, and importing settings from whatever
 * plugin they are replacing.
 */
final class SNTelegramCommand implements CommandExecutor, TabCompleter {

    private final SNTelegramPlugin plugin;

    SNTelegramCommand(SNTelegramPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload", "перезагрузить" -> {
                if (!sender.hasPermission("sntelegram.reload")) {
                    sender.sendMessage(TextRender.template("<red>Недостаточно прав.</red>"));
                    return true;
                }
                plugin.reload();
                sender.sendMessage(TextRender.template(
                        "<green>Настройки перечитаны.</green> <gray>Подробности — в консоли.</gray>"));
            }
            case "import", "импорт" -> runImport(sender, args);
            case "version", "версия" -> sender.sendMessage(TextRender.template(
                    "<gray>SNTelegram</gray> <white>" + Build.VERSION + "</white> "
                            + "<dark_gray>·</dark_gray> <gray>" + Build.CHANNEL + "</gray>"));
            case "status", "статус" -> status(sender);
            // Everything else is tried as a moderation verb, and only then rejected. Falling
            // through to the status screen - which is what this did - is worse than an error:
            // the admin typed something meaningful, got a wall of unrelated text, and had no
            // way to tell whether the command had run.
            default -> moderate(sender, args);
        }
        return true;
    }

    /**
     * Moderation from the server console: {@code /sntelegram mute Steve 10m флуд}.
     *
     * <p>Exists for the case the Telegram path cannot cover. If the token expires or the route to
     * Telegram dies, every command in this plugin would otherwise be unreachable - including the
     * one that lifts a permanent mute. An admin standing at the console must never be locked out
     * of their own server by an outage somewhere else.
     *
     * <p>The line is handed to the same parser the Telegram side uses, by rebuilding it into the
     * shape that parser expects. That is deliberate: durations, Russian aliases and the rule that
     * stops a duration being mistaken for a nickname are then identical on both paths, and the
     * self-test that covers one covers the other.
     */
    private void moderate(CommandSender sender, String[] args) {
        ModerationCommand parsed = ModerationCommand.parse("/" + String.join(" ", args), null);
        if (!parsed.known()) {
            sender.sendMessage(TextRender.template("<red>Неизвестная команда «"
                    + escape(args[0]) + "».</red>\n"
                    + "<gray>Доступно: </gray><white>status, reload, import, version</white>"
                    + "<gray>, а также модерация: </gray><white>mute, unmute, kick, ban, unban, "
                    + "info, list</white><gray>.</gray>\n"
                    + "<gray>Например: </gray><white>/sntelegram mute Steve 10m флуд</white>"));
            return;
        }
        if (!sender.hasPermission("sntelegram.moderate")) {
            sender.sendMessage(TextRender.template("<red>Недостаточно прав.</red>"));
            return;
        }
        if (parsed.needsTarget()) {
            sender.sendMessage(TextRender.template("<red>Не указан игрок.</red> <gray>Из консоли "
                    + "ник обязателен — отвечать здесь не на что: </gray><white>/sntelegram "
                    + parsed.action().name().toLowerCase(Locale.ROOT) + " Ник</white>"));
            return;
        }

        Bridge bridge = plugin.bridge();
        String by = sender.getName();
        // The console gets plain text; Telegram gets the same sentence as HTML. Building it once
        // and undressing it is what keeps the two wordings from drifting apart.
        java.util.function.Consumer<String> answer = html -> {
            sender.sendMessage(TextRender.template("<white>"
                    + escape(TelegramText.stripHtml(html)) + "</white>"));
            if (bridge != null) {
                // The team watching Telegram should see console actions too, otherwise the
                // moderation log has holes exactly where someone worked around an outage.
                bridge.announceModeration(html);
            }
        };
        if (bridge != null) {
            bridge.run(parsed, by, answer, answer);
            return;
        }
        // No bridge: act anyway. This is the whole reason the command exists.
        plugin.moderation().runWithoutBridge(parsed, by, answer);
    }

    private void status(CommandSender sender) {
        Config config = plugin.config();
        Bridge bridge = plugin.bridge();
        StringBuilder sb = new StringBuilder();
        sb.append("<gray>───── </gray><white>SNTelegram ").append(Build.VERSION)
                .append("</white><gray> ─────</gray>\n");
        if (bridge == null) {
            sb.append("<red>Мост не запущен.</red> <gray>Не хватает настроек в config.yml.</gray>\n");
        } else {
            sb.append(bridge.poller().isRunning()
                            ? "<green>Опрос Telegram работает.</green>\n"
                            : "<red>Опрос Telegram остановлен.</red> <gray>Смотри консоль.</gray>\n")
                    .append("<gray>Отправлено:</gray> <white>").append(bridge.outbox().sentCount())
                    .append("</white>  <gray>в очереди:</gray> <white>")
                    .append(bridge.outbox().pending())
                    .append("</white>  <gray>потеряно:</gray> <white>")
                    .append(bridge.outbox().droppedCount())
                    .append("</white>  <gray>ошибок:</gray> <white>")
                    .append(bridge.outbox().failedCount()).append("</white>\n")
                    .append("<gray>Мутов активно:</gray> <white>").append(plugin.mutes().size())
                    .append("</white>\n");
        }
        for (Topic topic : config.topics()) {
            sb.append("<gray>• тема</gray> <white>").append(escape(topic.name()))
                    .append("</white> <dark_gray>(")
                    .append(topic.hasThread() ? "thread " + topic.threadId() : "General")
                    .append(")</dark_gray>\n");
        }
        if (plugin.scheduling().isFolia()) {
            sb.append("<gray>Сервер определён как Folia.</gray>\n");
        }
        for (String warning : config.warnings()) {
            sb.append("<yellow>! ").append(escape(warning)).append("</yellow>\n");
        }
        sender.sendMessage(TextRender.template(sb.toString().trim()));
    }

    /**
     * Reads another bridge's config and writes the equivalent SNTelegram settings.
     *
     * <p>Migration is a required feature of every plugin in the suite, and for a bridge it is the
     * only barrier that matters: an admin already running one has a working token, a chat id and
     * a set of topic ids, and asking them to find all of that again is what makes them not
     * bother. The importer writes a file for review rather than overwriting the live config -
     * the token is a secret and a silent overwrite of it would be unforgivable.
     */
    private void runImport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sntelegram.reload")) {
            sender.sendMessage(TextRender.template("<red>Недостаточно прав.</red>"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(TextRender.template(
                    "<gray>Использование:</gray> <white>/sntelegram import "
                            + "&lt;" + String.join("|", Importer.sources()) + "&gt;</white>\n"
                            + "<gray>Плагин найдёт конфиг сам и напишет "
                            + "config.imported.yml — его нужно проверить и переименовать.</gray>"));
            return;
        }
        String from = args[1].toLowerCase(Locale.ROOT);
        // On the calling thread would mean reading someone else's config off disk on the server
        // thread. It is a small file, but "small" is not a guarantee on a network mount.
        plugin.scheduling().async(() -> {
            Path pluginsDir = plugin.getDataFolder().toPath().getParent();
            Path out = plugin.getDataFolder().toPath().resolve("config.imported.yml");
            try {
                Importer.Result result = Importer.importFrom(from, pluginsDir);
                if (!result.found()) {
                    sender.sendMessage(TextRender.template("<red>Конфиг «" + escape(from)
                            + "» не найден в папке plugins.</red> <gray>" + escape(result.note())
                            + "</gray>"));
                    return;
                }
                Files.createDirectories(out.getParent());
                Files.writeString(out, result.yaml(), StandardCharsets.UTF_8);
                sender.sendMessage(TextRender.template(
                        "<green>Готово.</green> <gray>Перенесено настроек: </gray><white>"
                                + result.moved() + "</white><gray>. Проверь файл "
                                + "plugins/SNTelegram/config.imported.yml, затем переименуй его "
                                + "в config.yml и выполни /sntelegram reload.</gray>"));
                for (String note : result.notes()) {
                    sender.sendMessage(TextRender.template("<yellow>! " + escape(note) + "</yellow>"));
                }
            } catch (IOException e) {
                sender.sendMessage(TextRender.template("<red>Не удалось прочитать или записать "
                        + "файл: " + escape(String.valueOf(e.getMessage())) + "</red>"));
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "reload", "import", "version",
                    "mute", "unmute", "kick", "ban", "unban", "info", "list"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            return filter(Importer.sources(), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }

    /** Escapes text before it is fed to a MiniMessage template built here. */
    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("<", "\\<");
    }
}
