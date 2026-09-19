package com.gatehousemc.integration.discord;

import com.gatehousemc.application.DecisionService;
import com.gatehousemc.application.ConfirmationStore;
import com.gatehousemc.application.ExternalCommandRateLimiter;
import com.gatehousemc.application.admin.RequestAction;
import com.gatehousemc.application.admin.RequestActionPolicy;
import com.gatehousemc.application.admin.AdminCommand;
import com.gatehousemc.application.admin.AdminCommandService;
import com.gatehousemc.application.admin.RequestReference;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.*;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.integration.common.ApprovalMessageRenderer;
import com.gatehousemc.integration.common.CallbackActionParser;
import com.gatehousemc.integration.common.CallbackActionParser.ParsedCallback;
import com.gatehousemc.integration.common.CallbackActionParser.CallbackKind;
import com.gatehousemc.port.ApprovalInterface;
import com.gatehousemc.port.ProviderHealth;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Discord projection and interaction adapter. Business transitions remain in DecisionService. */
public final class DiscordApprovalInterface extends ListenerAdapter implements ApprovalInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordApprovalInterface.class);

    private final ModConfig.Discord config;
    private final DecisionService decisions;
    private final ConfirmationStore confirmations;
    private final AdminCommandService adminCommands;
    private final ExternalCommandRateLimiter commandRateLimiter;
    private volatile ProviderHealth health = ProviderHealth.STOPPED;
    private volatile DiscordTransport transport;
    private volatile String lastSuccessfulOperation = "";
    private volatile String lastActionableError = "";

    public DiscordApprovalInterface(ModConfig.Discord config, DecisionService decisions) {
        this.config = config;
        this.decisions = decisions;
        this.confirmations = new ConfirmationStore(Instant::now);
        this.adminCommands = null;
        this.commandRateLimiter = new ExternalCommandRateLimiter(Instant::now);
    }

    /** Package-private constructor for fake-transport testing. */
    DiscordApprovalInterface(ModConfig.Discord config, DecisionService decisions, DiscordTransport transport) {
        this(config, decisions, transport, new ConfirmationStore(Instant::now));
    }

    DiscordApprovalInterface(ModConfig.Discord config, DecisionService decisions,
                             DiscordTransport transport, ConfirmationStore confirmations) {
        this(config, decisions, transport, confirmations, null);
    }

    public DiscordApprovalInterface(ModConfig.Discord config, DecisionService decisions,
                                    DiscordTransport transport, AdminCommandService adminCommands) {
        this(config, decisions, transport, new ConfirmationStore(Instant::now), adminCommands);
    }

    private DiscordApprovalInterface(ModConfig.Discord config, DecisionService decisions,
                                     DiscordTransport transport, ConfirmationStore confirmations,
                                     AdminCommandService adminCommands) {
        this.config = config;
        this.decisions = decisions;
        this.confirmations = confirmations;
        this.adminCommands = adminCommands;
        this.commandRateLimiter = new ExternalCommandRateLimiter(Instant::now);
        this.transport = transport;
        this.health = ProviderHealth.HEALTHY;
    }

    @Override
    public String id() { return "discord"; }

    @Override
    public ProviderHealth health() { return health; }

    @Override
    public String configuredDestination() {
        return !config.dmUserId().isBlank() ? "Discord DM " + config.dmUserId()
                : (config.channelId().isBlank() ? "unconfigured" : "Discord channel " + config.channelId());
    }

    @Override
    public String lastSuccessfulOperation() { return lastSuccessfulOperation; }

    @Override
    public String lastActionableError() { return lastActionableError; }

    @Override
    public CompletionStage<Void> testDelivery() {
        DiscordTransport current = transport;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord is unavailable"));
        String destination = destination();
        return current.sendMessage(destination, "GatehouseMC provider test — delivery is working.", UUID.randomUUID(), RequestStatus.RESOLVING)
                .thenRun(() -> { lastSuccessfulOperation = "provider test"; lastActionableError = ""; })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        lastActionableError = safeError(error);
                        health = ProviderHealth.DEGRADED;
                    }
                });
    }

    @Override
    public void start() {
        if (!config.enabled()) {
            health = ProviderHealth.DISABLED;
            lastActionableError = "provider is intentionally disabled";
            return;
        }
        if (config.token() == null || config.token().isBlank()
                || (config.channelId().isBlank() && config.dmUserId().isBlank())
                || (config.principals().isEmpty() && config.allowedUserIds().isEmpty() && config.allowedRoleIds().isEmpty())) {
            health = ProviderHealth.SETUP_REQUIRED;
            lastActionableError = "configure the Discord token, destination, and at least one administrator principal";
            return;
        }
        if (transport != null) {
            transport.start();
            health = ProviderHealth.HEALTHY;
            return;
        }
        try {
            health = ProviderHealth.STARTING;
            JDA jda = JDABuilder.createDefault(config.token()).addEventListeners(this).build();
            transport = new JdaDiscordTransport(jda, config.guildId(), config.dmUserId());
        } catch (RuntimeException error) {
            LOGGER.error("discord.start_failed: failed to initialize JDA", error);
            health = ProviderHealth.UNAVAILABLE;
            lastActionableError = safeError(error);
        }
    }

    @Override
    public void stop() {
        DiscordTransport current = transport;
        transport = null;
        health = ProviderHealth.STOPPED;
        if (current != null) current.stop();
    }

    @Override
    public void onReady(ReadyEvent event) {
        health = ProviderHealth.HEALTHY;
        JDA readyJda = event == null ? null : event.getJDA();
        if (readyJda != null) registerSlashCommands(readyJda);
        if (!isDirectMessageMode() && transport instanceof JdaDiscordTransport jdaTransport) {
            String destination = destination();
            if (destination != null && !destination.isBlank()) {
                jdaTransport.validateChannel(destination).whenComplete((channel, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        LOGGER.error("discord.channel_unusable: GatehouseMC cannot publish to configured Discord channel {}: {}",
                                destination, cause.getMessage());
                    } else if (channel instanceof StandardGuildMessageChannel guildChannel) {
                        LOGGER.info("discord.channel_verified: Connected to Discord channel '{}' ({}) in guild '{}'",
                                guildChannel.getName(), guildChannel.getId(), guildChannel.getGuild().getName());
                    }
                });
            }
        }
    }

    @Override
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        if (health != ProviderHealth.STOPPED) health = ProviderHealth.STARTING;
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        if (health != ProviderHealth.STOPPED) health = ProviderHealth.UNAVAILABLE;
    }

    @Override
    public CompletionStage<PublicationRef> publish(RequestView request) {
        DiscordTransport current = transport;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord is unavailable"));
        String destination = destination();
        CompletionStage<String> send;
        try {
            send = current.sendMessage(destination, render(request), request.id(), request.status());
        } catch (RuntimeException error) {
            LOGGER.error("discord.publish_failed destination={}: {}", destination, error.getMessage());
            return CompletableFuture.failedFuture(error);
        }
        return send.thenApply(messageId -> {
                    lastSuccessfulOperation = "publish";
                    lastActionableError = "";
                    return new PublicationRef(id(), destination, messageId);
                })
                .exceptionallyCompose(error -> {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    lastActionableError = safeError(cause);
                    health = ProviderHealth.DEGRADED;
                    LOGGER.error("discord.publish_failed destination={}: {}", destination, cause.getMessage());
                    return CompletableFuture.failedFuture(cause);
                });
    }

    @Override
    public CompletionStage<Void> update(PublicationRef publication, RequestView request) {
        DiscordTransport current = transport;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord is stopped"));
        return current.editMessage(publication.containerId(), publication.messageId(), render(request), request.id(), request.status())
                .thenCompose(ignored -> {
                    if (!request.status().isTerminal()) return CompletableFuture.completedFuture(null);
                    return current.sendActionFollowUp(publication.containerId(), publication.messageId(),
                                    followUpMessage(request), request.id(), request.status())
                            .exceptionally(error -> {
                                LOGGER.warn("discord.followup_failed destination={}: {}", publication.containerId(), error.getMessage());
                                return null;
                            });
                })
                .thenRun(() -> {
                    lastSuccessfulOperation = "update";
                    lastActionableError = "";
                })
                .exceptionallyCompose(error -> {
                    lastActionableError = safeError(error);
                    health = ProviderHealth.DEGRADED;
                    return CompletableFuture.failedFuture(error);
                });
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String customId = event.getComponentId();
        ParsedCallback callback = CallbackActionParser.parse(customId);
        if (callback == null) {
            event.reply(Messages.get("provider.invalid_action")).setEphemeral(true).queue();
            return;
        }
        if (!authorized(event)) {
            event.reply(Messages.get("provider.not_authorized")).setEphemeral(true).queue();
            return;
        }
        ExternalCommandRateLimiter.Decision rate = commandRateLimiter.allow("discord", event.getUser().getId(), true);
        if (!rate.allowed()) {
            event.reply("Rate limited; retry in " + Math.max(1, rate.retryAfter().toSeconds()) + "s.").setEphemeral(true).queue();
            return;
        }
        AdminPrincipal principal = principalOf(event);
        switch (callback.kind()) {
            case PRIMARY -> {
                Optional<RequestView> request = decisions.findRequest(callback.requestId());
                if (request.isEmpty()) {
                    event.reply(Messages.get("command.request_not_found")).setEphemeral(true).queue();
                    return;
                }
                RequestView current = request.get();
                RequestAction requestedAction = requestAction(callback.action(), current.status());
                if (!RequestActionPolicy.allows(current.status(), requestedAction)) {
                    event.reply(ApprovalMessageRenderer.render(current)).setEphemeral(true).queue();
                    return;
                }
                ConfirmationStore.Confirmation confirmation = confirmations.issue(
                        principal.provider(), principal.externalId(), current.id(), requestedAction, current.status());
                String player = current.identity().exactUsername();
                String shortId = shortId(current.id());
                String confirmMsg = confirmMessage(requestedAction, player, shortId);
                Button confirmBtn = Button.danger(CallbackActionParser.formatConfirmToken(confirmation.token()), Messages.get("button.confirm"));
                Button cancelBtn = Button.secondary(CallbackActionParser.formatCancelToken(confirmation.token()), Messages.get("button.cancel"));
                event.editMessage(confirmMsg).setComponents(ActionRow.of(confirmBtn, cancelBtn)).queue();
            }
            case CONFIRM -> {
                if (callback.token() == null) {
                    event.deferEdit().queue();
                    decisions.decide(callback.requestId(), callback.action(), principal, Optional.empty())
                            .thenAccept(result -> event.getHook().sendMessage(result.message()).setEphemeral(true).queue())
                            .exceptionally(error -> { event.getHook().sendMessage(Messages.get("provider.decision_failed")).setEphemeral(true).queue(); return null; });
                    return;
                }
                ConfirmationStore.Confirmation pending = confirmations.find(callback.token()).orElse(null);
                if (pending == null) {
                    event.reply(Messages.get("confirm.expired")).setEphemeral(true).queue();
                    return;
                }
                Optional<RequestView> current = decisions.findRequest(pending.requestId());
                ConfirmationStore.ConsumeResult consumed = confirmations.consume(callback.token(), principal.provider(),
                        principal.externalId(), current.map(RequestView::status).orElse(null));
                if (consumed.status() != ConfirmationStore.ConsumeStatus.CONSUMED) {
                    event.reply(confirmationFailure(consumed.status(), current)).setEphemeral(true).queue();
                    return;
                }
                event.deferEdit().queue();
                executeConfirmation(pending, principal)
                        .thenAccept(result -> event.getHook().sendMessage(result.message()).setEphemeral(true).queue())
                        .exceptionally(error -> { event.getHook().sendMessage(Messages.get("provider.decision_failed")).setEphemeral(true).queue(); return null; });
            }
            case CANCEL -> {
                UUID requestId = callback.requestId();
                if (callback.token() != null) {
                    ConfirmationStore.ConsumeResult cancelled = confirmations.cancel(callback.token(), principal.provider(), principal.externalId());
                    if (cancelled.status() != ConfirmationStore.ConsumeStatus.CONSUMED) {
                        event.reply(confirmationFailure(cancelled.status(), Optional.empty())).setEphemeral(true).queue();
                        return;
                    }
                    requestId = cancelled.confirmation().orElseThrow().requestId();
                }
                Optional<RequestView> request = requestId == null ? Optional.empty() : decisions.findRequest(requestId);
                if (request.isPresent()) {
                    String originalText = ApprovalMessageRenderer.render(request.get());
                    Button[] buttons = cardButtons(request.get().id(), request.get().status());
                    if (buttons.length > 0) {
                        event.editMessage(originalText).setComponents(ActionRow.of(Arrays.asList(buttons))).queue();
                    } else {
                        event.editMessage(originalText).setComponents().queue();
                    }
                } else {
                    event.editMessage(Messages.get("confirm.cancelled")).setComponents().queue();
                }
            }
        }
    }

    private void registerSlashCommands(JDA jda) {
        jda.updateCommands().addCommands(
                Commands.slash("gatehouse", "Gatehouse administration and whitelist requests")
                        .addSubcommands(new SubcommandData("help", "Show Gatehouse help"))
                        .addSubcommands(new SubcommandData("requests", "Browse requests")
                                .addOption(OptionType.STRING, "status", "Request status", false)
                                .addOption(OptionType.INTEGER, "page", "Page number", false))
                        .addSubcommands(new SubcommandData("show", "Show one request")
                                .addOption(OptionType.STRING, "request", "Request UUID, prefix, or username", true))
                        .addSubcommands(new SubcommandData("approve", "Approve a request")
                                .addOption(OptionType.STRING, "request", "Request reference", true)
                                .addOption(OptionType.STRING, "reason", "Optional reason", false))
                        .addSubcommands(new SubcommandData("deny", "Deny a request")
                                .addOption(OptionType.STRING, "request", "Request reference", true)
                                .addOption(OptionType.STRING, "reason", "Optional reason", false))
                        .addSubcommands(new SubcommandData("block", "Block a request")
                                .addOption(OptionType.STRING, "request", "Request reference", true)
                                .addOption(OptionType.STRING, "reason", "Optional reason", false))
                        .addSubcommands(new SubcommandData("reopen", "Reopen a request")
                                .addOption(OptionType.STRING, "request", "Request reference", true)
                                .addOption(OptionType.STRING, "reason", "Optional reason", false))
                        .addSubcommands(new SubcommandData("undo", "Undo an approval")
                                .addOption(OptionType.STRING, "request", "Request reference", true)
                                .addOption(OptionType.STRING, "reason", "Optional reason", false))
                        .addSubcommands(new SubcommandData("unblock", "Remove an active identity block")
                                .addOption(OptionType.STRING, "username", "Minecraft username", true)
                                .addOption(OptionType.STRING, "reason", "Optional reason", false))
                        .addSubcommands(new SubcommandData("status", "Show runtime status"))
                        .addSubcommands(new SubcommandData("reload", "Reload configuration"))
                                        .addSubcommandGroups(
                                new SubcommandGroupData("provider", "Provider operations")
                                        .addSubcommands(new SubcommandData("status", "Show provider status")
                                                .addOption(OptionType.STRING, "provider", "discord or telegram", false))
                                        .addSubcommands(new SubcommandData("test", "Send one provider test")
                                                .addOption(OptionType.STRING, "provider", "discord or telegram", true)))
                        .addSubcommandGroups(
                                new SubcommandGroupData("setup", "Guided provider setup")
                                        .addSubcommands(new SubcommandData("status", "Show setup status"))
                                        .addSubcommands(new SubcommandData("discord", "Start Discord setup"))
                                        .addSubcommands(new SubcommandData("telegram", "Start Telegram setup"))
                                        .addSubcommands(new SubcommandData("bind", "Bind a setup code")
                                                .addOption(OptionType.STRING, "provider", "Provider", true)
                                                .addOption(OptionType.STRING, "code", "Setup code", true))
                                        .addSubcommands(new SubcommandData("cancel", "Cancel setup")
                                                .addOption(OptionType.STRING, "provider", "Provider", true)))
                        .addSubcommandGroups(
                                new SubcommandGroupData("admin", "External administrator management")
                                        .addSubcommands(new SubcommandData("list", "List principals")
                                                .addOption(OptionType.STRING, "provider", "Provider", true))
                                        .addSubcommands(new SubcommandData("add", "Add a principal")
                                                .addOption(OptionType.STRING, "provider", "Provider", true)
                                                .addOption(OptionType.STRING, "principal", "Stable principal ID", true)
                                                .addOption(OptionType.STRING, "access", "VIEW, DECIDE, or MANAGE", true))
                                        .addSubcommands(new SubcommandData("remove", "Remove a principal")
                                                .addOption(OptionType.STRING, "provider", "Provider", true)
                                                .addOption(OptionType.STRING, "principal", "Stable principal ID", true)))
        ).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("gatehouse")) return;
        if (!authorized(event)) {
            event.reply(Messages.get("provider.not_authorized")).setEphemeral(true).queue();
            return;
        }
        String text = slashText(event);
        com.gatehousemc.application.admin.AdminCommandResult parsed =
                com.gatehousemc.application.admin.AdminCommandParser.parse(text);
        if (!parsed.succeeded()) {
            event.reply(parsed.message()).setEphemeral(true).queue();
            return;
        }
        ExternalCommandRateLimiter.Decision rate = commandRateLimiter.allow("discord", event.getUser().getId(),
                isMutating((AdminCommand) parsed.value().orElseThrow()));
        if (!rate.allowed()) {
            event.reply("Rate limited; retry in " + Math.max(1, rate.retryAfter().toSeconds()) + "s.").setEphemeral(true).queue();
            return;
        }
        if (adminCommands == null) {
            event.reply(Messages.get("command.not_available")).setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        adminCommands.execute((AdminCommand) parsed.value().orElseThrow(), principalOf(event))
                .thenAccept(result -> event.getHook().editOriginal(result.message()).queue())
                .exceptionally(error -> { event.getHook().editOriginal(Messages.get("provider.decision_failed")).queue(); return null; });
    }

    private static RequestAction requestAction(DecisionAction action, RequestStatus status) {
        if (action == DecisionAction.UNDO) return status == RequestStatus.APPROVED ? RequestAction.UNDO : RequestAction.REOPEN;
        return switch (action) {
            case APPROVE -> RequestAction.APPROVE;
            case DENY -> RequestAction.DENY;
            case BLOCK -> RequestAction.BLOCK;
            case UNDO -> throw new IllegalStateException();
        };
    }

    private static DecisionAction toDecisionAction(RequestAction action) {
        return switch (action) {
            case APPROVE -> DecisionAction.APPROVE;
            case DENY -> DecisionAction.DENY;
            case BLOCK -> DecisionAction.BLOCK;
            case REOPEN, UNDO -> DecisionAction.UNDO;
        };
    }

    private CompletionStage<AdminCommandResultView> executeConfirmation(ConfirmationStore.Confirmation confirmation,
                                                                          AdminPrincipal principal) {
        if (adminCommands == null) {
            return decisions.decide(confirmation.requestId(), toDecisionAction(confirmation.action()), principal, Optional.empty())
                    .thenApply(result -> new AdminCommandResultView(result.message()));
        }
        AdminCommand.Kind kind = switch (confirmation.action()) {
            case APPROVE -> AdminCommand.Kind.APPROVE;
            case DENY -> AdminCommand.Kind.DENY;
            case BLOCK -> AdminCommand.Kind.BLOCK;
            case REOPEN -> AdminCommand.Kind.REOPEN;
            case UNDO -> AdminCommand.Kind.UNDO;
        };
        AdminCommand command = AdminCommand.base(kind).withRequest(RequestReference.parse(confirmation.requestId().toString()), null);
        return adminCommands.execute(command, principal).thenApply(result -> new AdminCommandResultView(result.message()));
    }

    private record AdminCommandResultView(String message) {}

    private static String confirmationFailure(ConfirmationStore.ConsumeStatus status, Optional<RequestView> current) {
        return switch (status) {
            case EXPIRED -> Messages.get("confirm.expired");
            case STALE, MISSING_REQUEST -> current.map(ApprovalMessageRenderer::render)
                    .orElse(Messages.get("command.request_not_found"));
            case WRONG_ACTOR, WRONG_PROVIDER, MISSING -> Messages.get("provider.not_authorized");
            case CONSUMED -> Messages.get("confirm.expired");
        };
    }

    private static String confirmMessage(RequestAction action, String player, String shortId) {
        return switch (action) {
            case APPROVE -> Messages.get("confirm.approve", player, shortId);
            case DENY -> Messages.get("confirm.deny", player, shortId);
            case BLOCK -> Messages.get("confirm.block", player, shortId);
            case UNDO -> Messages.get("confirm.undo", player, shortId);
            case REOPEN -> Messages.get("confirm.reopen", player, shortId);
        };
    }

    private static String followUpMessage(RequestView request) {
        String player = request.identity().exactUsername();
        String id = shortId(request.id());
        return switch (request.status()) {
            case APPROVED -> Messages.get("discord.followup.approved", player, id);
            case DENIED -> Messages.get("discord.followup.denied", player, id);
            case BLOCKED -> Messages.get("discord.followup.blocked", player, id);
            default -> "";
        };
    }

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }

    private String safeError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return config.token() == null || config.token().isBlank() ? message : message.replace(config.token(), "[redacted]");
    }

    /** Buttons rendered on the request card itself: primary actions while pending, none once resolved. */
    static Button[] cardButtons(UUID requestId, RequestStatus status) {
        return status == RequestStatus.PENDING ? actionButtons(requestId, status) : new Button[0];
    }

    /** Actions valid for a request status; also used on the post-resolution follow-up reply. */
    static Button[] actionButtons(UUID requestId, RequestStatus status) {
        return switch (status) {
            case PENDING -> new Button[]{
                    Button.success(CallbackActionParser.formatPrimary(DecisionAction.APPROVE, requestId), Messages.get("button.approve")),
                    Button.danger(CallbackActionParser.formatPrimary(DecisionAction.DENY, requestId), Messages.get("button.deny")),
                    Button.secondary(CallbackActionParser.formatPrimary(DecisionAction.BLOCK, requestId), Messages.get("button.block"))};
            case APPROVED -> new Button[]{
                    Button.secondary(CallbackActionParser.formatPrimary(DecisionAction.UNDO, requestId), Messages.get("button.undo"))};
            case DENIED, BLOCKED -> new Button[]{
                    Button.secondary(CallbackActionParser.formatPrimary(DecisionAction.UNDO, requestId), Messages.get("button.reopen"))};
            default -> new Button[0];
        };
    }

    private boolean authorized(ButtonInteractionEvent event) {
        if (!config.principals().isEmpty()) {
            if (isDirectMessageMode()) {
                return event.getGuild() == null && config.dmUserId().equals(event.getUser().getId())
                        && config.principals().stream().anyMatch(principal -> principal.kind().equals("USER")
                        && principal.id().equals(event.getUser().getId()));
            }
            if (event.getGuild() == null || !event.getGuild().getId().equals(config.guildId())) return false;
            if (config.principals().stream().anyMatch(principal -> principal.kind().equals("USER")
                    && principal.id().equals(event.getUser().getId()))) return true;
            Member member = event.getMember();
            return member != null && member.getRoles().stream().map(Role::getId)
                    .anyMatch(roleId -> config.principals().stream().anyMatch(principal -> principal.kind().equals("ROLE")
                            && principal.id().equals(roleId)));
        }
        if (isDirectMessageMode()) {
            return event.getGuild() == null
                    && config.allowedUserIds().contains(event.getUser().getId())
                    && config.dmUserId().equals(event.getUser().getId());
        }
        if (event.getGuild() == null || !event.getGuild().getId().equals(config.guildId())) return false;
        if (config.allowedUserIds().contains(event.getUser().getId())) return true;
        Member member = event.getMember();
        if (member == null) return false;
        return member.getRoles().stream().map(Role::getId).anyMatch(config.allowedRoleIds()::contains);
    }

    private boolean isDirectMessageMode() { return config.dmUserId() != null && !config.dmUserId().isBlank(); }

    private AdminPrincipal principalOf(ButtonInteractionEvent event) {
        if (!config.principals().isEmpty() && event.getMember() != null) {
            Optional<String> role = event.getMember().getRoles().stream().map(Role::getId)
                    .filter(roleId -> config.principals().stream().anyMatch(principal -> principal.kind().equals("ROLE")
                            && principal.id().equals(roleId))).findFirst();
            if (role.isPresent() && config.principals().stream().noneMatch(principal -> principal.kind().equals("USER")
                    && principal.id().equals(event.getUser().getId()))) {
                return new AdminPrincipal("discord", "role:" + role.get(), event.getUser().getEffectiveName());
            }
        }
        return new AdminPrincipal("discord", event.getUser().getId(), event.getUser().getEffectiveName());
    }

    private boolean authorized(SlashCommandInteractionEvent event) {
        if (!config.principals().isEmpty()) {
            if (event.getGuild() == null) {
                return isDirectMessageMode() && config.dmUserId().equals(event.getUser().getId())
                        && config.principals().stream().anyMatch(principal -> principal.kind().equals("USER")
                        && principal.id().equals(event.getUser().getId()));
            }
            if (!event.getGuild().getId().equals(config.guildId())) return false;
            if (config.principals().stream().anyMatch(principal -> principal.kind().equals("USER")
                    && principal.id().equals(event.getUser().getId()))) return true;
            Member member = event.getMember();
            return member != null && member.getRoles().stream().map(Role::getId)
                    .anyMatch(roleId -> config.principals().stream().anyMatch(principal -> principal.kind().equals("ROLE")
                            && principal.id().equals(roleId)));
        }
        if (isDirectMessageMode()) return event.getGuild() == null
                && config.dmUserId().equals(event.getUser().getId())
                && config.allowedUserIds().contains(event.getUser().getId());
        if (event.getGuild() == null || !event.getGuild().getId().equals(config.guildId())) return false;
        if (config.allowedUserIds().contains(event.getUser().getId())) return true;
        Member member = event.getMember();
        return member != null && member.getRoles().stream().map(Role::getId).anyMatch(config.allowedRoleIds()::contains);
    }

    private AdminPrincipal principalOf(SlashCommandInteractionEvent event) {
        if (!config.principals().isEmpty() && event.getMember() != null) {
            Optional<String> role = event.getMember().getRoles().stream().map(Role::getId)
                    .filter(roleId -> config.principals().stream().anyMatch(principal -> principal.kind().equals("ROLE")
                            && principal.id().equals(roleId))).findFirst();
            if (role.isPresent() && config.principals().stream().noneMatch(principal -> principal.kind().equals("USER")
                    && principal.id().equals(event.getUser().getId()))) {
                return new AdminPrincipal("discord", "role:" + role.get(), event.getUser().getEffectiveName());
            }
        }
        return new AdminPrincipal("discord", event.getUser().getId(), event.getUser().getEffectiveName());
    }

    private static String slashText(SlashCommandInteractionEvent event) {
        String group = event.getSubcommandGroup();
        String subcommand = event.getSubcommandName();
        if (subcommand == null) return "help";
        StringBuilder text = new StringBuilder();
        if (group != null) text.append(group).append(' ');
        text.append(subcommand);
        appendOption(text, event, "status");
        appendOption(text, event, "page");
        appendOption(text, event, "request");
        appendOption(text, event, "username");
        appendOption(text, event, "reason");
        appendOption(text, event, "provider");
        appendOption(text, event, "principal");
        appendOption(text, event, "access");
        appendOption(text, event, "code");
        return text.toString();
    }

    private static void appendOption(StringBuilder text, SlashCommandInteractionEvent event, String name) {
        OptionMapping option = event.getOption(name);
        if (option != null) text.append(' ').append(option.getAsString());
    }

    private static boolean isMutating(AdminCommand command) {
        return switch (command.kind()) {
            case APPROVE, DENY, BLOCK, REOPEN, UNDO, UNBLOCK, RELOAD, PROVIDER_TEST,
                    SETUP_PROVIDER, SETUP_BIND, SETUP_CANCEL, ADMIN_ADD, ADMIN_REMOVE -> true;
            default -> false;
        };
    }

    private String destination() { return isDirectMessageMode() ? config.dmUserId() : config.channelId(); }

    static String render(RequestView request) {
        return ApprovalMessageRenderer.render(request);
    }

    static ParsedAction parseAction(String value) {
        CallbackActionParser.ParsedAction parsed = CallbackActionParser.parseAction(value);
        return parsed == null ? null : new ParsedAction(parsed.action(), parsed.requestId());
    }

    /** Thin subclass of the shared {@link CallbackActionParser.ParsedAction} for backward-compatible test access. */
    static final class ParsedAction extends CallbackActionParser.ParsedAction {
        ParsedAction(DecisionAction action, UUID requestId) {
            super(action, requestId);
        }
    }
}
