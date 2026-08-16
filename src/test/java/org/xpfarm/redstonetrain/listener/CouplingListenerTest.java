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

import java.util.List;
import java.util.UUID;
import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.xpfarm.redstonetrain.train.Train;
import org.xpfarm.redstonetrain.train.TrainRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for {@link CouplingListener}'s pure rail-connection helper and
 * its registry-driven coupling/uncoupling decision logic.
 *
 * <p>Event wiring ({@code VehicleCreateEvent} firing, locomotive item placement
 * tagging, entity resolution) needs a live world and is deferred to gate 7a.
 * {@link Rail.Shape} is a plain enum, safe to use without a running server.
 */
class CouplingListenerTest {

    /** Minecraft axes: north = -Z, south = +Z, east = +X, west = -X. */
    @Nested
    class RailConnection {

        private static boolean connected(Rail.Shape shape, int dx, int dy, int dz) {
            return CouplingListener.isRailConnected(shape, dx, dy, dz);
        }

        @Test
        void northSouthConnectsNorthAndSouth() {
            assertTrue(connected(Rail.Shape.NORTH_SOUTH, 0, 0, -1), "north");
            assertTrue(connected(Rail.Shape.NORTH_SOUTH, 0, 0, 1), "south");
        }

        @Test
        void northSouthConnectsOneDownForDescendingNeighbors() {
            assertTrue(connected(Rail.Shape.NORTH_SOUTH, 0, -1, -1), "north, one down");
            assertTrue(connected(Rail.Shape.NORTH_SOUTH, 0, -1, 1), "south, one down");
        }

        @Test
        void northSouthRejectsEastWestUpAndDiagonals() {
            assertFalse(connected(Rail.Shape.NORTH_SOUTH, 1, 0, 0), "east");
            assertFalse(connected(Rail.Shape.NORTH_SOUTH, -1, 0, 0), "west");
            assertFalse(connected(Rail.Shape.NORTH_SOUTH, 0, 1, -1), "north, one up");
            assertFalse(connected(Rail.Shape.NORTH_SOUTH, 0, 1, 1), "south, one up");
            assertFalse(connected(Rail.Shape.NORTH_SOUTH, 1, 0, 1), "diagonal");
        }

        @Test
        void eastWestConnectsEastAndWestOnly() {
            assertTrue(connected(Rail.Shape.EAST_WEST, 1, 0, 0), "east");
            assertTrue(connected(Rail.Shape.EAST_WEST, -1, 0, 0), "west");
            assertTrue(connected(Rail.Shape.EAST_WEST, 1, -1, 0), "east, one down");
            assertFalse(connected(Rail.Shape.EAST_WEST, 0, 0, 1), "south");
            assertFalse(connected(Rail.Shape.EAST_WEST, 0, 0, -1), "north");
        }

        @Test
        void ascendingEastConnectsHighEastAndLowWest() {
            assertTrue(connected(Rail.Shape.ASCENDING_EAST, 1, 1, 0), "high end: east one up");
            assertTrue(connected(Rail.Shape.ASCENDING_EAST, -1, 0, 0), "low end: west level");
            assertTrue(connected(Rail.Shape.ASCENDING_EAST, -1, -1, 0), "low end: west one down");
            assertFalse(connected(Rail.Shape.ASCENDING_EAST, 1, 0, 0),
                    "east at same level is NOT connected on an ascending rail");
            assertFalse(connected(Rail.Shape.ASCENDING_EAST, -1, 1, 0), "west one up");
        }

        @Test
        void ascendingWestConnectsHighWestAndLowEast() {
            assertTrue(connected(Rail.Shape.ASCENDING_WEST, -1, 1, 0), "high end: west one up");
            assertTrue(connected(Rail.Shape.ASCENDING_WEST, 1, 0, 0), "low end: east level");
            assertFalse(connected(Rail.Shape.ASCENDING_WEST, -1, 0, 0), "west at same level");
        }

        @Test
        void ascendingNorthConnectsHighNorthAndLowSouth() {
            assertTrue(connected(Rail.Shape.ASCENDING_NORTH, 0, 1, -1), "high end: north one up");
            assertTrue(connected(Rail.Shape.ASCENDING_NORTH, 0, 0, 1), "low end: south level");
            assertFalse(connected(Rail.Shape.ASCENDING_NORTH, 0, 0, -1), "north at same level");
        }

        @Test
        void ascendingSouthConnectsHighSouthAndLowNorth() {
            assertTrue(connected(Rail.Shape.ASCENDING_SOUTH, 0, 1, 1), "high end: south one up");
            assertTrue(connected(Rail.Shape.ASCENDING_SOUTH, 0, 0, -1), "low end: north level");
            assertFalse(connected(Rail.Shape.ASCENDING_SOUTH, 0, 0, 1), "south at same level");
        }

