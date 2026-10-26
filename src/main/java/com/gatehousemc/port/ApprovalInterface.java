package com.gatehousemc.port;

import com.gatehousemc.domain.PublicationRef;
import com.gatehousemc.domain.RequestView;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

public interface ApprovalInterface {
    String id();

    ProviderHealth health();

    /** Secret-free diagnostics for the shared status surface. */
    default String configuredDestination() { return ""; }
    default String lastSuccessfulOperation() { return ""; }
    default String lastActionableError() { return ""; }

    /** Sends exactly one non-secret provider test message when supported. */
    default CompletionStage<Void> testDelivery() {
        return CompletableFuture.failedFuture(new IllegalStateException("provider test is unavailable"));
    }

    CompletionStage<PublicationRef> publish(RequestView request);

    CompletionStage<Void> update(PublicationRef publication, RequestView request);

    void start();

    void stop();
}
