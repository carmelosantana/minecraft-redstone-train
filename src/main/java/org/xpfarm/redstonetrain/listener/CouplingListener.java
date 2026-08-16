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

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.item.RtItems;
import org.xpfarm.redstonetrain.item.RtKeys;
import org.xpfarm.redstonetrain.train.Train;
import org.xpfarm.redstonetrain.train.TrainCodec;
import org.xpfarm.redstonetrain.train.TrainRegistry;

/**
 * Auto-coupling: placed locomotive minecarts become new single-member trains, plain
 * minecarts placed on connected rail adjacent to a train member couple onto that train,
 * and destroyed members uncouple (a destroyed mid-car also frees every car behind it;
 * a destroyed locomotive dissolves the whole train).
 *
 * <p>Geyser/Bedrock safety: everything here is server-side event handling and PDC
 * state — no client-specific packets, resources, or display tricks — so Bedrock
 * players joining through Geyser/Floodgate get identical behavior.
 *
 * <p>Locomotive placement detection: vanilla minecart placement does <em>not</em> copy
 * the item's PDC onto the spawned entity, so {@link PlayerInteractEvent} records the
 * clicked rail block whenever a Locomotive item is used on it, and the
 * {@link VehicleCreateEvent} that follows (same tick) claims that pending placement and
 * tags the new entity via {@link TrainCodec#write}. The pending marker expires after
 * one tick so a cancelled placement cannot leak into a later vanilla cart.
 * <em>Gate-7a runtime checks:</em> confirm the created cart's block equals the clicked
 * rail block on a live server, and confirm chunk unloads do not uncouple (see
 * {@link #onEntityRemoveFromWorld}).
 *
 * <p>The rail-adjacency decision ({@link #isRailConnected}) and the couple/uncouple
 * decisions ({@link #coupleToAdjacentTrain}, {@link #uncoupleMember}) are pure with
 * respect to the server and unit-tested headless; the event handlers only gather world
 * facts (nearby carts, rail shapes) and persist results.
 */
public final class CouplingListener implements Listener {

    private final Plugin plugin;
    private final RtConfig config;
    private final TrainRegistry registry;
    private final TrainCodec codec;
    private final RtItems items;
    private final RtKeys keys;

    /**
     * Rail blocks a Locomotive item was just used on; consumed by the
     * {@link VehicleCreateEvent} of the same tick, swept one tick later.
     */
    private final Set<Location> pendingLocomotivePlacements = new HashSet<>();

    public CouplingListener(Plugin plugin, RtConfig config, TrainRegistry registry,
                            TrainCodec codec, RtItems items, RtKeys keys) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.items = Objects.requireNonNull(items, "items");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    // ------------------------------------------------------------------ events

