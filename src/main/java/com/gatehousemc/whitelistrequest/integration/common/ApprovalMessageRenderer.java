package com.gatehousemc.whitelistrequest.integration.common;

import com.gatehousemc.whitelistrequest.domain.RequestView;

/**
 * Shared plain-text rendering of a whitelist request summary for approval messages.
 * Used by all transport adapters (Discord, Telegram, ...) so the message text stays identical.
 */
public final class ApprovalMessageRenderer {
    private ApprovalMessageRenderer() {}

    public static String render(RequestView request) {
        return "Whitelist request\n" +
                "Player: " + request.identity().exactUsername() + "\n" +
                "Offline UUID: " + request.identity().offlineUuid() + "\n" +
                "Identity: OFFLINE / UNAUTHENTICATED\n" +
                "Attempts: " + request.attemptCount() + "\n" +
                "Request: " + request.id() + "\n" +
                "Status: " + request.status();
    }
}
