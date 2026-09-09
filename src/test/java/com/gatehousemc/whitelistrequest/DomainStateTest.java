package com.gatehousemc.whitelistrequest;

import com.gatehousemc.whitelistrequest.domain.AdminPrincipal;
import com.gatehousemc.whitelistrequest.domain.DecisionAction;
import com.gatehousemc.whitelistrequest.domain.PlayerIdentity;
import com.gatehousemc.whitelistrequest.domain.RequestStatus;
import com.gatehousemc.whitelistrequest.domain.WhitelistRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainStateTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final PlayerIdentity IDENTITY = PlayerIdentity.of("Alice");
    private static final AdminPrincipal ACTOR = AdminPrincipal.console();

    @Test
    void acceptsOnlyDocumentedRequestTransitions() {
        assertDoesNotThrow(() -> RequestStatus.requireTransition(RequestStatus.PENDING, RequestStatus.RESOLVING));
        assertDoesNotThrow(() -> RequestStatus.requireTransition(RequestStatus.RESOLVING, RequestStatus.PENDING));
        assertDoesNotThrow(() -> RequestStatus.requireTransition(RequestStatus.RESOLVING, RequestStatus.APPROVED));
        assertDoesNotThrow(() -> RequestStatus.requireTransition(RequestStatus.PENDING, RequestStatus.DENIED));
        assertDoesNotThrow(() -> RequestStatus.requireTransition(RequestStatus.PENDING, RequestStatus.BLOCKED));
        // Undo: terminal states can transition back to PENDING.
        assertDoesNotThrow(() -> RequestStatus.requireTransition(RequestStatus.APPROVED, RequestStatus.PENDING));
        assertDoesNotThrow(() -> RequestStatus.requireTransition(RequestStatus.DENIED, RequestStatus.PENDING));
        assertDoesNotThrow(() -> RequestStatus.requireTransition(RequestStatus.BLOCKED, RequestStatus.PENDING));
    }

    @Test
    void rejectsDirectApprovalAndTerminalStateChanges() {
        assertThrows(IllegalStateException.class,
                () -> RequestStatus.requireTransition(RequestStatus.PENDING, RequestStatus.APPROVED));
        assertThrows(IllegalStateException.class,
                () -> RequestStatus.requireTransition(RequestStatus.DENIED, RequestStatus.APPROVED));
        assertThrows(IllegalStateException.class,
                () -> RequestStatus.requireTransition(RequestStatus.APPROVED, RequestStatus.DENIED));
    }

    @Test
    void acceptsAResolvingApprovalClaim() {
        assertDoesNotThrow(() -> new WhitelistRequest(
                UUID.randomUUID(), IDENTITY, RequestStatus.RESOLVING,
                NOW, NOW, NOW, NOW, 1,
                null, ACTOR, null, DecisionAction.APPROVE, UUID.randomUUID()));
    }

    @Test
    void acceptsAResolvingUndoClaim() {
        assertDoesNotThrow(() -> new WhitelistRequest(
                UUID.randomUUID(), IDENTITY, RequestStatus.RESOLVING,
                NOW, NOW, NOW, NOW, 1,
                null, ACTOR, null, DecisionAction.UNDO, UUID.randomUUID()));
    }

    @Test
    void rejectsApprovedRequestWithoutResolutionMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new WhitelistRequest(
                UUID.randomUUID(), IDENTITY, RequestStatus.APPROVED,
                NOW, NOW, NOW, NOW, 1,
                null, null, null, null, null));
    }

    @Test
    void rejectsResolutionBeforeRequestCreation() {
        assertThrows(IllegalArgumentException.class, () -> new WhitelistRequest(
                UUID.randomUUID(), IDENTITY, RequestStatus.APPROVED,
                NOW, NOW, NOW, NOW, 1,
                NOW.minusSeconds(1), ACTOR, null, null, null));
    }

    @Test
    void rejectsMinecraftInvalidUsername() {
        assertThrows(IllegalArgumentException.class,
                () -> PlayerIdentity.of("A B C!"));
    }

    @Test
    void rejectsResolvingRequestWithoutApprovalClaim() {
        assertThrows(IllegalArgumentException.class, () -> new WhitelistRequest(
                UUID.randomUUID(), IDENTITY, RequestStatus.RESOLVING,
                NOW, NOW, NOW, NOW, 1,
                null, ACTOR, null, null, null));
    }

    @Test
    void rejectsPendingRequestWithResolutionMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new WhitelistRequest(
                UUID.randomUUID(), IDENTITY, RequestStatus.PENDING,
                NOW, NOW, NOW, NOW, 1,
                null, ACTOR, null, DecisionAction.APPROVE, UUID.randomUUID()));
    }
}
