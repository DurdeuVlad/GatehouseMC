package com.gatehousemc;

import com.gatehousemc.application.ApprovalInterfaceRouter;
import com.gatehousemc.application.FanoutPublishException;
import com.gatehousemc.domain.*;
import com.gatehousemc.port.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.concurrent.CompletionException;

class RouterTest {
    @Test
    void primaryFailureFallsBackToTelegram() {
        RequestView request = new RequestView(UUID.randomUUID(), PlayerIdentity.of("Alice"), RequestStatus.PENDING,
                1, Instant.EPOCH, Instant.EPOCH, null, null, null);
        FakeProvider discord = new FakeProvider("discord", true);
        FakeProvider telegram = new FakeProvider("telegram", false);
        ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(List.of(discord, telegram), RoutingMode.PRIMARY_FALLBACK);

        List<PublicationRef> publications = router.publish(request).toCompletableFuture().join();

        assertEquals(List.of("telegram"), publications.stream().map(PublicationRef::provider).toList());
    }

    @Test
    void startingProviderIsSkippedUntilHealthy() {
        RequestView request = new RequestView(UUID.randomUUID(), PlayerIdentity.of("Alice"), RequestStatus.PENDING,
                1, Instant.EPOCH, Instant.EPOCH, null, null, null);
        FakeProvider starting = new FakeProvider("discord", false, ProviderHealth.STARTING);
        FakeProvider telegram = new FakeProvider("telegram", false);
        ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(List.of(starting, telegram), RoutingMode.PRIMARY_FALLBACK);

        List<PublicationRef> publications = router.publish(request).toCompletableFuture().join();

        assertEquals(List.of("telegram"), publications.stream().map(PublicationRef::provider).toList());
    }

    @Test
    void fanoutExposesSuccessfulPublicationsWhenOneProviderFails() {
        RequestView request = new RequestView(UUID.randomUUID(), PlayerIdentity.of("Alice"), RequestStatus.PENDING,
                1, Instant.EPOCH, Instant.EPOCH, null, null, null);
        FakeProvider discord = new FakeProvider("discord", false);
        FakeProvider telegram = new FakeProvider("telegram", true);
        ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(List.of(discord, telegram), RoutingMode.FANOUT);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> router.publish(request).toCompletableFuture().join());
        FanoutPublishException error = (FanoutPublishException) failure.getCause();

        assertEquals(List.of("discord"), error.successfulPublications().stream().map(PublicationRef::provider).toList());
    }

    private static final class FakeProvider implements ApprovalInterface {
        private final String id;
        private final boolean fails;
        private final ProviderHealth health;
        private FakeProvider(String id, boolean fails) { this(id, fails, ProviderHealth.HEALTHY); }
        private FakeProvider(String id, boolean fails, ProviderHealth health) {
            this.id = id;
            this.fails = fails;
            this.health = health;
        }
        @Override public String id() { return id; }
        @Override public ProviderHealth health() { return health; }
        @Override public java.util.concurrent.CompletionStage<PublicationRef> publish(RequestView request) {
            return fails ? CompletableFuture.failedFuture(new IllegalStateException("offline")) : CompletableFuture.completedFuture(new PublicationRef(id, "container", "message"));
        }
        @Override public java.util.concurrent.CompletionStage<Void> update(PublicationRef publication, RequestView request) { return CompletableFuture.completedFuture(null); }
        @Override public void start() {}
        @Override public void stop() {}
    }
}
