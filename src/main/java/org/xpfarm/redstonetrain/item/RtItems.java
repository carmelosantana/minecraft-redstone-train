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

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Builds and identifies the plugin's custom items.
 *
 * <p>Geyser/Bedrock safety: identity is carried by a PDC byte tag ({@link RtKeys}),
 * never by display name or CustomModelData. Display name and lore are cosmetic only;
 * Bedrock clients see plain vanilla models but every identity check reads the tag.
 */
public final class RtItems {

    private final RtKeys keys;

    public RtItems(RtKeys keys) {
        this.keys = keys;
    }

    /**
     * A Locomotive: a {@link Material#MINECART} item carrying the
     * {@code redstonetrain:locomotive} PDC tag.
     */
    public ItemStack locomotive() {
        return tagged(Material.MINECART, keys.locomotive,
                Component.text("Locomotive", NamedTextColor.RED),
                List.of(
                        Component.text("A self-propelled electric locomotive.", NamedTextColor.GRAY),
                        Component.text("Place on rails, fuel with redstone,", NamedTextColor.GRAY),
                        Component.text("and couple carts behind it.", NamedTextColor.GRAY)));
    }

    /**
     * A Train Wrench: a {@link Material#BLAZE_ROD} item carrying the
     * {@code redstonetrain:wrench} PDC tag.
     */
    public ItemStack wrench() {
        return tagged(Material.BLAZE_ROD, keys.wrench,
                Component.text("Train Wrench", NamedTextColor.GOLD),
                List.of(
                        Component.text("Couples and uncouples train cars.", NamedTextColor.GRAY),
                        Component.text("Sneak-click a locomotive to inspect it.", NamedTextColor.GRAY)));
    }

    /** True when the stack is a Locomotive item. Null/empty-safe. */
    public boolean isLocomotive(ItemStack stack) {
        return hasTag(stack, keys.locomotive);
    }

    /** True when the stack is a Train Wrench item. Null/empty-safe. */
    public boolean isWrench(ItemStack stack) {
        return hasTag(stack, keys.wrench);
    }

    private ItemStack tagged(Material material, NamespacedKey tag, Component name, List<Component> lore) {
        ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    private static boolean hasTag(ItemStack stack, NamespacedKey tag) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && hasTag(meta.getPersistentDataContainer(), tag);
    }

    /**
     * Pure tag predicate, the headless-testable seam: true when the container carries
     * the tag as a byte. Null-safe.
     */
    static boolean hasTag(PersistentDataContainer container, NamespacedKey tag) {
        return container != null && container.has(tag, PersistentDataType.BYTE);
    }
}
