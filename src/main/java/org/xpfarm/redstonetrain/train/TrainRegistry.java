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