    /**
     * Records "a Locomotive item is being placed on this rail block" so the vehicle
     * created moments later can be tagged. Runs at MONITOR so protection plugins have
     * already had their say.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.useItemInHand() == Event.Result.DENY
                || !items.isLocomotive(event.getItem())) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !(clicked.getBlockData() instanceof Rail)) {
            return;
        }
        Location key = clicked.getLocation();
        pendingLocomotivePlacements.add(key);
        // Sweep next tick: if no VehicleCreateEvent claimed it (placement failed or was
        // cancelled downstream), the marker must not linger for future placements.
        plugin.getServer().getScheduler().runTask(plugin,
                () -> pendingLocomotivePlacements.remove(key));
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) {
            return;
        }
        if (pendingLocomotivePlacements.remove(cart.getLocation().getBlock().getLocation())) {
            registerNewLocomotive(cart);
            return;
        }
        if (codec.isLocomotiveEntity(cart)) {
            // Already tagged (restored by some other path): never couple a locomotive
            // as a car. Re-registration from PDC is the persistence task's concern.
            return;
        }
        tryCoupleAdjacent(cart);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            handleMemberRemoved(cart.getUniqueId());
        }
    }

    /**
     * Safety net for removals that bypass {@link VehicleDestroyEvent} (e.g. {@code /kill},
     * void, plugin removal). Chunk unloads also fire this event but leave the entity
     * alive ({@code !isDead()}), so they are ignored and trains survive unloaded chunks.
     * <em>Gate-7a runtime check:</em> verify the isDead guard on a live server.
     */
    @EventHandler
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Minecart cart && cart.isDead()) {
            handleMemberRemoved(cart.getUniqueId());
        }
    }

    // ------------------------------------------------------- world-facing glue

    /** A freshly placed Locomotive: new single-member train with the craft-start charge. */
    private void registerNewLocomotive(Minecart cart) {
        Train train = new Train(cart.getUniqueId());
        train.setCharge(config.craftStart());
        registry.register(train);
        codec.write(cart, train);
    }

    /**
     * Gathers the minecarts within one block that sit on rail-connected neighbor blocks
     * (per the new cart's rail shape, including one up/down for ascending slopes), then
     * runs the pure coupling decision and persists the consist on success.
     */
    private void tryCoupleAdjacent(Minecart cart) {
        Block block = cart.getLocation().getBlock();
        if (!(block.getBlockData() instanceof Rail rail)) {
            return;
        }
        List<UUID> adjacentCarts = new ArrayList<>();
        for (Entity nearby : cart.getNearbyEntities(1.0, 1.0, 1.0)) {
            if (!(nearby instanceof Minecart other)) {
                continue;
            }
            Block otherBlock = other.getLocation().getBlock();
            if (isRailConnected(rail.getShape(),
                    otherBlock.getX() - block.getX(),
                    otherBlock.getY() - block.getY(),
                    otherBlock.getZ() - block.getZ())) {
                adjacentCarts.add(other.getUniqueId());
            }
        }
        Train train = coupleToAdjacentTrain(registry, cart.getUniqueId(), adjacentCarts);
        if (train != null) {
            persist(train);
        }
    }

    /** Uncouples a removed member and persists whatever train survives. */
    private void handleMemberRemoved(UUID entityId) {
        UncoupleResult result = uncoupleMember(registry, entityId);
        if (result != null && !result.trainRemoved()) {
            persist(result.train());
        }
        // A removed locomotive takes its PDC with it; freed cars are plain vanilla
        // minecarts and carry no train state, so there is nothing else to write.
    }

    /** Writes the train's state to its locomotive entity's PDC, if it is resolvable. */
    private void persist(Train train) {
        if (plugin.getServer().getEntity(train.locomotiveId()) instanceof Minecart loco) {
            codec.write(loco, train);
        }
    }

    // ------------------------------------------------- pure, headless-testable

    /**
     * Pure rail-adjacency decision: is the block at offset ({@code dx}, {@code dy},
     * {@code dz}) from a rail of this shape one of the two blocks the rail connects to?
     *
     * <p>Minecraft axes: north is -Z, south is +Z, east is +X, west is -X. An ascending
     * shape's high end connects only one block <em>up</em> in the ascent direction; every
     * level end also accepts the neighbor one block <em>down</em>, because that neighbor
     * can be an ascending rail rising into this one. The same block (0,0,0) is never
     * "connected" — adjacency means a neighboring block.
     */
    static boolean isRailConnected(Rail.Shape shape, int dx, int dy, int dz) {
        return switch (shape) {
            case NORTH_SOUTH -> level(dx, dy, dz, 0, -1) || level(dx, dy, dz, 0, 1);
            case EAST_WEST -> level(dx, dy, dz, 1, 0) || level(dx, dy, dz, -1, 0);
            case ASCENDING_EAST -> rising(dx, dy, dz, 1, 0) || level(dx, dy, dz, -1, 0);
            case ASCENDING_WEST -> rising(dx, dy, dz, -1, 0) || level(dx, dy, dz, 1, 0);
            case ASCENDING_NORTH -> rising(dx, dy, dz, 0, -1) || level(dx, dy, dz, 0, 1);
            case ASCENDING_SOUTH -> rising(dx, dy, dz, 0, 1) || level(dx, dy, dz, 0, -1);
            case SOUTH_EAST -> level(dx, dy, dz, 0, 1) || level(dx, dy, dz, 1, 0);
            case SOUTH_WEST -> level(dx, dy, dz, 0, 1) || level(dx, dy, dz, -1, 0);
            case NORTH_WEST -> level(dx, dy, dz, 0, -1) || level(dx, dy, dz, -1, 0);
            case NORTH_EAST -> level(dx, dy, dz, 0, -1) || level(dx, dy, dz, 1, 0);
        };
    }

    /** A level connection end: same height, or one down (a rail ascending into us). */
    private static boolean level(int dx, int dy, int dz, int endX, int endZ) {
        return dx == endX && dz == endZ && (dy == 0 || dy == -1);
    }

    /** An ascending connection end: exactly one block up in the ascent direction. */
    private static boolean rising(int dx, int dy, int dz, int endX, int endZ) {
        return dx == endX && dz == endZ && dy == 1;
    }

    /**
     * Coupling decision, headless-testable: given the new cart and the candidate carts
     * found on rail-connected neighbor blocks, append the cart to the first candidate's
     * train (back of the consist — MVP behavior) through the registry's atomic
     * {@link TrainRegistry#coupleCar} so {@code byCar} resolves immediately.
     *
     * @return the train the cart was coupled to, or {@code null} if it stays a free cart
     */
    static @Nullable Train coupleToAdjacentTrain(TrainRegistry registry, UUID cartId,
                                                 List<UUID> adjacentCartIds) {
        if (registry.byMember(cartId) != null) {
            return null; // already a locomotive or a coupled car of some train
        }
        for (UUID candidate : adjacentCartIds) {
            Train train = registry.byMember(candidate);
            if (train != null) {
                registry.coupleCar(train, cartId);
                return train;
            }
        }
        return null;
    }

    /**
     * Uncoupling decision, headless-testable. A removed locomotive unregisters its whole
     * train (all cars become free); a removed car detaches itself <em>and every car
     * behind it</em> through the registry's atomic {@link TrainRegistry#uncoupleTail}.
     *
     * @return what changed, or {@code null} if the entity was not a train member
     */
    static @Nullable UncoupleResult uncoupleMember(TrainRegistry registry, UUID memberId) {
        Train asLocomotive = registry.byLocomotive(memberId);
        if (asLocomotive != null) {
            registry.unregister(memberId);
            return new UncoupleResult(asLocomotive, true, List.copyOf(asLocomotive.cars()));
        }
        Train owner = registry.byCar(memberId);
        if (owner == null) {
            return null;
        }
        int index = owner.cars().indexOf(memberId);
        List<UUID> detached = registry.uncoupleTail(owner, index);
        return new UncoupleResult(owner, false, detached);
    }

    /**
     * Outcome of {@link #uncoupleMember}.
     *
     * @param train the affected train (unregistered when {@code trainRemoved})
     * @param trainRemoved true when the removed member was the locomotive
     * @param detachedCars every car that left the train, front to back (includes the
     *     removed member itself when it was a car)
     */
    record UncoupleResult(Train train, boolean trainRemoved, List<UUID> detachedCars) {
    }
}
