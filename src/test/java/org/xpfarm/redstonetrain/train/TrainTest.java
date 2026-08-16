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
package org.xpfarm.redstonetrain.train;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless unit tests for the {@link Train} model (UUIDs only, no entities). */
class TrainTest {

    private final UUID loco = UUID.randomUUID();

    private static List<UUID> freshCars(int n) {
        return java.util.stream.Stream.generate(UUID::randomUUID).limit(n).toList();
    }

    @Test
    void newTrainHasNoCarsAndDefaults() {
        Train train = new Train(loco);
        assertEquals(loco, train.locomotiveId());
        assertEquals(0, train.carCount());
        assertTrue(train.cars().isEmpty());
        assertEquals(0.0, train.charge());
        assertFalse(train.engineOn());
        assertEquals(Train.DEFAULT_SPEED_PRESET, train.speedPreset());
        assertNull(train.lastFacing());
    }

    @Test
    void addCarPreservesFrontToBackOrder() {
        Train train = new Train(loco);
        List<UUID> cars = freshCars(4);
        cars.forEach(train::addCar);
        assertEquals(cars, train.cars());
        assertEquals(4, train.carCount());
    }

    @Test
    void addCarRejectsNullDuplicateAndLocomotive() {
        Train train = new Train(loco);
        UUID car = UUID.randomUUID();
        train.addCar(car);
        assertThrows(NullPointerException.class, () -> train.addCar(null));
        assertThrows(IllegalArgumentException.class, () -> train.addCar(car));
        assertThrows(IllegalArgumentException.class, () -> train.addCar(loco));
        assertEquals(1, train.carCount());
    }

    @Test
    void removeMiddleCarKeepsRemainingOrder() {
        Train train = new Train(loco);
        List<UUID> cars = freshCars(3);
        cars.forEach(train::addCar);
        assertTrue(train.removeCar(cars.get(1)));
        assertEquals(List.of(cars.get(0), cars.get(2)), train.cars());
        assertEquals(2, train.carCount());
    }

    @Test
    void removeUnknownCarReturnsFalse() {
        Train train = new Train(loco);
        train.addCar(UUID.randomUUID());
        assertFalse(train.removeCar(UUID.randomUUID()));
        assertEquals(1, train.carCount());
    }

    @Test
    void uncoupleFromIndexReturnsTailAndLeavesHead() {
        Train train = new Train(loco);
        List<UUID> cars = freshCars(4);
        cars.forEach(train::addCar);

        List<UUID> tail = train.uncoupleFrom(2);

        assertEquals(cars.subList(2, 4), tail);
        assertEquals(cars.subList(0, 2), train.cars());
        assertEquals(2, train.carCount());
    }

    @Test
    void uncoupleFromZeroRemovesEveryCar() {
        Train train = new Train(loco);
        List<UUID> cars = freshCars(3);
        cars.forEach(train::addCar);

        List<UUID> tail = train.uncoupleFrom(0);

        assertEquals(cars, tail);
        assertEquals(0, train.carCount());
    }

    @Test
    void uncoupleFromInvalidIndexThrows() {
        Train train = new Train(loco);
        train.addCar(UUID.randomUUID());
        assertThrows(IndexOutOfBoundsException.class, () -> train.uncoupleFrom(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> train.uncoupleFrom(1));
    }

    @Test
    void carsViewIsUnmodifiable() {
        Train train = new Train(loco);
        train.addCar(UUID.randomUUID());
        assertThrows(UnsupportedOperationException.class, () -> train.cars().clear());
    }

    @Test
    void mutableStateRoundTrips() {
        Train train = new Train(loco);
        train.setCharge(123.5);
        train.setEngineOn(true);
        train.setSpeedPreset("slow");
        train.setLastFacing("NORTH");
        assertEquals(123.5, train.charge());
        assertTrue(train.engineOn());
        assertEquals("slow", train.speedPreset());
        assertEquals("NORTH", train.lastFacing());
    }

    @Test
    void speedPresetRejectsNull() {
        Train train = new Train(loco);
        assertThrows(NullPointerException.class, () -> train.setSpeedPreset(null));
    }
}
