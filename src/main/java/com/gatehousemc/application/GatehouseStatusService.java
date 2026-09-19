package com.gatehousemc.application;

import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.port.ApprovalInterface;
import com.gatehousemc.port.ProviderHealth;
import com.gatehousemc.port.WorkflowRepository;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured, secret-free runtime diagnostics shared by every UI. */
public final class GatehouseStatusService {
    public enum OverallHealth { HEALTHY, DEGRADED }
    public enum RuntimeState { STARTING, RUNNING, DEGRADED }

    public record ProviderSnapshot(String id, ProviderHealth state, String destination,
                                   String problem, String fix, String lastSuccessfulOperation,
                                   String lastActionableError) {
        public ProviderSnapshot(String id, ProviderHealth state, String destination,
                                String problem, String fix) {
            this(id, state, destination, problem, fix, "", "");
        }
    }

    public record Snapshot(String version, OverallHealth health, RuntimeState runtimeState,
                           Map<RequestStatus, Integer> requestCounts, int activeRequests,
                           int queueCurrent, int queueCapacity, int limiterAvailable,
                           long coalescedAttemptsSinceStartup, boolean sqliteHealthy,
                           long pendingOutbox, List<ProviderSnapshot> providers,
                           boolean reloadAvailable, boolean restartRequired) {}

    private final ModConfig config;
    private final WorkflowRepository repository;
    private final AdmissionWorker worker;
    private final List<ApprovalInterface> providers;
    private final boolean degraded;

    public GatehouseStatusService(ModConfig config, WorkflowRepository repository,
                                  AdmissionWorker worker, List<ApprovalInterface> providers,
                                  boolean degraded) {
        this.config = Objects.requireNonNull(config, "config");
        this.repository = repository;
        this.worker = worker;
        this.providers = List.copyOf(providers);
        this.degraded = degraded;
    }

    public Snapshot snapshot() {
        EnumMap<RequestStatus, Integer> counts = new EnumMap<>(RequestStatus.class);
        for (RequestStatus status : RequestStatus.values()) counts.put(status, 0);
        boolean sqliteHealthy = !degraded && repository != null;
        long pendingOutbox = 0;
        if (sqliteHealthy) {
            try {
                for (RequestStatus status : RequestStatus.values()) {
                    counts.put(status, repository.findByStatus(java.util.Optional.of(status), 5000).size());
                }
                pendingOutbox = repository.pendingOutboxCount();
            } catch (RuntimeException error) {
                sqliteHealthy = false;
            }
        }
        int active = counts.get(RequestStatus.PENDING) + counts.get(RequestStatus.RESOLVING);
        List<ProviderSnapshot> providerSnapshots = providers.stream().map(provider ->
                new ProviderSnapshot(provider.id(), provider.health(), safeDestination(provider),
                        providerProblem(provider), providerFix(provider),
                        provider.lastSuccessfulOperation(), provider.lastActionableError()))
                .toList();
        boolean healthy = sqliteHealthy && !degraded;
        return new Snapshot("1.2.0", healthy ? OverallHealth.HEALTHY : OverallHealth.DEGRADED,
                healthy ? RuntimeState.RUNNING : RuntimeState.DEGRADED, Map.copyOf(counts), active,
                worker == null ? 0 : worker.pendingWorkUnits(), config.requests().queueCapacity(),
                worker == null ? 0 : worker.availableNewRequestTokens(),
                worker == null ? 0 : worker.coalescedAttempts(), sqliteHealthy, pendingOutbox,
                providerSnapshots, true, false);
    }

    private String safeDestination(ApprovalInterface provider) {
        if (!provider.configuredDestination().isBlank()) return provider.configuredDestination();
        if (provider.id().equals("discord")) {
            if (!config.discord().dmUserId().isBlank()) return "Discord DM " + config.discord().dmUserId();
            return config.discord().channelId().isBlank() ? "unconfigured" : "Discord channel " + config.discord().channelId();
        }
        return config.telegram().chatId().isBlank() ? "unconfigured" : "Telegram chat " + config.telegram().chatId();
    }

    private static String providerProblem(ApprovalInterface provider) {
        return switch (provider.health()) {
            case DISABLED -> "provider is intentionally disabled";
            case SETUP_REQUIRED -> "provider setup is incomplete";
            case DEGRADED -> provider.lastActionableError().isBlank() ? "provider is degraded" : provider.lastActionableError();
            case UNAVAILABLE -> provider.lastActionableError().isBlank() ? "provider is unavailable" : provider.lastActionableError();
            default -> "";
        };
    }

    private static String providerFix(ApprovalInterface provider) {
        return switch (provider.health()) {
            case SETUP_REQUIRED -> "/gatehouse setup " + provider.id();
            case DEGRADED, UNAVAILABLE -> "/gatehouse provider test " + provider.id();
            default -> "";
        };
    }
}
