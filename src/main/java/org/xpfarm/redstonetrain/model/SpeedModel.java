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
package org.xpfarm.redstonetrain.model;

import org.jetbrains.annotations.Nullable;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.train.Train;

/**
 * Pure speed math for a train, in blocks per tick. No Bukkit types; every method is a
 * function of its numeric inputs and the {@link RtConfig} snapshot.
 *
 * <p>This class is also the single home of preset-name resolution
 * ({@link #fallbackPreset}, {@link #presetMultiplier}): the movement controller, the
 * boss bar, and the {@code /redstonetrain} command all resolve a train's preset name
 * to its multiplier here, with one consistent fallback rule.
 */
public final class SpeedModel {

    private SpeedModel() {
        // Static utility; not instantiable.
    }

    /**
     * Cruise speed for a locomotive pulling {@code cars} coupled cars:
     * {@code clamp(baseCruise - max(0, cars - freeCars) * penaltyPerCar, floor, cap)}.
     *
     * @param cars number of coupled cars (the locomotive itself is not a car)
     * @param cfg the config snapshot
     * @return cruise speed in blocks per tick, within {@code [floor, cap]}
     */
    public static double cruise(int cars, RtConfig cfg) {
        int penalizedCars = Math.max(0, cars - cfg.freeCars());
        double speed = cfg.baseCruise() - penalizedCars * cfg.penaltyPerCar();
        return Math.min(Math.max(speed, cfg.floor()), cfg.cap());
    }

    /**
     * Applies a Train Wrench preset multiplier to a cruise speed:
     * {@code min(cruise * presetMultiplier, cap)}.
     *
     * @param cruise the current cruise speed in blocks per tick
     * @param presetMultiplier the preset's multiplier (e.g. 0.5 for "slow")
     * @param cfg the config snapshot
     * @return the adjusted speed, never above {@code cap}
     */
    public static double withPreset(double cruise, double presetMultiplier, RtConfig cfg) {
        return Math.min(cruise * presetMultiplier, cfg.cap());
    }

    /**
     * Adds the remaining powered-rail boost on top of the current speed:
     * {@code min(current + boostRemaining, cap)}.
     *
     * @param current the current speed in blocks per tick
     * @param boostRemaining the remaining boost in blocks per tick
     * @param cfg the config snapshot
     * @return the boosted speed, never above {@code cap}
     */
    public static double applyBoost(double current, double boostRemaining, RtConfig cfg) {
        return Math.min(current + boostRemaining, cfg.cap());
    }

    /**
     * Decays the remaining boost linearly toward zero over {@code boostDecayTicks} ticks,
     * subtracting {@code poweredRailBoost / boostDecayTicks} per call, floored at 0.
     *
     * <p>If {@code boostDecayTicks} is 0 the boost decays instantly to 0 (no division).
     *
     * @param boostRemaining the boost left before this tick, in blocks per tick
     * @param cfg the config snapshot
     * @return the boost left after this tick, never negative
     */
    public static double decayBoost(double boostRemaining, RtConfig cfg) {
        int ticks = cfg.boostDecayTicks();
        if (ticks <= 0) {
            return 0.0;
        }
        double step = cfg.poweredRailBoost() / ticks;
        return Math.max(0.0, boostRemaining - step);
    }

    // ------------------------------------------------------- preset resolution

    /**
     * The preset name a train should default to: {@link Train#DEFAULT_SPEED_PRESET}
     * when configured, otherwise the <em>first</em> configured preset (config order),
     * otherwise {@link Train#DEFAULT_SPEED_PRESET} as a harmless label when no presets
     * exist at all.
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
     * Multiplier for a train's preset name — the single shared resolver (movement
     * controller, boss bar, and command all call this): the named preset if configured,
     * else the {@link #fallbackPreset} multiplier, else {@code 1.0} when no presets are
     * configured at all — never a crash.
     */
    public static double presetMultiplier(@Nullable String preset, RtConfig cfg) {
        if (preset != null) {
            Double multiplier = cfg.speedPresets().get(preset);
            if (multiplier != null) {
                return multiplier;
            }
        }
        Double fallback = cfg.speedPresets().get(fallbackPreset(cfg));
        return fallback != null ? fallback : 1.0;
    }
}
