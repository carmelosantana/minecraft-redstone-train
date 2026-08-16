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
package org.xpfarm.redstonetrain.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.xpfarm.redstonetrain.config.RtConfig;

/** Headless unit tests for the pure {@link ChargeDisplay} helpers. */
class ChargeDisplayTest {

    /** Shipped defaults: chargeMax 100, baseCruise 0.36, presets slow 0.5 / cruise 1.0. */
    private static final RtConfig CFG = RtConfig.from(new YamlConfiguration());

    // ------------------------------------------------------------------ format

    @Test
    void formatMatchesSpecExample() {
        assertEquals("⚡ 72%  ·  6.4 b/s  ·  4 cars",
                ChargeDisplay.format(72.0, 100.0, 0.32, 4));
    }

    @Test
    void formatFullChargeBoundaryAndSingularCar() {
        assertEquals("⚡ 100%  ·  8.0 b/s  ·  1 car",
                ChargeDisplay.format(100.0, 100.0, 0.40, 1));
    }

    @Test
    void formatEmptyCharge() {
        assertEquals("⚡ 0%  ·  0.0 b/s  ·  0 cars",
                ChargeDisplay.format(0.0, 100.0, 0.0, 0));
    }

    @Test
    void formatGuardsZeroChargeMaxWithoutNaN() {
        assertEquals("⚡ 0%  ·  2.0 b/s  ·  2 cars",
                ChargeDisplay.format(50.0, 0.0, 0.10, 2));
    }

    @Test
    void formatRoundsPercentHalfUp() {
        assertEquals("⚡ 72%  ·  0.0 b/s  ·  0 cars",
                ChargeDisplay.format(71.5, 100.0, 0.0, 0));
        assertEquals("⚡ 71%  ·  0.0 b/s  ·  0 cars",
                ChargeDisplay.format(71.4, 100.0, 0.0, 0));
    }

    @Test
    void formatClampsPercentToHundred() {
        assertEquals("⚡ 100%  ·  0.0 b/s  ·  0 cars",
                ChargeDisplay.format(150.0, 100.0, 0.0, 0));
    }

    @Test
    void formatSpeedIsBlocksPerTickTimesTwentyOneDecimal() {
        assertEquals("⚡ 50%  ·  7.2 b/s  ·  3 cars",
                ChargeDisplay.format(50.0, 100.0, 0.36, 3));
    }

    // ---------------------------------------------------------------- progress

    @Test
    void progressIsChargeFraction() {
        assertEquals(0.5f, ChargeDisplay.progress(50.0, 100.0));
    }

    @Test
    void progressGuardsZeroChargeMax() {
        assertEquals(0.0f, ChargeDisplay.progress(50.0, 0.0));
    }

    @Test
    void progressClampsToUnitRange() {
        assertEquals(1.0f, ChargeDisplay.progress(150.0, 100.0));
        assertEquals(0.0f, ChargeDisplay.progress(-5.0, 100.0));
    }

    // ------------------------------------------------------------ displaySpeed

    @Test
    void displaySpeedZeroWhenEngineOff() {
        assertEquals(0.0, ChargeDisplay.displaySpeed(false, 50.0, 0, "cruise", CFG));
    }

    @Test
    void displaySpeedZeroWhenChargeEmpty() {
        assertEquals(0.0, ChargeDisplay.displaySpeed(true, 0.0, 0, "cruise", CFG));
    }

    @Test
    void displaySpeedAppliesPresetMultiplier() {
        // Lone locomotive cruise 0.36 at preset "slow" (0.5) = 0.18 blocks/tick.
        assertEquals(0.18, ChargeDisplay.displaySpeed(true, 50.0, 0, "slow", CFG), 1e-9);
    }

    @Test
    void displaySpeedUnknownPresetFallsBackToFullCruise() {
        assertEquals(0.36, ChargeDisplay.displaySpeed(true, 50.0, 0, "warp", CFG), 1e-9);
    }
}
