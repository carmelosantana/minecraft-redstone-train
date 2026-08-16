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
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.display.ChargeDisplay;
import org.xpfarm.redstonetrain.item.RtItems;
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
 *   <li>{@code give <player> <locomotive|wrench> [amount]}
 *       ({@code redstonetrain.admin}) — hands an online player tagged plugin items
 *       from {@link RtItems}. The amount defaults to 1 and clamps to 1..64; overflow
 *       that does not fit the inventory drops at the player's feet.</li>
 * </ul>
 *
 * <p>The config arrives through a {@link Supplier} so a successful reload is visible
 * here without re-registering the executor. Routing and permission gating are
 * headless-testable; preset resolution is shared via
 * {@link SpeedModel#presetMultiplier}; only {@link #findTrain(Player)} touches live
 * entities (gate 7a).
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

    static final String USAGE =
            "Usage: /redstonetrain <info | reload | give <player> <locomotive|wrench> [amount]>";
    static final String GIVE_USAGE =
            "Usage: /redstonetrain give <player> <locomotive|wrench> [amount]";

    /** {@link #parseAmount} sentinel: the raw amount was not a whole number. */
    static final int AMOUNT_INVALID = -1;

    /** Inclusive bounds every give amount is clamped into. */
    static final int AMOUNT_MIN = 1;
    static final int AMOUNT_MAX = 64;

    /** Blocks within which {@code info} ray-traces for a looked-at train member. */
    private static final int TARGET_RANGE = 8;

    private final TrainRegistry registry;
    private final Supplier<RtConfig> config;
    private final ConfigReloader reloader;
    private final RtItems items;

    public RedstoneTrainCommand(TrainRegistry registry, Supplier<RtConfig> config,
                                ConfigReloader reloader, RtItems items) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.config = Objects.requireNonNull(config, "config");
        this.reloader = Objects.requireNonNull(reloader, "reloader");
        this.items = Objects.requireNonNull(items, "items");
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
            case "give" -> give(sender, args);
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
        return SpeedModel.withPreset(cruise,
                SpeedModel.presetMultiplier(train.speedPreset(), cfg), cfg);
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

    // ------------------------------------------------------------------- give

    /** The two admin-giveable items and their canonical, pluralizable names. */
    enum GiveItem {
        LOCOMOTIVE("locomotive", "locomotives"),
        WRENCH("wrench", "wrenches");

        final String singular;
        final String plural;

        GiveItem(String singular, String plural) {
            this.singular = singular;
            this.plural = plural;
        }

        /** Case-insensitive lookup by canonical name; {@code null} when unknown. */
        static @Nullable GiveItem resolve(String raw) {
            for (GiveItem item : values()) {
                if (item.singular.equalsIgnoreCase(raw)) {
                    return item;
                }
            }
            return null;
        }

        /** Fresh tagged stack for this item type. Live-item glue, gate 7a. */
        ItemStack stack(RtItems items) {
            return this == LOCOMOTIVE ? items.locomotive() : items.wrench();
        }

        String describe(int amount) {
            return amount == 1 ? singular : plural;
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("redstonetrain.admin")) {
            sender.sendMessage(Component.text(
                    "You do not have permission to give RedstoneTrain items.",
                    NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text(GIVE_USAGE, NamedTextColor.GRAY));
            return;
        }
        GiveItem item = GiveItem.resolve(args[2]);
        if (item == null) {
            sender.sendMessage(Component.text("Unknown item '" + args[2]
                            + "' — valid items: locomotive, wrench.", NamedTextColor.RED));
            return;
        }
        int amount = parseAmount(args.length > 3 ? args[3] : null);
        if (amount == AMOUNT_INVALID) {
            sender.sendMessage(Component.text("Amount must be a whole number — ",
                    NamedTextColor.RED)
                    .append(Component.text(GIVE_USAGE, NamedTextColor.GRAY)));
            return;
        }
        Player target = sender.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player '" + args[1] + "' is not online.",
                    NamedTextColor.RED));
            return;
        }
        deliver(sender, target, item, amount);
    }

    /** Inventory add plus feet-drop overflow. Live-server glue, gate 7a. */
    private void deliver(CommandSender sender, Player target, GiveItem item, int amount) {
        ItemStack stack = item.stack(items);
        stack.setAmount(amount);
        for (ItemStack overflow : target.getInventory().addItem(stack).values()) {
            target.getWorld().dropItem(target.getLocation(), overflow);
        }
        String what = amount + " " + item.describe(amount);
        sender.sendMessage(Component.text("Gave " + what + " to " + target.getName() + ".",
                NamedTextColor.GREEN));
        target.sendMessage(Component.text("You received " + what + ".", NamedTextColor.GOLD));
    }

    /**
     * Pure amount parsing (headless-testable): absent or empty defaults to 1, whole
     * numbers clamp into {@link #AMOUNT_MIN}..{@link #AMOUNT_MAX}, anything else is
     * {@link #AMOUNT_INVALID}.
     */
    static int parseAmount(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return AMOUNT_MIN;
        }
        try {
            return clampAmount(Integer.parseInt(raw));
        } catch (NumberFormatException notAWholeNumber) {
            return AMOUNT_INVALID;
        }
    }

    /** Pure clamp into the giveable range 1..64. */
    static int clampAmount(int amount) {
        return Math.clamp(amount, AMOUNT_MIN, AMOUNT_MAX);
    }

    // --------------------------------------------------------- tab completion

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (args.length == 1) {
            return complete(args[0], sender.hasPermission("redstonetrain.use"),
                    sender.hasPermission("redstonetrain.admin"));
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")
                && sender.hasPermission("redstonetrain.admin")) {
            if (args.length == 2) {
                return completeOnlinePlayers(sender, args[1]);
            }
            if (args.length == 3) {
                return completeGiveItems(args[2]);
            }
        }
        return List.of();
    }

    /** Online player names matching the prefix. Live-server glue, gate 7a. */
    private static List<String> completeOnlinePlayers(CommandSender sender, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Player online : sender.getServer().getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(online.getName());
            }
        }
        return matches;
    }

    /** Pure completion: permitted subcommands matching the prefix, case-insensitive. */
    static List<String> complete(String prefix, boolean canUse, boolean canAdmin) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>(3);
        if (canUse && "info".startsWith(lower)) {
            matches.add("info");
        }
        if (canAdmin && "give".startsWith(lower)) {
            matches.add("give");
        }
        if (canAdmin && "reload".startsWith(lower)) {
            matches.add("reload");
        }
        return matches;
    }

    /** Pure completion: giveable item names matching the prefix, case-insensitive. */
    static List<String> completeGiveItems(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>(2);
        for (GiveItem item : GiveItem.values()) {
            if (item.singular.startsWith(lower)) {
                matches.add(item.singular);
            }
        }
        return matches;
    }
}
