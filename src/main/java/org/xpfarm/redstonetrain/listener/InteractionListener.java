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
package org.xpfarm.redstonetrain.listener;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.display.ChargeDisplay;
import org.xpfarm.redstonetrain.item.RtItems;
import org.xpfarm.redstonetrain.model.ChargeModel;
import org.xpfarm.redstonetrain.train.Train;
import org.xpfarm.redstonetrain.train.TrainCodec;
import org.xpfarm.redstonetrain.train.TrainRegistry;

/**
 * Right-click interactions with train members, plus the rider boss bar lifecycle and
 * the inter-member collision suppression the movement controller relies on.
 *
 * <p>Interaction rules ({@link #onPlayerInteractEntity}), main hand only:
 * <ul>
 *   <li>Train Wrench + sneaking, any member → uncouple the clicked car and every car
 *       behind it (clicking the locomotive detaches the whole consist); persist.</li>
 *   <li>Train Wrench, not sneaking → cycle the speed preset through the configured
 *       presets in {@code config.yml} order, wrapping around; persist; message.</li>
 *   <li>Redstone dust / redstone block on the locomotive → top up charge via
 *       {@link ChargeModel#addRedstone}; consume one item (never in creative, never
 *       when the battery is already full); persist; message.</li>
 *   <li>Empty or any other hand on the locomotive → toggle the engine; persist;
 *       message. The click is consumed, so players ride the coupled cars, not the
 *       locomotive itself.</li>
 * </ul>
 *
 * <p><strong>Atomic uncoupling</strong> (Task 3 invariant): every uncouple goes through
 * {@link TrainRegistry#uncoupleTail} via {@link #uncoupleFromMember}, never through
 * {@code Train.removeCar}/{@code uncoupleFrom} directly, so {@code byCar} never goes
 * stale.
 *
 * <p><strong>Collision suppression</strong> (Task 6 handoff):
 * {@link #onVehicleEntityCollision} cancels {@code VehicleEntityCollisionEvent}
 * whenever both entities are members of the same registered train — followers chase
 * the member ahead at a 1.2-block gap and would otherwise bounce off each other.
 *
 * <p>Geyser/Bedrock safety: right-click-entity, right-click-with-item, boss bar, and
 * chat messages only — all faithfully translated by Geyser. No GUI forms, no
 * Java-only chat input; all text goes through the Adventure component API.
 *
 * <p>The decisions ({@link #nextPreset}, {@link #uncoupleFromMember},
 * {@link #sameTrain}, {@link #facingFromYaw}) are pure and unit-tested headless;
 * event wiring and item consumption are validated at gate 7a on a live server.
 */
public final class InteractionListener implements Listener {

    private final Plugin plugin;
    private final RtConfig config;
    private final TrainRegistry registry;
    private final TrainCodec codec;
    private final RtItems items;
    private final ChargeDisplay display;

