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
package org.xpfarm.redstonetrain.item;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for the item identity layer.
 *
 * <p>Bukkit's {@code ItemStack}/{@code ItemMeta} construction requires an initialized
 * server ({@code Bukkit.getServer()}), which is unavailable in plain JUnit. The tag
 * predicate is therefore exercised through the pure {@link RtItems#hasTag} seam using a
 * map-backed {@link PersistentDataContainer} test double (a data-container double, not a
 * fake server). Assertions that {@code locomotive()} is a {@code MINECART} carrying the
 * tag on a real item stack are deferred to gate 7a runtime verification.
 */
class RtItemsTest {

    /** Namespace-string key construction avoids needing a live Plugin instance. */
    private final RtKeys keys = new RtKeys(name -> new NamespacedKey("redstonetrain", name));
    private final RtItems items = new RtItems(keys);

    @Test
    void keysUseStableNamespacedNames() {
        assertEquals("redstonetrain:locomotive", keys.locomotive.toString());
        assertEquals("redstonetrain:wrench", keys.wrench.toString());
        assertEquals("redstonetrain:charge", keys.charge.toString());
        assertEquals("redstonetrain:engine_on", keys.engineOn.toString());
        assertEquals("redstonetrain:speed_preset", keys.speedPreset.toString());
        assertEquals("redstonetrain:coupled_cars", keys.coupledCars.toString());
        assertEquals("redstonetrain:last_facing", keys.lastFacing.toString());
        assertEquals("redstonetrain:locomotive_recipe", keys.locomotiveRecipe.toString());
        assertEquals("redstonetrain:wrench_recipe", keys.wrenchRecipe.toString());
    }

    @Test
    void isLocomotiveIsNullSafe() {
        assertFalse(items.isLocomotive(null));
    }

    @Test
    void isWrenchIsNullSafe() {
        assertFalse(items.isWrench(null));
    }

    @Test
    void hasTagMatchesOnlyTheTaggedKey() {
        MapDataContainer pdc = new MapDataContainer();
        pdc.set(keys.locomotive, PersistentDataType.BYTE, (byte) 1);

        assertTrue(RtItems.hasTag(pdc, keys.locomotive));
        assertFalse(RtItems.hasTag(pdc, keys.wrench));
    }

    @Test
    void hasTagDistinguishesWrenchFromLocomotive() {
        MapDataContainer pdc = new MapDataContainer();
        pdc.set(keys.wrench, PersistentDataType.BYTE, (byte) 1);

        assertTrue(RtItems.hasTag(pdc, keys.wrench));
        assertFalse(RtItems.hasTag(pdc, keys.locomotive));
    }

    @Test
    void hasTagFalseForEmptyContainer() {
        assertFalse(RtItems.hasTag(new MapDataContainer(), keys.locomotive));
    }

    @Test
    void hasTagFalseForNullContainer() {
        assertFalse(RtItems.hasTag(null, keys.locomotive));
    }

    @Test
    void hasTagRequiresMatchingValueType() {
        MapDataContainer pdc = new MapDataContainer();
        pdc.set(keys.locomotive, PersistentDataType.STRING, "not-a-byte");

        assertFalse(RtItems.hasTag(pdc, keys.locomotive));
    }

    /**
     * Minimal map-backed {@link PersistentDataContainer}. Only the read/write methods the
     * tag seam touches are functional; serialization and adapter-context methods are
     * unsupported because no test needs them.
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
