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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.model.ChargeModel;
import org.xpfarm.redstonetrain.model.SpeedModel;

/**
 * Headless tests for {@link MovementController}'s pure per-tick decision helpers.
 * The Bukkit entity manipulation (velocity, max speed, rail block data, collision)
 * is exercised at gate 7a on a live server.
 */
class MovementControllerTest {

    private static final double EPS = 1e-9;

    /** Shipped defaults, constructed directly so no Bukkit is involved. */
    private static RtConfig defaults() {
        return new RtConfig(0.40, 0.36, 2, 0.02, 0.10, 6, 0.06, 60, 100.0, 0.2, 50.0,
                10.0, 90.0, 2.0, 0.5, true, Map.of("slow", 0.5, "cruise", 1.0));
    }

    /** Defaults but with the divide-by-zero-bait values the config legally allows. */
    private static RtConfig zeroedGuards() {
        return new RtConfig(0.40, 0.36, 2, 0.02, 0.10, 6, 0.06, 0, 0.0, 0.2, 0.0,
                10.0, 90.0, 2.0, 0.5, true, Map.of("slow", 0.5, "cruise", 1.0));
    }

    // ------------------------------------------------------------ target speed

    @Test
    void zeroChargeMeansTargetSpeedZero() {
        RtConfig cfg = defaults();
        assertEquals(0.0, MovementController.targetSpeed(0, 1.0, 0.0, 0.0, cfg), EPS);
        // Even with boost pending and few cars: no charge, no movement.
        assertEquals(0.0, MovementController.targetSpeed(0, 1.0, 0.06, 0.0, cfg), EPS);
        assertEquals(0.0, MovementController.targetSpeed(0, 1.0, 0.06, -1.0, cfg), EPS);
    }

    @Test
    void targetSpeedIsCruiseWithPresetAndBoost() {
        RtConfig cfg = defaults();
        // 2 cars: cruise 0.36; slow preset 0.5 -> 0.18; +0.02 boost -> 0.20.
        double expected = SpeedModel.applyBoost(
                SpeedModel.withPreset(SpeedModel.cruise(2, cfg), 0.5, cfg), 0.02, cfg);
        assertEquals(expected, MovementController.targetSpeed(2, 0.5, 0.02, 50.0, cfg), EPS);
        assertEquals(0.20, MovementController.targetSpeed(2, 0.5, 0.02, 50.0, cfg), EPS);
    }

    @Test
    void targetSpeedClampsToCap() {
        RtConfig cfg = defaults();
        // 0 cars cruise 0.36 + full boost 0.06 = 0.42 -> clamped at cap 0.40.
        assertEquals(cfg.cap(),
                MovementController.targetSpeed(0, 1.0, cfg.poweredRailBoost(), 50.0, cfg), EPS);
        // Absurd preset multiplier still clamps.
        assertEquals(cfg.cap(),
                MovementController.targetSpeed(0, 100.0, 0.0, 50.0, cfg), EPS);
    }

    // ------------------------------------------------------------ boost

    @Test
    void activePoweredRailSetsBoostToConfiguredValue() {
        RtConfig cfg = defaults();
        assertEquals(cfg.poweredRailBoost(),
                MovementController.nextBoost(true, 0.0, cfg), EPS);
        assertEquals(cfg.poweredRailBoost(),
                MovementController.nextBoost(true, 0.01, cfg), EPS);
    }

    @Test
    void offPoweredRailBoostDecaysViaSpeedModel() {
        RtConfig cfg = defaults();
        double before = cfg.poweredRailBoost();
        double after = MovementController.nextBoost(false, before, cfg);
        assertEquals(SpeedModel.decayBoost(before, cfg), after, EPS);
        assertTrue(after < before, "boost must shrink off powered rail");
    }

    @Test
    void boostDecayHandlesZeroDecayTicksWithoutDividing() {
        // boostDecayTicks == 0 is legal config; decays instantly, no ArithmeticException.
        assertEquals(0.0, MovementController.nextBoost(false, 0.06, zeroedGuards()), EPS);
    }

    // ------------------------------------------------------------ charge delta

    @Test
    void normalRailDrainsDrainPerBlockTimesBlocks() {
        RtConfig cfg = defaults();
        double next = MovementController.nextCharge(50.0, 0.4, false, cfg);
        assertEquals(50.0 - cfg.drainPerBlock() * 0.4, next, EPS);
        assertEquals(ChargeModel.drain(50.0, 0.4, cfg), next, EPS);
    }

    @Test
    void activePoweredRailChargeIsNonDecreasing() {
        RtConfig cfg = defaults();
        // Moving over active powered rail: gainOverRail, never a net drain.
        double moving = MovementController.nextCharge(50.0, 0.4, true, cfg);
        assertEquals(ChargeModel.gainOverRail(50.0, 0.4, cfg), moving, EPS);
        assertTrue(moving >= 50.0, "charge must not decrease over active powered rail");
        // Parked on active powered rail: one tick of idle trickle.
        double parked = MovementController.nextCharge(50.0, 0.0, true, cfg);
        assertEquals(ChargeModel.idleTrickle(50.0, 1.0 / 20.0, cfg), parked, EPS);
        assertTrue(parked >= 50.0, "idle trickle must not decrease charge");
    }

