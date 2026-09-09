package com.gatehousemc.integration.discord;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Transport port for Discord message operations. Allows fake transports in tests
 * without requiring a live JDA connection.
 */
public interface DiscordTransport {
    /**
     * Sends a message with approve/deny/block buttons to the specified channel.
     * @return the message ID
     */
    CompletableFuture<String> sendMessage(String channelId, String text, UUID requestId, boolean disabled);

    /**
     * Edits an existing message and updates its buttons.
     * @return void on success
     */
    CompletableFuture<Void> editMessage(String channelId, String messageId, String text, UUID requestId, boolean disabled);

    /** Starts the transport (e.g. JDA connection). */
    void start();

    /** Stops the transport. */
    void stop();
}
