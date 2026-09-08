package com.gatehousemc.whitelistrequest.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record WhitelistRequest(
        UUID id,
        PlayerIdentity identity,
        RequestStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant firstAttemptAt,
        Instant lastAttemptAt,
        long attemptCount,
        Instant resolvedAt,
        AdminPrincipal resolvedBy,
        String resolutionReason,
        DecisionAction resolvingAction,
        UUID resolvingToken
) {
    public WhitelistRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(firstAttemptAt, "firstAttemptAt");
        Objects.requireNonNull(lastAttemptAt, "lastAttemptAt");
        if (attemptCount < 1) throw new IllegalArgumentException("attemptCount must be positive");
    }

    public Optional<AdminPrincipal> resolvedByOptional() {
        return Optional.ofNullable(resolvedBy);
    }
}
