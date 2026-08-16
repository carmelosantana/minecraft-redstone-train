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

import java.util.function.Function;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Central registry of every {@link NamespacedKey} the plugin uses, so all tasks share
 * the exact same keys.
 *
 * <p>Item and entity identity is carried exclusively by these PDC keys — never by
 * display name or CustomModelData — so Bedrock players joining through Geyser get
 * identical behavior.
 */
public final class RtKeys {

    /** Item/entity tag: marks an item stack or minecart entity as a Locomotive. */
    public final NamespacedKey locomotive;
    /** Item tag: marks an item stack as the Train Wrench. */
    public final NamespacedKey wrench;
    /** Persistence: stored charge (redstone energy) on a locomotive entity. */
    public final NamespacedKey charge;
    /** Persistence: whether the locomotive engine is running. */
    public final NamespacedKey engineOn;
    /** Persistence: selected speed preset on a locomotive entity. */
    public final NamespacedKey speedPreset;
    /** Persistence: UUID list of carts coupled behind a locomotive. */
    public final NamespacedKey coupledCars;
    /** Persistence: last travel direction of a locomotive. */
    public final NamespacedKey lastFacing;
    /** Recipe key for the Locomotive crafting recipe. */
    public final NamespacedKey locomotiveRecipe;
    /** Recipe key for the Train Wrench crafting recipe. */
    public final NamespacedKey wrenchRecipe;

    /**
     * Builds the keys from the live plugin instance (the normal runtime path).
     */
    public RtKeys(Plugin plugin) {
        this(name -> new NamespacedKey(plugin, name));
    }

    /**
     * Seam used by headless unit tests, which cannot obtain a live {@link Plugin}
     * instance: keys are produced by the supplied factory instead.
     */
    RtKeys(Function<String, NamespacedKey> factory) {
        this.locomotive = factory.apply("locomotive");
        this.wrench = factory.apply("wrench");
        this.charge = factory.apply("charge");
        this.engineOn = factory.apply("engine_on");
        this.speedPreset = factory.apply("speed_preset");
        this.coupledCars = factory.apply("coupled_cars");
        this.lastFacing = factory.apply("last_facing");
        this.locomotiveRecipe = factory.apply("locomotive_recipe");
        this.wrenchRecipe = factory.apply("wrench_recipe");
    }
}
