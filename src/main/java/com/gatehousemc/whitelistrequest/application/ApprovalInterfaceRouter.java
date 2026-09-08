package com.gatehousemc.whitelistrequest.application;

import com.gatehousemc.whitelistrequest.domain.PublicationRef;
import com.gatehousemc.whitelistrequest.domain.RequestView;
import com.gatehousemc.whitelistrequest.port.ApprovalInterface;
import com.gatehousemc.whitelistrequest.port.ProviderHealth;
import com.gatehousemc.whitelistrequest.port.RoutingMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ApprovalInterfaceRouter {
    private final List<ApprovalInterface> providers;
    private final RoutingMode mode;

    public ApprovalInterfaceRouter(List<ApprovalInterface> providers, RoutingMode mode) {
        this.providers = List.copyOf(providers);
        this.mode = mode;
    }

    public CompletionStage<List<PublicationRef>> publish(RequestView request) {
        return publish(request, List.of());
    }

    /**
     * Publishes only providers that do not already have a durable publication
     * for the request. This is important when a FANOUT attempt partially
     * succeeds and the outbox retries the remaining providers.
     */
    public CompletionStage<List<PublicationRef>> publish(RequestView request, List<PublicationRef> existing) {
        if (!existing.isEmpty() && mode != RoutingMode.FANOUT) {
            return CompletableFuture.completedFuture(List.copyOf(existing));
        }
        if (mode == RoutingMode.FANOUT) {
            List<CompletableFuture<PublicationRef>> futures = providers.stream()
                    .filter(provider -> enabled(provider) && existing.stream().noneMatch(ref -> ref.provider().equals(provider.id())))
                    .map(provider -> provider.publish(request).toCompletableFuture())
                    .toList();
            if (futures.isEmpty()) return CompletableFuture.completedFuture(List.copyOf(existing));
            CompletableFuture<List<PublicationRef>> result = new CompletableFuture<>();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                List<PublicationRef> successful = futures.stream()
                        .filter(CompletableFuture::isDone)
                        .filter(future -> !future.isCompletedExceptionally() && !future.isCancelled())
                        .map(CompletableFuture::join)
                        .toList();
                if (error == null) {
                    result.complete(java.util.stream.Stream.concat(existing.stream(), successful.stream()).toList());
                } else {
                    result.completeExceptionally(new FanoutPublishException(successful, error));
                }
            });
            return result;
        }
        return tryNext(request, 0, new ArrayList<>());
    }

    public CompletionStage<Void> updateAll(List<PublicationRef> publications, RequestView request) {
        List<CompletableFuture<Void>> futures = publications.stream()
                .flatMap(ref -> providers.stream().filter(provider -> provider.id().equals(ref.provider())).findFirst().stream())
                .map(provider -> provider.update(publications.stream().filter(ref -> ref.provider().equals(provider.id())).findFirst().orElseThrow(), request).toCompletableFuture())
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private CompletionStage<List<PublicationRef>> tryNext(RequestView request, int index, List<PublicationRef> collected) {
        if (index >= providers.size()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No approval provider could publish the request"));
        }
        ApprovalInterface provider = providers.get(index);
        if (!enabled(provider)) return tryNext(request, index + 1, collected);
        return provider.publish(request).handle((publication, error) -> {
            if (error == null) {
                collected.add(publication);
                return CompletableFuture.completedFuture(List.copyOf(collected));
            }
            return tryNext(request, index + 1, collected).toCompletableFuture();
        }).thenCompose(future -> future);
    }

    private boolean enabled(ApprovalInterface provider) {
        return provider.health() != ProviderHealth.UNAVAILABLE && provider.health() != ProviderHealth.STOPPED;
    }
}