        @Test
        void curveSouthEastConnectsSouthAndEast() {
            assertTrue(connected(Rail.Shape.SOUTH_EAST, 0, 0, 1), "south");
            assertTrue(connected(Rail.Shape.SOUTH_EAST, 1, 0, 0), "east");
            assertFalse(connected(Rail.Shape.SOUTH_EAST, 0, 0, -1), "north");
            assertFalse(connected(Rail.Shape.SOUTH_EAST, -1, 0, 0), "west");
        }

        @Test
        void curveSouthWestConnectsSouthAndWest() {
            assertTrue(connected(Rail.Shape.SOUTH_WEST, 0, 0, 1), "south");
            assertTrue(connected(Rail.Shape.SOUTH_WEST, -1, 0, 0), "west");
            assertFalse(connected(Rail.Shape.SOUTH_WEST, 1, 0, 0), "east");
        }

        @Test
        void curveNorthWestConnectsNorthAndWest() {
            assertTrue(connected(Rail.Shape.NORTH_WEST, 0, 0, -1), "north");
            assertTrue(connected(Rail.Shape.NORTH_WEST, -1, 0, 0), "west");
            assertFalse(connected(Rail.Shape.NORTH_WEST, 0, 0, 1), "south");
        }

        @Test
        void curveNorthEastConnectsNorthAndEast() {
            assertTrue(connected(Rail.Shape.NORTH_EAST, 0, 0, -1), "north");
            assertTrue(connected(Rail.Shape.NORTH_EAST, 1, 0, 0), "east");
            assertFalse(connected(Rail.Shape.NORTH_EAST, -1, 0, 0), "west");
        }

        @Test
        void sameBlockIsNeverConnected() {
            for (Rail.Shape shape : Rail.Shape.values()) {
                assertFalse(connected(shape, 0, 0, 0), "same block for " + shape);
            }
        }

        @Test
        void twoBlocksAwayIsNeverConnected() {
            for (Rail.Shape shape : Rail.Shape.values()) {
                assertFalse(connected(shape, 2, 0, 0), "+2x for " + shape);
                assertFalse(connected(shape, 0, 0, 2), "+2z for " + shape);
            }
        }
    }

    @Nested
    class Coupling {

        private final TrainRegistry registry = new TrainRegistry();

        private Train newTrain(UUID loco, UUID... cars) {
            Train train = new Train(loco);
            for (UUID car : cars) {
                train.addCar(car);
            }
            registry.register(train);
            return train;
        }

        @Test
        void coupleAppendsCartAndRegistryResolvesItByCar() {
            UUID loco = UUID.randomUUID();
            Train train = newTrain(loco);
            UUID newCart = UUID.randomUUID();

            Train coupled = CouplingListener.coupleToAdjacentTrain(
                    registry, newCart, List.of(loco));

            assertSame(train, coupled);
            assertEquals(List.of(newCart), train.cars());
            // THE invariant: registry.byCar must resolve the newly coupled car.
            assertSame(train, registry.byCar(newCart),
                    "registry.byCar must return the owning train immediately after coupling");
            assertSame(train, registry.byMember(newCart));
        }

        @Test
        void coupleViaAdjacentCarMemberAppendsAtBack() {
            UUID loco = UUID.randomUUID();
            UUID rearCar = UUID.randomUUID();
            Train train = newTrain(loco, rearCar);
            UUID newCart = UUID.randomUUID();

            Train coupled = CouplingListener.coupleToAdjacentTrain(
                    registry, newCart, List.of(rearCar));

            assertSame(train, coupled);
            assertEquals(List.of(rearCar, newCart), train.cars(), "new cart appends at back");
            assertSame(train, registry.byCar(newCart));
        }

        @Test
        void coupleSkipsCandidatesThatAreNotMembers() {
            UUID loco = UUID.randomUUID();
            Train train = newTrain(loco);
            UUID newCart = UUID.randomUUID();
            UUID strangerCart = UUID.randomUUID();

            Train coupled = CouplingListener.coupleToAdjacentTrain(
                    registry, newCart, List.of(strangerCart, loco));

            assertSame(train, coupled, "must skip the non-member and find the train");
            assertSame(train, registry.byCar(newCart));
        }

        @Test
        void noMemberCandidatesMeansNoCoupling() {
            UUID newCart = UUID.randomUUID();
            assertNull(CouplingListener.coupleToAdjacentTrain(
                    registry, newCart, List.of(UUID.randomUUID())));
            assertNull(registry.byMember(newCart));
        }

        @Test
        void emptyCandidateListMeansNoCoupling() {
            assertNull(CouplingListener.coupleToAdjacentTrain(
                    registry, UUID.randomUUID(), List.of()));
        }

