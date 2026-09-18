package com.gatehousemc.platform.neoforge;

import com.gatehousemc.domain.*;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.runtime.CommandViewFormatter;
import com.gatehousemc.runtime.ReloadResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class GatehouseNeoForgeCommands {
    private static final int DEFAULT_PERMISSION_LEVEL = 3;
    private static final int LIST_LIMIT = 50;

    private GatehouseNeoForgeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Supplier<NeoForgeRuntime> runtimeSupplier) {
        dispatcher.register(build("gatehouse", runtimeSupplier));
        dispatcher.register(build("gh", runtimeSupplier));
        dispatcher.register(build("wlreq", runtimeSupplier));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> build(
            String name, Supplier<NeoForgeRuntime> supplier) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(permissionLevel(supplier)))
                .executes(context -> feedback(context, Messages.get("command.help")))
                .then(Commands.literal("list")
                        .executes(context -> list(context, supplier, Optional.empty()))
                        .then(Commands.argument("status", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        new String[]{"pending", "resolving", "approved", "denied", "blocked"}, builder))
                                .executes(context -> listWithStatus(context, supplier))))
                .then(Commands.literal("show")
                        .then(Commands.argument("request", StringArgumentType.word())
                                .executes(context -> show(context, supplier))))
                .then(decision("approve", DecisionAction.APPROVE, supplier))
                .then(decision("deny", DecisionAction.DENY, supplier))
                .then(decision("block", DecisionAction.BLOCK, supplier))
                .then(decision("undo", DecisionAction.UNDO, supplier))
                .then(Commands.literal("unblock")
                        .then(Commands.argument("username", StringArgumentType.word())
                                .executes(context -> unblock(context, supplier, ""))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> unblock(context, supplier,
                                                context.getArgument("reason", String.class))))))
                .then(Commands.literal("status").executes(context -> status(context, supplier)))
                .then(Commands.literal("reload").executes(context -> reload(context, supplier)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> decision(
            String name, DecisionAction action, Supplier<NeoForgeRuntime> supplier) {
        return Commands.literal(name)
                .then(Commands.argument("request", StringArgumentType.word())
                        .executes(context -> decide(context, supplier, action, ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> decide(context, supplier, action,
                                        context.getArgument("reason", String.class)))));
    }

    private static int permissionLevel(Supplier<NeoForgeRuntime> supplier) {
        NeoForgeRuntime runtime = supplier.get();
        return runtime == null ? DEFAULT_PERMISSION_LEVEL : runtime.config().requests().commandPermissionLevel();
    }

    private static int listWithStatus(CommandContext<CommandSourceStack> context,
                                      Supplier<NeoForgeRuntime> supplier) {
        String raw = context.getArgument("status", String.class);
        Optional<RequestStatus> status = parseStatus(raw);
        if (status.isEmpty()) return fail(context, Messages.get("command.invalid_status", raw));
        return list(context, supplier, status);
    }

    private static int list(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier,
                            Optional<RequestStatus> status) {
        NeoForgeRuntime runtime = operational(context, supplier);
        if (runtime == null) return 0;
        CommandSourceStack source = context.getSource();
        runtime.commandExecutor().execute(() -> {
            try {
                List<WhitelistRequest> requests = runtime.repository().findByStatus(status, LIST_LIMIT + 1);
                source.getServer().execute(() -> {
                    if (requests.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(Messages.get("command.no_requests")), false);
                        return;
                    }
                    requests.stream().limit(LIST_LIMIT).forEach(request ->
                            source.sendSuccess(() -> Component.literal(CommandViewFormatter.listEntry(request)), false));
                    if (requests.size() > LIST_LIMIT) {
                        source.sendSuccess(() -> Component.literal(Messages.get("command.list_truncated")), false);
                    }
                });
            } catch (RuntimeException error) {
                source.getServer().execute(() ->
                        source.sendFailure(Component.literal(Messages.get("command.operation_failed", safeMessage(error)))));
            }
        });
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier) {
        NeoForgeRuntime runtime = operational(context, supplier);
        if (runtime == null) return 0;
        CommandSourceStack source = context.getSource();
        String key = context.getArgument("request", String.class);
        runtime.commandExecutor().execute(() -> {
            try {
                Optional<WhitelistRequest> request = resolveForShow(runtime, key);
                source.getServer().execute(() -> {
                    if (request.isEmpty()) {
                        source.sendFailure(Component.literal(Messages.get("command.request_not_found_key", key)));
                    } else {
                        source.sendSuccess(() -> Component.literal(CommandViewFormatter.details(request.get())), false);
                    }
                });
            } catch (RuntimeException error) {
                source.getServer().execute(() ->
                        source.sendFailure(Component.literal(Messages.get("command.operation_failed", safeMessage(error)))));
            }
        });
        return 1;
    }

    private static int decide(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier,
                              DecisionAction action, String reason) {
        NeoForgeRuntime runtime = operational(context, supplier);
        if (runtime == null) return 0;
        CommandSourceStack source = context.getSource();
        String key = context.getArgument("request", String.class);
        runtime.commandExecutor().execute(() -> {
            try {
                Optional<WhitelistRequest> request = resolveForDecision(runtime, key, action);
                if (request.isEmpty()) {
                    String message = lookupMissMessage(action, key);
                    source.getServer().execute(() -> source.sendFailure(Component.literal(message)));
                    return;
                }
                runtime.decisions().decide(request.get().id(), action, principalOf(source),
                                Optional.ofNullable(reason).filter(value -> !value.isBlank()))
                        .thenAcceptAsync(result ->
                                        source.sendSuccess(() -> Component.literal(result.message()), false),
                                source.getServer()::execute)
                        .exceptionally(error -> {
                            source.getServer().execute(() -> source.sendFailure(Component.literal(
                                    Messages.get("command.decision_failed", safeMessage(error)))));
                            return null;
                        });
            } catch (RuntimeException error) {
                source.getServer().execute(() ->
                        source.sendFailure(Component.literal(Messages.get("command.operation_failed", safeMessage(error)))));
            }
        });
        return 1;
    }

    private static int unblock(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier,
                               String reason) {
        NeoForgeRuntime runtime = operational(context, supplier);
        if (runtime == null) return 0;
        CommandSourceStack source = context.getSource();
        String username = context.getArgument("username", String.class);
        String normalized = username.toLowerCase(Locale.ROOT);
        runtime.commandExecutor().execute(() -> {
            try {
                boolean removed = runtime.decisions().unblock(normalized, principalOf(source),
                        reason == null ? "" : reason.trim());
                source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(removed
                        ? Messages.get("command.unblocked", username, source.getTextName())
                        : Messages.get("command.no_block", username)), false));
            } catch (RuntimeException error) {
                source.getServer().execute(() ->
                        source.sendFailure(Component.literal(Messages.get("command.operation_failed", safeMessage(error)))));
            }
        });
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier) {
        NeoForgeRuntime runtime = supplier.get();
        if (runtime == null) return feedback(context, Messages.get("command.health_starting"));
        if (runtime.degraded()) {
            return feedback(context, Messages.get("command.health_core_degraded", runtime.providerHealthSummary()));
        }
        CommandSourceStack source = context.getSource();
        runtime.commandExecutor().execute(() -> {
            try {
                long pending = runtime.repository().pendingOutboxCount();
                source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(
                        Messages.get("command.health_healthy", runtime.queueSize(), pending,
                                runtime.providerHealthSummary())), false));
            } catch (RuntimeException error) {
                source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(
                        Messages.get("command.health_degraded", runtime.queueSize(), safeMessage(error),
                                runtime.providerHealthSummary())), false));
            }
        });
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier) {
        NeoForgeRuntime runtime = supplier.get();
        if (runtime == null) return fail(context, Messages.get("command.starting"));
        CommandSourceStack source = context.getSource();
        GatehouseNeoForgeMod.reloadAsync().thenAccept(result ->
                source.getServer().execute(() -> reloadFeedback(source, result)));
        return feedback(context, Messages.get("command.reload_started"));
    }

    private static void reloadFeedback(CommandSourceStack source, ReloadResult result) {
        switch (result.status()) {
            case SUCCESS -> source.sendSuccess(() -> Component.literal(Messages.get("command.reload_succeeded")), false);
            case RESTART_REQUIRED -> source.sendFailure(Component.literal(Messages.get("command.reload_restart_required")));
            case FAILED -> source.sendFailure(Component.literal(Messages.get("command.reload_failed", result.detail())));
            case UNAVAILABLE -> source.sendFailure(Component.literal(Messages.get("command.not_available")));
        }
    }

    private static NeoForgeRuntime operational(CommandContext<CommandSourceStack> context,
                                             Supplier<NeoForgeRuntime> supplier) {
        NeoForgeRuntime runtime = supplier.get();
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

    private static AdminPrincipal principalOf(CommandSourceStack source) {
        String name = source.getTextName();
        return source.getEntity() == null ? AdminPrincipal.console()
                : new AdminPrincipal("minecraft", name, name);
    }

    private static Optional<WhitelistRequest> resolveForShow(NeoForgeRuntime runtime, String value) {
        try {
            return runtime.find(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return runtime.latest(value);
        }
    }

    private static Optional<WhitelistRequest> resolveForDecision(NeoForgeRuntime runtime, String value,
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

    private static int feedback(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
