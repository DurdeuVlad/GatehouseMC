package com.gatehousemc.whitelistrequest.platform.fabric.command;

import com.gatehousemc.whitelistrequest.domain.*;
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
                .requires(source -> runtimeSupplier.get() != null && source.hasPermissionLevel(runtimeSupplier.get().config().requests().commandPermissionLevel()))
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
                .then(CommandManager.literal("unblock")
                        .then(CommandManager.argument("username", StringArgumentType.word())
                                .executes(context -> unblock(context, runtimeSupplier))
                                .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> unblock(context, runtimeSupplier)))))
                .then(CommandManager.literal("status").executes(context -> status(context, runtimeSupplier)))
                .then(CommandManager.literal("reload").executes(context -> {
                    String message = WhitelistRequestMod.reload();
                    context.getSource().sendFeedback(() -> Text.literal(message), false);
                    return message.startsWith("Config reload failed") ? 0 : 1;
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
        if (runtime == null) return fail(context, "Whitelist Request is not running");
        var requests = runtime.repository().findByStatus(status, 50);
        if (requests.isEmpty()) return feedback(context, "No requests found");
        for (WhitelistRequest request : requests) {
            context.getSource().sendFeedback(() -> Text.literal(shortId(request.id()) + " " + request.status() + " " + request.identity().exactUsername() + " attempts=" + request.attemptCount()), false);
        }
        return requests.size();
    }

    private static int show(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null) return fail(context, "Whitelist Request is not running");
        Optional<WhitelistRequest> request = resolve(runtime, context.getArgument("request", String.class));
        if (request.isEmpty()) return fail(context, "Request not found");
        WhitelistRequest value = request.get();
        return feedback(context, value.id() + " " + value.status() + " player=" + value.identity().exactUsername() + " uuid=" + value.identity().offlineUuid() + " attempts=" + value.attemptCount());
    }

    private static int decide(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier, DecisionAction action, String reason) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null) return fail(context, "Whitelist Request is not running");
        Optional<WhitelistRequest> request = resolve(runtime, context.getArgument("request", String.class));
        if (request.isEmpty()) return fail(context, "Request not found");
        runtime.decisions().decide(request.get().id(), action, AdminPrincipal.console(), Optional.ofNullable(reason).filter(value -> !value.isBlank()))
                .thenAccept(result -> context.getSource().sendFeedback(() -> Text.literal(result.message()), false));
        return 1;
    }

    private static int unblock(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null) return fail(context, "Whitelist Request is not running");
        String username = context.getArgument("username", String.class).toLowerCase(Locale.ROOT);
        boolean removed = runtime.repository().unblock(username, AdminPrincipal.console(), "", java.time.Instant.now());
        return feedback(context, removed ? "Unblocked " + username : "No block exists for " + username);
    }

    private static int status(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null) return fail(context, "Whitelist Request is not running");
        return feedback(context, "health=" + (runtime.degraded() ? "DEGRADED" : "HEALTHY") + " queue=" + runtime.queueSize() + " outbox=" + runtime.repository().pendingOutboxCount());
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
}