        @Test
        void cartAlreadyInATrainIsNotRecoupled() {
            UUID locoA = UUID.randomUUID();
            UUID carA = UUID.randomUUID();
            Train trainA = newTrain(locoA, carA);
            UUID locoB = UUID.randomUUID();
            Train trainB = newTrain(locoB);

            assertNull(CouplingListener.coupleToAdjacentTrain(registry, carA, List.of(locoB)),
                    "a cart that already belongs to a train must not couple again");
            assertSame(trainA, registry.byCar(carA), "ownership unchanged");
            assertEquals(0, trainB.carCount());
        }

        @Test
        void locomotiveOfAnotherTrainIsNeverCoupledAsACar() {
            UUID locoA = UUID.randomUUID();
            newTrain(locoA);
            UUID locoB = UUID.randomUUID();
            Train trainB = newTrain(locoB);

            assertNull(CouplingListener.coupleToAdjacentTrain(registry, locoA, List.of(locoB)));
            assertEquals(0, trainB.carCount());
            assertNotNull(registry.byLocomotive(locoA), "train A untouched");
        }
    }

    @Nested
    class Uncoupling {

        private final TrainRegistry registry = new TrainRegistry();

        private Train newTrain(UUID loco, UUID... cars) {
            Train train = new Train(loco);
            for (UUID car : cars) {
                train.addCar(car);
            }
            registry.register(train);
            return train;
        }

        @Test
        void removingTheLocomotiveUnregistersTheWholeTrain() {
            UUID loco = UUID.randomUUID();
            UUID carA = UUID.randomUUID();
            UUID carB = UUID.randomUUID();
            Train train = newTrain(loco, carA, carB);

            CouplingListener.UncoupleResult result =
                    CouplingListener.uncoupleMember(registry, loco);

            assertNotNull(result);
            assertSame(train, result.train());
            assertTrue(result.trainRemoved());
            assertEquals(List.of(carA, carB), result.detachedCars());
            assertNull(registry.byLocomotive(loco));
            assertNull(registry.byCar(carA), "freed car must not resolve to a train");
            assertNull(registry.byCar(carB), "freed car must not resolve to a train");
            assertTrue(registry.all().isEmpty());
        }

        @Test
        void removingAMidCarDetachesItAndEverythingBehind() {
            UUID loco = UUID.randomUUID();
            UUID front = UUID.randomUUID();
            UUID mid = UUID.randomUUID();
            UUID rear = UUID.randomUUID();
            Train train = newTrain(loco, front, mid, rear);

            CouplingListener.UncoupleResult result =
                    CouplingListener.uncoupleMember(registry, mid);

            assertNotNull(result);
            assertSame(train, result.train());
            assertFalse(result.trainRemoved());
            assertEquals(List.of(mid, rear), result.detachedCars());
            assertEquals(List.of(front), train.cars(), "only the front car remains coupled");
            assertSame(train, registry.byCar(front), "front car still resolves to the train");
            assertNull(registry.byCar(mid), "removed car no longer resolves");
            assertNull(registry.byCar(rear), "trailing car no longer resolves");
            assertSame(train, registry.byLocomotive(loco), "train itself stays registered");
        }

        @Test
        void removingTheOnlyCarLeavesALoneLocomotive() {
            UUID loco = UUID.randomUUID();
            UUID car = UUID.randomUUID();
            Train train = newTrain(loco, car);

            CouplingListener.UncoupleResult result =
                    CouplingListener.uncoupleMember(registry, car);

            assertNotNull(result);
            assertFalse(result.trainRemoved());
            assertEquals(List.of(car), result.detachedCars());
            assertEquals(0, train.carCount());
            assertNull(registry.byCar(car));
            assertSame(train, registry.byLocomotive(loco));
        }

        @Test
        void unknownEntityIsNotAMemberAndNothingChanges() {
            UUID loco = UUID.randomUUID();
            newTrain(loco, UUID.randomUUID());
            assertNull(CouplingListener.uncoupleMember(registry, UUID.randomUUID()));
            assertEquals(1, registry.all().size());
        }

        @Test
        void coupleThenUncoupleRoundTripKeepsRegistryConsistent() {
            UUID loco = UUID.randomUUID();
            Train train = newTrain(loco);
            UUID cart = UUID.randomUUID();

            CouplingListener.coupleToAdjacentTrain(registry, cart, List.of(loco));
            assertSame(train, registry.byCar(cart));

            CouplingListener.UncoupleResult result =
                    CouplingListener.uncoupleMember(registry, cart);

            assertNotNull(result);
            assertNull(registry.byCar(cart), "uncoupled cart must not resolve to a train");
            assertSame(train, registry.byLocomotive(loco), "locomotive keeps its train");
            assertEquals(0, train.carCount());
        }
    }
}
