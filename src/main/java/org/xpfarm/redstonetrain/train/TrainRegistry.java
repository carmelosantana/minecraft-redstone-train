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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory index of live trains with O(1) lookup both ways: locomotive UUID to
 * {@link Train}, and car UUID to the owning {@link Train}.
 *
 * <p>Both maps are kept consistent on every mutation. After changing a train's consist
 * (adding or removing cars on the {@link Train} itself), call {@link #register(Train)}
 * again to re-sync the car index; {@link #unregister(UUID)} sweeps every mapping that
 * points at the removed train, so cars never leak even if the consist changed since the
 * last sync.
 *
 * <p>Not thread-safe by design: all access happens on the server main thread.
 */
public final class TrainRegistry {

    private final Map<UUID, Train> byLocomotive = new HashMap<>();
    private final Map<UUID, Train> byCar = new HashMap<>();

    /**
     * Registers (or re-syncs) a train. Any previous mappings for the same locomotive
     * UUID or the same train instance are dropped before the current consist is indexed.
     */
    public void register(Train train) {
        Objects.requireNonNull(train, "train");
        Train previous = byLocomotive.put(train.locomotiveId(), train);
        if (previous != null && previous != train) {
            byCar.values().removeIf(owner -> owner == previous);
        }
        byCar.values().removeIf(owner -> owner == train);
        for (UUID car : train.cars()) {
            byCar.put(car, train);
        }
    }

    /**
     * Removes the train owned by this locomotive, clearing every car mapping that points
     * at it (including cars added since the last {@link #register(Train)} sync).
     *
     * @return the removed train, or {@code null} if the locomotive was not registered
     */
    public @Nullable Train unregister(UUID locomotiveId) {
        Train removed = byLocomotive.remove(locomotiveId);
        if (removed != null) {
            byCar.values().removeIf(owner -> owner == removed);
        }
        return removed;
    }

    /**
     * Atomically couples a car at the back of a <em>registered</em> train, updating the
     * consist and the car index together so {@code byCar(carId)} resolves immediately.
     *
     * <p>This is the required write path for coupling: never call
     * {@link Train#addCar(UUID)} directly without re-indexing, or {@link #byCar(UUID)}
     * silently returns {@code null} for the new car.
     *
     * @throws IllegalStateException if the train is not currently registered
     * @throws IllegalArgumentException if the car already belongs to any train (as car
     *     or locomotive), or equals the train's own locomotive
     */
    public void coupleCar(Train train, UUID carId) {
        Objects.requireNonNull(train, "train");
        Objects.requireNonNull(carId, "carId");
        if (byLocomotive.get(train.locomotiveId()) != train) {
            throw new IllegalStateException(
                    "Train is not registered; register it before coupling: "
                            + train.locomotiveId());
        }
        if (byCar.containsKey(carId) || byLocomotive.containsKey(carId)) {
            throw new IllegalArgumentException(
                    "Entity already belongs to a train: " + carId);
        }
        train.addCar(carId);
        byCar.put(carId, train);
    }

    /**
     * Atomically uncouples every car from {@code index} (0-based, inclusive) to the back
     * of a <em>registered</em> train, updating the consist and the car index together so
     * {@code byCar} stops resolving the removed cars immediately.
     *
     * @return the removed tail, front to back
     * @throws IllegalStateException if the train is not currently registered
     * @throws IndexOutOfBoundsException if {@code index} is not in {@code [0, carCount())}
     */
    public List<UUID> uncoupleTail(Train train, int index) {
        Objects.requireNonNull(train, "train");
        if (byLocomotive.get(train.locomotiveId()) != train) {
            throw new IllegalStateException(
                    "Train is not registered; register it before uncoupling: "
                            + train.locomotiveId());
        }
        List<UUID> tail = train.uncoupleFrom(index);
        for (UUID car : tail) {
            byCar.remove(car);
        }
        return tail;
    }

    /** The train whose locomotive has this UUID, or {@code null}. */
    public @Nullable Train byLocomotive(UUID locomotiveId) {
        return byLocomotive.get(locomotiveId);
    }

    /** The train that owns this coupled car, or {@code null}. */
    public @Nullable Train byCar(UUID carId) {
        return byCar.get(carId);
    }

    /** The train this entity belongs to as locomotive or car, or {@code null}. */
    public @Nullable Train byMember(UUID entityId) {
        Train train = byLocomotive.get(entityId);
        return train != null ? train : byCar.get(entityId);
    }

    /** Unmodifiable live view of every registered train. */
    public Collection<Train> all() {
        return Collections.unmodifiableCollection(byLocomotive.values());
    }
}