    @Test
    void depletedTrainCoastingDoesNotDrainFurther() {
        RtConfig cfg = defaults();
        // Coasting with zero charge on normal rail: no further drain, stays at 0.
        assertEquals(0.0, MovementController.nextCharge(0.0, 0.4, false, cfg), EPS);
    }

    @Test
    void depletedTrainStillRechargesOnActivePoweredRail() {
        RtConfig cfg = defaults();
        double parked = MovementController.nextCharge(0.0, 0.0, true, cfg);
        assertTrue(parked > 0.0, "a dead train parked on active powered rail must recharge");
    }

    @Test
    void chargeMaxZeroPinsEveryGainAtZero() {
        RtConfig cfg = zeroedGuards();
        assertEquals(0.0, MovementController.nextCharge(0.0, 0.4, true, cfg), EPS);
        assertEquals(0.0, MovementController.nextCharge(0.0, 0.0, true, cfg), EPS);
    }

    // ------------------------------------------------------------ follower spring

    @Test
    void followerAtTargetGapMatchesGroupSpeed() {
        assertEquals(0.30, MovementController.followerSpeed(0.30,
                MovementController.TARGET_GAP, defaults()), EPS);
    }

    @Test
    void followerTooFarBehindSpeedsUpTooCloseSlowsDown() {
        RtConfig cfg = defaults();
        double group = 0.30;
        double behind = MovementController.followerSpeed(group,
                MovementController.TARGET_GAP + 1.0, cfg);
        double close = MovementController.followerSpeed(group,
                MovementController.TARGET_GAP - 0.5, cfg);
        assertTrue(behind > group, "lagging follower must catch up");
        assertTrue(close < group, "crowding follower must ease off");
    }

    @Test
    void followerSpeedClampsToZeroAndCap() {
        RtConfig cfg = defaults();
        assertEquals(0.0, MovementController.followerSpeed(0.05, 0.0, cfg), EPS);
        assertEquals(cfg.cap(), MovementController.followerSpeed(0.38, 10.0, cfg), EPS);
    }

    // ------------------------------------------------------------ facing

    @Test
    void facingFromVelocityPicksDominantCardinal() {
        assertEquals("EAST", MovementController.facingFromVelocity(0.3, 0.1));
        assertEquals("WEST", MovementController.facingFromVelocity(-0.3, 0.1));
        assertEquals("SOUTH", MovementController.facingFromVelocity(0.1, 0.3));
        assertEquals("NORTH", MovementController.facingFromVelocity(0.1, -0.3));
    }

    @Test
    void facingFromVelocityIsNullAtRest() {
        assertNull(MovementController.facingFromVelocity(0.0, 0.0));
        assertNull(MovementController.facingFromVelocity(1e-6, -1e-6));
    }

    // -------------------------------------------- cold-start rail alignment

    @Test
    void railFacingsCoverEveryShapeWithTwoValidCardinals() {
        for (Rail.Shape shape : Rail.Shape.values()) {
            List<String> facings = MovementController.railFacings(shape);
            assertEquals(2, facings.size(), shape.name());
            for (String facing : facings) {
                assertTrue(List.of("NORTH", "SOUTH", "EAST", "WEST").contains(facing),
                        shape + " produced non-cardinal " + facing);
            }
        }
    }

    @Test
    void railFacingsStraightShapesListBothAxisEnds() {
        assertEquals(List.of("SOUTH", "NORTH"),
                MovementController.railFacings(Rail.Shape.NORTH_SOUTH));
        assertEquals(List.of("EAST", "WEST"),
                MovementController.railFacings(Rail.Shape.EAST_WEST));
    }

    @Test
    void alignFacingToRailKeepsAFacingAlreadyAlongTheRail() {
        assertEquals("NORTH", MovementController.alignFacingToRail(
                "NORTH", Rail.Shape.NORTH_SOUTH));
        assertEquals("SOUTH", MovementController.alignFacingToRail(
                "SOUTH", Rail.Shape.NORTH_SOUTH));
        assertEquals("EAST", MovementController.alignFacingToRail(
                "EAST", Rail.Shape.SOUTH_EAST));
    }

    @Test
    void alignFacingToRailSnapsAPerpendicularFacingOntoTheAxis() {
        // Player toggled the engine while facing across the track: depart along it.
        assertEquals("SOUTH", MovementController.alignFacingToRail(
                "EAST", Rail.Shape.NORTH_SOUTH));
        assertEquals("EAST", MovementController.alignFacingToRail(
                "NORTH", Rail.Shape.EAST_WEST));
    }

    @Test
    void alignFacingToRailDerivesDeterministicDefaultWhenFacingUnknown() {
        // The cold-start case: freshly placed loco, no LAST_FACING, straight rail.
        assertEquals("SOUTH", MovementController.alignFacingToRail(
                null, Rail.Shape.NORTH_SOUTH));
        assertEquals("EAST", MovementController.alignFacingToRail(
                null, Rail.Shape.EAST_WEST));
        assertEquals("SOUTH", MovementController.alignFacingToRail(
                null, Rail.Shape.SOUTH_WEST));
        assertEquals("WEST", MovementController.alignFacingToRail(
                null, Rail.Shape.ASCENDING_WEST));
    }

    @Test
    void alignFacingToRailOffRailKeepsWhateverFacingExists() {
        assertEquals("NORTH", MovementController.alignFacingToRail("NORTH", null));
        assertNull(MovementController.alignFacingToRail(null, null));
    }
}
