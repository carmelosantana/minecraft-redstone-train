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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Tests for {@link RtConfig} against the shipped defaults and the validation ranges. */
class RtConfigTest {

    private static YamlConfiguration yaml(String source) {
        return YamlConfiguration.loadConfiguration(new StringReader(source));
    }

    private static YamlConfiguration shippedDefaults() {
        InputStream in = RtConfigTest.class.getResourceAsStream("/config.yml");
        assertTrue(in != null, "shipped config.yml must be on the test classpath");
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static void assertDefaults(RtConfig config) {
        assertEquals(0.40, config.cap());
        assertEquals(0.36, config.baseCruise());
        assertEquals(2, config.freeCars());
        assertEquals(0.02, config.penaltyPerCar());
        assertEquals(0.10, config.floor());
        assertEquals(6, config.softCap());
        assertEquals(0.06, config.poweredRailBoost());
        assertEquals(60, config.boostDecayTicks());
        assertEquals(100.0, config.chargeMax());
        assertEquals(0.2, config.drainPerBlock());
        assertEquals(50.0, config.craftStart());
        assertEquals(10.0, config.redstoneDust());
        assertEquals(90.0, config.redstoneBlock());
        assertEquals(2.0, config.idleTricklePerSecond());
        assertEquals(0.5, config.poweredRailGainPerBlock());
        assertTrue(config.bossbarEnabled());
        assertEquals(Map.of("slow", 0.5, "cruise", 1.0), config.speedPresets());
    }

    @Test
    void loadsShippedDefaults() {
        assertDefaults(RtConfig.from(shippedDefaults()));
    }

    @Test
    void missingKeysFallBackToDefaults() {
        assertDefaults(RtConfig.from(yaml("")));
    }

    @Test
    void partialOverrideKeepsOtherDefaults() {
        RtConfig config = RtConfig.from(yaml("""
                speed:
                  cap: 0.38
                display:
                  bossbar-enabled: false
                """));
        assertEquals(0.38, config.cap());
        assertEquals(0.36, config.baseCruise());
        assertEquals(0.10, config.floor());
        assertFalse(config.bossbarEnabled());
    }

    @Test
    void speedPresetsParseIntoMap() {
        RtConfig config = RtConfig.from(yaml("""
                speed-presets:
                  slow: 0.5
                  cruise: 1.0
                  fast: 1.2
                """));
        assertEquals(Map.of("slow", 0.5, "cruise", 1.0, "fast", 1.2), config.speedPresets());
    }

    @Test
    void speedPresetsPreserveConfigOrder() {
        // The Train Wrench cycles presets in the order they appear in config.yml,
        // so the snapshot must preserve YAML insertion order.
        RtConfig config = RtConfig.from(yaml("""
                speed-presets:
                  crawl: 0.25
                  slow: 0.5
                  cruise: 1.0
                  fast: 1.2
                """));
        assertEquals(java.util.List.of("crawl", "slow", "cruise", "fast"),
                java.util.List.copyOf(config.speedPresets().keySet()));
    }

    @Test
    void defaultSpeedPresetsOrderSlowThenCruise() {
        RtConfig config = RtConfig.from(yaml(""));
        assertEquals(java.util.List.of("slow", "cruise"),
                java.util.List.copyOf(config.speedPresets().keySet()));
    }

    @Test
    void rejectsCapAboveOne() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("speed:\n  cap: 1.5\n")));
        assertTrue(ex.getMessage().contains("speed.cap"), ex.getMessage());
        assertTrue(ex.getMessage().contains("1.5"), ex.getMessage());
    }

    @Test
    void rejectsNonPositiveCap() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("""
                        speed:
                          cap: 0.0
                          base-cruise: 0.0
                          floor: 0.0
                        """)));
        assertTrue(ex.getMessage().contains("speed.cap"), ex.getMessage());
    }

    @Test
    void rejectsFloorAboveBaseCruise() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("speed:\n  floor: 0.37\n")));
        assertTrue(ex.getMessage().contains("speed.floor"), ex.getMessage());
    }

    @Test
    void rejectsBaseCruiseAboveCap() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("speed:\n  base-cruise: 0.41\n")));
        assertTrue(ex.getMessage().contains("speed.base-cruise"), ex.getMessage());
    }

    @Test
    void rejectsNegativePenaltyPerCar() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("speed:\n  penalty-per-car: -0.01\n")));
        assertTrue(ex.getMessage().contains("speed.penalty-per-car"), ex.getMessage());
    }

    @Test
    void rejectsNegativeFreeCars() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("speed:\n  free-cars: -1\n")));
        assertTrue(ex.getMessage().contains("speed.free-cars"), ex.getMessage());
    }

    @Test
    void rejectsSoftCapBelowOne() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("capacity:\n  soft-cap: 0\n")));
        assertTrue(ex.getMessage().contains("capacity.soft-cap"), ex.getMessage());
    }

    @Test
    void rejectsNegativeBoost() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("boost:\n  powered-rail: -0.06\n")));
        assertTrue(ex.getMessage().contains("boost.powered-rail"), ex.getMessage());
    }

    @Test
    void rejectsNegativeBoostDecayTicks() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("boost:\n  decay-ticks: -1\n")));
        assertTrue(ex.getMessage().contains("boost.decay-ticks"), ex.getMessage());
    }

    @Test
    void rejectsNegativeChargeValues() {
        for (String key : new String[] {
                "max", "drain-per-block", "craft-start", "redstone-dust",
                "redstone-block", "idle-trickle-per-second", "powered-rail-gain-per-block"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> RtConfig.from(yaml("charge:\n  " + key + ": -1.0\n")),
                    "charge." + key + " should reject a negative value");
            assertTrue(ex.getMessage().contains("charge." + key), ex.getMessage());
            assertTrue(ex.getMessage().contains("-1.0"), ex.getMessage());
        }
    }

    @Test
    void rejectsCraftStartAboveChargeMax() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("charge:\n  craft-start: 150.0\n")));
        assertTrue(ex.getMessage().contains("charge.craft-start"), ex.getMessage());
        assertTrue(ex.getMessage().contains("150"), ex.getMessage());
    }

    @Test
    void rejectsNonPositivePresetMultiplier() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RtConfig.from(yaml("speed-presets:\n  slow: 0.0\n")));
        assertTrue(ex.getMessage().contains("speed-presets.slow"), ex.getMessage());
    }

    @Test
    void speedPresetsMapIsImmutable() {
        RtConfig config = RtConfig.from(yaml(""));
        assertThrows(UnsupportedOperationException.class,
                () -> config.speedPresets().put("turbo", 2.0));
    }
}
