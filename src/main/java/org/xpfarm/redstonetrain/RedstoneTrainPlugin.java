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
package org.xpfarm.redstonetrain;

import java.util.Objects;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import org.xpfarm.redstonetrain.command.RedstoneTrainCommand;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.control.MovementController;
import org.xpfarm.redstonetrain.display.ChargeDisplay;
import org.xpfarm.redstonetrain.item.RtItems;
import org.xpfarm.redstonetrain.item.RtKeys;
import org.xpfarm.redstonetrain.item.RtRecipes;
import org.xpfarm.redstonetrain.listener.CouplingListener;
import org.xpfarm.redstonetrain.listener.InteractionListener;
import org.xpfarm.redstonetrain.train.Train;
import org.xpfarm.redstonetrain.train.TrainCodec;
import org.xpfarm.redstonetrain.train.TrainRegistry;

/**
 * Plugin entry point: assembles every component (Task 8 wiring).
 *
 * <p><strong>Enable order.</strong> {@code config.yml} is validated into an immutable
 * {@link RtConfig} first (an invalid file logs the offending key and disables the
 * plugin — no half-wired state). Then the config-independent core is built
 * ({@link RtKeys}, {@link RtItems}, {@link TrainRegistry}, {@link TrainCodec}),
 * recipes are registered, trains are restored from the PDC of every already-loaded
 * chunk (plus an {@link EntitiesLoadEvent} listener for chunks that load later), and
 * finally {@link #wireConfigServices} builds everything that holds the config
 * snapshot: {@link ChargeDisplay}, both listeners, and the {@link MovementController}
 * task (every tick, period 1).
 *
 * <p><strong>Reload.</strong> All config-holding services take {@link RtConfig} as an
 * immutable constructor argument, so {@code /redstonetrain reload} re-injects by
 * rebuilding them: cancel the task, unregister the listeners, swap the snapshot, and
 * wire fresh instances. On a validation error the old wiring stays untouched and the
 * error message is returned to the command. The registry, codec, keys, and items are
 * config-free and survive reloads, so no train state is lost.
 *
 * <p><strong>Per-tick display.</strong> {@link MovementController} deliberately does
 * not know about the boss bar; the scheduled task runs the controller and then pushes
 * a {@link ChargeDisplay#update} for every running train so riders see live charge
 * and speed.
 *
 * <p>Geyser/Bedrock safety: commands, chat components, boss bars, PDC, and
 * server-side physics only — no client-specific packets anywhere in the plugin.
 */
public final class RedstoneTrainPlugin extends JavaPlugin {

    // Config-independent core; built once per enable, survives reloads.
    private @Nullable RtKeys keys;
    private @Nullable RtItems items;
    private @Nullable TrainRegistry registry;
    private @Nullable TrainCodec codec;

    // Config snapshot and the services rebuilt from it on every (re)load.
    private @Nullable RtConfig config;
    private @Nullable ChargeDisplay display;
    private @Nullable CouplingListener couplingListener;
    private @Nullable InteractionListener interactionListener;
    private @Nullable BukkitTask movementTask;

