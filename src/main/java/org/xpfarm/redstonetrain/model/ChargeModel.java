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
package org.xpfarm.redstonetrain.model;

import org.xpfarm.redstonetrain.config.RtConfig;

/**
 * Pure charge math for a locomotive's battery. No Bukkit types; every method is a
 * function of its numeric inputs and the {@link RtConfig} snapshot.
 *
 * <p>All results stay within {@code [0, chargeMax]}. A config with {@code chargeMax == 0}
 * is legal and simply pins every gain at 0.
 */
public final class ChargeModel {

    private ChargeModel() {
        // Static utility; not instantiable.
    }

    /**
     * Charge left after travelling: {@code max(0, charge - blocksMoved * drainPerBlock)}.
     *
     * @param charge the current charge
     * @param blocksMoved distance travelled in blocks
     * @param cfg the config snapshot
     * @return the remaining charge, never negative
     */
    public static double drain(double charge, double blocksMoved, RtConfig cfg) {
        return Math.max(0.0, charge - blocksMoved * cfg.drainPerBlock());
    }

    /**
     * Charge after moving over active powered rail:
     * {@code min(chargeMax, charge + blocksMoved * poweredRailGainPerBlock)}.
     *
     * @param charge the current charge
     * @param blocksMoved distance travelled over active powered rail in blocks
     * @param cfg the config snapshot
     * @return the new charge, never above {@code chargeMax}
     */
    public static double gainOverRail(double charge, double blocksMoved, RtConfig cfg) {
        return Math.min(cfg.chargeMax(), charge + blocksMoved * cfg.poweredRailGainPerBlock());
    }

    /**
     * Charge after idling parked on an active powered rail:
     * {@code min(chargeMax, charge + seconds * idleTricklePerSecond)}.
     *
     * @param charge the current charge
     * @param seconds time spent idling in seconds
     * @param cfg the config snapshot
     * @return the new charge, never above {@code chargeMax}
     */
    public static double idleTrickle(double charge, double seconds, RtConfig cfg) {
        return Math.min(cfg.chargeMax(), charge + seconds * cfg.idleTricklePerSecond());
    }

    /**
     * Charge after feeding the locomotive one redstone item:
     * {@code min(chargeMax, charge + (isBlock ? redstoneBlock : redstoneDust))}.
     *
     * @param charge the current charge
     * @param isBlock {@code true} for a redstone block, {@code false} for redstone dust
     * @param cfg the config snapshot
     * @return the new charge, never above {@code chargeMax}
     */
    public static double addRedstone(double charge, boolean isBlock, RtConfig cfg) {
        double gain = isBlock ? cfg.redstoneBlock() : cfg.redstoneDust();
        return Math.min(cfg.chargeMax(), charge + gain);
    }

    /**
     * Whether the train has any charge to move with.
     *
     * @param charge the current charge
     * @return {@code true} iff {@code charge > 0}
     */
    public static boolean canMove(double charge) {
        return charge > 0.0;
    }
}
