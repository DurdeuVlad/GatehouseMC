package com.gatehousemc.whitelistrequest.integration.discord;

import com.gatehousemc.whitelistrequest.application.DecisionService;
import com.gatehousemc.whitelistrequest.config.ModConfig;
import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.i18n.Messages;
import com.gatehousemc.whitelistrequest.integration.common.ApprovalMessageRenderer;
import com.gatehousemc.whitelistrequest.integration.common.CallbackActionParser;
import com.gatehousemc.whitelistrequest.integration.common.CallbackActionParser.ParsedCallback;
import com.gatehousemc.whitelistrequest.integration.common.CallbackActionParser.CallbackKind;
import com.gatehousemc.whitelistrequest.port.ApprovalInterface;
import com.gatehousemc.whitelistrequest.port.ProviderHealth;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Discord projection and interaction adapter. Business transitions remain in DecisionService. */
public final class DiscordApprovalInterface extends ListenerAdapter implements ApprovalInterface {
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
            transport = new JdaDiscordTransport(jda, config.channelId());
            health = ProviderHealth.HEALTHY;
        } catch (RuntimeException error) {
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
    public CompletionStage<PublicationRef> publish(RequestView request) {
        DiscordTransport current = transport;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord is unavailable"));
        return current.sendMessage(config.channelId(), render(request), request.id(), false)
                .thenApply(messageId -> new PublicationRef(id(), config.channelId(), messageId));
    }

    @Override
    public CompletionStage<Void> update(PublicationRef publication, RequestView request) {
        DiscordTransport current = transport;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord is stopped"));
        return current.editMessage(publication.containerId(), publication.messageId(), render(request), request.id(), request.status().isTerminal());
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
                String player = request.map(r -> r.identity().exactUsername()).orElse("?");
                String shortId = shortId(callback.requestId());
                String confirmMsg = confirmMessage(callback.action(), player, shortId);
                Button confirmBtn = Button.danger(CallbackActionParser.formatConfirm(callback.action(), callback.requestId()), Messages.get("button.confirm"));
                Button cancelBtn = Button.secondary(CallbackActionParser.formatCancel(callback.requestId()), Messages.get("button.cancel"));
                event.editMessage(confirmMsg).setComponents(ActionRow.of(confirmBtn, cancelBtn)).queue();
            }
            case CONFIRM -> {
                event.deferEdit().queue();
                decisions.decide(callback.requestId(), callback.action(), principal, Optional.empty())
                        .thenAccept(result -> event.getHook().sendMessage(result.message()).setEphemeral(true).queue())
                        .exceptionally(error -> { event.getHook().sendMessage(Messages.get("provider.decision_failed")).setEphemeral(true).queue(); return null; });
            }
            case CANCEL -> {
                Optional<RequestView> request = decisions.findRequest(callback.requestId());
                if (request.isPresent()) {
                    String originalText = ApprovalMessageRenderer.render(request.get());
                    event.editMessage(originalText).setComponents(ActionRow.of(Arrays.asList(actionButtons(callback.requestId(), request.get().status().isTerminal())))).queue();
                } else {
                    event.editMessage(Messages.get("confirm.cancelled")).setComponents().queue();
                }
            }
        }
    }

    private static String confirmMessage(DecisionAction action, String player, String shortId) {
        return switch (action) {
            case APPROVE -> Messages.get("confirm.approve", player, shortId);
            case DENY -> Messages.get("confirm.deny", player, shortId);
            case BLOCK -> Messages.get("confirm.block", player, shortId);
            case UNDO -> Messages.get("confirm.undo", player, shortId);
        };
    }

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }

    static Button[] actionButtons(UUID requestId, boolean disabled) {
        return new Button[]{
                Button.success(CallbackActionParser.formatPrimary(DecisionAction.APPROVE, requestId), Messages.get("button.approve")).withDisabled(disabled),
                Button.danger(CallbackActionParser.formatPrimary(DecisionAction.DENY, requestId), Messages.get("button.deny")).withDisabled(disabled),
                Button.secondary(CallbackActionParser.formatPrimary(DecisionAction.BLOCK, requestId), Messages.get("button.block")).withDisabled(disabled),
                Button.secondary(CallbackActionParser.formatPrimary(DecisionAction.UNDO, requestId), Messages.get("button.undo")).withDisabled(disabled)
        };
    }

    private boolean authorized(ButtonInteractionEvent event) {
        if (event.getGuild() == null || !event.getGuild().getId().equals(config.guildId())) return false;
        if (config.allowedUserIds().contains(event.getUser().getId())) return true;
        Member member = event.getMember();
        if (member == null) return false;
        return member.getRoles().stream().map(Role::getId).anyMatch(config.allowedRoleIds()::contains);
    }

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
