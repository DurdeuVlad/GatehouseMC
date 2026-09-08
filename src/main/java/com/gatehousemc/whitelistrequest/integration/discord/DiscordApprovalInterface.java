package com.gatehousemc.whitelistrequest.integration.discord;

import com.gatehousemc.whitelistrequest.application.DecisionService;
import com.gatehousemc.whitelistrequest.config.ModConfig;
import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.port.ApprovalInterface;
import com.gatehousemc.whitelistrequest.port.ProviderHealth;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Discord projection and interaction adapter. Business transitions remain in DecisionService. */
public final class DiscordApprovalInterface extends ListenerAdapter implements ApprovalInterface {
    private final ModConfig.Discord config;
    private final DecisionService decisions;
    private volatile ProviderHealth health = ProviderHealth.STOPPED;
    private volatile JDA jda;

    public DiscordApprovalInterface(ModConfig.Discord config, DecisionService decisions) {
        this.config = config;
        this.decisions = decisions;
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
        try {
            health = ProviderHealth.STARTING;
            jda = JDABuilder.createDefault(config.token()).addEventListeners(this).build();
            health = ProviderHealth.HEALTHY;
        } catch (RuntimeException error) {
            health = ProviderHealth.UNAVAILABLE;
        }
    }

    @Override
    public void stop() {
        JDA current = jda;
        jda = null;
        health = ProviderHealth.STOPPED;
        if (current != null) current.shutdownNow();
    }

    @Override
    public CompletionStage<PublicationRef> publish(RequestView request) {
        TextChannel channel = channel();
        if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord channel is unavailable"));
        CompletableFuture<PublicationRef> result = new CompletableFuture<>();
        channel.sendMessage(render(request))
                .addComponents(ActionRow.of(Arrays.asList(actionButtons(request.id(), false))))
                .queue(message -> result.complete(new PublicationRef(id(), config.channelId(), message.getId())), result::completeExceptionally);
        return result;
    }

    @Override
    public CompletionStage<Void> update(PublicationRef publication, RequestView request) {
        JDA current = jda;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord is stopped"));
        TextChannel channel = current.getTextChannelById(publication.containerId());
        if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord channel is unavailable"));
        CompletableFuture<Void> result = new CompletableFuture<>();
        channel.editMessageById(publication.messageId(), render(request))
                .setComponents(ActionRow.of(Arrays.asList(actionButtons(request.id(), request.status().isTerminal()))))
                .queue(message -> result.complete(null), result::completeExceptionally);
        return result;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String customId = event.getComponentId();
        ParsedAction action = parse(customId);
        if (action == null) {
            event.reply("Invalid whitelist request action.").setEphemeral(true).queue();
            return;
        }
        if (!authorized(event)) {
            event.reply("You are not authorized to resolve whitelist requests.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        decisions.decide(action.requestId(), action.action(),
                        new AdminPrincipal("discord", event.getUser().getId(), event.getUser().getEffectiveName()), Optional.empty())
                .thenAccept(result -> event.getHook().sendMessage(result.message()).setEphemeral(true).queue())
                .exceptionally(error -> { event.getHook().sendMessage("Decision failed.").setEphemeral(true).queue(); return null; });
    }

    private TextChannel channel() {
        JDA current = jda;
        return current == null || config.channelId().isBlank() ? null : current.getTextChannelById(config.channelId());
    }

    private boolean authorized(ButtonInteractionEvent event) {
        if (event.getGuild() == null || !event.getGuild().getId().equals(config.guildId())) return false;
        if (config.allowedUserIds().contains(event.getUser().getId())) return true;
        Member member = event.getMember();
        if (member == null) return false;
        return member.getRoles().stream().map(Role::getId).anyMatch(config.allowedRoleIds()::contains);
    }

    private static String render(RequestView request) {
        return "Whitelist request\n" +
                "Player: " + request.identity().exactUsername() + "\n" +
                "Offline UUID: " + request.identity().offlineUuid() + "\n" +
                "Identity: OFFLINE / UNAUTHENTICATED\n" +
                "Attempts: " + request.attemptCount() + "\n" +
                "Request: " + request.id() + "\n" +
                "Status: " + request.status();
    }

    private static Button[] actionButtons(UUID requestId, boolean disabled) {
        return new Button[]{
                Button.success("wr:a:" + requestId, "Approve").withDisabled(disabled),
                Button.danger("wr:d:" + requestId, "Deny").withDisabled(disabled),
                Button.secondary("wr:b:" + requestId, "Block").withDisabled(disabled)
        };
    }

    private static ParsedAction parse(String value) {
        if (value == null || !value.startsWith("wr:") || value.length() < 5) return null;
        String[] parts = value.split(":", 3);
        if (parts.length != 3) return null;
        try {
            DecisionAction action = switch (parts[1]) {
                case "a" -> DecisionAction.APPROVE;
                case "d" -> DecisionAction.DENY;
                case "b" -> DecisionAction.BLOCK;
                default -> null;
            };
            return action == null ? null : new ParsedAction(action, UUID.fromString(parts[2]));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record ParsedAction(DecisionAction action, UUID requestId) {}
}
