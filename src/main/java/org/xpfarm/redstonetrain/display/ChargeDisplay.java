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
package org.xpfarm.redstonetrain.display;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.model.ChargeModel;
import org.xpfarm.redstonetrain.model.SpeedModel;
import org.xpfarm.redstonetrain.train.Train;

/**
 * Per-rider boss bar showing a train's charge, speed, and consist size, e.g.
 * {@code ⚡ 72%  ·  6.4 b/s  ·  4 cars}.
 *
 * <p>Geyser/Bedrock safety: the Adventure boss bar API is one of the few display
 * surfaces Geyser translates faithfully to Bedrock (no GUI forms, no Java-only chat
 * input), which is why the rider HUD is a boss bar and nothing else.
 *
 * <p>The string building ({@link #format}), fill fraction ({@link #progress}), and
 * displayed speed ({@link #displaySpeed}) are pure and unit-tested headless; only
 * {@link #show}/{@link #update}/{@link #hide} touch live {@link Player}s, and their
 * rendering is validated at gate 7a. When {@code display.bossbar-enabled} is false
 * every method is a no-op.
 */
public final class ChargeDisplay {

    private final RtConfig config;

    /** One entry per rider currently shown a bar. Main-thread only, like the registry. */
    private final Map<UUID, Viewer> viewers = new HashMap<>();

    public ChargeDisplay(RtConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    // ------------------------------------------------------------ rider bars

    /** Shows (or refreshes) this rider's bar for the given train. */
    public void show(Player player, Train train) {
        if (!config.bossbarEnabled()) {
            return;
        }
        hide(player);
        BossBar bar = BossBar.bossBar(name(train), progress(train.charge(), config.chargeMax()),
                BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        viewers.put(player.getUniqueId(), new Viewer(player, bar, train.locomotiveId()));
        player.showBossBar(bar);
    }

    /**
     * Refreshes the bar of every rider currently viewing this train. Adventure boss
     * bars push name/progress changes to all their viewers, so mutating the shared
     * instance is enough.
     */
    public void update(Train train) {
        for (Viewer viewer : viewers.values()) {
            if (viewer.locomotiveId().equals(train.locomotiveId())) {
                viewer.bar().name(name(train));
                viewer.bar().progress(progress(train.charge(), config.chargeMax()));
            }
        }
    }

    /** Hides and forgets this rider's bar, if any. */
    public void hide(Player player) {
        Viewer viewer = viewers.remove(player.getUniqueId());
        if (viewer != null) {
            player.hideBossBar(viewer.bar());
        }
    }

    /** Hides every bar (plugin shutdown). */
    public void hideAll() {
        for (Viewer viewer : viewers.values()) {
            viewer.player().hideBossBar(viewer.bar());
        }
        viewers.clear();
    }

    private Component name(Train train) {
        double speed = displaySpeed(train.engineOn(), train.charge(), train.carCount(),
                train.speedPreset(), config);
        return Component.text(
                format(train.charge(), config.chargeMax(), speed, train.carCount()));
    }

    // ------------------------------------------------- pure, headless-testable

    /**
     * Builds the bar text, e.g. {@code ⚡ 72%  ·  6.4 b/s  ·  4 cars}: percent is
     * {@code round(charge / chargeMax * 100)} clamped to {@code [0, 100]} (0 when
     * {@code chargeMax == 0} — never NaN), speed is blocks/tick times 20 with one
     * decimal, and the car count is pluralized.
     */
    public static String format(double charge, double chargeMax, double speedBlocksPerTick,
                                int cars) {
        long percent = chargeMax > 0.0
                ? Math.round(Math.clamp(charge / chargeMax, 0.0, 1.0) * 100.0)
                : 0L;
        String blocksPerSecond = String.format(Locale.ROOT, "%.1f", speedBlocksPerTick * 20.0);
        String carsWord = cars == 1 ? "car" : "cars";
        return "⚡ " + percent + "%  ·  " + blocksPerSecond + " b/s  ·  " + cars + " " + carsWord;
    }

    /**
     * Boss-bar fill fraction: {@code charge / chargeMax} clamped to {@code [0, 1]},
     * 0 when {@code chargeMax == 0} (never NaN).
     */
    static float progress(double charge, double chargeMax) {
        if (chargeMax <= 0.0) {
            return 0.0f;
        }
        return (float) Math.clamp(charge / chargeMax, 0.0, 1.0);
    }

    /**
     * The speed shown to riders, in blocks per tick: the train's preset-adjusted
     * cruise target, or 0 when the engine is off or the battery is empty. Transient
     * powered-rail boost is deliberately excluded — the bar shows the sustained speed.
     */
    static double displaySpeed(boolean engineOn, double charge, int cars,
                               @Nullable String preset, RtConfig cfg) {
        if (!engineOn || !ChargeModel.canMove(charge)) {
            return 0.0;
        }
        return SpeedModel.withPreset(SpeedModel.cruise(cars, cfg),
                SpeedModel.presetMultiplier(preset, cfg), cfg);
    }

    /** One rider's bar and the train it tracks. */
    private record Viewer(Player player, BossBar bar, UUID locomotiveId) {
    }
}
