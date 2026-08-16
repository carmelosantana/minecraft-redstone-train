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
package org.xpfarm.redstonetrain.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Minecart;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.model.ChargeModel;
import org.xpfarm.redstonetrain.model.SpeedModel;
import org.xpfarm.redstonetrain.train.Train;
import org.xpfarm.redstonetrain.train.TrainCodec;
import org.xpfarm.redstonetrain.train.TrainRegistry;

/**
 * The per-tick movement engine: moves every running train, applies charge drain/gain,
 * and handles the powered-rail boost. Schedule {@link #run()} at a fixed rate of one
 * call per server tick (Task 8 wiring registers it).
 *
 * <p><strong>Structure.</strong> Every per-tick <em>decision</em> is a pure static
 * helper delegating to {@link SpeedModel}/{@link ChargeModel} (no duplicated formulas,
 * unit-tested headless): {@link #targetSpeed}, {@link #nextBoost}, {@link #nextCharge},
 * {@link #followerSpeed}, {@link #presetMultiplier}, {@link #facingFromVelocity}.
 * The Bukkit entity I/O (resolving members, velocity, max speed, rail block data) lives
 * in thin private methods and is exercised at gate 7a on a live server.
 *
 * <p><strong>Held trains.</strong> If any member entity cannot be resolved (unloaded
 * chunk), the whole train is held this tick — nothing moves, nothing drains, the
 * consist is never torn apart. Displacement tracking resets so the next loaded tick
 * does not see a teleport-sized jump.
 *
 * <p><strong>Motion strategy</strong> (per research: prefer {@code setMaxSpeed} plus a
 * periodic impulse over hard-writing the lead's velocity every tick, which stutters):
 * the target speed caps every member via {@link Minecart#setMaxSpeed(double)}, and the
 * lead only receives a fresh impulse when its actual speed falls below
 * {@value #IMPULSE_THRESHOLD} of target. Followers are simpler physics-wise and get
 * their velocity set toward the member ahead at group speed plus a linear spring
 * correction toward {@link #TARGET_GAP} — a deliberate MVP simplification (a straight
 * pursuit spring, not a rail-path follower); gate 7a validates feel on curves/slopes.
 *
 * <p><strong>Inter-member collision</strong> is suppressed by Task 7's collision
 * listener (it cancels {@code VehicleEntityCollisionEvent} between members of the same
 * registered train); this controller deliberately does not duplicate that.
 *
 * <p>Geyser/Bedrock safety: pure server-side entity physics and PDC persistence — no
 * client-specific packets — so Bedrock players via Geyser/Floodgate see identical
 * behavior.
 */
public final class MovementController implements Runnable {

    /** Seconds per server tick, for {@link ChargeModel#idleTrickle}. */
    static final double TICK_SECONDS = 1.0 / 20.0;

    /** Desired center-to-center gap between consecutive members, in blocks. */
    static final double TARGET_GAP = 1.2;

    /** Spring gain: extra blocks/tick of follower speed per block of gap error. */
    static final double SPRING_GAIN = 0.1;

    /** Below this fraction of target speed the lead gets a fresh impulse. */
    static final double IMPULSE_THRESHOLD = 0.9;

    /** Horizontal speeds below this (blocks/tick) count as "at rest". */
    static final double REST_EPSILON = 1e-4;

    /** Persist charge/state to the locomotive PDC every this many ticks per train. */
    static final int PERSIST_INTERVAL_TICKS = 100;

    private final Plugin plugin;
    private final RtConfig config;
    private final TrainRegistry registry;
    private final TrainCodec codec;

    /** Transient per-locomotive runtime state (boost, last position, persist clock). */
    private final Map<UUID, LocoState> states = new HashMap<>();

    private long tick;

    public MovementController(Plugin plugin, RtConfig config, TrainRegistry registry,
                              TrainCodec codec) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    // ---------------------------------------------------------------- tick loop

    @Override
    public void run() {
        tick++;
        for (Train train : List.copyOf(registry.all())) {
            if (train.engineOn()) {
                tickTrain(train);
            }
        }
        if (tick % PERSIST_INTERVAL_TICKS == 0) {
            sweepStaleStates();
        }
    }

    /** One tick of one running train. Thin Bukkit glue around the pure helpers. */
    private void tickTrain(Train train) {
        List<Minecart> members = resolveMembers(train);
        if (members == null) {
            // A member is in an unloaded chunk: hold the whole train. Reset displacement
            // tracking so the next loaded tick measures from the fresh position.
            LocoState held = states.get(train.locomotiveId());
            if (held != null) {
                held.lastPosition = null;
            }
            return;
        }
        Minecart loco = members.getFirst();
        LocoState state = states.computeIfAbsent(train.locomotiveId(), id -> new LocoState());

        double blocksMoved = measureBlocksMoved(loco, state);
        boolean powered = isOverActivePoweredRail(loco);

        // --- pure decisions -------------------------------------------------
        double charge = nextCharge(train.charge(), blocksMoved, powered, config);
        state.boostRemaining = nextBoost(powered, state.boostRemaining, config);
        double target = targetSpeed(train.carCount(),
                presetMultiplier(train.speedPreset(), config),
                state.boostRemaining, charge, config);

        // --- entity I/O -----------------------------------------------------
        boolean depleted = !ChargeModel.canMove(charge);
        train.setCharge(charge);
        for (Minecart member : members) {
            // Depleted trains coast: keep the cap so momentum bleeds off naturally.
            member.setMaxSpeed(depleted ? config.cap() : Math.max(target, 0.0));
        }
        if (!depleted && target > 0.0) {
            driveLead(loco, train, target);
            driveFollowers(members, target);
        }
        persistIfDue(loco, train, state);
    }

