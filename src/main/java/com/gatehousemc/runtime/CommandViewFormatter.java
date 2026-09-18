package com.gatehousemc.runtime;

import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.i18n.Messages;

/** Loader-neutral formatting for Minecraft command output. */
public final class CommandViewFormatter {
    private CommandViewFormatter() {}

    public static String listEntry(WhitelistRequest request) {
        return request.id() + " | " + request.status() + " | "
                + request.identity().exactUsername() + " | attempts=" + request.attemptCount();
    }

    public static String details(WhitelistRequest request) {
        StringBuilder value = new StringBuilder();
        append(value, Messages.get("request.id"), request.id());
        append(value, Messages.get("request.status"), request.status());
        append(value, Messages.get("request.player"), request.identity().exactUsername());
        append(value, Messages.get("request.offline_uuid"), request.identity().offlineUuid());
        append(value, Messages.get("request.attempts"), request.attemptCount());
        append(value, Messages.get("request.first_attempt"), request.firstAttemptAt());
        append(value, Messages.get("request.last_attempt"), request.lastAttemptAt());
        if (request.resolvingAction() != null) {
            append(value, Messages.get("request.resolving_action"), request.resolvingAction());
        }
        if (request.resolvedAt() != null) {
            append(value, Messages.get("request.resolved_at"), request.resolvedAt());
        }
        if (request.resolvedBy() != null) {
            AdminPrincipal actor = request.resolvedBy();
            append(value, Messages.get("request.resolved_by"),
                    actor.displayName() + " (" + actor.provider() + ")");
        }
        if (request.resolutionReason() != null && !request.resolutionReason().isBlank()) {
            append(value, Messages.get("request.reason"), request.resolutionReason());
        }
        return value.toString();
    }

    private static void append(StringBuilder target, String label, Object value) {
        if (target.length() > 0) target.append('\n');
        target.append(label).append(": ").append(value);
    }
}
