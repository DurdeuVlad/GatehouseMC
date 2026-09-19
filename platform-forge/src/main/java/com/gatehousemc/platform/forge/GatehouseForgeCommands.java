package com.gatehousemc.platform.forge;

import com.gatehousemc.application.admin.AdminCommand;
import com.gatehousemc.application.admin.AdminCommandResult;
import com.gatehousemc.application.admin.AdminCommandParser;
import com.gatehousemc.application.admin.RequestReference;
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

public final class GatehouseForgeCommands {
    private GatehouseForgeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                 Supplier<ForgeRuntime> runtimeSupplier) {
        dispatcher.register(build("gatehouse", runtimeSupplier));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> build(
            String name, Supplier<ForgeRuntime> supplier) {
        return Commands.literal(name)
                .requires(source -> {
                    ForgeRuntime runtime = supplier.get();
                    int permissionLevel = runtime == null ? 2 : runtime.config().minecraft().viewPermissionLevel();
                    return source.hasPermission(permissionLevel);
                })
                .executes(context -> help(context))
                .then(Commands.literal("help").executes(context -> help(context)))
                .then(Commands.literal("requests")
                        .executes(context -> list(context, supplier, Optional.of(RequestStatus.PENDING), 1))
                        .then(Commands.argument("status", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        new String[]{"pending", "resolving", "approved", "denied", "blocked", "all"}, builder))
                                .executes(context -> list(context, supplier,
                                        parseStatus(context.getArgument("status", String.class)), 1))
                                .then(Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .executes(context -> list(context, supplier,
                                                parseStatus(context.getArgument("status", String.class)),
                                                context.getArgument("page", Integer.class))))))
                .then(Commands.literal("show")
                        .then(Commands.argument("request", StringArgumentType.word())
                                .executes(context -> show(context, supplier))))
                .then(decision("approve", DecisionAction.APPROVE, supplier))
                .then(decision("deny", DecisionAction.DENY, supplier))
                .then(decision("block", DecisionAction.BLOCK, supplier))
                .then(reopen(supplier))
                .then(decision("undo", DecisionAction.UNDO, supplier))
                .then(Commands.literal("unblock")
                        .then(Commands.argument("username", StringArgumentType.word())
                                .executes(context -> unblock(context, supplier, ""))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> unblock(context, supplier,
                                                context.getArgument("reason", String.class))))))
                .then(Commands.literal("status").executes(context -> executeText(context, supplier, "status")))
                .then(Commands.literal("reload").executes(context -> executeText(context, supplier, "reload")))
                .then(providerCommands(supplier))
                .then(setupCommands(supplier))
                .then(adminCommands(supplier));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> providerCommands(
            Supplier<ForgeRuntime> supplier) {
        var root = Commands.literal("provider");
        root.then(Commands.literal("status").executes(context -> executeText(context, supplier, "provider status"))
                .then(Commands.argument("provider", StringArgumentType.word())
                        .executes(context -> executeText(context, supplier, "provider status "
                                + context.getArgument("provider", String.class)))));
        root.then(Commands.literal("test").then(Commands.argument("provider", StringArgumentType.word())
                .executes(context -> executeText(context, supplier, "provider test "
                        + context.getArgument("provider", String.class)))));
        return root;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> setupCommands(
            Supplier<ForgeRuntime> supplier) {
        var root = Commands.literal("setup");
        root.then(Commands.literal("status").executes(context -> executeText(context, supplier, "setup status")));
        root.then(Commands.literal("discord").executes(context -> executeText(context, supplier, "setup discord")));
        root.then(Commands.literal("telegram").executes(context -> executeText(context, supplier, "setup telegram")));
        root.then(Commands.literal("bind").then(Commands.argument("provider", StringArgumentType.word())
                .then(Commands.argument("code", StringArgumentType.word())
                        .executes(context -> executeText(context, supplier, "setup bind "
                                + context.getArgument("provider", String.class) + " "
                                + context.getArgument("code", String.class))))));
        root.then(Commands.literal("cancel").then(Commands.argument("provider", StringArgumentType.word())
                .executes(context -> executeText(context, supplier, "setup cancel "
                        + context.getArgument("provider", String.class)))));
        return root;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminCommands(
            Supplier<ForgeRuntime> supplier) {
        var root = Commands.literal("admin");
        root.then(Commands.literal("list").then(Commands.argument("provider", StringArgumentType.word())
                .executes(context -> executeText(context, supplier, "admin list "
                        + context.getArgument("provider", String.class)))));
        root.then(Commands.literal("add")
                .then(Commands.argument("provider", StringArgumentType.word())
                        .then(Commands.argument("principal", StringArgumentType.word())
                                .then(Commands.argument("access", StringArgumentType.word())
                                        .executes(context -> executeText(context, supplier, "admin add "
                                                + context.getArgument("provider", String.class) + " "
                                                + context.getArgument("principal", String.class) + " "
                                                + context.getArgument("access", String.class)))))));
        root.then(Commands.literal("remove")
                .then(Commands.argument("provider", StringArgumentType.word())
                        .then(Commands.argument("principal", StringArgumentType.word())
                                .executes(context -> executeText(context, supplier, "admin remove "
                                        + context.getArgument("provider", String.class) + " "
                                        + context.getArgument("principal", String.class))))));
        return root;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> decision(
            String name, DecisionAction action, Supplier<ForgeRuntime> supplier) {
        return Commands.literal(name)
                .then(Commands.argument("request", StringArgumentType.word())
                        .executes(context -> decide(context, supplier, action, ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> decide(context, supplier, action,
                                        context.getArgument("reason", String.class)))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> reopen(
            Supplier<ForgeRuntime> supplier) {
        return Commands.literal("reopen")
                .then(Commands.argument("request", StringArgumentType.word())
                        .executes(context -> reopen(context, supplier, ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> reopen(context, supplier,
                                        context.getArgument("reason", String.class)))));
    }

    private static int list(CommandContext<CommandSourceStack> context, Supplier<ForgeRuntime> supplier,
                             Optional<RequestStatus> status, int page) {
        return execute(context, supplier, AdminCommand.base(AdminCommand.Kind.REQUESTS)
                .withStatus(status.orElse(null), page));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(Messages.get("command.help")), false);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context, Supplier<ForgeRuntime> supplier) {
        try {
            return execute(context, supplier, AdminCommand.base(AdminCommand.Kind.SHOW)
                    .withRequest(RequestReference.parse(context.getArgument("request", String.class)), null));
        } catch (IllegalArgumentException error) { return fail(context, error.getMessage()); }
    }

    private static int decide(CommandContext<CommandSourceStack> context, Supplier<ForgeRuntime> supplier,
                               DecisionAction action, String reason) {
        try {
            AdminCommand.Kind kind = switch (action) {
                case APPROVE -> AdminCommand.Kind.APPROVE;
                case DENY -> AdminCommand.Kind.DENY;
                case BLOCK -> AdminCommand.Kind.BLOCK;
                case UNDO -> AdminCommand.Kind.UNDO;
            };
            return execute(context, supplier, AdminCommand.base(kind)
                    .withRequest(RequestReference.parse(context.getArgument("request", String.class)), reason));
        } catch (IllegalArgumentException error) { return fail(context, error.getMessage()); }
    }

    private static int unblock(CommandContext<CommandSourceStack> context, Supplier<ForgeRuntime> supplier, String reason) {
        return executeText(context, supplier, "unblock " + context.getArgument("username", String.class)
                + (reason == null || reason.isBlank() ? "" : " " + reason));
    }

    private static int reopen(CommandContext<CommandSourceStack> context, Supplier<ForgeRuntime> supplier, String reason) {
        try {
            return execute(context, supplier, AdminCommand.base(AdminCommand.Kind.REOPEN)
                    .withRequest(RequestReference.parse(context.getArgument("request", String.class)), reason));
        } catch (IllegalArgumentException error) { return fail(context, error.getMessage()); }
    }


    private static AdminPrincipal principalOf(CommandSourceStack source) {
        String name = source.getTextName();
        if (source.getEntity() == null) return AdminPrincipal.console();
        int level = source.hasPermission(4) ? 4 : source.hasPermission(3) ? 3 :
                source.hasPermission(2) ? 2 : source.hasPermission(1) ? 1 : 0;
        return new AdminPrincipal("minecraft", "perm:" + level, name);
    }

    private static int executeText(CommandContext<CommandSourceStack> context, Supplier<ForgeRuntime> supplier, String text) {
        AdminCommandResult parsed = AdminCommandParser.parse(text);
        if (!parsed.succeeded()) return fail(context, parsed.message());
        return execute(context, supplier, (AdminCommand) parsed.value().orElseThrow());
    }

    private static int execute(CommandContext<CommandSourceStack> context, Supplier<ForgeRuntime> supplier,
                               AdminCommand command) {
        ForgeRuntime runtime = supplier.get();
        if (runtime == null || runtime.adminCommands() == null || runtime.commandExecutor() == null) {
            return fail(context, Messages.get("command.not_available"));
        }
        CommandSourceStack source = context.getSource();
        runtime.commandExecutor().execute(() -> runtime.adminCommands().execute(command, principalOf(source))
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) source.sendFailure(Component.literal(Messages.get("command.decision_failed", safeMessage(error))));
                    else if (result.succeeded()) source.sendSuccess(() -> Component.literal(render(result)), false);
                    else source.sendFailure(Component.literal(result.message()));
                })));
        return 1;
    }

    private static String render(AdminCommandResult result) {
        if (result.value().isEmpty()) return result.message();
        Object value = result.value().orElseThrow();
        if (value instanceof WhitelistRequest request) {
            return result.message() + ": " + request.id() + " " + request.status() + " player="
                    + request.identity().exactUsername() + " uuid=" + request.identity().offlineUuid()
                    + " attempts=" + request.attemptCount();
        }
        if (value instanceof java.util.List<?> values) {
            if (values.isEmpty()) return result.message() + ": " + Messages.get("command.no_requests");
            return result.message() + " (" + values.size() + " results)";
        }
        return result.message();
    }

    private static Optional<WhitelistRequest> resolve(ForgeRuntime runtime, String value) {
        try { return runtime.find(UUID.fromString(value)); }
        catch (IllegalArgumentException ignored) { return runtime.active(value); }
    }

    private static Optional<RequestStatus> parseStatus(String value) {
        if (value.equalsIgnoreCase("all")) return Optional.empty();
        try { return Optional.of(RequestStatus.valueOf(value.toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException ignored) { throw new IllegalArgumentException("invalid request status: " + value); }
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
