package com.gatehousemc.whitelistrequest.port;

import com.gatehousemc.whitelistrequest.domain.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository extends AutoCloseable {
    AttemptOutcome recordAttempt(PlayerIdentity identity, Instant now, Duration denialCooldown);

    Optional<WhitelistRequest> findById(UUID requestId);

    Optional<WhitelistRequest> findActiveByName(String normalizedUsername);

    List<WhitelistRequest> findByStatus(Optional<RequestStatus> status, int limit);

    void savePublication(UUID requestId, PublicationRef publication, Instant now);

    List<PublicationRef> publications(UUID requestId);

    DecisionClaim claimApproval(UUID requestId, AdminPrincipal actor, String reason, Instant now, UUID token);

    DecisionResultSnapshot resolveTerminal(UUID requestId, DecisionAction action, AdminPrincipal actor,
                                           String reason, Instant now);

    boolean finalizeApproval(UUID requestId, UUID token, AdminPrincipal actor, String reason, Instant now);

    boolean resetApproval(UUID requestId, UUID token, AdminPrincipal actor, String error, Instant now);

    List<WhitelistRequest> findResolvingApprovals();

    boolean unblock(String normalizedUsername, AdminPrincipal actor, String reason, Instant now);

    List<OutboxEvent> readyOutbox(Instant now, int limit);

    void completeOutbox(UUID outboxId, Instant now);

    void retryOutbox(UUID outboxId, Instant nextAttempt, String error, Instant now);

    long pendingOutboxCount();

    record DecisionResultSnapshot(DecisionOutcome outcome, Optional<WhitelistRequest> request, String message) {}

    @Override
    default void close() throws Exception {}
}
