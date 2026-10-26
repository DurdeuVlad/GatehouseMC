package com.gatehousemc.integration.common;

import com.gatehousemc.domain.RequestView;
import com.gatehousemc.i18n.Messages;

/**
 * Shared plain-text rendering of a whitelist request summary for approval messages.
 * Used by all transport adapters (Discord, Telegram, ...) so the message text stays identical.
 */
public final class ApprovalMessageRenderer {
    private ApprovalMessageRenderer() {}

    public static String render(RequestView request) {
        return Messages.get("request.title") + "\n" +
                Messages.get("request.player") + ": " + request.identity().exactUsername() + "\n" +
                Messages.get("request.offline_uuid") + ": " + request.identity().offlineUuid() + "\n" +
                Messages.get("request.identity") + "\n" +
                Messages.get("request.attempts") + ": " + request.attemptCount() + "\n" +
                Messages.get("request.id") + ": " + request.id() + "\n" +
                Messages.get("request.status") + ": " + request.status() + terminalMetadata(request);
    }

    private static String terminalMetadata(RequestView request) {
        if (!request.status().isTerminal()) return "";
        StringBuilder result = new StringBuilder();
        if (request.resolvedBy() != null) {
            result.append("\nResolved by: ").append(request.resolvedBy().displayName());
        }
        if (request.resolutionReason() != null && !request.resolutionReason().isBlank()) {
            result.append("\nReason: ").append(request.resolutionReason());
        }
        return result.toString();
    }
}
