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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.entity.Minecart;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.xpfarm.redstonetrain.item.RtKeys;

/**
 * Persists {@link Train} state to and from a locomotive entity's
 * {@link PersistentDataContainer}, using the shared {@link RtKeys}.
 *
 * <p>Layout on the locomotive minecart's PDC:
 * <ul>
 *   <li>{@code locomotive} — BYTE 1, the identity tag (present iff this cart is a
 *       locomotive)</li>
 *   <li>{@code charge} — DOUBLE</li>
 *   <li>{@code engine_on} — BYTE 0/1</li>
 *   <li>{@code speed_preset} — STRING preset name</li>
 *   <li>{@code coupled_cars} — STRING, comma-joined car UUIDs front to back
 *       (see {@link #encode(List)}/{@link #decode(String)})</li>
 *   <li>{@code last_facing} — STRING BlockFace name; absent when unknown</li>
 * </ul>
 *
 * <p>The container-level {@code write}/{@code read} overloads are the headless-testable
 * seam; the {@link Minecart} overloads only fetch the entity's container and delegate.
 */
public final class TrainCodec {

    private final RtKeys keys;

    public TrainCodec(RtKeys keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** True when this minecart entity carries the locomotive identity tag. Null-safe. */
    public boolean isLocomotiveEntity(Minecart cart) {
        return cart != null && isLocomotive(cart.getPersistentDataContainer());
    }

    /** Pure container-level locomotive predicate (headless-testable seam). Null-safe. */
    boolean isLocomotive(PersistentDataContainer pdc) {
        return pdc != null && pdc.has(keys.locomotive, PersistentDataType.BYTE);
    }

    /** Writes the train's full state onto the locomotive entity, tagging it as one. */
    public void write(Minecart loco, Train train) {
        write(loco.getPersistentDataContainer(), train);
    }

    /** Container-level write (headless-testable seam). */
    void write(PersistentDataContainer pdc, Train train) {
        pdc.set(keys.locomotive, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keys.charge, PersistentDataType.DOUBLE, train.charge());
        pdc.set(keys.engineOn, PersistentDataType.BYTE, (byte) (train.engineOn() ? 1 : 0));
        pdc.set(keys.speedPreset, PersistentDataType.STRING, train.speedPreset());
        pdc.set(keys.coupledCars, PersistentDataType.STRING, encode(train.cars()));
        String facing = train.lastFacing();
        if (facing != null) {
            pdc.set(keys.lastFacing, PersistentDataType.STRING, facing);
        } else {
            pdc.remove(keys.lastFacing);
        }
    }

    /**
     * Restores a {@link Train} from the locomotive entity's PDC.
     *
     * @return empty if the cart does not carry the {@code locomotive} tag
     */
    public Optional<Train> read(Minecart loco) {
        return read(loco.getUniqueId(), loco.getPersistentDataContainer());
    }

    /** Container-level read (headless-testable seam). */
    Optional<Train> read(UUID locomotiveId, PersistentDataContainer pdc) {
        if (!isLocomotive(pdc)) {
            return Optional.empty();
        }
        Train train = new Train(locomotiveId);
        train.setCharge(pdc.getOrDefault(keys.charge, PersistentDataType.DOUBLE, 0.0));
        train.setEngineOn(pdc.getOrDefault(keys.engineOn, PersistentDataType.BYTE, (byte) 0) != 0);
        train.setSpeedPreset(pdc.getOrDefault(
                keys.speedPreset, PersistentDataType.STRING, Train.DEFAULT_SPEED_PRESET));
        for (UUID car : decode(pdc.getOrDefault(keys.coupledCars, PersistentDataType.STRING, ""))) {
            // Defensive against corrupted PDC: skip self-references and duplicates
            // instead of failing the whole restore.
            if (!car.equals(locomotiveId) && !train.cars().contains(car)) {
                train.addCar(car);
            }
        }
        train.setLastFacing(pdc.get(keys.lastFacing, PersistentDataType.STRING));
        return Optional.of(train);
    }

    /**
     * Pure helper: encodes an ordered UUID list as a comma-joined string
     * ({@code ""} for an empty list). Inverse of {@link #decode(String)}.
     */
    public static String encode(List<UUID> cars) {
        return cars.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    /**
     * Pure helper: decodes a comma-joined UUID string back into an ordered list.
     * Null or blank input yields an empty list; malformed tokens are skipped so one
     * corrupted entry cannot take down the rest of the consist.
     */
    public static List<UUID> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<UUID> cars = new ArrayList<>();
        for (String token : encoded.split(",")) {
            try {
                cars.add(UUID.fromString(token.trim()));
            } catch (IllegalArgumentException malformed) {
                // Skip the corrupted token; keep every valid car.
            }
        }
        return List.copyOf(cars);
    }
}
