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
package org.xpfarm.redstonetrain.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable, validated snapshot of {@code config.yml}.
 *
 * <p>Pure logic: no file I/O and no server calls. Build one with
 * {@link #from(ConfigurationSection)} from a {@code FileConfiguration} (or any
 * {@code ConfigurationSection}) the caller loaded. Every downstream component reads
 * its values from this record.
 *
 * <p>All speeds are in blocks per tick; multiply by 20 for blocks per second.
 *
 * @param cap absolute speed ceiling for every train member, {@code 0 < cap <= 1.0}
 * @param baseCruise cruise speed of a lone locomotive, {@code floor <= baseCruise <= cap}
 * @param freeCars coupled cars that incur no speed penalty, {@code >= 0}
 * @param penaltyPerCar speed subtracted per car beyond {@code freeCars}, {@code >= 0}
 * @param floor hard speed floor for overloaded trains, {@code 0 <= floor <= baseCruise}
 * @param softCap soft car limit before slowdown toward the floor, {@code >= 1}
 * @param poweredRailBoost speed added when the lead crosses an active powered rail, {@code >= 0}
 * @param boostDecayTicks ticks over which the boost decays back to cruise, {@code >= 0}
 * @param chargeMax charge meter range, {@code >= 0}
 * @param drainPerBlock charge spent per block travelled, {@code >= 0}
 * @param craftStart charge a fresh locomotive starts with, {@code 0 <= craftStart <= chargeMax}
 * @param redstoneDust charge added per redstone dust fed to the locomotive, {@code >= 0}
 * @param redstoneBlock charge added per redstone block fed to the locomotive, {@code >= 0}
 * @param idleTricklePerSecond charge per second gained parked on an active powered rail,
 *     {@code >= 0}
 * @param poweredRailGainPerBlock charge gained per block moving over an active powered rail,
 *     {@code >= 0}
 * @param bossbarEnabled whether riders see the charge/speed boss bar
 * @param speedPresets Train Wrench presets, name to cruise-speed multiplier, each {@code > 0}
 */
public record RtConfig(
        double cap,
        double baseCruise,
        int freeCars,
        double penaltyPerCar,
        double floor,
        int softCap,
        double poweredRailBoost,
        int boostDecayTicks,
        double chargeMax,
        double drainPerBlock,
        double craftStart,
        double redstoneDust,
        double redstoneBlock,
        double idleTricklePerSecond,
        double poweredRailGainPerBlock,
        boolean bossbarEnabled,
        Map<String, Double> speedPresets) {

    /** Defensive copy so the record is deeply immutable regardless of the map passed in. */
    public RtConfig {
        speedPresets = Map.copyOf(speedPresets);
    }

    /**
     * Reads and validates a configuration section (typically the plugin's
     * {@code FileConfiguration}). Missing keys fall back to the shipped defaults.
     *
     * @param root the configuration root to read from
     * @return the validated, immutable configuration
     * @throws IllegalArgumentException if any value is outside its documented range;
     *     the message names the offending key and value
     */
    public static RtConfig from(ConfigurationSection root) {
        double cap = root.getDouble("speed.cap", 0.40);
        double baseCruise = root.getDouble("speed.base-cruise", 0.36);
        int freeCars = root.getInt("speed.free-cars", 2);
        double penaltyPerCar = root.getDouble("speed.penalty-per-car", 0.02);
        double floor = root.getDouble("speed.floor", 0.10);
        int softCap = root.getInt("capacity.soft-cap", 6);
        double poweredRailBoost = root.getDouble("boost.powered-rail", 0.06);
        int boostDecayTicks = root.getInt("boost.decay-ticks", 60);
        double chargeMax = root.getDouble("charge.max", 100.0);
        double drainPerBlock = root.getDouble("charge.drain-per-block", 0.2);
        double craftStart = root.getDouble("charge.craft-start", 50.0);
        double redstoneDust = root.getDouble("charge.redstone-dust", 10.0);
        double redstoneBlock = root.getDouble("charge.redstone-block", 90.0);
        double idleTricklePerSecond = root.getDouble("charge.idle-trickle-per-second", 2.0);
        double poweredRailGainPerBlock =
                root.getDouble("charge.powered-rail-gain-per-block", 0.5);
        boolean bossbarEnabled = root.getBoolean("display.bossbar-enabled", true);

        check(cap > 0 && cap <= 1.0, "speed.cap", cap, "must be > 0 and <= 1.0");
        check(baseCruise <= cap, "speed.base-cruise", baseCruise,
                "must be <= speed.cap (" + cap + ")");
        check(floor >= 0, "speed.floor", floor, "must be >= 0");
        check(floor <= baseCruise, "speed.floor", floor,
                "must be <= speed.base-cruise (" + baseCruise + ")");
        check(freeCars >= 0, "speed.free-cars", freeCars, "must be >= 0");
        check(penaltyPerCar >= 0, "speed.penalty-per-car", penaltyPerCar, "must be >= 0");
        check(softCap >= 1, "capacity.soft-cap", softCap, "must be >= 1");
        check(poweredRailBoost >= 0, "boost.powered-rail", poweredRailBoost, "must be >= 0");
        check(boostDecayTicks >= 0, "boost.decay-ticks", boostDecayTicks, "must be >= 0");
        check(chargeMax >= 0, "charge.max", chargeMax, "must be >= 0");
        check(drainPerBlock >= 0, "charge.drain-per-block", drainPerBlock, "must be >= 0");
        check(craftStart >= 0, "charge.craft-start", craftStart, "must be >= 0");
        check(craftStart <= chargeMax, "charge.craft-start", craftStart,
                "must be <= charge.max (" + chargeMax + ")");
        check(redstoneDust >= 0, "charge.redstone-dust", redstoneDust, "must be >= 0");
        check(redstoneBlock >= 0, "charge.redstone-block", redstoneBlock, "must be >= 0");
        check(idleTricklePerSecond >= 0, "charge.idle-trickle-per-second",
                idleTricklePerSecond, "must be >= 0");
        check(poweredRailGainPerBlock >= 0, "charge.powered-rail-gain-per-block",
                poweredRailGainPerBlock, "must be >= 0");

        Map<String, Double> speedPresets = readSpeedPresets(root);

        return new RtConfig(cap, baseCruise, freeCars, penaltyPerCar, floor, softCap,
                poweredRailBoost, boostDecayTicks, chargeMax, drainPerBlock, craftStart,
                redstoneDust, redstoneBlock, idleTricklePerSecond, poweredRailGainPerBlock,
                bossbarEnabled, speedPresets);
    }

    private static Map<String, Double> readSpeedPresets(ConfigurationSection root) {
        ConfigurationSection section = root.getConfigurationSection("speed-presets");
        if (section == null) {
            return Map.of("slow", 0.5, "cruise", 1.0);
        }
        Map<String, Double> presets = new LinkedHashMap<>();
        for (String name : section.getKeys(false)) {
            double multiplier = section.getDouble(name);
            check(multiplier > 0, "speed-presets." + name, section.get(name), "must be > 0");
            presets.put(name, multiplier);
        }
        return presets;
    }

    private static void check(boolean valid, String key, Object value, String requirement) {
        if (!valid) {
            throw new IllegalArgumentException(
                    "Invalid config value for '" + key + "': " + value + " (" + requirement + ")");
        }
    }
}
