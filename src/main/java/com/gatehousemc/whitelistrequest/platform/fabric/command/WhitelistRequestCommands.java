package com.gatehousemc.whitelistrequest.platform.fabric.command;

import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.i18n.Messages;
import com.gatehousemc.whitelistrequest.platform.fabric.FabricRuntime;
import com.gatehousemc.whitelistrequest.platform.fabric.WhitelistRequestMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class WhitelistRequestCommands {
    private WhitelistRequestCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, Supplier<FabricRuntime> runtimeSupplier) {
        dispatcher.register(CommandManager.literal("wlreq")
                .requires(source -> {
                    FabricRuntime runtime = runtimeSupplier.get();
                    return runtime != null && !runtime.degraded() && source.hasPermissionLevel(runtime.config().requests().commandPermissionLevel());
                })
                .then(CommandManager.literal("list")
                        .executes(context -> list(context, runtimeSupplier, Optional.empty()))
                        .then(CommandManager.argument("status", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(new String[]{"pending", "approved", "denied", "blocked"}, builder))
                                .executes(context -> list(context, runtimeSupplier, parseStatus(context.getArgument("status", String.class))))))
                .then(CommandManager.literal("show")
                        .then(CommandManager.argument("request", StringArgumentType.word()).executes(context -> show(context, runtimeSupplier))))
                .then(decision("approve", DecisionAction.APPROVE, runtimeSupplier))
                .then(decision("deny", DecisionAction.DENY, runtimeSupplier))
                .then(decision("block", DecisionAction.BLOCK, runtimeSupplier))
                .then(decision("undo", DecisionAction.UNDO, runtimeSupplier))
                .then(CommandManager.literal("unblock")
                        .then(CommandManager.argument("username", StringArgumentType.word())
                                .executes(context -> unblock(context, runtimeSupplier))
                                .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> unblock(context, runtimeSupplier)))))
                .then(CommandManager.literal("status").executes(context -> status(context, runtimeSupplier)))
                .then(CommandManager.literal("reload").executes(context -> {
                    WhitelistRequestMod.reloadAsync();
                    return feedback(context, Messages.get("command.reload_started"));
                })));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> decision(
            String name, DecisionAction action, Supplier<FabricRuntime> runtimeSupplier) {
        return CommandManager.literal(name)
                .then(CommandManager.argument("request", StringArgumentType.word())
                        .executes(context -> decide(context, runtimeSupplier, action, ""))
                        .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> decide(context, runtimeSupplier, action, context.getArgument("reason", String.class)))));
    }

    private static int list(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier, Optional<RequestStatus> status) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        ServerCommandSource source = context.getSource();
        runtime.commandExecutor().execute(() -> {
            var requests = runtime.repository().findByStatus(status, 50);
            source.getServer().execute(() -> {
                if (requests.isEmpty()) {
                    source.sendFeedback(() -> Text.literal(Messages.get("command.no_requests")), false);
                } else {
                    for (WhitelistRequest request : requests) {
                        source.sendFeedback(() -> Text.literal(shortId(request.id()) + " " + request.status() + " " + request.identity().exactUsername() + " attempts=" + request.attemptCount()), false);
                    }
                }
            });
        });
        return 1;
    }

    private static int show(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        ServerCommandSource source = context.getSource();
        String key = context.getArgument("request", String.class);
        runtime.commandExecutor().execute(() -> {
            Optional<WhitelistRequest> request = resolve(runtime, key);
            source.getServer().execute(() -> {
                if (request.isEmpty()) {
                    source.sendError(Text.literal(Messages.get("command.request_not_found")));
                } else {
                    WhitelistRequest value = request.get();
                    source.sendFeedback(() -> Text.literal(value.id() + " " + value.status() + " player=" + value.identity().exactUsername() + " uuid=" + value.identity().offlineUuid() + " attempts=" + value.attemptCount()), false);
                }
            });
        });
        return 1;
    }

    private static int decide(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier, DecisionAction action, String reason) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        ServerCommandSource source = context.getSource();
        String key = context.getArgument("request", String.class);
        runtime.commandExecutor().execute(() -> {
            Optional<WhitelistRequest> request = resolve(runtime, key);
            if (request.isEmpty()) {
                source.getServer().execute(() -> source.sendError(Text.literal(Messages.get("command.request_not_found"))));
                return;
            }
            runtime.decisions().decide(request.get().id(), action, principalOf(source), Optional.ofNullable(reason).filter(value -> !value.isBlank()))
                    .thenAcceptAsync(result -> source.sendFeedback(() -> Text.literal(result.message()), false), source.getServer()::execute)
                    .exceptionallyAsync(error -> {
                        source.getServer().execute(() -> source.sendError(Text.literal(Messages.get("command.decision_failed", safeMessage(error)))));
                        return null;
                    }, source.getServer()::execute);
        });
        return 1;
    }

    private static int unblock(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        ServerCommandSource source = context.getSource();
        String username = context.getArgument("username", String.class).toLowerCase(Locale.ROOT);
        runtime.commandExecutor().execute(() -> {
            boolean removed = runtime.decisions().unblock(username, principalOf(source), "");
            source.getServer().execute(() ->
                    source.sendFeedback(() -> Text.literal(removed ? Messages.get("command.unblocked", username, source.getName()) : Messages.get("command.no_block", username)), false));
        });
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        ServerCommandSource source = context.getSource();
        runtime.commandExecutor().execute(() -> {
            try {
                long pending = runtime.repository().pendingOutboxCount();
                source.getServer().execute(() ->
                        source.sendFeedback(() -> Text.literal(Messages.get("command.health_healthy", runtime.queueSize(), pending)), false));
            } catch (RuntimeException error) {
                source.getServer().execute(() ->
                        source.sendFeedback(() -> Text.literal(Messages.get("command.health_degraded", runtime.queueSize(), safeMessage(error))), false));
            }
        });
        return 1;
    }

    private static AdminPrincipal principalOf(ServerCommandSource source) {
        if (source.getEntity() != null) {
            return new AdminPrincipal("minecraft", source.getName(), source.getName());
        }
        return AdminPrincipal.console();
    }

    private static Optional<WhitelistRequest> resolve(FabricRuntime runtime, String value) {
        try {
            return runtime.find(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return runtime.active(value);
        }
    }

    private static Optional<RequestStatus> parseStatus(String value) {
        try { return Optional.of(RequestStatus.valueOf(value.toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }
    private static int feedback(CommandContext<ServerCommandSource> context, String message) { context.getSource().sendFeedback(() -> Text.literal(message), false); return 1; }
    private static int fail(CommandContext<ServerCommandSource> context, String message) { context.getSource().sendError(Text.literal(message)); return 0; }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
