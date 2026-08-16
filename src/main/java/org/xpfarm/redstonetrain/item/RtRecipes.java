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

import org.bukkit.Material;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.Plugin;

/**
 * Registers the crafting recipes for the plugin's items.
 */
public final class RtRecipes {

    private RtRecipes() {
    }

    /**
     * Registers both recipes with the server:
     *
     * <ul>
     *   <li>Locomotive — shapeless: minecart + redstone block.</li>
     *   <li>Train Wrench — shaped 2x2 diagonal: redstone over stick. The diagonal shape
     *       avoids conflicting with the vanilla redstone torch (redstone stacked
     *       vertically on a stick).</li>
     * </ul>
     */
    public static void register(Plugin plugin, RtItems items, RtKeys keys) {
        ShapelessRecipe locomotive = new ShapelessRecipe(keys.locomotiveRecipe, items.locomotive());
        locomotive.addIngredient(Material.MINECART);
        locomotive.addIngredient(Material.REDSTONE_BLOCK);
        plugin.getServer().addRecipe(locomotive);

        ShapedRecipe wrench = new ShapedRecipe(keys.wrenchRecipe, items.wrench());
        wrench.shape("R ", " S");
        wrench.setIngredient('R', Material.REDSTONE);
        wrench.setIngredient('S', Material.STICK);
        plugin.getServer().addRecipe(wrench);
    }
}
