package com.gatehousemc.whitelistrequest.application;

import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.port.ClockPort;
import com.gatehousemc.whitelistrequest.port.VanillaWhitelistPort;
import com.gatehousemc.whitelistrequest.port.WorkflowRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

public final class DecisionService {
    private final WorkflowRepository repository;
    private final VanillaWhitelistPort vanillaWhitelist;
    private final ClockPort clock;
    private final RequestAdmissionCache cache;
    private final Duration denialCooldown;
    private final Executor decisionExecutor;

    public DecisionService(WorkflowRepository repository, VanillaWhitelistPort vanillaWhitelist,
                           ClockPort clock, RequestAdmissionCache cache) {
        this(repository, vanillaWhitelist, clock, cache, Duration.ZERO, ForkJoinPool.commonPool());
    }

    public DecisionService(WorkflowRepository repository, VanillaWhitelistPort vanillaWhitelist,
                           ClockPort clock, RequestAdmissionCache cache, Duration denialCooldown) {
        this(repository, vanillaWhitelist, clock, cache, denialCooldown, ForkJoinPool.commonPool());
    }

    public DecisionService(WorkflowRepository repository, VanillaWhitelistPort vanillaWhitelist,
                           ClockPort clock, RequestAdmissionCache cache, Duration denialCooldown,
                           Executor decisionExecutor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.vanillaWhitelist = Objects.requireNonNull(vanillaWhitelist, "vanillaWhitelist");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.denialCooldown = Objects.requireNonNull(denialCooldown, "denialCooldown");
        this.decisionExecutor = Objects.requireNonNull(decisionExecutor, "decisionExecutor");
    }

    public CompletionStage<DecisionResult> decide(UUID requestId, DecisionAction action, AdminPrincipal actor,
                                                   Optional<String> reason) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(reason, "reason");
        String cleanReason = reason.orElse("").trim();
        if (action != DecisionAction.APPROVE) {
            return CompletableFuture.supplyAsync(() -> decideTerminal(requestId, action, actor, cleanReason), decisionExecutor);
        }

        return CompletableFuture.supplyAsync(() -> repository.claimApproval(
                        requestId, actor, cleanReason, clock.now(), UUID.randomUUID()), decisionExecutor)
                .thenCompose(claim -> {
                                if (claim.outcome() != DecisionClaim.ClaimOutcome.CLAIMED) {
                        return CompletableFuture.completedFuture(claimResult(claim));
                    }
                    WhitelistRequest request = claim.request().orElseThrow();
                    try {
                        return vanillaWhitelist.addExactProfile(request.identity())
                                .handleAsync((ignored, error) -> completeApproval(request, actor, cleanReason, claim.token(), error),
                                        decisionExecutor);
                    } catch (Throwable error) {
                        return CompletableFuture.completedFuture(
                                completeApproval(request, actor, cleanReason, claim.token(), error));
                    }
                });
    }

    public boolean unblock(String normalizedUsername, AdminPrincipal actor, String reason) {
        Objects.requireNonNull(normalizedUsername, "normalizedUsername");
        Objects.requireNonNull(actor, "actor");
        boolean removed = repository.unblock(normalizedUsername, actor, reason, clock.now());
        if (removed) cache.invalidate(normalizedUsername);
        return removed;
    }

    public CompletionStage<Void> recoverInterruptedApprovals() {
        List<CompletableFuture<Void>> recoveries = new ArrayList<>();
        for (WhitelistRequest request : repository.findResolvingApprovals()) {
            CompletableFuture<Void> recovery;
            try {
                recovery = vanillaWhitelist.isWhitelisted(request.identity())
                        .handleAsync((allowed, error) -> recover(request, allowed, error), decisionExecutor);
            } catch (Throwable error) {
                recovery = CompletableFuture.completedFuture(recover(request, false, error));
            }
            recoveries.add(recovery);
        }
        return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new));
    }

    private DecisionResult decideTerminal(UUID requestId, DecisionAction action, AdminPrincipal actor, String reason) {
        WorkflowRepository.DecisionResultSnapshot result = repository.resolveTerminal(
                requestId, action, actor, reason, clock.now());
        result.request().ifPresent(this::refreshCache);
        return new DecisionResult(result.outcome(), result.request(), result.message());
    }

    private DecisionResult completeApproval(WhitelistRequest request, AdminPrincipal actor, String reason,
                                            UUID token, Throwable error) {
        if (error != null) {
            String failure = safeMessage(error);
            boolean reset = repository.resetApproval(request.id(), token, actor, failure, clock.now());
            WhitelistRequest current = repository.findById(request.id()).orElse(request);
            if (reset || current.status() != RequestStatus.RESOLVING) refreshCache(current);
            return DecisionResult.of(DecisionOutcome.FAILED, current, "Whitelist mutation failed: " + failure);
        }

        boolean finalized = repository.finalizeApproval(request.id(), token, actor, reason, clock.now());
        WhitelistRequest current = repository.findById(request.id()).orElse(request);
        if (!finalized) {
            refreshCache(current);
            return DecisionResult.of(DecisionOutcome.FAILED, current,
                    "Approval completed outside the expected state transition");
        }
        refreshCache(current);
        return DecisionResult.of(DecisionOutcome.APPROVED, current, "Request approved");
    }

    private Void recover(WhitelistRequest request, Boolean allowed, Throwable error) {
        UUID token = request.resolvingToken();
        if (token == null) return null;
        AdminPrincipal actor = request.resolvedBy() == null ? AdminPrincipal.console() : request.resolvedBy();
        if (error != null) {
            repository.resetApproval(request.id(), token, actor,
                    "Whitelist recovery check failed: " + safeMessage(error), clock.now());
        } else if (Boolean.TRUE.equals(allowed)) {
            repository.finalizeApproval(request.id(), token, actor, request.resolutionReason(), clock.now());
        } else {
            repository.resetApproval(request.id(), token, actor, "Recovered unresolved approval", clock.now());
        }
        repository.findById(request.id()).ifPresent(this::refreshCache);
        return null;
    }

    private void refreshCache(WhitelistRequest request) {
        switch (request.status()) {
            case PENDING, RESOLVING -> cache.put(request.identity().normalizedUsername(), AdmissionState.pending());
            case APPROVED -> cache.put(request.identity().normalizedUsername(), AdmissionState.unknown());
            case DENIED -> cache.put(request.identity().normalizedUsername(),
                    AdmissionState.deniedUntil(request.resolvedAt().plus(denialCooldown)));
            case BLOCKED -> cache.put(request.identity().normalizedUsername(), AdmissionState.blocked());
        }
    }

    private DecisionResult claimResult(DecisionClaim claim) {
        claim.request().ifPresent(this::refreshCache);
        return switch (claim.outcome()) {
            case NOT_FOUND -> DecisionResult.of(DecisionOutcome.NOT_FOUND, null, "Request not found");
            case ALREADY_RESOLVING -> DecisionResult.of(DecisionOutcome.RESOLVING, claim.request().orElse(null), "Request is already resolving");
            case ALREADY_RESOLVED -> DecisionResult.of(DecisionOutcome.ALREADY_RESOLVED, claim.request().orElse(null), "Request is already resolved");
            case CLAIMED -> throw new IllegalStateException("claimed result must not use claimResult");
        };
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName();
    }
}
