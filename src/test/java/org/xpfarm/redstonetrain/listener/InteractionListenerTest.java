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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.xpfarm.redstonetrain.train.Train;
import org.xpfarm.redstonetrain.train.TrainRegistry;

/**
 * Headless unit tests for {@link InteractionListener}'s pure decision helpers:
 * preset cycling, wrench uncoupling through the atomic registry helper, and the
 * same-train collision-suppression predicate. Event wiring (the actual
 * {@code PlayerInteractEntityEvent} handling, item consumption, boss bar rendering)
 * is validated at gate 7a on a live server.
 */
class InteractionListenerTest {

    // -------------------------------------------------------------- nextPreset

    @Test
    void nextPresetAdvancesInOrder() {
        assertEquals("cruise", InteractionListener.nextPreset("slow", List.of("slow", "cruise")));
    }

    @Test
    void nextPresetWrapsAroundAtTheEnd() {
        assertEquals("slow", InteractionListener.nextPreset("cruise", List.of("slow", "cruise")));
    }

    @Test
    void nextPresetCyclesThreePresetsInOrder() {
        List<String> presets = List.of("slow", "cruise", "fast");
        assertEquals("cruise", InteractionListener.nextPreset("slow", presets));
        assertEquals("fast", InteractionListener.nextPreset("cruise", presets));
        assertEquals("slow", InteractionListener.nextPreset("fast", presets));
    }

    @Test
    void nextPresetUnknownCurrentStartsAtFirst() {
        assertEquals("slow", InteractionListener.nextPreset("warp", List.of("slow", "cruise")));
    }

    @Test
    void nextPresetEmptyListKeepsCurrent() {
        assertEquals("cruise", InteractionListener.nextPreset("cruise", List.of()));
    }

    @Test
    void nextPresetSinglePresetWrapsToItself() {
        assertEquals("only", InteractionListener.nextPreset("only", List.of("only")));
    }

    // ------------------------------------------------- uncouple via registry

    private record Rig(TrainRegistry registry, Train train, UUID loco, List<UUID> cars) {
        static Rig withCars(int count) {
            TrainRegistry registry = new TrainRegistry();
            UUID loco = UUID.randomUUID();
            Train train = new Train(loco);
            registry.register(train);
            List<UUID> cars = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                UUID car = UUID.randomUUID();
                registry.coupleCar(train, car);
                cars.add(car);
            }
            return new Rig(registry, train, loco, cars);
        }
    }

    @Test
    void uncoupleClickedCarDetachesItAndItsTailAndClearsByCar() {
        Rig rig = Rig.withCars(3);
        UUID a = rig.cars().get(0);
        UUID b = rig.cars().get(1);
        UUID c = rig.cars().get(2);

        List<UUID> detached = InteractionListener.uncoupleFromMember(rig.registry(), b);

        assertEquals(List.of(b, c), detached);
        assertNull(rig.registry().byCar(b), "byCar must go stale-free immediately");
        assertNull(rig.registry().byCar(c), "byCar must go stale-free immediately");
        assertSame(rig.train(), rig.registry().byCar(a), "front car stays coupled");
        assertEquals(List.of(a), rig.train().cars());
    }

    @Test
    void uncoupleAtLocomotiveFreesEveryCarButKeepsTrainRegistered() {
        Rig rig = Rig.withCars(2);

        List<UUID> detached = InteractionListener.uncoupleFromMember(rig.registry(), rig.loco());

        assertEquals(rig.cars(), detached);
        assertEquals(0, rig.train().carCount());
        assertSame(rig.train(), rig.registry().byLocomotive(rig.loco()));
        for (UUID car : rig.cars()) {
            assertNull(rig.registry().byCar(car));
        }
    }

    @Test
    void uncoupleLocomotiveWithNoCarsDetachesNothing() {
        Rig rig = Rig.withCars(0);
        assertEquals(List.of(), InteractionListener.uncoupleFromMember(rig.registry(), rig.loco()));
        assertSame(rig.train(), rig.registry().byLocomotive(rig.loco()));
    }

    @Test
    void uncoupleNonMemberDetachesNothing() {
        Rig rig = Rig.withCars(1);
        assertEquals(List.of(),
                InteractionListener.uncoupleFromMember(rig.registry(), UUID.randomUUID()));
        assertEquals(1, rig.train().carCount());
    }

    // ------------------------------------------------------ collision predicate

    @Test
    void sameTrainTrueForLocomotiveAndItsCar() {
        Rig rig = Rig.withCars(2);
        assertTrue(InteractionListener.sameTrain(rig.registry(), rig.loco(), rig.cars().get(1)));
        assertTrue(InteractionListener.sameTrain(rig.registry(),
                rig.cars().get(0), rig.cars().get(1)));
    }

    @Test
    void sameTrainFalseForMemberAndFreeCart() {
        Rig rig = Rig.withCars(1);
        assertFalse(InteractionListener.sameTrain(rig.registry(), rig.loco(), UUID.randomUUID()));
        assertFalse(InteractionListener.sameTrain(rig.registry(), UUID.randomUUID(), rig.loco()));
    }

    @Test
    void sameTrainFalseAcrossTwoDifferentTrains() {
        Rig one = Rig.withCars(1);
        UUID otherLoco = UUID.randomUUID();
        Train other = new Train(otherLoco);
        one.registry().register(other);
        assertFalse(InteractionListener.sameTrain(one.registry(), one.loco(), otherLoco));
    }

    @Test
    void sameTrainFalseForTwoFreeCarts() {
        Rig rig = Rig.withCars(0);
        assertFalse(InteractionListener.sameTrain(rig.registry(),
                UUID.randomUUID(), UUID.randomUUID()));
    }
}
