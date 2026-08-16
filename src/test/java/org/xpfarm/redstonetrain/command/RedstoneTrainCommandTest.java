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
package org.xpfarm.redstonetrain.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.train.TrainRegistry;

/**
 * Headless tests for {@link RedstoneTrainCommand}: pure argument routing, permission
 * gating, reload delegation, tab completion, and the speed-preset fallback helpers.
 *
 * <p>The sender is a {@link Proxy}-based {@link CommandSender} stub — no live server.
 * Anything needing live entities (target-entity ray trace, riding lookup) is gate 7a.
 */
final class RedstoneTrainCommandTest {

    // ------------------------------------------------------------ stub sender

    /** Records every message sent; grants exactly the given permission nodes. */
    private static final class StubSender implements InvocationHandler {
        final List<String> messages = new ArrayList<>();
        final Set<String> permissions;

        StubSender(String... permissions) {
            this.permissions = Set.of(permissions);
        }

        CommandSender proxy() {
            return (CommandSender) Proxy.newProxyInstance(
                    CommandSender.class.getClassLoader(),
                    new Class<?>[] {CommandSender.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "hasPermission" -> {
                    return args != null && args.length == 1 && args[0] instanceof String node
                            && permissions.contains(node);
                }
                case "sendMessage" -> {
                    if (args != null && args.length >= 1) {
                        if (args[0] instanceof Component component) {
                            messages.add(plain(component));
                        } else if (args[0] instanceof String text) {
                            messages.add(text);
                        }
                    }
                    return null;
                }
                case "getName" -> {
                    return "stub";
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "StubSender";
                }
                default -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type.isPrimitive() && type != void.class) {
                        return (byte) 0;
                    }
                    return null;
                }
            }
        }

