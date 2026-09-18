package com.gatehousemc.platform.fabric.command;

import com.gatehousemc.domain.*;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.platform.fabric.FabricRuntime;
import com.gatehousemc.platform.fabric.GatehouseMod;
import com.gatehousemc.runtime.CommandViewFormatter;
import com.gatehousemc.runtime.ReloadResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class GatehouseCommands {
    private static final int DEFAULT_PERMISSION_LEVEL = 3;
    private static final int LIST_LIMIT = 50;

    private GatehouseCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, Supplier<FabricRuntime> runtimeSupplier) {
        dispatcher.register(buildCommand("gatehouse", runtimeSupplier));
        dispatcher.register(buildCommand("gh", runtimeSupplier));
        dispatcher.register(buildCommand("wlreq", runtimeSupplier));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildCommand(
            String name, Supplier<FabricRuntime> runtimeSupplier) {
        return CommandManager.literal(name)
                .requires(source -> source.hasPermissionLevel(permissionLevel(runtimeSupplier)))
                .executes(context -> feedback(context, Messages.get("command.help")))
                .then(CommandManager.literal("list")
                        .executes(context -> list(context, runtimeSupplier, Optional.empty()))
                        .then(CommandManager.argument("status", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        new String[]{"pending", "resolving", "approved", "denied", "blocked"}, builder))
                                .executes(context -> listWithStatus(context, runtimeSupplier))))
                .then(CommandManager.literal("show")
                        .then(CommandManager.argument("request", StringArgumentType.word())
                                .executes(context -> show(context, runtimeSupplier))))
                .then(decision("approve", DecisionAction.APPROVE, runtimeSupplier))
                .then(decision("deny", DecisionAction.DENY, runtimeSupplier))
                .then(decision("block", DecisionAction.BLOCK, runtimeSupplier))
                .then(decision("undo", DecisionAction.UNDO, runtimeSupplier))
                .then(CommandManager.literal("unblock")
                        .then(CommandManager.argument("username", StringArgumentType.word())
                                .executes(context -> unblock(context, runtimeSupplier, ""))
                                .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> unblock(context, runtimeSupplier,
                                                context.getArgument("reason", String.class))))))
                .then(CommandManager.literal("status").executes(context -> status(context, runtimeSupplier)))
                .then(CommandManager.literal("reload").executes(context -> reload(context, runtimeSupplier)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> decision(
            String name, DecisionAction action, Supplier<FabricRuntime> runtimeSupplier) {
        return CommandManager.literal(name)
                .then(CommandManager.argument("request", StringArgumentType.word())
                        .executes(context -> decide(context, runtimeSupplier, action, ""))
                        .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> decide(context, runtimeSupplier, action,
                                        context.getArgument("reason", String.class)))));
    }

    private static int permissionLevel(Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        return runtime == null ? DEFAULT_PERMISSION_LEVEL : runtime.config().requests().commandPermissionLevel();
    }

    private static int listWithStatus(CommandContext<ServerCommandSource> context,
                                      Supplier<FabricRuntime> supplier) {
        String raw = context.getArgument("status", String.class);
        Optional<RequestStatus> status = parseStatus(raw);
        if (status.isEmpty()) return fail(context, Messages.get("command.invalid_status", raw));
        return list(context, supplier, status);
    }

    private static int list(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier,
                            Optional<RequestStatus> status) {
        FabricRuntime runtime = operational(context, supplier);
        if (runtime == null) return 0;
        ServerCommandSource source = context.getSource();
        runtime.commandExecutor().execute(() -> {
            try {
                List<WhitelistRequest> requests = runtime.repository().findByStatus(status, LIST_LIMIT + 1);
                source.getServer().execute(() -> {
                    if (requests.isEmpty()) {
                        source.sendFeedback(() -> Text.literal(Messages.get("command.no_requests")), false);
                        return;
                    }
                    requests.stream().limit(LIST_LIMIT).forEach(request ->
                            source.sendFeedback(() -> Text.literal(CommandViewFormatter.listEntry(request)), false));
                    if (requests.size() > LIST_LIMIT) {
                        source.sendFeedback(() -> Text.literal(Messages.get("command.list_truncated")), false);
                    }
                });
            } catch (RuntimeException error) {
                source.getServer().execute(() ->
                        source.sendError(Text.literal(Messages.get("command.operation_failed", safeMessage(error)))));
            }
        });
        return 1;
    }

    private static int show(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = operational(context, supplier);
        if (runtime == null) return 0;
        ServerCommandSource source = context.getSource();
        String key = context.getArgument("request", String.class);
        runtime.commandExecutor().execute(() -> {
            try {
                Optional<WhitelistRequest> request = resolveForShow(runtime, key);
                source.getServer().execute(() -> {
                    if (request.isEmpty()) {
                        source.sendError(Text.literal(Messages.get("command.request_not_found_key", key)));
                    } else {
                        source.sendFeedback(() -> Text.literal(CommandViewFormatter.details(request.get())), false);
                    }
                });
            } catch (RuntimeException error) {
                source.getServer().execute(() ->
                        source.sendError(Text.literal(Messages.get("command.operation_failed", safeMessage(error)))));
            }
        });
        return 1;
    }

    private static int decide(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier,
                              DecisionAction action, String reason) {
        FabricRuntime runtime = operational(context, supplier);
        if (runtime == null) return 0;
        ServerCommandSource source = context.getSource();
        String key = context.getArgument("request", String.class);
        runtime.commandExecutor().execute(() -> {
            try {
                Optional<WhitelistRequest> request = resolveForDecision(runtime, key, action);
                if (request.isEmpty()) {
                    String message = lookupMissMessage(action, key);
                    source.getServer().execute(() -> source.sendError(Text.literal(message)));
                    return;
                }
                runtime.decisions().decide(request.get().id(), action, principalOf(source),
                                Optional.ofNullable(reason).filter(value -> !value.isBlank()))
                        .thenAcceptAsync(result ->
                                        source.sendFeedback(() -> Text.literal(result.message()), false),
                                source.getServer()::execute)
                        .exceptionally(error -> {
                            source.getServer().execute(() -> source.sendError(Text.literal(
                                    Messages.get("command.decision_failed", safeMessage(error)))));
                            return null;
                        });
            } catch (RuntimeException error) {
                source.getServer().execute(() ->
                        source.sendError(Text.literal(Messages.get("command.operation_failed", safeMessage(error)))));
            }
        });
        return 1;
    }

    private static int unblock(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier,
                               String reason) {
        FabricRuntime runtime = operational(context, supplier);
        if (runtime == null) return 0;
        ServerCommandSource source = context.getSource();
        String username = context.getArgument("username", String.class);
        String normalized = username.toLowerCase(Locale.ROOT);
        runtime.commandExecutor().execute(() -> {
            try {
                boolean removed = runtime.decisions().unblock(normalized, principalOf(source),
                        reason == null ? "" : reason.trim());
                source.getServer().execute(() ->
                        source.sendFeedback(() -> Text.literal(removed
                                ? Messages.get("command.unblocked", username, source.getName())
                                : Messages.get("command.no_block", username)), false));
            } catch (RuntimeException error) {
                source.getServer().execute(() ->
                        source.sendError(Text.literal(Messages.get("command.operation_failed", safeMessage(error)))));
            }
        });
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null) return feedback(context, Messages.get("command.health_starting"));
        if (runtime.degraded()) {
            return feedback(context, Messages.get("command.health_core_degraded", runtime.providerHealthSummary()));
        }
        ServerCommandSource source = context.getSource();
        runtime.commandExecutor().execute(() -> {
            try {
                long pending = runtime.repository().pendingOutboxCount();
                source.getServer().execute(() -> source.sendFeedback(() -> Text.literal(
                        Messages.get("command.health_healthy", runtime.queueSize(), pending,
                                runtime.providerHealthSummary())), false));
            } catch (RuntimeException error) {
                source.getServer().execute(() -> source.sendFeedback(() -> Text.literal(
                        Messages.get("command.health_degraded", runtime.queueSize(), safeMessage(error),
                                runtime.providerHealthSummary())), false));
            }
        });
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null) return fail(context, Messages.get("command.starting"));
        ServerCommandSource source = context.getSource();
        GatehouseMod.reloadAsync().thenAccept(result -> source.getServer().execute(() -> reloadFeedback(source, result)));
        return feedback(context, Messages.get("command.reload_started"));
    }

    private static void reloadFeedback(ServerCommandSource source, ReloadResult result) {
        switch (result.status()) {
            case SUCCESS -> source.sendFeedback(() -> Text.literal(Messages.get("command.reload_succeeded")), false);
            case RESTART_REQUIRED -> source.sendError(Text.literal(Messages.get("command.reload_restart_required")));
            case FAILED -> source.sendError(Text.literal(Messages.get("command.reload_failed", result.detail())));
            case UNAVAILABLE -> source.sendError(Text.literal(Messages.get("command.not_available")));
        }
    }

    private static FabricRuntime operational(CommandContext<ServerCommandSource> context,
                                             Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null) {
            fail(context, Messages.get("command.starting"));
            return null;
        }
        if (runtime.degraded()) {
            fail(context, Messages.get("command.not_available"));
            return null;
        }
        return runtime;
    }

    private static AdminPrincipal principalOf(ServerCommandSource source) {
        if (source.getEntity() != null) {
            return new AdminPrincipal("minecraft", source.getName(), source.getName());
        }
        return AdminPrincipal.console();
    }

    private static Optional<WhitelistRequest> resolveForShow(FabricRuntime runtime, String value) {
        try {
            return runtime.find(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return runtime.latest(value);
        }
    }

    private static Optional<WhitelistRequest> resolveForDecision(FabricRuntime runtime, String value,
                                                                 DecisionAction action) {
        try {
            return runtime.find(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return action == DecisionAction.UNDO ? runtime.latestTerminal(value) : runtime.active(value);
        }
    }

    private static String lookupMissMessage(DecisionAction action, String key) {
        return action == DecisionAction.UNDO
                ? Messages.get("command.no_terminal_request", key)
                : Messages.get("command.no_active_request", key);
    }

    private static Optional<RequestStatus> parseStatus(String value) {
        try {
            return Optional.of(RequestStatus.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static int feedback(CommandContext<ServerCommandSource> context, String message) {
        context.getSource().sendFeedback(() -> Text.literal(message), false);
        return 1;
    }

    private static int fail(CommandContext<ServerCommandSource> context, String message) {
        context.getSource().sendError(Text.literal(message));
        return 0;
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
