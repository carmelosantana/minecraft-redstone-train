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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless unit tests for {@link TrainRegistry} both-way lookup consistency. */
class TrainRegistryTest {

    private final TrainRegistry registry = new TrainRegistry();

    private static Train trainWithCars(UUID loco, List<UUID> cars) {
        Train train = new Train(loco);
        cars.forEach(train::addCar);
        return train;
    }

    @Test
    void byLocomotiveFindsRegisteredTrain() {
        UUID loco = UUID.randomUUID();
        Train train = trainWithCars(loco, List.of(UUID.randomUUID()));
        registry.register(train);
        assertSame(train, registry.byLocomotive(loco));
    }

    @Test
    void byCarFindsOwningTrainForEveryCar() {
        UUID loco = UUID.randomUUID();
        List<UUID> cars = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Train train = trainWithCars(loco, cars);
        registry.register(train);
        for (UUID car : cars) {
            assertSame(train, registry.byCar(car));
        }
        assertNull(registry.byCar(loco), "locomotive id must not resolve through byCar");
    }

    @Test
    void byMemberFindsLocomotiveAndCars() {
        UUID loco = UUID.randomUUID();
        UUID car = UUID.randomUUID();
        Train train = trainWithCars(loco, List.of(car));
        registry.register(train);
        assertSame(train, registry.byMember(loco));
        assertSame(train, registry.byMember(car));
        assertNull(registry.byMember(UUID.randomUUID()));
    }

    @Test
    void unregisterClearsBothMaps() {
        UUID loco = UUID.randomUUID();
        List<UUID> cars = List.of(UUID.randomUUID(), UUID.randomUUID());
        Train train = trainWithCars(loco, cars);
        registry.register(train);

        assertSame(train, registry.unregister(loco));

        assertNull(registry.byLocomotive(loco));
        assertNull(registry.byMember(loco));
        for (UUID car : cars) {
            assertNull(registry.byCar(car), "car mapping leaked after unregister");
        }
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void unregisterUnknownLocomotiveReturnsNull() {
        assertNull(registry.unregister(UUID.randomUUID()));
    }

    @Test
    void unregisterClearsCarsAddedAfterRegistration() {
        UUID loco = UUID.randomUUID();
        Train train = trainWithCars(loco, List.of(UUID.randomUUID()));
        registry.register(train);

        UUID lateCar = UUID.randomUUID();
        train.addCar(lateCar);
        registry.register(train); // re-sync after consist change

        assertSame(train, registry.byCar(lateCar));
        registry.unregister(loco);
        assertNull(registry.byCar(lateCar), "late car leaked after unregister");
    }

    @Test
    void reRegisterDropsStaleCarMappings() {
        UUID loco = UUID.randomUUID();
        UUID removedCar = UUID.randomUUID();
        Train train = trainWithCars(loco, List.of(removedCar));
        registry.register(train);

        train.removeCar(removedCar);
        registry.register(train); // re-sync after consist change

        assertNull(registry.byCar(removedCar), "stale car mapping survived re-register");
        assertSame(train, registry.byLocomotive(loco));
    }

    @Test
    void registryTracksMultipleTrainsIndependently() {
        Train first = trainWithCars(UUID.randomUUID(), List.of(UUID.randomUUID()));
        Train second = trainWithCars(UUID.randomUUID(), List.of(UUID.randomUUID()));
        registry.register(first);
        registry.register(second);

        assertEquals(2, registry.all().size());
        registry.unregister(first.locomotiveId());
        assertEquals(1, registry.all().size());
        assertSame(second, registry.byLocomotive(second.locomotiveId()));
        assertSame(second, registry.byCar(second.cars().get(0)));
    }

    @Test
    void allViewIsUnmodifiable() {
        registry.register(trainWithCars(UUID.randomUUID(), List.of()));
        assertThrows(UnsupportedOperationException.class, () -> registry.all().clear());
    }

    @Test
    void coupleCarMutatesConsistAndIndexAtomically() {
        UUID loco = UUID.randomUUID();
        Train train = trainWithCars(loco, List.of());
        registry.register(train);

        UUID car = UUID.randomUUID();
        registry.coupleCar(train, car);

        assertEquals(List.of(car), train.cars());
        assertSame(train, registry.byCar(car),
                "byCar must resolve immediately after coupleCar, no re-register needed");
    }

    @Test
    void coupleCarOnUnregisteredTrainThrows() {
        Train unregistered = trainWithCars(UUID.randomUUID(), List.of());
        assertThrows(IllegalStateException.class,
                () -> registry.coupleCar(unregistered, UUID.randomUUID()));
    }

    @Test
    void coupleCarRejectsAMemberOfAnyTrain() {
        UUID loco = UUID.randomUUID();
        UUID existingCar = UUID.randomUUID();
        Train train = trainWithCars(loco, List.of(existingCar));
        registry.register(train);
        Train other = trainWithCars(UUID.randomUUID(), List.of());
        registry.register(other);

        assertThrows(IllegalArgumentException.class,
                () -> registry.coupleCar(other, existingCar), "car of another train");
        assertThrows(IllegalArgumentException.class,
                () -> registry.coupleCar(other, loco), "locomotive of another train");
        assertEquals(0, other.carCount());
    }

    @Test
    void uncoupleTailMutatesConsistAndIndexAtomically() {
        UUID loco = UUID.randomUUID();
        UUID front = UUID.randomUUID();
        UUID rear = UUID.randomUUID();
        Train train = trainWithCars(loco, List.of(front, rear));
        registry.register(train);

        List<UUID> tail = registry.uncoupleTail(train, 1);

        assertEquals(List.of(rear), tail);
        assertEquals(List.of(front), train.cars());
        assertSame(train, registry.byCar(front));
        assertNull(registry.byCar(rear),
                "byCar must stop resolving immediately after uncoupleTail");
    }

    @Test
    void uncoupleTailOnUnregisteredTrainThrows() {
        Train unregistered = trainWithCars(UUID.randomUUID(), List.of(UUID.randomUUID()));
        assertThrows(IllegalStateException.class,
                () -> registry.uncoupleTail(unregistered, 0));
    }

    @Test
    void pruneCarRemovesOnlyThatCarAndItsIndexEntry() {
        UUID loco = UUID.randomUUID();
        UUID front = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID rear = UUID.randomUUID();
        Train train = trainWithCars(loco, List.of(front, middle, rear));
        registry.register(train);

        assertTrue(registry.pruneCar(train, middle));

        assertEquals(List.of(front, rear), train.cars(),
                "cars behind the pruned one must stay coupled, unlike uncoupleTail");
        assertNull(registry.byCar(middle),
                "byCar must stop resolving immediately after pruneCar");
        assertSame(train, registry.byCar(front));
        assertSame(train, registry.byCar(rear));
    }

    @Test
    void pruneCarUnknownCarReturnsFalseAndChangesNothing() {
        UUID loco = UUID.randomUUID();
        UUID car = UUID.randomUUID();
        Train train = trainWithCars(loco, List.of(car));
        registry.register(train);

        assertFalse(registry.pruneCar(train, UUID.randomUUID()));
        assertEquals(List.of(car), train.cars());
        assertSame(train, registry.byCar(car));
    }

    @Test
    void pruneCarOnUnregisteredTrainThrows() {
        UUID car = UUID.randomUUID();
        Train unregistered = trainWithCars(UUID.randomUUID(), List.of(car));
        assertThrows(IllegalStateException.class,
                () -> registry.pruneCar(unregistered, car));
    }
}
