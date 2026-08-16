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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One train: the locomotive's entity UUID plus an <em>ordered</em> list of coupled car
 * UUIDs (front to back) and the locomotive's mutable running state.
 *
 * <p>This class stores UUIDs only and never resolves entities — callers look entities up
 * through the server ({@code Bukkit.getEntity}) when they need them. That keeps the model
 * pure, headless-testable, and safe to hold across chunk unloads where the entity handle
 * would go stale.
 */
public final class Train {

    /** Preset assumed when none has been chosen or persisted (config default multiplier 1.0). */
    public static final String DEFAULT_SPEED_PRESET = "cruise";

    private final UUID locomotiveId;
    private final List<UUID> cars = new ArrayList<>();

    private double charge;
    private boolean engineOn;
    private String speedPreset = DEFAULT_SPEED_PRESET;
    /** Last travel direction (BlockFace name), or {@code null} if the train never moved. */
    private String lastFacing;

    public Train(UUID locomotiveId) {
        this.locomotiveId = Objects.requireNonNull(locomotiveId, "locomotiveId");
    }

    /** The locomotive entity's UUID. */
    public UUID locomotiveId() {
        return locomotiveId;
    }

    /** Unmodifiable live view of the coupled car UUIDs, front to back. */
    public List<UUID> cars() {
        return Collections.unmodifiableList(cars);
    }

    /** Number of coupled cars (excluding the locomotive). */
    public int carCount() {
        return cars.size();
    }

    /**
     * Couples a car at the back of the train.
     *
     * @throws NullPointerException if {@code carId} is null
     * @throws IllegalArgumentException if the car is already coupled or is the locomotive
     */
    public void addCar(UUID carId) {
        Objects.requireNonNull(carId, "carId");
        if (carId.equals(locomotiveId)) {
            throw new IllegalArgumentException("Cannot couple the locomotive to itself: " + carId);
        }
        if (cars.contains(carId)) {
            throw new IllegalArgumentException("Car already coupled: " + carId);
        }
        cars.add(carId);
    }

    /**
     * Removes one car wherever it sits, preserving the order of the rest.
     *
     * @return true if the car was coupled and has been removed
     */
    public boolean removeCar(UUID carId) {
        return cars.remove(carId);
    }

    /**
     * Uncouples every car from {@code index} (0-based, inclusive) to the back of the
     * train.
     *
     * @return the removed tail, front to back
     * @throws IndexOutOfBoundsException if {@code index} is not in {@code [0, carCount())}
     */
    public List<UUID> uncoupleFrom(int index) {
        Objects.checkIndex(index, cars.size());
        List<UUID> view = cars.subList(index, cars.size());
        List<UUID> tail = List.copyOf(view);
        view.clear();
        return tail;
    }

    /** Stored redstone charge. Units and caps are the caller's concern. */
    public double charge() {
        return charge;
    }

    public void setCharge(double charge) {
        this.charge = charge;
    }

    /** Whether the engine is currently running. */
    public boolean engineOn() {
        return engineOn;
    }

    public void setEngineOn(boolean engineOn) {
        this.engineOn = engineOn;
    }

    /** Selected speed-preset name (a key of {@code speed-presets} in config). Never null. */
    public String speedPreset() {
        return speedPreset;
    }

    public void setSpeedPreset(String speedPreset) {
        this.speedPreset = Objects.requireNonNull(speedPreset, "speedPreset");
    }

    /** Last travel direction (BlockFace name), or {@code null} if unknown. */
    public String lastFacing() {
        return lastFacing;
    }

    public void setLastFacing(String lastFacing) {
        this.lastFacing = lastFacing;
    }
}
