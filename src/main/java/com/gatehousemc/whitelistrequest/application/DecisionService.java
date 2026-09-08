package com.gatehousemc.whitelistrequest.application;

import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.port.ClockPort;
import com.gatehousemc.whitelistrequest.port.VanillaWhitelistPort;
import com.gatehousemc.whitelistrequest.port.WorkflowRepository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DecisionService {
    private final WorkflowRepository repository;
    private final VanillaWhitelistPort vanillaWhitelist;
    private final ClockPort clock;
    private final RequestAdmissionCache cache;
    private final Duration denialCooldown;

    public DecisionService(WorkflowRepository repository, VanillaWhitelistPort vanillaWhitelist,
                           ClockPort clock, RequestAdmissionCache cache) {
        this(repository, vanillaWhitelist, clock, cache, Duration.ZERO);
    }

    public DecisionService(WorkflowRepository repository, VanillaWhitelistPort vanillaWhitelist,
                           ClockPort clock, RequestAdmissionCache cache, Duration denialCooldown) {
        this.repository = repository;
        this.vanillaWhitelist = vanillaWhitelist;
        this.clock = clock;
        this.cache = cache;
        this.denialCooldown = denialCooldown;
    }

    public CompletionStage<DecisionResult> decide(UUID requestId, DecisionAction action, AdminPrincipal actor,
                                                   Optional<String> reason) {
        String cleanReason = reason.orElse("").trim();
        if (action != DecisionAction.APPROVE) {
            WorkflowRepository.DecisionResultSnapshot result = repository.resolveTerminal(
                    requestId, action, actor, cleanReason, clock.now());
            result.request().ifPresent(request -> updateCache(request, result.outcome()));
            return CompletableFuture.completedFuture(new DecisionResult(result.outcome(), result.request(), result.message()));
        }

        UUID token = UUID.randomUUID();
        DecisionClaim claim = repository.claimApproval(requestId, actor, cleanReason, clock.now(), token);
        if (claim.outcome() != DecisionClaim.ClaimOutcome.CLAIMED) {
            return CompletableFuture.completedFuture(claimResult(claim));
        }

        WhitelistRequest request = claim.request().orElseThrow();
        return vanillaWhitelist.addExactProfile(request.identity()).handle((ignored, error) -> {
            if (error != null) {
                repository.resetApproval(requestId, token, safeMessage(error), clock.now());
                cache.put(request.identity().normalizedUsername(), AdmissionState.pending());
                return DecisionResult.of(DecisionOutcome.FAILED, repository.findById(requestId).orElse(request),
                        "Whitelist mutation failed: " + safeMessage(error));
            }
            boolean finalized = repository.finalizeApproval(requestId, token, actor, cleanReason, clock.now());
            WhitelistRequest finalRequest = repository.findById(requestId).orElse(request);
            if (!finalized) {
                return DecisionResult.of(DecisionOutcome.FAILED, finalRequest,
                        "Approval completed outside the expected state transition");
            }
            cache.put(request.identity().normalizedUsername(), AdmissionState.pending());
            return DecisionResult.of(DecisionOutcome.APPROVED, finalRequest, "Request approved");
        });
    }

    public void recoverInterruptedApprovals() {
        for (WhitelistRequest request : repository.findResolvingApprovals()) {
            vanillaWhitelist.isWhitelisted(request.identity()).thenAccept(allowed -> {
                UUID token = request.resolvingToken();
                if (token == null) return;
                if (allowed) {
                    AdminPrincipal actor = request.resolvedBy() == null ? AdminPrincipal.console() : request.resolvedBy();
                    repository.finalizeApproval(request.id(), token, actor, request.resolutionReason(), clock.now());
                } else {
                    repository.resetApproval(request.id(), token, "Recovered unresolved approval", clock.now());
                }
            });
        }
    }

    private void updateCache(WhitelistRequest request, DecisionOutcome outcome) {
        if (outcome == DecisionOutcome.BLOCKED) cache.put(request.identity().normalizedUsername(), AdmissionState.blocked());
        else if (outcome == DecisionOutcome.DENIED) cache.put(request.identity().normalizedUsername(), AdmissionState.deniedUntil(clock.now().plus(denialCooldown)));
    }

    private static DecisionResult claimResult(DecisionClaim claim) {
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
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