    @Override
    public void onEnable() {
        // 1. Validated config snapshot; disable gracefully on a bad config.yml.
        saveDefaultConfig();
        RtConfig loaded;
        try {
            loaded = RtConfig.from(getConfig());
        } catch (IllegalArgumentException invalid) {
            getLogger().severe("config.yml is invalid: " + invalid.getMessage());
            getLogger().severe("Fix the value (or delete config.yml to regenerate the "
                    + "defaults) and restart. Disabling RedstoneTrain.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        config = loaded;

        // 2. Config-independent core.
        keys = new RtKeys(this);
        items = new RtItems(keys);
        registry = new TrainRegistry();
        codec = new TrainCodec(keys);

        // 3. Crafting recipes.
        RtRecipes.register(this, items, keys);

        // 6. Restore trains from PDC: worlds/chunks already loaded now, later chunk
        //    loads via the EntitiesLoadEvent listener (registered once; config-free).
        int restored = rebuildRegistryFromLoadedWorlds();
        getServer().getPluginManager().registerEvents(new LocomotiveRestoreListener(), this);

        // 4 + 5. Config-holding services: display, listeners, movement task.
        wireConfigServices(loaded);

        // Command executor + tab completion.
        PluginCommand command = Objects.requireNonNull(getCommand("redstonetrain"),
                "plugin.yml must declare the redstonetrain command");
        RedstoneTrainCommand executor = new RedstoneTrainCommand(
                registry, () -> Objects.requireNonNull(config), this::reloadRtConfig);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        getLogger().info("RedstoneTrain enabled; restored " + restored
                + (restored == 1 ? " train" : " trains") + " from loaded chunks.");
    }

    @Override
    public void onDisable() {
        if (movementTask != null) {
            movementTask.cancel();
            movementTask = null;
        }
        if (display != null) {
            display.hideAll();
        }
        persistAllTrains();
        getLogger().info("RedstoneTrain disabled.");
    }

    // ------------------------------------------------------------ config wiring

    /**
     * Builds (or rebuilds, on reload) every service that holds the immutable config
     * snapshot, tearing down the previous generation first: the movement task is
     * cancelled, the old listeners are unregistered, and riders' boss bars are
     * re-shown from the fresh display so nobody keeps a stale bar.
     */
    private void wireConfigServices(RtConfig fresh) {
        if (movementTask != null) {
            movementTask.cancel();
        }
        if (couplingListener != null) {
            HandlerList.unregisterAll(couplingListener);
        }
        if (interactionListener != null) {
            HandlerList.unregisterAll(interactionListener);
        }
        if (display != null) {
            display.hideAll();
        }

        config = fresh;
        TrainRegistry registry = Objects.requireNonNull(this.registry);
        TrainCodec codec = Objects.requireNonNull(this.codec);
        RtItems items = Objects.requireNonNull(this.items);
        RtKeys keys = Objects.requireNonNull(this.keys);

        normalizeTrainPresets(fresh);

        ChargeDisplay display = new ChargeDisplay(fresh);
        this.display = display;
        couplingListener = new CouplingListener(this, fresh, registry, codec, items, keys);
        interactionListener =
                new InteractionListener(this, fresh, registry, codec, items, display);
        getServer().getPluginManager().registerEvents(couplingListener, this);
        getServer().getPluginManager().registerEvents(interactionListener, this);

        MovementController controller = new MovementController(this, fresh, registry, codec);
        movementTask = getServer().getScheduler().runTaskTimer(this, () -> {
            controller.run();
            // The controller is display-agnostic; push rider bar updates here.
            for (Train train : registry.all()) {
                if (train.engineOn()) {
                    display.update(train);
                }
            }
        }, 1L, 1L);

        reshowRiderBars(display, registry);
    }

    /**
     * {@code /redstonetrain reload}: rebuild the snapshot from disk and re-wire.
     *
     * @return {@code null} on success, otherwise the validation error message (the
     *     previous configuration and wiring stay active)
     */
    private @Nullable String reloadRtConfig() {
        reloadConfig();
        RtConfig fresh;
        try {
            fresh = RtConfig.from(getConfig());
        } catch (IllegalArgumentException invalid) {
            getLogger().warning("Reload rejected: " + invalid.getMessage());
            return invalid.getMessage();
        }
        wireConfigServices(fresh);
        getLogger().info("Configuration reloaded and services re-wired.");
        return null;
    }

    /**
     * Task-3 fallback: a train whose persisted preset no longer exists in the config
     * (e.g. {@code cruise} removed or renamed) is moved to
     * {@link RedstoneTrainCommand#fallbackPreset} so wrench cycling and the displayed
     * speed stay sensible instead of silently using an unknown name.
     */
    private void normalizeTrainPresets(RtConfig cfg) {
        if (registry == null || cfg.speedPresets().isEmpty()) {
            return;
        }
        String fallback = RedstoneTrainCommand.fallbackPreset(cfg);
        for (Train train : registry.all()) {
            if (!cfg.speedPresets().containsKey(train.speedPreset())) {
                train.setSpeedPreset(fallback);
            }
        }
    }

    /** After a reload, players already riding a train get their boss bar back. */
    private void reshowRiderBars(ChargeDisplay display, TrainRegistry registry) {
        for (Train train : registry.all()) {
            for (Minecart member : resolvableMembers(train)) {
                for (Entity passenger : member.getPassengers()) {
                    if (passenger instanceof Player player) {
                        display.show(player, train);
                    }
                }
            }
        }
    }

    /** Every member entity of the train that currently resolves to a live minecart. */
    private java.util.List<Minecart> resolvableMembers(Train train) {
        java.util.List<Minecart> members = new java.util.ArrayList<>(train.carCount() + 1);
        if (getServer().getEntity(train.locomotiveId()) instanceof Minecart loco) {
            members.add(loco);
        }
        for (java.util.UUID carId : train.cars()) {
            if (getServer().getEntity(carId) instanceof Minecart car) {
                members.add(car);
            }
        }
        return members;
    }

    // -------------------------------------------------------- PDC persistence

    /** Scans every loaded world's entities and restores tagged locomotives. */
    private int rebuildRegistryFromLoadedWorlds() {
        int restored = 0;
        for (World world : getServer().getWorlds()) {
            for (Minecart cart : world.getEntitiesByClass(Minecart.class)) {
                if (restoreLocomotive(cart)) {
                    restored++;
                }
            }
        }
        return restored;
    }

    /** Restores one tagged locomotive into the registry; idempotent. */
    private boolean restoreLocomotive(Entity entity) {
        TrainRegistry registry = this.registry;
        TrainCodec codec = this.codec;
        RtConfig cfg = this.config;
        if (registry == null || codec == null || cfg == null
                || !(entity instanceof Minecart cart)
                || !codec.isLocomotiveEntity(cart)
                || registry.byLocomotive(cart.getUniqueId()) != null) {
            return false;
        }
        return codec.read(cart).map(train -> {
            if (!cfg.speedPresets().isEmpty()
                    && !cfg.speedPresets().containsKey(train.speedPreset())) {
                train.setSpeedPreset(RedstoneTrainCommand.fallbackPreset(cfg));
            }
            registry.register(train);
            return true;
        }).orElse(false);
    }

    /** onDisable: writes every registered train to its locomotive's PDC via the codec. */
    private void persistAllTrains() {
        if (registry == null || codec == null) {
            return; // enable bailed out on an invalid config; nothing to persist
        }
        int persisted = 0;
        for (Train train : registry.all()) {
            if (getServer().getEntity(train.locomotiveId()) instanceof Minecart loco) {
                codec.write(loco, train);
                persisted++;
            }
        }
        getLogger().info("Persisted " + persisted
                + (persisted == 1 ? " train" : " trains") + " to PDC.");
    }

    /**
     * Chunks (and their entities) that load after enable: restore any tagged
     * locomotive so trains far from spawn come back without a server restart.
     */
    private final class LocomotiveRestoreListener implements Listener {
        @EventHandler
        public void onEntitiesLoad(EntitiesLoadEvent event) {
            for (Entity entity : event.getEntities()) {
                restoreLocomotive(entity);
            }
        }
    }
}
