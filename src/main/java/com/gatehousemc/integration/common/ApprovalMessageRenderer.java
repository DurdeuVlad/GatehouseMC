package com.gatehousemc.integration.common;

import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.RequestView;
import com.gatehousemc.i18n.Messages;

/**
 * Shared plain-text rendering of a whitelist request summary for approval messages.
 * Used by all transport adapters (Discord, Telegram, ...) so the message text stays identical.
 */
public final class ApprovalMessageRenderer {
    private ApprovalMessageRenderer() {}

    public static String render(RequestView request) {
        StringBuilder text = new StringBuilder();
        line(text, Messages.get("request.title"));
        field(text, Messages.get("request.player"), request.identity().exactUsername());
        field(text, Messages.get("request.offline_uuid"), request.identity().offlineUuid());
        line(text, Messages.get("request.identity"));
        field(text, Messages.get("request.attempts"), request.attemptCount());
        field(text, Messages.get("request.first_attempt"), request.firstAttemptAt());
        field(text, Messages.get("request.last_attempt"), request.lastAttemptAt());
        field(text, Messages.get("request.id"), request.id());
        field(text, Messages.get("request.status"), request.status());

        if (request.resolvedAt() != null) {
            field(text, Messages.get("request.resolved_at"), request.resolvedAt());
        }
        if (request.resolvedBy() != null) {
            AdminPrincipal actor = request.resolvedBy();
            field(text, Messages.get("request.resolved_by"),
                    actor.displayName() + " (" + actor.provider() + ")");
        }
        if (request.resolutionReason() != null && !request.resolutionReason().isBlank()) {
            field(text, Messages.get("request.reason"), request.resolutionReason());
        }
        return text.toString();
    }

    private static void field(StringBuilder text, String label, Object value) {
        line(text, label + ": " + value);
    }

    private static void line(StringBuilder text, String value) {
        if (text.length() > 0) text.append('\n');
        text.append(value);
    }
}
