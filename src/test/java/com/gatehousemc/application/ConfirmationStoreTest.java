package com.gatehousemc.application;

import com.gatehousemc.application.admin.RequestAction;
import com.gatehousemc.domain.RequestStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationStoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID REQUEST_ID = UUID.randomUUID();

    @Test
    void validConfirmationIsBoundAndConsumedOnce() {
        ConfirmationStore store = new ConfirmationStore(() -> NOW);
        ConfirmationStore.Confirmation confirmation = store.issue("discord", "42", REQUEST_ID,
                RequestAction.DENY, RequestStatus.PENDING);

        ConfirmationStore.ConsumeResult result = store.consume(confirmation.token(), "discord", "42",
                RequestStatus.PENDING);
        assertEquals(ConfirmationStore.ConsumeStatus.CONSUMED, result.status());
        assertTrue(result.confirmation().isPresent());
        assertEquals(ConfirmationStore.ConsumeStatus.MISSING,
                store.consume(confirmation.token(), "discord", "42", RequestStatus.PENDING).status());
    }

    @Test
    void wrongActorProviderAndStateNeverMutateTheTokenAsAValidAction() {
        ConfirmationStore store = new ConfirmationStore(() -> NOW);
        ConfirmationStore.Confirmation confirmation = store.issue("telegram", "42", REQUEST_ID,
                RequestAction.APPROVE, RequestStatus.PENDING);

        assertEquals(ConfirmationStore.ConsumeStatus.WRONG_ACTOR,
                store.consume(confirmation.token(), "telegram", "99", RequestStatus.PENDING).status());
        assertEquals(ConfirmationStore.ConsumeStatus.WRONG_PROVIDER,
                store.consume(confirmation.token(), "discord", "42", RequestStatus.PENDING).status());
        assertEquals(ConfirmationStore.ConsumeStatus.STALE,
                store.consume(confirmation.token(), "telegram", "42", RequestStatus.APPROVED).status());
        assertFalse(store.find(confirmation.token()).isPresent());
    }

    @Test
    void expiredConfirmationCannotBeConsumed() {
        Instant[] now = {NOW};
        ConfirmationStore store = new ConfirmationStore(() -> now[0]);
        ConfirmationStore.Confirmation confirmation = store.issue("discord", "42", REQUEST_ID,
                RequestAction.BLOCK, RequestStatus.PENDING);
        now[0] = NOW.plusSeconds(61);

        assertEquals(ConfirmationStore.ConsumeStatus.EXPIRED,
                store.consume(confirmation.token(), "discord", "42", RequestStatus.PENDING).status());
    }

    @Test
    void pendingConfirmationsHaveAnExplicitBound() {
        ConfirmationStore store = new ConfirmationStore(() -> NOW);
        for (int i = 0; i < ConfirmationStore.MAX_PENDING + 100; i++) {
            store.issue("discord", "actor-" + i, REQUEST_ID, RequestAction.DENY, RequestStatus.PENDING);
        }

        assertTrue(store.pendingCount() <= ConfirmationStore.MAX_PENDING);
    }
}
