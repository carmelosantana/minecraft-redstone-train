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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.xpfarm.redstonetrain.config.RtConfig;

/** Tests for {@link ChargeModel}: drain, gain, trickle, feeding, and the movement gate. */
class ChargeModelTest {

    private static final double EPS = 1e-9;

    /** Shipped defaults, constructed directly so no Bukkit is involved. */
    private static RtConfig defaults() {
        return withChargeMax(100.0);
    }

    private static RtConfig withChargeMax(double chargeMax) {
        return new RtConfig(0.40, 0.36, 2, 0.02, 0.10, 6, 0.06, 60, chargeMax, 0.2,
                Math.min(50.0, chargeMax), 10.0, 90.0, 2.0, 0.5, true,
                Map.of("slow", 0.5, "cruise", 1.0));
    }

    // --- drain ----------------------------------------------------------------------------

    @Test
    void drainSubtractsPerBlock() {
        // 0.2 charge per block: 10 blocks cost 2.0 charge.
        assertEquals(48.0, ChargeModel.drain(50.0, 10.0, defaults()), EPS);
        assertEquals(49.9, ChargeModel.drain(50.0, 0.5, defaults()), EPS);
    }

    @Test
    void drainZeroBlocksIsIdentity() {
        assertEquals(50.0, ChargeModel.drain(50.0, 0.0, defaults()), EPS);
    }

    @Test
    void drainFloorsAtZero() {
        assertEquals(0.0, ChargeModel.drain(1.0, 10.0, defaults()), EPS);
        assertEquals(0.0, ChargeModel.drain(0.0, 5.0, defaults()), EPS);
        assertTrue(ChargeModel.drain(0.5, 1000.0, defaults()) >= 0.0);
    }

    @Test
    void drainExactlyToZero() {
        // 2.0 charge / 0.2 per block = exactly 10 blocks.
        assertEquals(0.0, ChargeModel.drain(2.0, 10.0, defaults()), EPS);
    }

    // --- gainOverRail ---------------------------------------------------------------------

    @Test
    void gainOverRailAddsPerBlock() {
        // 0.5 charge per block: 10 blocks add 5.0 charge.
        assertEquals(55.0, ChargeModel.gainOverRail(50.0, 10.0, defaults()), EPS);
    }

    @Test
    void gainOverRailClampsAtChargeMax() {
        assertEquals(100.0, ChargeModel.gainOverRail(99.9, 10.0, defaults()), EPS);
        assertEquals(100.0, ChargeModel.gainOverRail(100.0, 1.0, defaults()), EPS);
    }

    @Test
    void gainOverRailWithZeroChargeMaxStaysAtZero() {
        RtConfig cfg = withChargeMax(0.0);
        assertEquals(0.0, ChargeModel.gainOverRail(0.0, 10.0, cfg), EPS);
    }

    // --- idleTrickle ----------------------------------------------------------------------

    @Test
    void idleTrickleAddsPerSecond() {
        // 2.0 charge per second: 5 seconds add 10.0 charge.
        assertEquals(60.0, ChargeModel.idleTrickle(50.0, 5.0, defaults()), EPS);
        assertEquals(51.0, ChargeModel.idleTrickle(50.0, 0.5, defaults()), EPS);
    }

    @Test
    void idleTrickleClampsAtChargeMax() {
        assertEquals(100.0, ChargeModel.idleTrickle(99.0, 60.0, defaults()), EPS);
        assertEquals(100.0, ChargeModel.idleTrickle(100.0, 1.0, defaults()), EPS);
    }

    @Test
    void idleTrickleWithZeroChargeMaxStaysAtZero() {
        RtConfig cfg = withChargeMax(0.0);
        assertEquals(0.0, ChargeModel.idleTrickle(0.0, 30.0, cfg), EPS);
    }

    // --- addRedstone ----------------------------------------------------------------------

    @Test
    void addRedstoneDustAddsDustValue() {
        assertEquals(60.0, ChargeModel.addRedstone(50.0, false, defaults()), EPS);
    }

    @Test
    void addRedstoneBlockAddsBlockValue() {
        assertEquals(90.0, ChargeModel.addRedstone(0.0, true, defaults()), EPS);
    }

    @Test
    void addRedstoneClampsAtChargeMax() {
        assertEquals(100.0, ChargeModel.addRedstone(95.0, false, defaults()), EPS);
        assertEquals(100.0, ChargeModel.addRedstone(50.0, true, defaults()), EPS);
        assertEquals(100.0, ChargeModel.addRedstone(100.0, true, defaults()), EPS);
    }

    @Test
    void addRedstoneWithZeroChargeMaxStaysAtZero() {
        RtConfig cfg = withChargeMax(0.0);
        assertEquals(0.0, ChargeModel.addRedstone(0.0, false, cfg), EPS);
        assertEquals(0.0, ChargeModel.addRedstone(0.0, true, cfg), EPS);
    }

    // --- canMove --------------------------------------------------------------------------

    @Test
    void canMoveIsStrictlyPositive() {
        assertTrue(ChargeModel.canMove(0.001));
        assertTrue(ChargeModel.canMove(100.0));
        assertFalse(ChargeModel.canMove(0.0));
        assertFalse(ChargeModel.canMove(-1.0));
    }

    @Test
    void canMoveIsFalseAfterDrainingDry() {
        double charge = ChargeModel.drain(2.0, 10.0, defaults());
        assertFalse(ChargeModel.canMove(charge));
    }
}
