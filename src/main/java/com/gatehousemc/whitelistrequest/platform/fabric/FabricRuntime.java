package com.gatehousemc.whitelistrequest.platform.fabric;

import com.gatehousemc.whitelistrequest.application.*;
import com.gatehousemc.whitelistrequest.config.ModConfig;
import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.persistence.sqlite.SqliteDatabase;
import com.gatehousemc.whitelistrequest.persistence.sqlite.SqliteWorkflowRepository;
import com.gatehousemc.whitelistrequest.port.ClockPort;
import com.gatehousemc.whitelistrequest.port.ApprovalInterface;
import com.gatehousemc.whitelistrequest.port.WorkflowRepository;
import com.gatehousemc.whitelistrequest.application.ApprovalInterfaceRouter;
import com.gatehousemc.whitelistrequest.application.OutboxWorker;
import com.gatehousemc.whitelistrequest.integration.discord.DiscordApprovalInterface;
import com.gatehousemc.whitelistrequest.integration.telegram.TelegramApprovalInterface;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class FabricRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final ModConfig config;
    private final RequestAdmissionCache cache;
    private final WorkflowRepository repository;
    private final WhitelistRequestService requests;
    private final DecisionService decisions;
    private final AdmissionWorker worker;
    private final ExecutorService decisionExecutor;
    private final List<ApprovalInterface> providers;
    private final OutboxWorker outbox;
    private final boolean degraded;

    private FabricRuntime(MinecraftServer server, ModConfig config, RequestAdmissionCache cache,
                           WorkflowRepository repository, WhitelistRequestService requests,
                           DecisionService decisions, AdmissionWorker worker, ExecutorService decisionExecutor,
                           boolean degraded) {
        this.server = server;
        this.config = config;
        this.cache = cache;
        this.repository = repository;
        this.requests = requests;
        this.decisions = decisions;
        this.worker = worker;
        this.decisionExecutor = decisionExecutor;
        this.degraded = degraded;
        this.providers = List.of();
        this.outbox = null;
    }

    private FabricRuntime(MinecraftServer server, ModConfig config, RequestAdmissionCache cache,
                           WorkflowRepository repository, WhitelistRequestService requests,
                           DecisionService decisions, AdmissionWorker worker, ExecutorService decisionExecutor,
                           List<ApprovalInterface> providers, OutboxWorker outbox) {
        this.server = server;
        this.config = config;
        this.cache = cache;
        this.repository = repository;
        this.requests = requests;
        this.decisions = decisions;
        this.worker = worker;
        this.decisionExecutor = decisionExecutor;
        this.degraded = false;
        this.providers = List.copyOf(providers);
        this.outbox = outbox;
    }

    static FabricRuntime start(MinecraftServer server, ModConfig config) throws IOException, SQLException {
        SqliteDatabase database = null;
        WorkflowRepository repository = null;
        AdmissionWorker worker = null;
        ExecutorService decisionExecutor = null;
        List<ApprovalInterface> providers = new ArrayList<>();
        OutboxWorker outbox = null;
        try {
        database = new SqliteDatabase(config.database().path(), config.database().busyTimeoutMs());
        repository = new SqliteWorkflowRepository(database);
        ClockPort clock = Instant::now;
        RequestAdmissionCache cache = new RequestAdmissionCache(clock);
        Duration denialCooldown = Duration.ofMinutes(config.requests().denialCooldownMinutes());
        WhitelistRequestService requests = new WhitelistRequestService(repository, clock, denialCooldown, cache);
        worker = new AdmissionWorker(config.requests().queueCapacity(), requests);
        decisionExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "whitelistrequest-decisions");
            thread.setDaemon(true);
            return thread;
        });
        DecisionService decisions = new DecisionService(repository, new FabricVanillaWhitelistAdapter(server), clock, cache,
                denialCooldown, decisionExecutor);
        repository.findByStatus(Optional.of(RequestStatus.PENDING), 500)
                .forEach(request -> cache.put(request.identity().normalizedUsername(), AdmissionState.pending()));
        repository.findByStatus(Optional.of(RequestStatus.BLOCKED), 500)
                .forEach(request -> cache.put(request.identity().normalizedUsername(), AdmissionState.blocked()));
        repository.findByStatus(Optional.of(RequestStatus.DENIED), 500)
                .forEach(request -> {
                    Instant deniedUntil = request.resolvedAt().plus(denialCooldown);
                    if (deniedUntil.isAfter(clock.now())) {
                        cache.put(request.identity().normalizedUsername(), AdmissionState.deniedUntil(deniedUntil));
                    }
                });
        decisions.recoverInterruptedApprovals().toCompletableFuture().join();
        for (String provider : config.routing().providers()) {
            if (provider.equalsIgnoreCase("discord")) providers.add(new DiscordApprovalInterface(config.discord(), decisions));
            if (provider.equalsIgnoreCase("telegram")) providers.add(new TelegramApprovalInterface(config.telegram(), decisions));
        }
        ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(providers, config.routing().mode());
        outbox = new OutboxWorker(repository, router, clock);
        providers.forEach(ApprovalInterface::start);
        worker.start();
        outbox.start();
        return new FabricRuntime(server, config, cache, repository, requests, decisions, worker, decisionExecutor, providers, outbox);
        } catch (IOException | SQLException | RuntimeException error) {
            if (outbox != null) outbox.close();
            providers.forEach(ApprovalInterface::stop);
            if (worker != null) worker.close();
            if (decisionExecutor != null) {
                decisionExecutor.shutdownNow();
            }
            if (repository != null) {
                try { repository.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
            } else if (database != null) {
                try { database.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
            }
            throw error;
        }
    }

    static FabricRuntime degraded(MinecraftServer server, ModConfig config) {
        RequestAdmissionCache cache = new RequestAdmissionCache();
        cache.markDegraded(true);
        return new FabricRuntime(server, config, cache, null, null, null, null, null, true);
    }

    public Text onWhitelistDenied(GameProfileIdentity profile) {
        if (degraded || requests == null || worker == null) return messagesUnavailable(profile.exactUsername());
        PlayerIdentity identity = profile.toDomain();
        AdmissionState known = cache.get(identity.normalizedUsername());
        if (!worker.offer(identity)) return messagesUnavailable(identity.exactUsername());
        return switch (known.kind()) {
            case BLOCKED -> Text.literal("Whitelist requests for this username are blocked. Please contact a server administrator.");
            case DENIED -> Text.literal("Your whitelist request was denied recently. Please contact a server administrator if you need another review.");
            case PENDING -> Text.literal("Your whitelist request is still pending. Player: " + identity.exactUsername() + ". Ask a server administrator to approve it, then reconnect.");
            case UNKNOWN -> Text.literal("You are not whitelisted on this server. A whitelist request has been queued automatically. Player: " + identity.exactUsername() + ". Ask a server administrator to approve the request, then reconnect.");
            case DEGRADED -> messagesUnavailable(identity.exactUsername());
        };
    }

    public DecisionService decisions() { return decisions; }
    public WorkflowRepository repository() { return repository; }
    public ModConfig config() { return config; }
    MinecraftServer server() { return server; }
    public int queueSize() { return worker == null ? 0 : worker.size(); }
    public boolean degraded() { return degraded || cache.isDegraded(); }

    public Optional<WhitelistRequest> find(UUID id) { return repository == null ? Optional.empty() : repository.findById(id); }
    public Optional<WhitelistRequest> active(String username) { return repository == null ? Optional.empty() : repository.findActiveByName(username.toLowerCase(java.util.Locale.ROOT)); }

    private static Text messagesUnavailable(String username) {
        return Text.literal("You are not whitelisted on this server. The whitelist request service is temporarily unavailable. Please contact a server administrator.");
    }

    @Override
    public void close() {
        providers.forEach(ApprovalInterface::stop);
        if (outbox != null) outbox.close();
        if (worker != null) worker.close();
        if (decisionExecutor != null) {
            decisionExecutor.shutdown();
            try {
                if (!decisionExecutor.awaitTermination(5, TimeUnit.SECONDS)) decisionExecutor.shutdownNow();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                decisionExecutor.shutdownNow();
            }
        }
        if (repository != null) {
            try { repository.close(); } catch (Exception ignored) {}
        }
    }

    public record GameProfileIdentity(UUID uuid, String exactUsername) {
        public PlayerIdentity toDomain() { return PlayerIdentity.of(uuid, exactUsername); }
    }
}