    // ------------------------------------------------- pure, headless-testable

    /**
     * Target group speed this tick, in blocks per tick: {@code 0} when the battery is
     * empty (coast), otherwise cruise for the car count, times the preset multiplier,
     * plus remaining boost, all capped by {@link SpeedModel}.
     */
    static double targetSpeed(int cars, double presetMultiplier, double boostRemaining,
                              double charge, RtConfig cfg) {
        if (!ChargeModel.canMove(charge)) {
            return 0.0;
        }
        double cruise = SpeedModel.cruise(cars, cfg);
        double preset = SpeedModel.withPreset(cruise, presetMultiplier, cfg);
        return SpeedModel.applyBoost(preset, boostRemaining, cfg);
    }

    /**
     * Boost after this tick: refreshed to {@code poweredRailBoost} while over an active
     * powered rail, otherwise decayed via {@link SpeedModel#decayBoost} (which already
     * guards {@code boostDecayTicks == 0}).
     */
    static double nextBoost(boolean overActivePoweredRail, double boostRemaining,
                            RtConfig cfg) {
        if (overActivePoweredRail) {
            return cfg.poweredRailBoost();
        }
        return SpeedModel.decayBoost(boostRemaining, cfg);
    }

    /**
     * Charge after this tick. Over an active powered rail the charge never decreases:
     * {@link ChargeModel#gainOverRail} while moving, one tick of
     * {@link ChargeModel#idleTrickle} while parked (so a dead locomotive parked on a
     * powered rail recovers). Off powered rail a moving train drains
     * {@code drainPerBlock * blocksMoved}; a depleted train coasts without draining
     * further.
     */
    static double nextCharge(double charge, double blocksMoved,
                             boolean overActivePoweredRail, RtConfig cfg) {
        if (overActivePoweredRail) {
            if (blocksMoved > REST_EPSILON) {
                return ChargeModel.gainOverRail(charge, blocksMoved, cfg);
            }
            return ChargeModel.idleTrickle(charge, TICK_SECONDS, cfg);
        }
        if (!ChargeModel.canMove(charge)) {
            return charge; // already coasting: no further drain
        }
        return ChargeModel.drain(charge, blocksMoved, cfg);
    }

    /**
     * Follower speed: group speed plus a linear spring correction toward
     * {@link #TARGET_GAP} ({@code SPRING_GAIN} blocks/tick per block of gap error),
     * clamped to {@code [0, cap]}. Simple by design — see the class javadoc.
     */
    static double followerSpeed(double groupSpeed, double actualGap, RtConfig cfg) {
        double corrected = groupSpeed + SPRING_GAIN * (actualGap - TARGET_GAP);
        return Math.min(Math.max(corrected, 0.0), cfg.cap());
    }

    /** The preset's multiplier, or {@code 1.0} for an unknown/absent preset name. */
    static double presetMultiplier(@Nullable String preset, RtConfig cfg) {
        if (preset == null) {
            return 1.0;
        }
        Double multiplier = cfg.speedPresets().get(preset);
        return multiplier != null ? multiplier : 1.0;
    }

    /**
     * Dominant cardinal direction of a horizontal velocity as a {@link BlockFace} name
     * ({@code NORTH}/{@code SOUTH}/{@code EAST}/{@code WEST}), or {@code null} when at
     * rest. Minecraft axes: north is -Z, south is +Z, east is +X, west is -X.
     */
    static @Nullable String facingFromVelocity(double vx, double vz) {
        if (Math.abs(vx) < REST_EPSILON && Math.abs(vz) < REST_EPSILON) {
            return null;
        }
        if (Math.abs(vx) >= Math.abs(vz)) {
            return vx > 0 ? "EAST" : "WEST";
        }
        return vz > 0 ? "SOUTH" : "NORTH";
    }

    // ------------------------------------------------------ thin Bukkit glue
    // Everything below touches live entities/blocks and is validated at gate 7a.

