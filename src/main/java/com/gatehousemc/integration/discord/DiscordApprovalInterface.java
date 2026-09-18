package com.gatehousemc.integration.discord;

import com.gatehousemc.application.DecisionService;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.*;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.integration.common.ApprovalActionState;
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
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Discord projection and interaction adapter. Business transitions remain in DecisionService. */
public final class DiscordApprovalInterface extends ListenerAdapter implements ApprovalInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordApprovalInterface.class);

    private final ModConfig.Discord config;
    private final DecisionService decisions;
    private volatile ProviderHealth health = ProviderHealth.STOPPED;
    private volatile DiscordTransport transport;

    public DiscordApprovalInterface(ModConfig.Discord config, DecisionService decisions) {
        this.config = config;
        this.decisions = decisions;
    }

    /** Package-private constructor for fake-transport testing. */
    DiscordApprovalInterface(ModConfig.Discord config, DecisionService decisions, DiscordTransport transport) {
        this.config = config;
        this.decisions = decisions;
        this.transport = transport;
        this.health = ProviderHealth.HEALTHY;
    }

    @Override
    public String id() { return "discord"; }

    @Override
    public ProviderHealth health() { return health; }

    @Override
    public void start() {
        if (!config.enabled() || config.token() == null || config.token().isBlank()) {
            health = ProviderHealth.UNAVAILABLE;
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
        if (isDirectMessageMode()) {
            health = ProviderHealth.HEALTHY;
            return;
        }
        if (transport instanceof JdaDiscordTransport jdaTransport) {
            String destination = destination();
            if (destination != null && !destination.isBlank()) {
                health = ProviderHealth.STARTING;
                jdaTransport.validateChannel(destination).whenComplete((channel, error) -> {
                    if (error != null) {
                        health = ProviderHealth.DEGRADED;
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        LOGGER.error("discord.channel_unusable: GatehouseMC cannot publish to configured Discord channel {}: {}",
                                destination, cause.getMessage());
                    } else {
                        health = ProviderHealth.HEALTHY;
                        if (channel instanceof StandardGuildMessageChannel guildChannel) {
                            LOGGER.info("discord.channel_verified: Connected to Discord channel '{}' ({}) in guild '{}'",
                                    guildChannel.getName(), guildChannel.getId(), guildChannel.getGuild().getName());
                        }
                    }
                });
                return;
            }
        }
        health = ProviderHealth.HEALTHY;
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
            health = ProviderHealth.DEGRADED;
            LOGGER.error("discord.publish_failed destination={}: {}", destination, error.getMessage());
            return CompletableFuture.failedFuture(error);
        }
        return send.thenApply(messageId -> {
                    health = ProviderHealth.HEALTHY;
                    return new PublicationRef(id(), destination, messageId);
                })
                .exceptionallyCompose(error -> {
                    health = ProviderHealth.DEGRADED;
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    LOGGER.error("discord.publish_failed destination={}: {}", destination, cause.getMessage());
                    return CompletableFuture.failedFuture(cause);
                });
    }

    @Override
    public CompletionStage<Void> update(PublicationRef publication, RequestView request) {
        DiscordTransport current = transport;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord is stopped"));
        return current.editMessage(publication.containerId(), publication.messageId(), render(request), request.id(), request.status())
                .whenComplete((ignored, error) -> health = error == null ? ProviderHealth.HEALTHY : ProviderHealth.DEGRADED);
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
        AdminPrincipal principal = new AdminPrincipal("discord", event.getUser().getId(), event.getUser().getEffectiveName());
        switch (callback.kind()) {
            case PRIMARY -> {
                Optional<RequestView> request = decisions.findRequest(callback.requestId());
                if (request.isEmpty()) {
                    event.reply(Messages.get("confirm.expired")).setEphemeral(true).queue();
                    return;
                }
                RequestView current = request.get();
                if (!ApprovalActionState.canExecute(current.status(), callback.action())) {
                    event.reply(ApprovalActionState.unavailableMessage(current.status())).setEphemeral(true).queue();
                    return;
                }
                String player = current.identity().exactUsername();
                String shortId = shortId(callback.requestId());
                String confirmMsg = confirmMessage(callback.action(), player, shortId, current.status());
                Button confirmBtn = Button.danger(CallbackActionParser.formatConfirm(callback.action(), callback.requestId()), Messages.get("button.confirm"));
                Button cancelBtn = Button.secondary(CallbackActionParser.formatCancel(callback.requestId()), Messages.get("button.cancel"));
                event.editMessage(confirmMsg).setComponents(ActionRow.of(confirmBtn, cancelBtn)).queue();
            }
            case CONFIRM -> {
                Optional<RequestView> request = decisions.findRequest(callback.requestId());
                if (request.isEmpty()) {
                    event.reply(Messages.get("confirm.expired")).setEphemeral(true).queue();
                    return;
                }
                if (!ApprovalActionState.canExecute(request.get().status(), callback.action())) {
                    event.reply(ApprovalActionState.unavailableMessage(request.get().status())).setEphemeral(true).queue();
                    return;
                }
                event.deferEdit().queue();
                decisions.decide(callback.requestId(), callback.action(), principal, Optional.empty())
                        .thenAccept(result -> event.getHook().sendMessage(result.message()).setEphemeral(true).queue())
                        .exceptionally(error -> {
                            event.getHook().sendMessage(Messages.get("provider.decision_failed")).setEphemeral(true).queue();
                            return null;
                        });
            }
            case CANCEL -> {
                Optional<RequestView> request = decisions.findRequest(callback.requestId());
                if (request.isPresent()) {
                    String originalText = ApprovalMessageRenderer.render(request.get());
                    event.editMessage(originalText)
                            .setComponents(ActionRow.of(Arrays.asList(actionButtons(callback.requestId(), request.get().status()))))
                            .queue();
                } else {
                    event.editMessage(Messages.get("confirm.expired")).setComponents().queue();
                }
            }
        }
    }

    private static String confirmMessage(DecisionAction action, String player, String shortId, RequestStatus status) {
        return switch (action) {
            case APPROVE -> Messages.get("confirm.approve", player, shortId);
            case DENY -> Messages.get("confirm.deny", player, shortId);
            case BLOCK -> Messages.get("confirm.block", player, shortId);
            case UNDO -> switch (status) {
                case APPROVED -> Messages.get("confirm.undo_approval", player, shortId);
                case DENIED -> Messages.get("confirm.reopen", player, shortId);
                case BLOCKED -> Messages.get("confirm.unblock_reopen", player, shortId);
                default -> Messages.get("confirm.undo", player, shortId);
            };
        };
    }

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }

    static Button[] actionButtons(UUID requestId, RequestStatus status) {
        return new Button[]{
                Button.success(CallbackActionParser.formatPrimary(DecisionAction.APPROVE, requestId),
                        ApprovalActionState.label(status, DecisionAction.APPROVE))
                        .withDisabled(!ApprovalActionState.canExecute(status, DecisionAction.APPROVE)),
                Button.danger(CallbackActionParser.formatPrimary(DecisionAction.DENY, requestId),
                        ApprovalActionState.label(status, DecisionAction.DENY))
                        .withDisabled(!ApprovalActionState.canExecute(status, DecisionAction.DENY)),
                Button.secondary(CallbackActionParser.formatPrimary(DecisionAction.BLOCK, requestId),
                        ApprovalActionState.label(status, DecisionAction.BLOCK))
                        .withDisabled(!ApprovalActionState.canExecute(status, DecisionAction.BLOCK)),
                Button.secondary(CallbackActionParser.formatPrimary(DecisionAction.UNDO, requestId),
                        ApprovalActionState.label(status, DecisionAction.UNDO))
                        .withDisabled(!ApprovalActionState.canExecute(status, DecisionAction.UNDO))
        };
    }

    private boolean authorized(ButtonInteractionEvent event) {
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
