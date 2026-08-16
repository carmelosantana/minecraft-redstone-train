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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.xpfarm.redstonetrain.item.HeadlessKeys;
import org.xpfarm.redstonetrain.item.RtKeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for {@link TrainCodec}.
 *
 * <p>The pure {@code encode}/{@code decode} helpers and the container-level
 * {@code write}/{@code read} seams are exercised against a map-backed
 * {@link PersistentDataContainer} double. What CANNOT run here — and is deferred to
 * gate 7a runtime verification — is the live-entity path: {@code write(Minecart, Train)}
 * / {@code read(Minecart)} against a real spawned Minecart's CraftPersistentDataContainer,
 * and persistence of the tags across a chunk save/load cycle.
 */
class TrainCodecTest {

    private final RtKeys keys = HeadlessKeys.create();
    private final TrainCodec codec = new TrainCodec(keys);

    // ---- pure encode/decode -------------------------------------------------

    @Test
    void encodeEmptyListYieldsEmptyString() {
        assertEquals("", TrainCodec.encode(List.of()));
    }

    @Test
    void decodeEmptyOrNullYieldsEmptyList() {
        assertTrue(TrainCodec.decode("").isEmpty());
        assertTrue(TrainCodec.decode("   ").isEmpty());
        assertTrue(TrainCodec.decode(null).isEmpty());
    }

    @Test
    void encodeDecodeRoundTripsPreservingOrder() {
        List<UUID> cars = List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertEquals(cars, TrainCodec.decode(TrainCodec.encode(cars)));
    }

    @Test
    void encodeSingleUuidHasNoSeparator() {
        UUID car = UUID.randomUUID();
        assertEquals(car.toString(), TrainCodec.encode(List.of(car)));
    }

    @Test
    void decodeSkipsMalformedTokens() {
        UUID good = UUID.randomUUID();
        String corrupted = "not-a-uuid," + good + ",,also-bad";
        assertEquals(List.of(good), TrainCodec.decode(corrupted));
    }

    // ---- container-level write/read (PDC double, no entity) -----------------

    @Test
    void isLocomotiveFalseForUntaggedOrNullContainer() {
        assertFalse(codec.isLocomotive(new MapDataContainer()));
        assertFalse(codec.isLocomotive(null));
    }

    @Test
    void writeTagsContainerAsLocomotive() {
        MapDataContainer pdc = new MapDataContainer();
        codec.write(pdc, new Train(UUID.randomUUID()));
        assertTrue(codec.isLocomotive(pdc));
    }

    @Test
    void readReturnsEmptyWithoutLocomotiveTag() {
        MapDataContainer pdc = new MapDataContainer();
        pdc.set(keys.charge, PersistentDataType.DOUBLE, 50.0);
        assertTrue(codec.read(UUID.randomUUID(), pdc).isEmpty());
    }

    @Test
    void writeThenReadRoundTripsFullTrainState() {
        UUID loco = UUID.randomUUID();
        List<UUID> cars = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Train original = new Train(loco);
        cars.forEach(original::addCar);
        original.setCharge(742.25);
        original.setEngineOn(true);
        original.setSpeedPreset("slow");
        original.setLastFacing("EAST");

        MapDataContainer pdc = new MapDataContainer();
        codec.write(pdc, original);
        Optional<Train> restored = codec.read(loco, pdc);

        assertTrue(restored.isPresent());
        Train train = restored.get();
        assertEquals(loco, train.locomotiveId());
        assertEquals(cars, train.cars());
        assertEquals(742.25, train.charge());
        assertTrue(train.engineOn());
        assertEquals("slow", train.speedPreset());
        assertEquals("EAST", train.lastFacing());
    }

    @Test
    void readAppliesDefaultsForMissingOptionalTags() {
        MapDataContainer pdc = new MapDataContainer();
        pdc.set(keys.locomotive, PersistentDataType.BYTE, (byte) 1);

        UUID loco = UUID.randomUUID();
        Optional<Train> restored = codec.read(loco, pdc);

        assertTrue(restored.isPresent());
        Train train = restored.get();
        assertEquals(0, train.carCount());
        assertEquals(0.0, train.charge());
        assertFalse(train.engineOn());
        assertEquals(Train.DEFAULT_SPEED_PRESET, train.speedPreset());
        assertNull(train.lastFacing());
    }

    @Test
    void readSkipsCorruptCarEntriesMatchingLocomotive() {
        UUID loco = UUID.randomUUID();
        UUID car = UUID.randomUUID();
        MapDataContainer pdc = new MapDataContainer();
        pdc.set(keys.locomotive, PersistentDataType.BYTE, (byte) 1);
        // Corrupted PDC: the locomotive's own id and a duplicate leaked into the car list.
        pdc.set(keys.coupledCars, PersistentDataType.STRING,
                loco + "," + car + "," + car);

        Train train = codec.read(loco, pdc).orElseThrow();
        assertEquals(List.of(car), train.cars());
    }

    @Test
    void writeClearsStaleLastFacingWhenUnset() {
        MapDataContainer pdc = new MapDataContainer();
        pdc.set(keys.locomotive, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keys.lastFacing, PersistentDataType.STRING, "WEST");

        codec.write(pdc, new Train(UUID.randomUUID())); // lastFacing null

        Train train = codec.read(UUID.randomUUID(), pdc).orElseThrow();
        assertNull(train.lastFacing());
    }

    /**
     * Minimal map-backed {@link PersistentDataContainer} double, mirroring the one in
     * {@code RtItemsTest}: only the read/write methods the codec touches are functional.
     */
    private static final class MapDataContainer implements PersistentDataContainer {

        private final Map<NamespacedKey, Object> data = new HashMap<>();

        @Override
        public <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value) {
            data.put(key, value);
        }

        @Override
        public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
            Object value = data.get(key);
            return value != null && type.getComplexType().isInstance(value);
        }

        @Override
        public boolean has(NamespacedKey key) {
            return data.containsKey(key);
        }

        @Override
        public <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type) {
            Object value = data.get(key);
            return type.getComplexType().isInstance(value) ? type.getComplexType().cast(value) : null;
        }

        @Override
        public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C defaultValue) {
            C value = get(key, type);
            return value != null ? value : defaultValue;
        }

        @Override
        public Set<NamespacedKey> getKeys() {
            return Set.copyOf(data.keySet());
        }

        @Override
        public void remove(NamespacedKey key) {
            data.remove(key);
        }

        @Override
        public boolean isEmpty() {
            return data.isEmpty();
        }

        @Override
        public int getSize() {
            return data.size();
        }

        @Override
        public void copyTo(PersistentDataContainer other, boolean replace) {
            throw new UnsupportedOperationException("Not needed by tests");
        }

        @Override
        public PersistentDataAdapterContext getAdapterContext() {
            throw new UnsupportedOperationException("Not needed by tests");
        }

        @Override
        public byte[] serializeToBytes() {
            throw new UnsupportedOperationException("Not needed by tests");
        }

        @Override
        public void readFromBytes(byte[] bytes, boolean clear) {
            throw new UnsupportedOperationException("Not needed by tests");
        }
    }
}
