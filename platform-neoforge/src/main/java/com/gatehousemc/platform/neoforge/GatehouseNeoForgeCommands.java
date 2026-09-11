package com.gatehousemc.platform.neoforge;

import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.DecisionAction;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.i18n.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class GatehouseNeoForgeCommands {
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
                .requires(source -> {
                    NeoForgeRuntime runtime = supplier.get();
                    return runtime != null && !runtime.degraded()
                            && source.hasPermission(runtime.config().requests().commandPermissionLevel());
                })
                .then(Commands.literal("list")
                        .executes(context -> list(context, supplier, Optional.empty()))
                        .then(Commands.argument("status", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        new String[]{"pending", "approved", "denied", "blocked"}, builder))
                                .executes(context -> list(context, supplier,
                                        parseStatus(context.getArgument("status", String.class))))))
                .then(Commands.literal("show")
                        .then(Commands.argument("request", StringArgumentType.word())
                                .executes(context -> show(context, supplier))))
                .then(decision("approve", DecisionAction.APPROVE, supplier))
                .then(decision("deny", DecisionAction.DENY, supplier))
                .then(decision("block", DecisionAction.BLOCK, supplier))
                .then(Commands.literal("unblock")
                        .then(Commands.argument("username", StringArgumentType.word())
                                .executes(context -> unblock(context, supplier))))
                .then(Commands.literal("status").executes(context -> status(context, supplier)));
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

    private static int list(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier,
                             Optional<RequestStatus> status) {
        NeoForgeRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        CommandSourceStack source = context.getSource();
        runtime.commandExecutor().execute(() -> {
            var requests = runtime.repository().findByStatus(status, 50);
            source.getServer().execute(() -> {
                if (requests.isEmpty()) source.sendSuccess(() -> Component.literal(Messages.get("command.no_requests")), false);
                else for (WhitelistRequest request : requests) source.sendSuccess(() -> Component.literal(
                        shortId(request.id()) + " " + request.status() + " " + request.identity().exactUsername()
                                + " attempts=" + request.attemptCount()), false);
            });
        });
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier) {
        NeoForgeRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        CommandSourceStack source = context.getSource();
        String key = context.getArgument("request", String.class);
        runtime.commandExecutor().execute(() -> {
            Optional<WhitelistRequest> request = resolve(runtime, key);
            source.getServer().execute(() -> {
                if (request.isEmpty()) source.sendFailure(Component.literal(Messages.get("command.request_not_found")));
                else {
                    WhitelistRequest value = request.get();
                    source.sendSuccess(() -> Component.literal(value.id() + " " + value.status() + " player="
                            + value.identity().exactUsername() + " uuid=" + value.identity().offlineUuid()
                            + " attempts=" + value.attemptCount()), false);
                }
            });
        });
        return 1;
    }

    private static int decide(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier,
                               DecisionAction action, String reason) {
        NeoForgeRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        CommandSourceStack source = context.getSource();
        String key = context.getArgument("request", String.class);
        runtime.commandExecutor().execute(() -> {
            Optional<WhitelistRequest> request = resolve(runtime, key);
            if (request.isEmpty()) {
                source.getServer().execute(() -> source.sendFailure(Component.literal(Messages.get("command.request_not_found"))));
                return;
            }
            runtime.decisions().decide(request.get().id(), action, principalOf(source),
                            Optional.ofNullable(reason).filter(value -> !value.isBlank()))
                    .thenAcceptAsync(result -> source.sendSuccess(() -> Component.literal(result.message()), false),
                            source.getServer()::execute)
                    .exceptionallyAsync(error -> {
                        source.getServer().execute(() -> source.sendFailure(Component.literal(
                                Messages.get("command.decision_failed", safeMessage(error)))));
                        return null;
                    }, source.getServer()::execute);
        });
        return 1;
    }

    private static int unblock(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier) {
        NeoForgeRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        CommandSourceStack source = context.getSource();
        String username = context.getArgument("username", String.class).toLowerCase(Locale.ROOT);
        runtime.commandExecutor().execute(() -> {
            boolean removed = runtime.decisions().unblock(username, principalOf(source), "");
            source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(removed
                    ? Messages.get("command.unblocked", username, source.getTextName())
                    : Messages.get("command.no_block", username)), false));
        });
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context, Supplier<NeoForgeRuntime> supplier) {
        NeoForgeRuntime runtime = supplier.get();
        if (runtime == null || runtime.degraded()) return fail(context, Messages.get("command.not_available"));
        CommandSourceStack source = context.getSource();
        runtime.commandExecutor().execute(() -> {
            try {
                long pending = runtime.repository().pendingOutboxCount();
                source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(
                        Messages.get("command.health_healthy", runtime.queueSize(), pending)), false));
            } catch (RuntimeException error) {
                source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(
                        Messages.get("command.health_degraded", runtime.queueSize(), safeMessage(error))), false));
            }
        });
        return 1;
    }

    private static AdminPrincipal principalOf(CommandSourceStack source) {
        String name = source.getTextName();
        return source.getEntity() == null ? AdminPrincipal.console()
                : new AdminPrincipal("minecraft", name, name);
    }

    private static Optional<WhitelistRequest> resolve(NeoForgeRuntime runtime, String value) {
        try { return runtime.find(UUID.fromString(value)); }
        catch (IllegalArgumentException ignored) { return runtime.active(value); }
    }

    private static Optional<RequestStatus> parseStatus(String value) {
        try { return Optional.of(RequestStatus.valueOf(value.toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }
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
