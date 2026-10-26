package com.gatehousemc.platform.fabric.command;

import com.gatehousemc.application.admin.AdminCommand;
import com.gatehousemc.application.admin.AdminCommandParser;
import com.gatehousemc.application.admin.AdminCommandResult;
import com.gatehousemc.application.admin.RequestReference;
import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.platform.fabric.FabricRuntime;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Fabric's transport adapter for the shared Gatehouse command model. */
public final class GatehouseCommands {
    private GatehouseCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, Supplier<FabricRuntime> runtimeSupplier) {
        dispatcher.register(buildCommand(runtimeSupplier));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildCommand(
            Supplier<FabricRuntime> runtimeSupplier) {
        return CommandManager.literal("gatehouse")
                .requires(source -> {
                    FabricRuntime runtime = runtimeSupplier.get();
                    int level = runtime == null ? 2 : runtime.config().minecraft().viewPermissionLevel();
                    return source.hasPermissionLevel(level);
                })
                .executes(GatehouseCommands::help)
                .then(CommandManager.literal("help").executes(GatehouseCommands::help))
                .then(CommandManager.literal("requests")
                        .executes(context -> requests(context, runtimeSupplier, "pending"))
                        .then(CommandManager.argument("status", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        new String[]{"pending", "resolving", "approved", "denied", "blocked", "all"}, builder))
                                .executes(context -> requests(context, runtimeSupplier,
                                        context.getArgument("status", String.class)))
                                .then(CommandManager.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> requests(context, runtimeSupplier,
                                                context.getArgument("status", String.class))))))
                .then(referenceAction("show", AdminCommand.Kind.SHOW, runtimeSupplier, false))
                .then(referenceAction("approve", AdminCommand.Kind.APPROVE, runtimeSupplier, true))
                .then(referenceAction("deny", AdminCommand.Kind.DENY, runtimeSupplier, true))
                .then(referenceAction("block", AdminCommand.Kind.BLOCK, runtimeSupplier, true))
                .then(referenceAction("reopen", AdminCommand.Kind.REOPEN, runtimeSupplier, true))
                .then(referenceAction("undo", AdminCommand.Kind.UNDO, runtimeSupplier, true))
                .then(CommandManager.literal("unblock")
                        .then(CommandManager.argument("username", StringArgumentType.word())
                                .executes(context -> executeText(context, runtimeSupplier,
                                        "unblock " + context.getArgument("username", String.class)))
                                .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "unblock " + context.getArgument("username", String.class) + " "
                                                        + context.getArgument("reason", String.class))))))
                .then(CommandManager.literal("status").executes(context -> status(context, runtimeSupplier)))
                .then(CommandManager.literal("reload").executes(context -> reload(context, runtimeSupplier)))
                .then(CommandManager.literal("provider")
                        .then(CommandManager.literal("status")
                                .executes(context -> executeText(context, runtimeSupplier, "provider status"))
                                .then(CommandManager.argument("provider", StringArgumentType.word())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "provider status " + context.getArgument("provider", String.class)))))
                        .then(CommandManager.literal("test")
                                .then(CommandManager.argument("provider", StringArgumentType.word())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "provider test " + context.getArgument("provider", String.class))))))
                .then(CommandManager.literal("setup")
                        .then(CommandManager.literal("status").executes(context -> executeText(context, runtimeSupplier, "setup status")))
                        .then(CommandManager.literal("discord").executes(context -> executeText(context, runtimeSupplier, "setup discord")))
                        .then(CommandManager.literal("telegram").executes(context -> executeText(context, runtimeSupplier, "setup telegram")))
                        .then(CommandManager.literal("bind")
                                .then(CommandManager.argument("provider", StringArgumentType.word())
                                        .then(CommandManager.argument("code", StringArgumentType.word())
                                                .executes(context -> executeText(context, runtimeSupplier,
                                                        "setup bind " + context.getArgument("provider", String.class) + " "
                                                                + context.getArgument("code", String.class))))))
                        .then(CommandManager.literal("cancel")
                                .then(CommandManager.argument("provider", StringArgumentType.word())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "setup cancel " + context.getArgument("provider", String.class))))))
                .then(CommandManager.literal("admin")
                        .then(CommandManager.literal("list")
                                .then(CommandManager.argument("provider", StringArgumentType.word())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "admin list " + context.getArgument("provider", String.class)))))
                        .then(CommandManager.literal("add")
                                .then(CommandManager.argument("provider", StringArgumentType.word())
                                        .then(CommandManager.argument("principal", StringArgumentType.word())
                                                .then(CommandManager.argument("access", StringArgumentType.word())
                                                        .executes(context -> executeText(context, runtimeSupplier,
                                                                "admin add " + context.getArgument("provider", String.class) + " "
                                                                        + context.getArgument("principal", String.class) + " "
                                                                        + context.getArgument("access", String.class)))))))
                        .then(CommandManager.literal("remove")
                                .then(CommandManager.argument("provider", StringArgumentType.word())
                                        .then(CommandManager.argument("principal", StringArgumentType.word())
                                                .executes(context -> executeText(context, runtimeSupplier,
                                                        "admin remove " + context.getArgument("provider", String.class) + " "
                                                                + context.getArgument("principal", String.class)))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> referenceAction(
            String name, AdminCommand.Kind kind, Supplier<FabricRuntime> supplier, boolean reasonAllowed) {
        var request = CommandManager.argument("request", StringArgumentType.word())
                .executes(context -> executeReference(context, supplier, kind, ""));
        if (reasonAllowed) {
            request.then(CommandManager.argument("reason", StringArgumentType.greedyString())
                    .executes(context -> executeReference(context, supplier, kind,
                            context.getArgument("reason", String.class))));
        }
        return CommandManager.literal(name).then(request);
    }

    private static int requests(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier, String statusText) {
        try {
            int page = 1;
            try { page = context.getArgument("page", Integer.class); } catch (IllegalArgumentException ignored) { }
            RequestStatus status = statusText.equalsIgnoreCase("all") ? null
                    : RequestStatus.valueOf(statusText.toUpperCase(Locale.ROOT));
            return execute(context, supplier, AdminCommand.base(AdminCommand.Kind.REQUESTS).withStatus(status, page));
        } catch (IllegalArgumentException error) {
            return fail(context, "Invalid request status or page: " + error.getMessage());
        }
    }

    private static int executeReference(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier,
                                        AdminCommand.Kind kind, String reason) {
        try {
            return execute(context, supplier, AdminCommand.base(kind).withRequest(
                    RequestReference.parse(context.getArgument("request", String.class)), reason));
        } catch (IllegalArgumentException error) {
            return fail(context, error.getMessage());
        }
    }

    private static int executeText(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier, String text) {
        AdminCommandResult parsed = AdminCommandParser.parse(text);
        if (!parsed.succeeded()) return fail(context, parsed.message());
        return execute(context, supplier, (AdminCommand) parsed.value().orElseThrow());
    }

    private static int execute(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier,
                               AdminCommand command) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null || runtime.adminCommands() == null || runtime.commandExecutor() == null) {
            return fail(context, Messages.get("command.not_available"));
        }
        ServerCommandSource source = context.getSource();
        runtime.commandExecutor().execute(() -> runtime.adminCommands().execute(command, principalOf(source))
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) source.sendError(Text.literal(Messages.get("command.decision_failed", safeMessage(error))));
                    else if (result.succeeded()) source.sendFeedback(() -> Text.literal(render(result)), false);
                    else source.sendError(Text.literal(result.message()));
                })));
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null || runtime.status() == null) return fail(context, Messages.get("command.not_available"));
        var snapshot = runtime.status().snapshot();
        return feedback(context, "Gatehouse " + snapshot.version() + " health=" + snapshot.health()
                + " runtime=" + snapshot.runtimeState() + " active=" + snapshot.activeRequests()
                + " queue=" + snapshot.queueCurrent() + "/" + snapshot.queueCapacity()
                + " outbox=" + snapshot.pendingOutbox());
    }

    private static int reload(CommandContext<ServerCommandSource> context, Supplier<FabricRuntime> supplier) {
        return executeText(context, supplier, "reload");
    }

    private static AdminPrincipal principalOf(ServerCommandSource source) {
        if (source.getEntity() == null) return AdminPrincipal.console();
        int level = source.hasPermissionLevel(4) ? 4 : source.hasPermissionLevel(3) ? 3 :
                source.hasPermissionLevel(2) ? 2 : source.hasPermissionLevel(1) ? 1 : 0;
        return new AdminPrincipal("minecraft", "perm:" + level, source.getName());
    }

    private static String render(AdminCommandResult result) {
        if (result.value().isEmpty()) return result.message();
        Object value = result.value().orElseThrow();
        if (value instanceof WhitelistRequest request) {
            return result.message() + ": " + request.id() + " " + request.status() + " player="
                    + request.identity().exactUsername() + " uuid=" + request.identity().offlineUuid()
                    + " attempts=" + request.attemptCount();
        }
        if (value instanceof List<?> values) {
            if (values.isEmpty()) return result.message() + ": " + Messages.get("command.no_requests");
            return result.message() + "\n" + values.stream().map(item -> {
                WhitelistRequest request = (WhitelistRequest) item;
                return request.id().toString().substring(0, 8) + " " + request.status() + " "
                        + request.identity().exactUsername() + " attempts=" + request.attemptCount();
            }).reduce((left, right) -> left + "\n" + right).orElse("");
        }
        return result.message();
    }

    private static int help(CommandContext<ServerCommandSource> context) {
        return feedback(context, Messages.get("command.help"));
    }

    private static int feedback(CommandContext<ServerCommandSource> context, String message) {
        context.getSource().sendFeedback(() -> Text.literal(message), false);
        return 1;
    }

    private static int fail(CommandContext<ServerCommandSource> context, String message) {
        context.getSource().sendError(Text.literal(message == null || message.isBlank() ? "Invalid Gatehouse command" : message));
        return 0;
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
