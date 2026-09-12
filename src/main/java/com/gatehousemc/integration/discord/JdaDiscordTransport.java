package com.gatehousemc.integration.discord;

import com.gatehousemc.i18n.Messages;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** JDA-backed implementation of DiscordTransport. */
final class JdaDiscordTransport implements DiscordTransport {
    private final JDA jda;
    private final String dmUserId;

    JdaDiscordTransport(JDA jda, String dmUserId) {
        this.jda = jda;
        this.dmUserId = dmUserId;
    }

    @Override
    public CompletableFuture<String> sendMessage(String destination, String text, UUID requestId, boolean disabled) {
        return resolveChannel(destination).thenCompose(channel -> channel.sendMessage(text)
                .addComponents(ActionRow.of(Arrays.asList(actionButtons(requestId, disabled))))
                .submit())
                .thenApply(message -> message.getId());
    }

    @Override
    public CompletableFuture<Void> editMessage(String destination, String messageId, String text, UUID requestId, boolean disabled) {
        return resolveChannel(destination).thenCompose(channel -> channel.editMessageById(messageId, text)
                .setComponents(ActionRow.of(Arrays.asList(actionButtons(requestId, disabled))))
                .submit())
                .thenApply(message -> null);
    }

    @Override
    public void start() {}

    @Override
    public void stop() {
        jda.shutdownNow();
    }

    private CompletableFuture<? extends MessageChannel> resolveChannel(String destination) {
        if (dmUserId != null && !dmUserId.isBlank()) {
            if (destination != null && !destination.isBlank()) {
                PrivateChannel cached = jda.getPrivateChannelById(destination);
                if (cached != null) return CompletableFuture.completedFuture(cached);
            }
            return jda.retrieveUserById(destination).submit()
                    .thenCompose(user -> user.openPrivateChannel().submit());
        }
        MessageChannel channel = jda.getTextChannelById(destination);
        if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord channel is unavailable"));
        return CompletableFuture.completedFuture(channel);
    }

    private static Button[] actionButtons(UUID requestId, boolean disabled) {
        return DiscordApprovalInterface.actionButtons(requestId, disabled);
    }
}
