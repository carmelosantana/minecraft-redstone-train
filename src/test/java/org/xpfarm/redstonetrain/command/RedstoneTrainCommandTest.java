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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.xpfarm.redstonetrain.config.RtConfig;
import org.xpfarm.redstonetrain.item.HeadlessKeys;
import org.xpfarm.redstonetrain.item.RtItems;
import org.xpfarm.redstonetrain.train.TrainRegistry;

/**
 * Headless tests for {@link RedstoneTrainCommand}: pure argument routing, permission
 * gating, reload delegation, and tab completion. Preset resolution moved to
 * {@code SpeedModelTest} with the shared {@code SpeedModel.presetMultiplier} helper.
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
        return new RedstoneTrainCommand(new TrainRegistry(), () -> cfg, reloader,
                new RtItems(HeadlessKeys.create()));
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

    // ------------------------------------------------------------------- give

    @Test
    void giveWithoutAdminPermissionIsDenied() {
        StubSender sender = new StubSender("redstonetrain.use");
        command().route(sender.proxy(), new String[] {"give", "Steve", "wrench"});
        assertTrue(sender.anyMessageContains("permission"), () -> sender.messages.toString());
    }

    @Test
    void giveWithMissingArgumentsShowsGiveUsage() {
        StubSender sender = new StubSender("redstonetrain.admin");
        command().route(sender.proxy(), new String[] {"give"});
        assertTrue(sender.anyMessageContains("Usage: /redstonetrain give"),
                () -> sender.messages.toString());

        StubSender partial = new StubSender("redstonetrain.admin");
        command().route(partial.proxy(), new String[] {"give", "Steve"});
        assertTrue(partial.anyMessageContains("Usage: /redstonetrain give"),
                () -> partial.messages.toString());
    }

    @Test
    void giveWithUnknownItemListsValidTypes() {
        StubSender sender = new StubSender("redstonetrain.admin");
        command().route(sender.proxy(), new String[] {"give", "Steve", "minecart"});
        assertTrue(sender.anyMessageContains("locomotive"), () -> sender.messages.toString());
        assertTrue(sender.anyMessageContains("wrench"), () -> sender.messages.toString());
    }

    @Test
    void giveWithNonIntegerAmountShowsUsageError() {
        StubSender sender = new StubSender("redstonetrain.admin");
        command().route(sender.proxy(), new String[] {"give", "Steve", "wrench", "abc"});
        assertTrue(sender.anyMessageContains("whole number"), () -> sender.messages.toString());
    }

    @Test
    void parseAmountDefaultsClampsAndRejects() {
        assertEquals(1, RedstoneTrainCommand.parseAmount(null), "absent -> default 1");
        assertEquals(1, RedstoneTrainCommand.parseAmount(""), "empty -> default 1");
        assertEquals(1, RedstoneTrainCommand.parseAmount("0"), "0 clamps up to 1");
        assertEquals(1, RedstoneTrainCommand.parseAmount("-5"), "negative clamps up to 1");
        assertEquals(1, RedstoneTrainCommand.parseAmount("1"));
        assertEquals(32, RedstoneTrainCommand.parseAmount("32"));
        assertEquals(64, RedstoneTrainCommand.parseAmount("64"));
        assertEquals(64, RedstoneTrainCommand.parseAmount("100"), "over-cap clamps down to 64");
        assertEquals(RedstoneTrainCommand.AMOUNT_INVALID,
                RedstoneTrainCommand.parseAmount("abc"), "non-integer signals an error");
        assertEquals(RedstoneTrainCommand.AMOUNT_INVALID,
                RedstoneTrainCommand.parseAmount("1.5"), "decimals signal an error");
    }

    @Test
    void clampAmountBoundsToOneThroughSixtyFour() {
        assertEquals(1, RedstoneTrainCommand.clampAmount(Integer.MIN_VALUE));
        assertEquals(1, RedstoneTrainCommand.clampAmount(0));
        assertEquals(1, RedstoneTrainCommand.clampAmount(1));
        assertEquals(64, RedstoneTrainCommand.clampAmount(64));
        assertEquals(64, RedstoneTrainCommand.clampAmount(Integer.MAX_VALUE));
    }

    @Test
    void giveItemResolvesCaseInsensitivelyAndRejectsUnknown() {
        assertEquals(RedstoneTrainCommand.GiveItem.LOCOMOTIVE,
                RedstoneTrainCommand.GiveItem.resolve("locomotive"));
        assertEquals(RedstoneTrainCommand.GiveItem.WRENCH,
                RedstoneTrainCommand.GiveItem.resolve("WRENCH"));
        assertEquals(RedstoneTrainCommand.GiveItem.LOCOMOTIVE,
                RedstoneTrainCommand.GiveItem.resolve("Locomotive"));
        assertNull(RedstoneTrainCommand.GiveItem.resolve("minecart"));
        assertNull(RedstoneTrainCommand.GiveItem.resolve(""));
    }

    // --------------------------------------------------------- tab completion

    @Test
    void completionOffersOnlyPermittedSubcommands() {
        assertEquals(List.of("info", "give", "reload"),
                RedstoneTrainCommand.complete("", true, true));
        assertEquals(List.of("info"), RedstoneTrainCommand.complete("", true, false));
        assertEquals(List.of("give", "reload"),
                RedstoneTrainCommand.complete("", false, true));
        assertEquals(List.of(), RedstoneTrainCommand.complete("", false, false));
    }

    @Test
    void completionFiltersByPrefixCaseInsensitively() {
        assertEquals(List.of("reload"), RedstoneTrainCommand.complete("RE", true, true));
        assertEquals(List.of("info"), RedstoneTrainCommand.complete("in", true, true));
        assertEquals(List.of("give"), RedstoneTrainCommand.complete("G", true, true));
        assertEquals(List.of(), RedstoneTrainCommand.complete("x", true, true));
    }

    @Test
    void giveItemCompletionFiltersByPrefix() {
        assertEquals(List.of("locomotive", "wrench"),
                RedstoneTrainCommand.completeGiveItems(""));
        assertEquals(List.of("locomotive"), RedstoneTrainCommand.completeGiveItems("LOC"));
        assertEquals(List.of("wrench"), RedstoneTrainCommand.completeGiveItems("w"));
        assertEquals(List.of(), RedstoneTrainCommand.completeGiveItems("x"));
    }
}