    public InteractionListener(Plugin plugin, RtConfig config, TrainRegistry registry,
                               TrainCodec codec, RtItems items, ChargeDisplay display) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.items = Objects.requireNonNull(items, "items");
        this.display = Objects.requireNonNull(display, "display");
    }

    // ------------------------------------------------------------------ events

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Minecart cart)) {
            return;
        }
        Train train = registry.byMember(cart.getUniqueId());
        if (train == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();

        if (items.isWrench(held)) {
            event.setCancelled(true);
            if (player.isSneaking()) {
                wrenchUncouple(player, train, cart.getUniqueId());
            } else {
                wrenchCyclePreset(player, train);
            }
            return;
        }

        if (!train.locomotiveId().equals(cart.getUniqueId())) {
            return; // Cars keep vanilla behavior (mounting); only the loco takes fuel/engine.
        }

        Material type = held.getType();
        if (type == Material.REDSTONE || type == Material.REDSTONE_BLOCK) {
            event.setCancelled(true);
            feedRedstone(player, train, held, type == Material.REDSTONE_BLOCK);
            return;
        }

        // Empty or any other hand: toggle the engine. Consume the click so the player
        // does not mount the locomotive on top of toggling it.
        event.setCancelled(true);
        train.setEngineOn(!train.engineOn());
        if (train.engineOn() && train.lastFacing() == null) {
            // Cold start: a freshly placed locomotive has never moved, so it has no
            // travel direction yet. Seed one from the toggling player's yaw; the
            // movement controller snaps it onto the rail axis on the next tick.
            train.setLastFacing(facingFromYaw(player.getLocation().getYaw()));
        }
        persist(train);
        display.update(train);
        player.sendMessage(train.engineOn()
                ? Component.text("Engine on.", NamedTextColor.GREEN)
                : Component.text("Engine off.", NamedTextColor.RED));
    }

    /** A player entering any member of a train gets the charge boss bar. */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player player)) {
            return;
        }
        Train train = registry.byMember(event.getVehicle().getUniqueId());
        if (train != null) {
            display.show(player, train);
        }
    }

    /** Leaving the vehicle hides the bar (harmless no-op for non-train vehicles). */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            display.hide(player);
        }
    }

    /**
     * Task 6 handoff: members of the same registered train never collide with each
     * other — followers deliberately run within bumping distance of the member ahead,
     * and vanilla collision would shove them off the rails.
     */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleEntityCollision(VehicleEntityCollisionEvent event) {
        if (sameTrain(registry, event.getVehicle().getUniqueId(),
                event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------- world-facing glue

    /** Sneak + wrench: atomic uncouple of the clicked member's tail; persist; message. */
    private void wrenchUncouple(Player player, Train train, UUID clickedId) {
        List<UUID> detached = uncoupleFromMember(registry, clickedId);
        if (detached.isEmpty()) {
            player.sendMessage(Component.text("Nothing to uncouple.", NamedTextColor.GRAY));
            return;
        }
        persist(train);
        display.update(train);
        player.sendMessage(Component.text(
                "Uncoupled " + detached.size() + (detached.size() == 1 ? " car." : " cars."),
                NamedTextColor.YELLOW));
    }

    /** Wrench without sneaking: cycle the speed preset in config order; persist; message. */
    private void wrenchCyclePreset(Player player, Train train) {
        String next = nextPreset(train.speedPreset(),
                List.copyOf(config.speedPresets().keySet()));
        train.setSpeedPreset(next);
        persist(train);
        display.update(train);
        player.sendMessage(Component.text("Speed preset: ", NamedTextColor.GRAY)
                .append(Component.text(next, NamedTextColor.GOLD)));
    }

    /** Redstone in hand on the locomotive: top up, consume one, persist, message. */
    private void feedRedstone(Player player, Train train, ItemStack held, boolean isBlock) {
        double before = train.charge();
        double after = ChargeModel.addRedstone(before, isBlock, config);
        if (after <= before) {
            player.sendMessage(Component.text("Battery already full.", NamedTextColor.GRAY));
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.subtract();
            player.getInventory().setItemInMainHand(held);
        }
        train.setCharge(after);
        persist(train);
        display.update(train);
        player.sendMessage(Component.text(
                String.format(Locale.ROOT, "⚡ Charge: %.0f / %.0f", after, config.chargeMax()),
                NamedTextColor.RED));
    }

    /** Writes the train's state to its locomotive entity's PDC, if it is resolvable. */
    private void persist(Train train) {
        if (plugin.getServer().getEntity(train.locomotiveId()) instanceof Minecart loco) {
            codec.write(loco, train);
        }
    }

    // ------------------------------------------------- pure, headless-testable

    /**
     * Nearest cardinal to a Minecraft yaw, as a {@code BlockFace} name. Minecraft
     * yaw: 0° is south (+Z), 90° west (-X), 180°/-180° north (-Z), 270°/-90° east
     * (+X); any real value (including negatives and multiples of 360°) normalizes.
     * Used to seed a cold-start travel direction when the engine is switched on.
     */
    static String facingFromYaw(float yaw) {
        int quarter = Math.floorMod(Math.round(yaw / 90.0f), 4);
        return switch (quarter) {
            case 0 -> "SOUTH";
            case 1 -> "WEST";
            case 2 -> "NORTH";
            default -> "EAST";
        };
    }

    /**
     * The preset after {@code current} in {@code presetsInOrder}, wrapping around at
     * the end. An unknown current preset restarts at the first configured preset; an
     * empty preset list keeps the current one.
     */
    static String nextPreset(String current, List<String> presetsInOrder) {
        if (presetsInOrder.isEmpty()) {
            return current;
        }
        int index = presetsInOrder.indexOf(current);
        return presetsInOrder.get((index + 1) % presetsInOrder.size());
    }

    /**
     * Uncouple decision for the wrench, always through the registry's atomic
     * {@link TrainRegistry#uncoupleTail} so {@code byCar} never goes stale: a clicked
     * car detaches itself and every car behind it; a clicked locomotive detaches the
     * whole consist (the train itself stays registered).
     *
     * @return the detached cars, front to back; empty if nothing changed
     */
    static List<UUID> uncoupleFromMember(TrainRegistry registry, UUID memberId) {
        Train asLocomotive = registry.byLocomotive(memberId);
        if (asLocomotive != null) {
            return asLocomotive.carCount() == 0
                    ? List.of()
                    : registry.uncoupleTail(asLocomotive, 0);
        }
        Train owner = registry.byCar(memberId);
        if (owner == null) {
            return List.of();
        }
        return registry.uncoupleTail(owner, owner.cars().indexOf(memberId));
    }

    /** True iff both entities are members (locomotive or car) of the same train. */
    static boolean sameTrain(TrainRegistry registry, UUID first, UUID second) {
        Train train = registry.byMember(first);
        return train != null && train == registry.byMember(second);
    }
}
