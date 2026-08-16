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

import org.bukkit.NamespacedKey;

/**
 * Test-only factory exposing the package-private {@link RtKeys} headless seam to tests
 * in other packages (e.g. {@code train.TrainCodecTest}), which cannot obtain a live
 * {@code Plugin} instance in plain JUnit.
 */
public final class HeadlessKeys {

    private HeadlessKeys() {
    }

    /** Builds {@link RtKeys} under the plugin's {@code redstonetrain} namespace. */
    public static RtKeys create() {
        return new RtKeys(name -> new NamespacedKey("redstonetrain", name));
    }
}