        boolean anyMessageContains(String needle) {
            return messages.stream().anyMatch(m -> m.contains(needle));
        }
    }

    /** Flattens a text component tree to its plain content, no serializer needed. */
    private static String plain(Component component) {
        StringBuilder sb = new StringBuilder();
        flatten(component, sb);
        return sb.toString();
    }

    private static void flatten(Component component, StringBuilder sb) {
        if (component instanceof TextComponent text) {
            sb.append(text.content());
        }
        for (Component child : component.children()) {
            flatten(child, sb);
        }
    }

    // --------------------------------------------------------------- fixtures

    private static RtConfig config(Map<String, Double> presets) {
        return new RtConfig(0.40, 0.36, 2, 0.02, 0.10, 6, 0.06, 60, 100.0, 0.2, 50.0,
                10.0, 90.0, 2.0, 0.5, true, presets);
    }

    private static RedstoneTrainCommand command(RtConfig cfg,
                                                RedstoneTrainCommand.ConfigReloader reloader) {
        return new RedstoneTrainCommand(new TrainRegistry(), () -> cfg, reloader);
    }

    private static RedstoneTrainCommand command() {
        return command(config(Map.of("cruise", 1.0)), () -> null);
    }

    // ---------------------------------------------------------------- routing

    @Test
    void unknownSubcommandShowsUsage() {
        StubSender sender = new StubSender("redstonetrain.use", "redstonetrain.admin");
        command().route(sender.proxy(), new String[] {"bogus"});
        assertTrue(sender.anyMessageContains("Usage:"), () -> sender.messages.toString());
    }

    @Test
    void noArgumentsShowsUsage() {
        StubSender sender = new StubSender("redstonetrain.use");
        command().route(sender.proxy(), new String[0]);
        assertTrue(sender.anyMessageContains("Usage:"), () -> sender.messages.toString());
    }

    @Test
    void subcommandsAreCaseInsensitive() {
        AtomicInteger reloads = new AtomicInteger();
        StubSender sender = new StubSender("redstonetrain.admin");
        command(config(Map.of("cruise", 1.0)), () -> {
            reloads.incrementAndGet();
            return null;
        }).route(sender.proxy(), new String[] {"RELOAD"});
        assertEquals(1, reloads.get());
    }

    // ----------------------------------------------------------------- reload

    @Test
    void reloadWithoutAdminPermissionIsDeniedAndNeverReloads() {
        AtomicInteger reloads = new AtomicInteger();
        StubSender sender = new StubSender("redstonetrain.use");
        command(config(Map.of("cruise", 1.0)), () -> {
            reloads.incrementAndGet();
            return null;
        }).route(sender.proxy(), new String[] {"reload"});
        assertEquals(0, reloads.get(), "reload must not run without redstonetrain.admin");
        assertTrue(sender.anyMessageContains("permission"), () -> sender.messages.toString());
    }

    @Test
    void reloadSuccessMessagesSuccess() {
        StubSender sender = new StubSender("redstonetrain.admin");
        command(config(Map.of("cruise", 1.0)), () -> null)
                .route(sender.proxy(), new String[] {"reload"});
        assertTrue(sender.anyMessageContains("reloaded"), () -> sender.messages.toString());
    }

    @Test
    void reloadFailureReportsTheValidationError() {
        StubSender sender = new StubSender("redstonetrain.admin");
        command(config(Map.of("cruise", 1.0)),
                () -> "Invalid config value for 'speed.cap': -1.0")
                .route(sender.proxy(), new String[] {"reload"});
        assertTrue(sender.anyMessageContains("Invalid config value for 'speed.cap'"),
                () -> sender.messages.toString());
        assertFalse(sender.anyMessageContains("reloaded"),
                "a failed reload must not claim success");
    }

    // ------------------------------------------------------------------- info

    @Test
    void infoWithoutUsePermissionIsDenied() {
        StubSender sender = new StubSender(); // no permissions at all
        command().route(sender.proxy(), new String[] {"info"});
        assertTrue(sender.anyMessageContains("permission"), () -> sender.messages.toString());
    }

    @Test
    void infoFromConsoleExplainsPlayersOnly() {
        StubSender sender = new StubSender("redstonetrain.use");
        command().route(sender.proxy(), new String[] {"info"});
        assertTrue(sender.anyMessageContains("player"), () -> sender.messages.toString());
    }

    // -------------------------------------------------- preset fallback (Task 3 flag)

    @Test
    void fallbackPresetPrefersCruiseWhenConfigured() {
        Map<String, Double> presets = new LinkedHashMap<>();
        presets.put("slow", 0.5);
        presets.put("cruise", 1.0);
        assertEquals("cruise", RedstoneTrainCommand.fallbackPreset(config(presets)));
    }

    @Test
    void fallbackPresetUsesFirstConfiguredWhenCruiseIsMissing() {
        Map<String, Double> presets = new LinkedHashMap<>();
        presets.put("crawl", 0.25);
        presets.put("fast", 1.2);
        assertEquals("crawl", RedstoneTrainCommand.fallbackPreset(config(presets)));
    }

    @Test
    void fallbackPresetSurvivesAnEmptyPresetMap() {
        assertEquals("cruise", RedstoneTrainCommand.fallbackPreset(config(Map.of())));
    }

    @Test
    void presetMultiplierUsesTheNamedPreset() {
        Map<String, Double> presets = new LinkedHashMap<>();
        presets.put("slow", 0.5);
        presets.put("cruise", 1.0);
        assertEquals(0.5, RedstoneTrainCommand.presetMultiplier("slow", config(presets)));
    }

    @Test
    void presetMultiplierFallsBackForUnknownPreset() {
        Map<String, Double> presets = new LinkedHashMap<>();
        presets.put("crawl", 0.25);
        assertEquals(0.25, RedstoneTrainCommand.presetMultiplier("cruise", config(presets)),
                "unknown preset must fall back to the first configured preset");
    }

    @Test
    void presetMultiplierIsOneWhenNoPresetsConfigured() {
        assertEquals(1.0, RedstoneTrainCommand.presetMultiplier("cruise", config(Map.of())));
    }

    // --------------------------------------------------------- tab completion

    @Test
    void completionOffersOnlyPermittedSubcommands() {
        assertEquals(List.of("info", "reload"),
                RedstoneTrainCommand.complete("", true, true));
        assertEquals(List.of("info"), RedstoneTrainCommand.complete("", true, false));
        assertEquals(List.of("reload"), RedstoneTrainCommand.complete("", false, true));
        assertEquals(List.of(), RedstoneTrainCommand.complete("", false, false));
    }

    @Test
    void completionFiltersByPrefixCaseInsensitively() {
        assertEquals(List.of("reload"), RedstoneTrainCommand.complete("RE", true, true));
        assertEquals(List.of("info"), RedstoneTrainCommand.complete("in", true, true));
        assertEquals(List.of(), RedstoneTrainCommand.complete("x", true, true));
    }
}
