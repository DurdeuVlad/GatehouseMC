package com.gatehousemc.whitelistrequest.integration.discord;

import com.gatehousemc.whitelistrequest.i18n.Messages;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** JDA-backed implementation of DiscordTransport. */
final class JdaDiscordTransport implements DiscordTransport {
    private final JDA jda;
    private final String channelId;

    JdaDiscordTransport(JDA jda, String channelId) {
        this.jda = jda;
        this.channelId = channelId;
    }

    @Override
    public CompletableFuture<String> sendMessage(String channelId, String text, UUID requestId, boolean disabled) {
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord channel is unavailable"));
        CompletableFuture<String> result = new CompletableFuture<>();
        channel.sendMessage(text)
                .addComponents(ActionRow.of(Arrays.asList(actionButtons(requestId, disabled))))
                .queue(message -> result.complete(message.getId()), result::completeExceptionally);
        return result;
    }

    @Override
    public CompletableFuture<Void> editMessage(String channelId, String messageId, String text, UUID requestId, boolean disabled) {
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord channel is unavailable"));
        CompletableFuture<Void> result = new CompletableFuture<>();
        channel.editMessageById(messageId, text)
                .setComponents(ActionRow.of(Arrays.asList(actionButtons(requestId, disabled))))
                .queue(message -> result.complete(null), result::completeExceptionally);
        return result;
    }

    @Override
    public void start() {}

    @Override
    public void stop() {
        jda.shutdownNow();
    }

    private static Button[] actionButtons(UUID requestId, boolean disabled) {
        return new Button[]{
                Button.success("wr:a:" + requestId, Messages.get("button.approve")).withDisabled(disabled),
                Button.danger("wr:d:" + requestId, Messages.get("button.deny")).withDisabled(disabled),
                Button.secondary("wr:b:" + requestId, Messages.get("button.block")).withDisabled(disabled),
                Button.secondary("wr:u:" + requestId, Messages.get("button.undo")).withDisabled(disabled)
        };
    }
}
