package com.gatehousemc.integration.discord;

import com.gatehousemc.domain.RequestStatus;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Transport port for Discord message operations. Allows fake transports in tests
 * without requiring a live JDA connection.
 */
public interface DiscordTransport {
    /**
     * Sends a request card carrying the buttons valid for {@code status} to the specified channel.
     * @return the message ID
     */
    CompletableFuture<String> sendMessage(String channelId, String text, UUID requestId, RequestStatus status);

    /**
     * Edits an existing request card and replaces its buttons with the actions valid for
     * {@code status}; terminal states strip buttons entirely.
     * @return void on success
     */
    CompletableFuture<Void> editMessage(String channelId, String messageId, String text, UUID requestId, RequestStatus status);

    /**
     * Sends a reply to a request card carrying the secondary action valid for {@code status}
     * (undo for approved requests, reopen for denied/blocked ones).
     * @return the message ID
     */
    CompletableFuture<String> sendActionFollowUp(String channelId, String referenceMessageId, String text,
                                                 UUID requestId, RequestStatus status);

    /** Starts the transport (e.g. JDA connection). */
    void start();

    /** Stops the transport. */
    void stop();
}
