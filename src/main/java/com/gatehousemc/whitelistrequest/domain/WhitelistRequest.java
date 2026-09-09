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
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        if (firstAttemptAt.isBefore(createdAt) || firstAttemptAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("firstAttemptAt must be within the request lifetime");
        }
        if (lastAttemptAt.isBefore(firstAttemptAt) || lastAttemptAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("lastAttemptAt must be within the request lifetime");
        }
        if (resolvedAt != null && (resolvedAt.isBefore(createdAt) || resolvedAt.isBefore(firstAttemptAt)
                || resolvedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("resolvedAt must be within the request lifetime");
        }
        switch (status) {
            case PENDING -> requirePendingMetadata(resolvedAt, resolvedBy, resolutionReason, resolvingAction, resolvingToken);
            case RESOLVING -> requireResolvingMetadata(resolvedAt, resolvedBy, resolvingAction, resolvingToken);
            case APPROVED, DENIED, BLOCKED -> requireTerminalMetadata(resolvedAt, resolvedBy, resolvingAction, resolvingToken);
        }
    }

    private static void requirePendingMetadata(Instant resolvedAt, AdminPrincipal resolvedBy,
                                               String resolutionReason, DecisionAction resolvingAction,
                                               UUID resolvingToken) {
        if (resolvedAt != null || resolvedBy != null || resolutionReason != null
                || resolvingAction != null || resolvingToken != null) {
            throw new IllegalArgumentException("PENDING request cannot contain resolution metadata");
        }
    }

    private static void requireResolvingMetadata(Instant resolvedAt, AdminPrincipal resolvedBy,
                                                 DecisionAction resolvingAction, UUID resolvingToken) {
        if (resolvedAt != null || resolvedBy == null
                || (resolvingAction != DecisionAction.APPROVE && resolvingAction != DecisionAction.UNDO)
                || resolvingToken == null) {
            throw new IllegalArgumentException("RESOLVING request requires an approval or undo claim");
        }
    }

    private static void requireTerminalMetadata(Instant resolvedAt, AdminPrincipal resolvedBy,
                                                DecisionAction resolvingAction, UUID resolvingToken) {
        if (resolvedAt == null || resolvedBy == null || resolvingAction != null || resolvingToken != null) {
            throw new IllegalArgumentException("Terminal request requires final resolution metadata");
        }
    }

    public Optional<AdminPrincipal> resolvedByOptional() {
        return Optional.ofNullable(resolvedBy);
    }
}
