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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.xpfarm.redstonetrain.config.RtConfig;

/** Tests for {@link SpeedModel} against the spec's exact speed table. */
class SpeedModelTest {

    private static final double EPS = 1e-9;

    /** Shipped defaults, constructed directly so no Bukkit is involved. */
    private static RtConfig defaults() {
        return config(0.40, 0.36, 2, 0.02, 0.10, 0.06, 60);
    }

    private static RtConfig config(double cap, double baseCruise, int freeCars,
            double penaltyPerCar, double floor, double poweredRailBoost, int boostDecayTicks) {
        return new RtConfig(cap, baseCruise, freeCars, penaltyPerCar, floor, 6,
                poweredRailBoost, boostDecayTicks, 100.0, 0.2, 50.0, 10.0, 90.0, 2.0, 0.5,
                true, Map.of("slow", 0.5, "cruise", 1.0));
    }

    // --- cruise: the spec table, verbatim -------------------------------------------------

    @Test
    void cruiseSpecTableVerbatim() {
        RtConfig cfg = defaults();
        assertEquals(0.36, SpeedModel.cruise(0, cfg), EPS); // 7.2 b/s
        assertEquals(0.36, SpeedModel.cruise(1, cfg), EPS); // 7.2 b/s
        assertEquals(0.36, SpeedModel.cruise(2, cfg), EPS); // 7.2 b/s
        assertEquals(0.32, SpeedModel.cruise(4, cfg), EPS); // 6.4 b/s
        assertEquals(0.28, SpeedModel.cruise(6, cfg), EPS); // 5.6 b/s
        assertEquals(0.24, SpeedModel.cruise(8, cfg), EPS); // 4.8 b/s
    }

    @Test
    void cruiseIntermediateCarCounts() {
        RtConfig cfg = defaults();
        assertEquals(0.34, SpeedModel.cruise(3, cfg), EPS);
        assertEquals(0.30, SpeedModel.cruise(5, cfg), EPS);
        assertEquals(0.26, SpeedModel.cruise(7, cfg), EPS);
    }

    @Test
    void cruiseVeryLargeCarCountClampsToFloor() {
        RtConfig cfg = defaults();
        assertEquals(0.10, SpeedModel.cruise(1000, cfg), EPS);
        assertEquals(0.10, SpeedModel.cruise(Integer.MAX_VALUE, cfg), EPS);
    }

    @Test
    void cruiseFirstCarCountAtFloor() {
        // 0.36 - (cars-2)*0.02 <= 0.10 at cars >= 15.
        RtConfig cfg = defaults();
        assertEquals(0.12, SpeedModel.cruise(14, cfg), EPS);
        assertEquals(0.10, SpeedModel.cruise(15, cfg), EPS);
        assertEquals(0.10, SpeedModel.cruise(16, cfg), EPS);
    }

    @Test
    void cruiseNeverExceedsCap() {
        RtConfig cfg = defaults();
        for (int cars = 0; cars <= 64; cars++) {
            double cruise = SpeedModel.cruise(cars, cfg);
            assertTrue(cruise <= cfg.cap() + EPS,
                    "cruise(" + cars + ")=" + cruise + " exceeds cap " + cfg.cap());
            assertTrue(cruise >= cfg.floor() - EPS,
                    "cruise(" + cars + ")=" + cruise + " below floor " + cfg.floor());
        }
    }

    @Test
    void cruiseClampsToCapWhenBaseCruiseAboveCap() {
        // Constructor does not enforce baseCruise <= cap; the model must still clamp.
        RtConfig cfg = config(0.40, 0.50, 2, 0.02, 0.10, 0.06, 60);
        assertEquals(0.40, SpeedModel.cruise(0, cfg), EPS);
    }

    @Test
    void cruiseNegativeCarCountTreatedAsNoPenalty() {
        // max(0, cars - freeCars) guards negative inputs too.
        assertEquals(0.36, SpeedModel.cruise(-1, defaults()), EPS);
    }

    // --- withPreset -----------------------------------------------------------------------

    @Test
    void withPresetHalvesCruise() {
        assertEquals(0.18, SpeedModel.withPreset(0.36, 0.5, defaults()), EPS);
    }

    @Test
    void withPresetIdentityMultiplier() {
        assertEquals(0.36, SpeedModel.withPreset(0.36, 1.0, defaults()), EPS);
    }

    @Test
    void withPresetClampsToCap() {
        assertEquals(0.40, SpeedModel.withPreset(0.36, 2.0, defaults()), EPS);
        assertEquals(0.40, SpeedModel.withPreset(0.40, 1.5, defaults()), EPS);
    }

    @Test
    void withPresetExactlyAtCapIsNotReduced() {
        // 0.20 * 2.0 lands exactly on the cap; it must pass through unreduced.
        assertEquals(0.40, SpeedModel.withPreset(0.20, 2.0, defaults()), EPS);
    }

    // --- applyBoost -----------------------------------------------------------------------

    @Test
    void applyBoostAddsOnTop() {
        assertEquals(0.30, SpeedModel.applyBoost(0.24, 0.06, defaults()), EPS);
    }

    @Test
    void applyBoostClampsToCap() {
        assertEquals(0.40, SpeedModel.applyBoost(0.36, 0.06, defaults()), EPS);
        assertEquals(0.40, SpeedModel.applyBoost(0.40, 0.06, defaults()), EPS);
    }

    @Test
    void applyBoostZeroBoostIsIdentity() {
        assertEquals(0.36, SpeedModel.applyBoost(0.36, 0.0, defaults()), EPS);
    }

    // --- decayBoost -----------------------------------------------------------------------

    @Test
    void decayBoostSubtractsOneStepPerTick() {
        RtConfig cfg = defaults(); // step = 0.06 / 60 = 0.001
        assertEquals(0.059, SpeedModel.decayBoost(0.06, cfg), EPS);
        assertEquals(0.001, SpeedModel.decayBoost(0.002, cfg), EPS);
    }

    @Test
    void decayBoostReachesZeroAfterExactlyDecayTicks() {
        RtConfig cfg = defaults();
        double boost = cfg.poweredRailBoost();
        for (int tick = 0; tick < cfg.boostDecayTicks(); tick++) {
            assertTrue(boost > 0, "boost hit zero early, at tick " + tick);
            boost = SpeedModel.decayBoost(boost, cfg);
        }
        assertEquals(0.0, boost, EPS);
    }

    @Test
    void decayBoostNeverGoesNegative() {
        RtConfig cfg = defaults();
        assertEquals(0.0, SpeedModel.decayBoost(0.0005, cfg), EPS);
        assertEquals(0.0, SpeedModel.decayBoost(0.0, cfg), EPS);
        assertTrue(SpeedModel.decayBoost(0.0, cfg) >= 0.0);
    }

    @Test
    void decayBoostZeroDecayTicksIsInstantDecayNotDivisionByZero() {
        RtConfig cfg = config(0.40, 0.36, 2, 0.02, 0.10, 0.06, 0);
        double result = SpeedModel.decayBoost(0.06, cfg);
        assertEquals(0.0, result, EPS);
        assertTrue(Double.isFinite(result), "decayBoost must not produce NaN/Infinity");
        assertEquals(0.0, SpeedModel.decayBoost(0.0, cfg), EPS);
    }
}
