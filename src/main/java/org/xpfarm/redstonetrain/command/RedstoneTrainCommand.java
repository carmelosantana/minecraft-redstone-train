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
package org.xpfarm.redstonetrain.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.display.ChargeDisplay;
import org.xpfarm.redstonetrain.model.ChargeModel;
import org.xpfarm.redstonetrain.model.SpeedModel;
import org.xpfarm.redstonetrain.train.Train;
import org.xpfarm.redstonetrain.train.TrainRegistry;

/**
 * The {@code /redstonetrain} (alias {@code /rtrain}) command surface.
 *
 * <ul>
 *   <li>{@code info} ({@code redstonetrain.use}) — reports the train the player is
 *       riding or looking at: car count, charge percent, sustained speed, engine
 *       state, and preset. A helpful message when there is none.</li>
 *   <li>{@code reload} ({@code redstonetrain.admin}) — delegates to the injected
 *       {@link ConfigReloader}; the plugin rebuilds {@link RtConfig} from disk and
 *       re-wires every config-holding service. Messages success or the validation
 *       error (the previous configuration stays active on failure).</li>
 * </ul>
 *
 * <p>The config arrives through a {@link Supplier} so a successful reload is visible
 * here without re-registering the executor. Routing, permission gating, and the
 * preset-fallback helpers are headless-testable; only {@link #findTrain(Player)}
 * touches live entities (gate 7a).
 *
 * <p>Geyser/Bedrock safety: plain chat components only — no GUI forms.
 */
public final class RedstoneTrainCommand implements CommandExecutor, TabCompleter {

    /** How the config is rebuilt on {@code /redstonetrain reload}. */
    @FunctionalInterface
    public interface ConfigReloader {
        /**
         * Rebuilds the config from disk and re-injects it into every service.
         *
         * @return {@code null} on success, otherwise the validation error message
         */
        @Nullable String reload();
    }

    static final String USAGE = "Usage: /redstonetrain <info | reload>";

    /** Blocks within which {@code info} ray-traces for a looked-at train member. */
    private static final int TARGET_RANGE = 8;

    private final TrainRegistry registry;
    private final Supplier<RtConfig> config;
    private final ConfigReloader reloader;

    public RedstoneTrainCommand(TrainRegistry registry, Supplier<RtConfig> config,
                                ConfigReloader reloader) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.config = Objects.requireNonNull(config, "config");
        this.reloader = Objects.requireNonNull(reloader, "reloader");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        route(sender, args);
        return true;
    }

    /** Pure argument routing (headless-testable seam; {@code onCommand} delegates). */
    void route(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "info" -> info(sender);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(Component.text(USAGE, NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------- info

    private void info(CommandSender sender) {
        if (!sender.hasPermission("redstonetrain.use")) {
            sender.sendMessage(Component.text(
                    "You do not have permission to use RedstoneTrain.", NamedTextColor.RED));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "Only players can inspect trains — ride one or look at one in game.",
                    NamedTextColor.GRAY));
            return;
        }
        Train train = findTrain(player);
        if (train == null) {
            sender.sendMessage(Component.text(
                    "No train found. Ride a train, or look at one of its carts within "
                            + TARGET_RANGE + " blocks.",
                    NamedTextColor.GRAY));
            return;
        }
        RtConfig cfg = config.get();
        double speed = currentSpeed(train, cfg);
        sender.sendMessage(Component.text("Train — ", NamedTextColor.GOLD)
                .append(Component.text(train.carCount()
                                + (train.carCount() == 1 ? " car, " : " cars, "),
                        NamedTextColor.WHITE))
                .append(Component.text("engine " + (train.engineOn() ? "on" : "off"),
                        train.engineOn() ? NamedTextColor.GREEN : NamedTextColor.RED))
                .append(Component.text(", preset ", NamedTextColor.WHITE))
                .append(Component.text(train.speedPreset(), NamedTextColor.GOLD)));
        sender.sendMessage(Component.text(
                ChargeDisplay.format(train.charge(), cfg.chargeMax(), speed, train.carCount()),
                NamedTextColor.RED));
    }

    /**
     * Sustained speed in blocks per tick: preset-adjusted cruise while the engine is on
     * and the battery holds charge, otherwise 0. Transient powered-rail boost excluded,
     * matching the boss bar.
     */
    private static double currentSpeed(Train train, RtConfig cfg) {
        if (!train.engineOn() || !ChargeModel.canMove(train.charge())) {
            return 0.0;
        }
        double cruise = SpeedModel.cruise(train.carCount(), cfg);
        return SpeedModel.withPreset(cruise, presetMultiplier(train.speedPreset(), cfg), cfg);
    }

    /** The riding lookup then an 8-block ray trace. Live-entity glue, gate 7a. */
    private @Nullable Train findTrain(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            Train riding = registry.byMember(vehicle.getUniqueId());
            if (riding != null) {
                return riding;
            }
        }
        Entity target = player.getTargetEntity(TARGET_RANGE);
        return target != null ? registry.byMember(target.getUniqueId()) : null;
    }

    // ----------------------------------------------------------------- reload

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("redstonetrain.admin")) {
            sender.sendMessage(Component.text(
                    "You do not have permission to reload RedstoneTrain.", NamedTextColor.RED));
            return;
        }
        String error = reloader.reload();
        if (error == null) {
            sender.sendMessage(Component.text(
                    "RedstoneTrain configuration reloaded.", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Reload failed: ", NamedTextColor.RED)
                    .append(Component.text(error, NamedTextColor.WHITE))
                    .append(Component.text(
                            " — keeping the previous configuration.", NamedTextColor.RED)));
        }
    }

    // ------------------------------------------- preset fallback (Task 3 flag)

    /**
     * The preset name a train should default to: {@code cruise} when configured,
     * otherwise the <em>first</em> configured preset (config order), otherwise
     * {@code cruise} as a harmless label when no presets exist at all.
     */
    public static String fallbackPreset(RtConfig cfg) {
        if (cfg.speedPresets().containsKey(Train.DEFAULT_SPEED_PRESET)) {
            return Train.DEFAULT_SPEED_PRESET;
        }
        return cfg.speedPresets().keySet().stream()
                .findFirst()
                .orElse(Train.DEFAULT_SPEED_PRESET);
    }

    /**
     * Multiplier for a train's preset with the Task-3 fallback: the named preset if
     * configured, else the {@link #fallbackPreset} multiplier, else {@code 1.0} when
     * no presets are configured — never a crash.
     */
    public static double presetMultiplier(String preset, RtConfig cfg) {
        Double multiplier = cfg.speedPresets().get(preset);
        if (multiplier != null) {
            return multiplier;
        }
        Double fallback = cfg.speedPresets().get(fallbackPreset(cfg));
        return fallback != null ? fallback : 1.0;
    }

    // --------------------------------------------------------- tab completion

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return complete(args[0], sender.hasPermission("redstonetrain.use"),
                sender.hasPermission("redstonetrain.admin"));
    }

    /** Pure completion: permitted subcommands matching the prefix, case-insensitive. */
    static List<String> complete(String prefix, boolean canUse, boolean canAdmin) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>(2);
        if (canUse && "info".startsWith(lower)) {
            matches.add("info");
        }
        if (canAdmin && "reload".startsWith(lower)) {
            matches.add("reload");
        }
        return matches;
    }
}
