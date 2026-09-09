package com.gatehousemc.domain;

import java.time.Instant;
import java.util.UUID;

public record RequestView(
        UUID id,
        PlayerIdentity identity,
        RequestStatus status,
        long attemptCount,
        Instant firstAttemptAt,
        Instant lastAttemptAt,
        Instant resolvedAt,
        AdminPrincipal resolvedBy,
        String resolutionReason
) {
    public static RequestView from(WhitelistRequest request) {
        return new RequestView(request.id(), request.identity(), request.status(), request.attemptCount(),
                request.firstAttemptAt(), request.lastAttemptAt(), request.resolvedAt(), request.resolvedBy(),
                request.resolutionReason());
    }
}
