package com.gatehousemc.port;

import com.gatehousemc.domain.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface WorkflowRepository extends AutoCloseable {
    AttemptOutcome recordAttempt(PlayerIdentity identity, Instant now, Duration denialCooldown);

    default AttemptOutcome recordAttempt(PlayerIdentity identity, Instant now, Duration denialCooldown,
                                        long attemptDelta) {
        if (attemptDelta < 1) throw new IllegalArgumentException("attemptDelta must be positive");
        AttemptOutcome outcome = recordAttempt(identity, now, denialCooldown);
        for (long i = 1; i < attemptDelta; i++) {
            outcome = recordAttempt(identity, now, denialCooldown);
        }
        return outcome;
    }

    default int countActiveRequests() {
        return findByStatus(Optional.of(RequestStatus.PENDING), Integer.MAX_VALUE).size()
                + findByStatus(Optional.of(RequestStatus.RESOLVING), Integer.MAX_VALUE).size();
    }

    Optional<WhitelistRequest> findById(UUID requestId);

    Optional<WhitelistRequest> findActiveByName(String normalizedUsername);

    Optional<WhitelistRequest> findLatestByName(String normalizedUsername);

    List<WhitelistRequest> findLatestByNameAndStatuses(String normalizedUsername, Set<RequestStatus> statuses, int limit);

    List<WhitelistRequest> findByIdPrefix(String prefix);

    List<WhitelistRequest> findByStatus(Optional<RequestStatus> status, int limit);

    void savePublication(UUID requestId, PublicationRef publication, Instant now);

    List<PublicationRef> publications(UUID requestId);

    DecisionClaim claimApproval(UUID requestId, AdminPrincipal actor, String reason, Instant now, UUID token);

    DecisionResultSnapshot resolveTerminal(UUID requestId, DecisionAction action, AdminPrincipal actor,
                                           String reason, Instant now);

    boolean finalizeApproval(UUID requestId, UUID token, AdminPrincipal actor, String reason, Instant now);

    boolean resetApproval(UUID requestId, UUID token, AdminPrincipal actor, String error, Instant now);

    List<WhitelistRequest> findResolvingApprovals();

    List<WhitelistRequest> findResolvingUndos();

    DecisionResultSnapshot reopen(UUID requestId, AdminPrincipal actor, String reason, Instant now);

    DecisionResultSnapshot claimUndo(UUID requestId, AdminPrincipal actor, String reason, Instant now, UUID token);

    boolean resetUndo(UUID requestId, UUID token, AdminPrincipal actor, String error, Instant now);

    DecisionResultSnapshot finalizeUndo(UUID requestId, UUID token, AdminPrincipal actor, String reason, Instant now);

    boolean unblock(String normalizedUsername, AdminPrincipal actor, String reason, Instant now);

    boolean isBlocked(String normalizedUsername);

    List<OutboxEvent> readyOutbox(Instant now, int limit);

    void completeOutbox(UUID outboxId, Instant now);

    void retryOutbox(UUID outboxId, Instant nextAttempt, String error, Instant now);

    default void deferOutbox(UUID outboxId, Instant nextAttempt, Instant now) {
        retryOutbox(outboxId, nextAttempt, "deferred", now);
    }

    long pendingOutboxCount();

    record DecisionResultSnapshot(DecisionOutcome outcome, Optional<WhitelistRequest> request, String message) {}

    @Override
    default void close() throws Exception {}
}
