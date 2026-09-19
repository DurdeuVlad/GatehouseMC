package com.gatehousemc.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SetupSessionStoreTest {
    @Test
    void codeIsEightCharactersSingleUseAndProviderScoped() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        SetupSessionStore store = new SetupSessionStore(now::get);
        SetupSessionStore.Issued issued = store.issue("discord");
        assertEquals(8, issued.code().length());
        assertTrue(issued.code().matches("[0-9A-HJKMNP-TV-Z]{8}"));
        assertEquals(SetupSessionStore.ConsumeStatus.WRONG_PROVIDER,
                store.consume("telegram", issued.code()).status());
        assertEquals(SetupSessionStore.ConsumeStatus.CONSUMED,
                store.consume("discord", issued.code()).status());
        assertEquals(SetupSessionStore.ConsumeStatus.REPLAY,
                store.consume("discord", issued.code()).status());
    }

    @Test
    void expiryAndCancellationDoNotRevealStoredPlaintext() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        SetupSessionStore.InMemoryPersistence persistence = new SetupSessionStore.InMemoryPersistence();
        SetupSessionStore store = new SetupSessionStore(now::get, new java.security.SecureRandom(), persistence);
        SetupSessionStore.Issued issued = store.issue("telegram");
        assertFalse(persistence.find(issued.code()).isPresent());
        now.set(Instant.EPOCH.plus(SetupSessionStore.TTL));
        assertEquals(SetupSessionStore.ConsumeStatus.EXPIRED, store.consume("telegram", issued.code()).status());
        store.cancel("telegram");
    }

    @Test
    void concurrentConsumersHaveExactlyOneWinner() {
        SetupSessionStore store = new SetupSessionStore(Instant::now);
        SetupSessionStore.Issued issued = store.issue("discord");
        List<SetupSessionStore.ConsumeStatus> results = List.of(
                CompletableFuture.supplyAsync(() -> store.consume("discord", issued.code()).status()),
                CompletableFuture.supplyAsync(() -> store.consume("discord", issued.code()).status()))
                .stream().map(CompletableFuture::join).toList();
        assertEquals(1, results.stream().filter(status -> status == SetupSessionStore.ConsumeStatus.CONSUMED).count());
        assertEquals(1, results.stream().filter(status -> status == SetupSessionStore.ConsumeStatus.REPLAY).count());
    }
}
