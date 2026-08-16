/*
 * RedstoneTrain - a craftable self-propelled electric locomotive that couples
 * vanilla minecarts into charge-powered trains.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.redstonetrain;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin entry point.
 *
 * <p>Scaffold skeleton (lifecycle gate 2/3): wires configuration loading and the
 * {@code /redstonetrain} command surface so the repository builds and the descriptor
 * test passes. The train mechanics — coupling, charge, and the movement controller —
 * are implemented at gate 4 by {@code minecraft-plugin-dev}.
 */
public final class RedstoneTrainPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("RedstoneTrain enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("RedstoneTrain disabled.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("redstonetrain")) {
            return false;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "info";
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("redstonetrain.admin")) {
                    sender.sendMessage("You do not have permission to reload RedstoneTrain.");
                    return true;
                }
                reloadConfig();
                sender.sendMessage("RedstoneTrain configuration reloaded.");
                return true;
            }
            case "info" -> {
                if (!sender.hasPermission("redstonetrain.use")) {
                    sender.sendMessage("You do not have permission to use RedstoneTrain.");
                    return true;
                }
                sender.sendMessage("RedstoneTrain v" + getPluginMeta().getVersion()
                        + " — train inspection arrives in gate 4.");
                return true;
            }
            default -> {
                sender.sendMessage("Usage: /redstonetrain <info | reload>");
                return true;
            }
        }
    }
}
