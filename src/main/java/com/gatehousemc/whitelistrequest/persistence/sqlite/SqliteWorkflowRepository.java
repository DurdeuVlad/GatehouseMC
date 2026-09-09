package com.gatehousemc.whitelistrequest.persistence.sqlite;

import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.port.StorageException;
import com.gatehousemc.whitelistrequest.port.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqliteWorkflowRepository implements WorkflowRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteWorkflowRepository.class);
    private final SqliteDatabase database;
    private final TransactionHelper tx;
    private final RequestSqlMapper requests;
    private final OutboxSqlMapper outbox;
    private final BlockStore blocks;
    private final AuditStore audit;

    public SqliteWorkflowRepository(SqliteDatabase database) {
        this.database = database;
        this.tx = new TransactionHelper(database.connection());
        this.requests = new RequestSqlMapper(tx);
        this.outbox = new OutboxSqlMapper(tx);
        this.blocks = new BlockStore(tx);
        this.audit = new AuditStore(tx);
    }

    @Override
    public synchronized AttemptOutcome recordAttempt(PlayerIdentity identity, Instant now, Duration denialCooldown) {
        return recordAttempt(identity, now, denialCooldown, 3);
    }

    private AttemptOutcome recordAttempt(PlayerIdentity identity, Instant now, Duration denialCooldown, int retriesRemaining) {
        try {
            tx.begin();
            if (blocks.isBlocked(identity.normalizedUsername())) {
                audit.record(null, "ATTEMPT_BLOCKED", null, AuditStore.jsonField("username", identity.exactUsername()), now);
                tx.commit();
                return AttemptOutcome.of(AttemptState.BLOCKED, null);
            }
            WhitelistRequest active = requests.findActiveByName(identity.normalizedUsername());
            if (active != null) {
                requests.updateAttemptTimestamps(active.id(), now);
                audit.record(active.id(), "ATTEMPT_UPDATED", null, AuditStore.jsonField("exactUsername", identity.exactUsername()), now);
                outbox.insert("REQUEST_UPDATED", active.id(), now);
                tx.commit();
                return AttemptOutcome.of(AttemptState.PENDING, requests.findById(active.id()));
            }
            WhitelistRequest latestDenied = requests.latestByStatus(identity.normalizedUsername(), RequestStatus.DENIED);
            if (latestDenied != null && latestDenied.resolvedAt() != null) {
                Instant until = latestDenied.resolvedAt().plus(denialCooldown);
                if (until.isAfter(now)) {
                    audit.record(latestDenied.id(), "DENIAL_COOLDOWN", null, AuditStore.emptyJson(), now);
                    tx.commit();
                    return AttemptOutcome.deniedUntil(until, latestDenied);
                }
            }
            UUID id = UUID.randomUUID();
            WhitelistRequest created = new WhitelistRequest(id, identity, RequestStatus.PENDING, now, now, now, now,
                    1, null, null, null, null, null);
            requests.insert(created);
            outbox.insert("REQUEST_CREATED", id, now);
            audit.record(id, "REQUEST_CREATED", null, AuditStore.emptyJson(), now);
            tx.commit();
            return AttemptOutcome.of(AttemptState.CREATED, created);
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            if (retriesRemaining > 0 && isRetryableAttemptRace(exception)) {
                tx.resetAutoCommit();
                return recordAttempt(identity, now, denialCooldown, retriesRemaining - 1);
            }
            return AttemptOutcome.of(AttemptState.DEGRADED, null);
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized Optional<WhitelistRequest> findById(UUID requestId) {
        try {
            return Optional.ofNullable(requests.findById(requestId));
        } catch (SQLException exception) {
            throw storageFailure("find_request", exception);
        }
    }

    @Override
    public synchronized Optional<WhitelistRequest> findActiveByName(String normalizedUsername) {
        try {
            return Optional.ofNullable(requests.findActiveByName(normalizedUsername));
        } catch (SQLException exception) {
            throw storageFailure("find_active_request", exception);
        }
    }

    @Override
    public synchronized List<WhitelistRequest> findByStatus(Optional<RequestStatus> status, int limit) {
        try {
            return requests.findByStatus(status, limit);
        } catch (SQLException exception) {
            throw storageFailure("find_requests_by_status", exception);
        }
    }

    @Override
    public synchronized void savePublication(UUID requestId, PublicationRef publication, Instant now) {
        try {
            requests.savePublication(requestId, publication, now);
        } catch (SQLException error) {
            LOGGER.warn("storage.persistence.failed operation=save_publication requestId={} provider={} errorType={}",
                    requestId, publication.provider(), error.getClass().getSimpleName());
            throw storageFailure("save_publication", error);
        }
    }

    @Override
    public synchronized List<PublicationRef> publications(UUID requestId) {
        try {
            return requests.publications(requestId);
        } catch (SQLException exception) {
            throw storageFailure("find_publications", exception);
        }
    }

    @Override
    public synchronized DecisionClaim claimApproval(UUID requestId, AdminPrincipal actor, String reason, Instant now, UUID token) {
        try {
            tx.begin();
            WhitelistRequest request = requests.findById(requestId);
            if (request == null) {
                tx.rollbackQuietly();
                return new DecisionClaim(DecisionClaim.ClaimOutcome.NOT_FOUND, Optional.empty(), token);
            }
            if (request.status() == RequestStatus.RESOLVING) {
                tx.rollbackQuietly();
                return new DecisionClaim(DecisionClaim.ClaimOutcome.ALREADY_RESOLVING, Optional.of(request), token);
            }
            if (request.status() != RequestStatus.PENDING) {
                tx.rollbackQuietly();
                return new DecisionClaim(DecisionClaim.ClaimOutcome.ALREADY_RESOLVED, Optional.of(request), token);
            }
            if (!requests.claimApproval(requestId, actor, reason, now, token)) {
                tx.rollbackQuietly();
                WhitelistRequest current = requests.findById(requestId);
                if (current == null) return new DecisionClaim(DecisionClaim.ClaimOutcome.NOT_FOUND, Optional.empty(), token);
                DecisionClaim.ClaimOutcome outcome = current.status() == RequestStatus.RESOLVING
                        ? DecisionClaim.ClaimOutcome.ALREADY_RESOLVING : DecisionClaim.ClaimOutcome.ALREADY_RESOLVED;
                return new DecisionClaim(outcome, Optional.of(current), token);
            }
            audit.record(requestId, "DECISION_CLAIMED", actor, AuditStore.jsonField("action", "APPROVE"), now);
            tx.commit();
            return new DecisionClaim(DecisionClaim.ClaimOutcome.CLAIMED, Optional.of(requests.findById(requestId)), token);
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            return new DecisionClaim(DecisionClaim.ClaimOutcome.NOT_FOUND, Optional.empty(), token);
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized DecisionResultSnapshot resolveTerminal(UUID requestId, DecisionAction action, AdminPrincipal actor, String reason, Instant now) {
        if (action == DecisionAction.APPROVE) throw new IllegalArgumentException("APPROVE requires claimApproval");
        try {
            tx.begin();
            WhitelistRequest request = requests.findById(requestId);
            if (request == null) {
                tx.rollbackQuietly();
                return new DecisionResultSnapshot(DecisionOutcome.NOT_FOUND, Optional.empty(), "Request not found");
            }
            if (request.status() != RequestStatus.PENDING) {
                tx.rollbackQuietly();
                return new DecisionResultSnapshot(request.status() == RequestStatus.RESOLVING ? DecisionOutcome.RESOLVING : DecisionOutcome.ALREADY_RESOLVED,
                        Optional.of(request), "Request is already " + request.status().name().toLowerCase());
            }
            RequestStatus status = action == DecisionAction.BLOCK ? RequestStatus.BLOCKED : RequestStatus.DENIED;
            if (!requests.resolveTerminal(requestId, status, actor, reason, now)) {
                tx.rollbackQuietly();
                WhitelistRequest current = requests.findById(requestId);
                if (current == null) return new DecisionResultSnapshot(DecisionOutcome.NOT_FOUND, Optional.empty(), "Request not found");
                DecisionOutcome outcome = current.status() == RequestStatus.RESOLVING ? DecisionOutcome.RESOLVING : DecisionOutcome.ALREADY_RESOLVED;
                return new DecisionResultSnapshot(outcome, Optional.of(current), "Request is already " + current.status().name().toLowerCase());
            }
            if (action == DecisionAction.BLOCK) {
                blocks.insertBlock(request.identity(), request.id(), actor, reason, now);
            }
            audit.record(requestId, action == DecisionAction.BLOCK ? "REQUEST_BLOCKED" : "REQUEST_DENIED", actor, AuditStore.emptyJson(), now);
            outbox.insert("REQUEST_RESOLVED", requestId, now);
            tx.commit();
            WhitelistRequest resolved = requests.findById(requestId);
            return new DecisionResultSnapshot(action == DecisionAction.BLOCK ? DecisionOutcome.BLOCKED : DecisionOutcome.DENIED,
                    Optional.of(resolved), "Request " + status.name().toLowerCase());
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            return new DecisionResultSnapshot(DecisionOutcome.FAILED, Optional.empty(), "Database error while resolving request");
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized boolean finalizeApproval(UUID requestId, UUID token, AdminPrincipal actor, String reason, Instant now) {
        try {
            tx.begin();
            if (!requests.finalizeApproval(requestId, token, actor, reason, now)) {
                tx.rollbackQuietly();
                return false;
            }
            audit.record(requestId, "REQUEST_APPROVED", actor, AuditStore.emptyJson(), now);
            outbox.insert("REQUEST_RESOLVED", requestId, now);
            tx.commit();
            return true;
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            return false;
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized boolean resetApproval(UUID requestId, UUID token, AdminPrincipal actor, String error, Instant now) {
        try {
            tx.begin();
            if (!requests.resetApproval(requestId, token, now)) {
                tx.rollbackQuietly();
                return false;
            }
            audit.record(requestId, "APPROVAL_FAILED", actor, AuditStore.jsonField("error", error), now);
            outbox.insert("REQUEST_UPDATED", requestId, now);
            tx.commit();
            return true;
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            return false;
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized List<WhitelistRequest> findResolvingApprovals() {
        try {
            return requests.findResolvingApprovals();
        } catch (SQLException exception) {
            throw new StorageException("Failed to query resolving approvals", exception);
        }
    }

    @Override
    public synchronized List<WhitelistRequest> findResolvingUndos() {
        try {
            return requests.findResolvingUndos();
        } catch (SQLException exception) {
            throw new StorageException("Failed to query resolving undos", exception);
        }
    }

    @Override
    public synchronized DecisionResultSnapshot reopen(UUID requestId, AdminPrincipal actor, String reason, Instant now) {
        try {
            tx.begin();
            WhitelistRequest request = requests.findById(requestId);
            if (request == null) {
                tx.rollbackQuietly();
                return new DecisionResultSnapshot(DecisionOutcome.NOT_FOUND, Optional.empty(), "Request not found");
            }
            if (request.status() == RequestStatus.PENDING) {
                tx.rollbackQuietly();
                return new DecisionResultSnapshot(DecisionOutcome.ALREADY_PENDING, Optional.of(request), "Request is already pending");
            }
            if (request.status() == RequestStatus.RESOLVING) {
                tx.rollbackQuietly();
                return new DecisionResultSnapshot(DecisionOutcome.RESOLVING, Optional.of(request), "Request is currently resolving");
            }
            boolean wasBlocked = request.status() == RequestStatus.BLOCKED;
            if (!requests.reopen(requestId, actor, reason, now)) {
                tx.rollbackQuietly();
                WhitelistRequest current = requests.findById(requestId);
                if (current == null) return new DecisionResultSnapshot(DecisionOutcome.NOT_FOUND, Optional.empty(), "Request not found");
                DecisionOutcome outcome = current.status() == RequestStatus.PENDING
                        ? DecisionOutcome.ALREADY_PENDING : DecisionOutcome.ALREADY_RESOLVED;
                return new DecisionResultSnapshot(outcome, Optional.of(current), "Request is " + current.status().name().toLowerCase());
            }
            if (wasBlocked) {
                blocks.removeBlock(request.identity().normalizedUsername());
            }
            audit.record(requestId, "REQUEST_REOPENED", actor, AuditStore.emptyJson(), now);
            outbox.insert("REQUEST_UPDATED", requestId, now);
            tx.commit();
            WhitelistRequest reopened = requests.findById(requestId);
            return new DecisionResultSnapshot(DecisionOutcome.UNDONE, Optional.of(reopened), "Request reopened");
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            return new DecisionResultSnapshot(DecisionOutcome.FAILED, Optional.empty(), "Database error while reopening request");
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized DecisionResultSnapshot claimUndo(UUID requestId, AdminPrincipal actor, String reason, Instant now, UUID token) {
        try {
            tx.begin();
            WhitelistRequest request = requests.findById(requestId);
            if (request == null) {
                tx.rollbackQuietly();
                return new DecisionResultSnapshot(DecisionOutcome.NOT_FOUND, Optional.empty(), "Request not found");
            }
            if (request.status() == RequestStatus.PENDING) {
                tx.rollbackQuietly();
                return new DecisionResultSnapshot(DecisionOutcome.ALREADY_PENDING, Optional.of(request), "Request is already pending");
            }
            if (request.status() == RequestStatus.RESOLVING) {
                tx.rollbackQuietly();
                return new DecisionResultSnapshot(DecisionOutcome.RESOLVING, Optional.of(request), "Request is currently resolving");
            }
            if (!requests.claimUndo(requestId, actor, reason, now, token)) {
                tx.rollbackQuietly();
                WhitelistRequest current = requests.findById(requestId);
                if (current == null) return new DecisionResultSnapshot(DecisionOutcome.NOT_FOUND, Optional.empty(), "Request not found");
                DecisionOutcome outcome = current.status() == RequestStatus.PENDING
                        ? DecisionOutcome.ALREADY_PENDING : DecisionOutcome.ALREADY_RESOLVED;
                return new DecisionResultSnapshot(outcome, Optional.of(current), "Request is " + current.status().name().toLowerCase());
            }
            audit.record(requestId, "UNDO_CLAIMED", actor, AuditStore.jsonField("action", "UNDO"), now);
            tx.commit();
            return new DecisionResultSnapshot(DecisionOutcome.RESOLVING, Optional.of(requests.findById(requestId)), "Undo claimed");
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            return new DecisionResultSnapshot(DecisionOutcome.FAILED, Optional.empty(), "Database error while claiming undo");
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized boolean resetUndo(UUID requestId, UUID token, AdminPrincipal actor, String error, Instant now) {
        try {
            tx.begin();
            if (!requests.resetUndo(requestId, token, now)) {
                tx.rollbackQuietly();
                return false;
            }
            audit.record(requestId, "UNDO_FAILED", actor, AuditStore.jsonField("error", error), now);
            outbox.insert("REQUEST_UPDATED", requestId, now);
            tx.commit();
            return true;
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            return false;
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized DecisionResultSnapshot finalizeUndo(UUID requestId, UUID token, AdminPrincipal actor, String reason, Instant now) {
        try {
            tx.begin();
            if (!requests.finalizeUndo(requestId, token, actor, reason, now)) {
                tx.rollbackQuietly();
                WhitelistRequest current = requests.findById(requestId);
                if (current == null) return new DecisionResultSnapshot(DecisionOutcome.NOT_FOUND, Optional.empty(), "Request not found");
                return new DecisionResultSnapshot(DecisionOutcome.ALREADY_RESOLVED, Optional.of(current), "Request is " + current.status().name().toLowerCase());
            }
            audit.record(requestId, "REQUEST_UNDONE", actor, AuditStore.emptyJson(), now);
            outbox.insert("REQUEST_UPDATED", requestId, now);
            tx.commit();
            WhitelistRequest undone = requests.findById(requestId);
            return new DecisionResultSnapshot(DecisionOutcome.UNDONE, Optional.of(undone), "Request reopened");
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            return new DecisionResultSnapshot(DecisionOutcome.FAILED, Optional.empty(), "Database error while finalizing undo");
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized boolean unblock(String normalizedUsername, AdminPrincipal actor, String reason, Instant now) {
        try {
            tx.begin();
            if (!blocks.removeBlock(normalizedUsername)) {
                tx.rollbackQuietly();
                return false;
            }
            audit.record(null, "BLOCK_REMOVED", actor, AuditStore.jsonField("username", normalizedUsername), now);
            tx.commit();
            return true;
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            return false;
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized boolean isBlocked(String normalizedUsername) {
        try {
            return blocks.isBlocked(normalizedUsername);
        } catch (SQLException exception) {
            throw new StorageException("Failed to check block status for " + normalizedUsername, exception);
        }
    }

    @Override
    public synchronized List<OutboxEvent> readyOutbox(Instant now, int limit) {
        return readyOutbox(now, limit, 3);
    }

    private List<OutboxEvent> readyOutbox(Instant now, int limit, int retriesRemaining) {
        try {
            tx.begin();
            List<OutboxEvent> claimed = outbox.readyAndClaim(now, limit);
            tx.commit();
            return claimed;
        } catch (SQLException exception) {
            tx.rollbackQuietly();
            if (retriesRemaining > 0 && isRetryableAttemptRace(exception)) {
                tx.resetAutoCommit();
                return readyOutbox(now, limit, retriesRemaining - 1);
            }
            throw storageFailure("claim_outbox", exception);
        } finally {
            tx.resetAutoCommit();
        }
    }

    @Override
    public synchronized void completeOutbox(UUID outboxId, Instant now) {
        outbox.complete(outboxId, now);
    }

    @Override
    public synchronized void retryOutbox(UUID outboxId, Instant nextAttempt, String error, Instant now) {
        outbox.retry(outboxId, nextAttempt, error, now);
    }

    @Override
    public synchronized long pendingOutboxCount() {
        try {
            return outbox.pendingCount();
        } catch (SQLException exception) {
            throw new StorageException("Failed to count pending outbox events", exception);
        }
    }

    @Override
    public void close() throws SQLException {
        database.close();
    }

    static Instant instant(long value) { return Instant.ofEpochMilli(value); }
    static long millis(Instant instant) { return instant.toEpochMilli(); }

    private static IllegalStateException storageFailure(String operation, SQLException error) {
        LOGGER.error("storage.degraded operation={} errorType={}", operation, error.getClass().getSimpleName());
        return new IllegalStateException("SQLite storage operation failed: " + operation, error);
    }

    private static boolean isRetryableAttemptRace(SQLException exception) {
        int errorCode = exception.getErrorCode();
        int primaryCode = errorCode & 0xff;
        if (primaryCode == 5 || primaryCode == 6) return true;
        String message = exception.getMessage();
        return (primaryCode == 19 && message != null && message.contains("whitelist_requests.normalized_name"))
                || (message != null && (message.contains("database is locked") || message.contains("database table is locked")));
    }
}