    /**
     * Resolves the locomotive plus every car, front to back. Returns {@code null} if
     * any member is unresolvable (unloaded chunk or dead entity) — the caller holds
     * the whole train rather than tearing it apart.
     */
    private @Nullable List<Minecart> resolveMembers(Train train) {
        List<Minecart> members = new ArrayList<>(train.carCount() + 1);
        if (!(plugin.getServer().getEntity(train.locomotiveId()) instanceof Minecart loco)
                || !loco.isValid()) {
            return null;
        }
        members.add(loco);
        for (UUID carId : train.cars()) {
            if (!(plugin.getServer().getEntity(carId) instanceof Minecart car)
                    || !car.isValid()) {
                return null;
            }
            members.add(car);
        }
        return members;
    }

    /** Horizontal blocks the locomotive moved since last measured tick (0 on first). */
    private double measureBlocksMoved(Minecart loco, LocoState state) {
        Location now = loco.getLocation();
        double moved = 0.0;
        if (state.lastPosition != null && state.lastPosition.getWorld() == now.getWorld()) {
            double dx = now.getX() - state.lastPosition.getX();
            double dz = now.getZ() - state.lastPosition.getZ();
            moved = Math.hypot(dx, dz);
        }
        state.lastPosition = now;
        return moved;
    }

    /** True when the block under the locomotive is a powered rail that is lit. */
    private boolean isOverActivePoweredRail(Minecart loco) {
        Block block = loco.getLocation().getBlock();
        if (block.getType() != Material.POWERED_RAIL) {
            // Ascending rails put the cart slightly above the rail block.
            block = block.getRelative(BlockFace.DOWN);
        }
        return block.getType() == Material.POWERED_RAIL
                && block.getBlockData() instanceof Powerable rail
                && rail.isPowered();
    }

    /**
     * Impulse strategy for the lead: only push when actual speed sags below
     * {@link #IMPULSE_THRESHOLD} of target (avoids per-tick velocity stutter). The
     * travel axis comes from current velocity, or from the persisted
     * {@code LAST_FACING} when at rest; a never-moved train with no facing stays put
     * until an external nudge (e.g. the player's start push) sets one.
     */
    private void driveLead(Minecart loco, Train train, double target) {
        Vector velocity = loco.getVelocity();
        double speed = Math.hypot(velocity.getX(), velocity.getZ());
        String facing = facingFromVelocity(velocity.getX(), velocity.getZ());
        if (facing != null) {
            train.setLastFacing(facing);
        }
        if (speed >= target * IMPULSE_THRESHOLD) {
            return; // cruising fine under setMaxSpeed; no impulse needed
        }
        Vector direction;
        if (speed > REST_EPSILON) {
            direction = new Vector(velocity.getX() / speed, 0.0, velocity.getZ() / speed);
        } else if (train.lastFacing() != null) {
            direction = blockFaceDirection(train.lastFacing());
            if (direction == null) {
                return;
            }
        } else {
            return; // at rest with unknown facing: nothing sensible to push toward
        }
        loco.setVelocity(direction.multiply(target));
    }

    /** {@code LAST_FACING} name to a horizontal unit vector, or null if malformed. */
    private static @Nullable Vector blockFaceDirection(String facing) {
        try {
            Vector direction = BlockFace.valueOf(facing).getDirection();
            direction.setY(0.0);
            return direction.lengthSquared() > 0 ? direction.normalize() : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * Followers chase the member ahead: velocity toward it at
     * {@link #followerSpeed group speed + spring correction}. Rails constrain the
     * actual path, so the straight-line pursuit direction is only a steering hint.
     */
    private void driveFollowers(List<Minecart> members, double target) {
        for (int i = 1; i < members.size(); i++) {
            Minecart ahead = members.get(i - 1);
            Minecart follower = members.get(i);
            Location a = ahead.getLocation();
            Location f = follower.getLocation();
            double dx = a.getX() - f.getX();
            double dz = a.getZ() - f.getZ();
            double gap = Math.hypot(dx, dz);
            double speed = followerSpeed(target, gap, config);
            if (gap > REST_EPSILON && speed > 0.0) {
                Vector velocity = new Vector(dx / gap, 0.0, dz / gap).multiply(speed);
                velocity.setY(follower.getVelocity().getY()); // keep gravity's pull
                follower.setVelocity(velocity);
            }
        }
    }

    /** Writes charge/state to the locomotive PDC every {@link #PERSIST_INTERVAL_TICKS}. */
    private void persistIfDue(Minecart loco, Train train, LocoState state) {
        boolean chargeJustEmptied =
                !ChargeModel.canMove(train.charge()) && ChargeModel.canMove(state.lastPersistedCharge);
        if (chargeJustEmptied || tick - state.lastPersistTick >= PERSIST_INTERVAL_TICKS) {
            codec.write(loco, train);
            state.lastPersistTick = tick;
            state.lastPersistedCharge = train.charge();
        }
    }

    /** Drops transient state for trains that are no longer registered. */
    private void sweepStaleStates() {
        states.keySet().removeIf(locomotiveId -> registry.byLocomotive(locomotiveId) == null);
    }

    /** Transient runtime state for one locomotive; never persisted. */
    private static final class LocoState {
        double boostRemaining;
        @Nullable Location lastPosition;
        long lastPersistTick;
        double lastPersistedCharge;
    }
}
