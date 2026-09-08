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

public final class FabricRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final ModConfig config;
    private final RequestAdmissionCache cache;
    private final WorkflowRepository repository;
    private final WhitelistRequestService requests;
    private final DecisionService decisions;
    private final AdmissionWorker worker;
    private final List<ApprovalInterface> providers;
    private final OutboxWorker outbox;
    private final boolean degraded;

    private FabricRuntime(MinecraftServer server, ModConfig config, RequestAdmissionCache cache,
                           WorkflowRepository repository, WhitelistRequestService requests,
                           DecisionService decisions, AdmissionWorker worker, boolean degraded) {
        this.server = server;
        this.config = config;
        this.cache = cache;
        this.repository = repository;
        this.requests = requests;
        this.decisions = decisions;
        this.worker = worker;
        this.degraded = degraded;
        this.providers = List.of();
        this.outbox = null;
    }

    private FabricRuntime(MinecraftServer server, ModConfig config, RequestAdmissionCache cache,
                           WorkflowRepository repository, WhitelistRequestService requests,
                           DecisionService decisions, AdmissionWorker worker, List<ApprovalInterface> providers,
                           OutboxWorker outbox) {
        this.server = server;
        this.config = config;
        this.cache = cache;
        this.repository = repository;
        this.requests = requests;
        this.decisions = decisions;
        this.worker = worker;
        this.degraded = false;
        this.providers = List.copyOf(providers);
        this.outbox = outbox;
    }

    static FabricRuntime start(MinecraftServer server, ModConfig config) throws IOException, SQLException {
        SqliteDatabase database = new SqliteDatabase(config.database().path(), config.database().busyTimeoutMs());
        WorkflowRepository repository = new SqliteWorkflowRepository(database);
        ClockPort clock = Instant::now;
        RequestAdmissionCache cache = new RequestAdmissionCache(clock);
        Duration denialCooldown = Duration.ofMinutes(config.requests().denialCooldownMinutes());
        WhitelistRequestService requests = new WhitelistRequestService(repository, clock, denialCooldown, cache);
        DecisionService decisions = new DecisionService(repository, new FabricVanillaWhitelistAdapter(server), clock, cache, denialCooldown);
        repository.findByStatus(Optional.of(RequestStatus.PENDING), 500)
                .forEach(request -> cache.put(request.identity().normalizedUsername(), AdmissionState.pending()));
        repository.findByStatus(Optional.of(RequestStatus.BLOCKED), 500)
                .forEach(request -> cache.put(request.identity().normalizedUsername(), AdmissionState.blocked()));
        AdmissionWorker worker = new AdmissionWorker(config.requests().queueCapacity(), requests);
        List<ApprovalInterface> providers = new ArrayList<>();
        for (String provider : config.routing().providers()) {
            if (provider.equalsIgnoreCase("discord")) providers.add(new DiscordApprovalInterface(config.discord(), decisions));
            if (provider.equalsIgnoreCase("telegram")) providers.add(new TelegramApprovalInterface(config.telegram(), decisions));
        }
        ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(providers, config.routing().mode());
        OutboxWorker outbox = new OutboxWorker(repository, router, clock);
        providers.forEach(ApprovalInterface::start);
        worker.start();
        outbox.start();
        decisions.recoverInterruptedApprovals();
        return new FabricRuntime(server, config, cache, repository, requests, decisions, worker, providers, outbox);
    }

    static FabricRuntime degraded(MinecraftServer server, ModConfig config) {
        RequestAdmissionCache cache = new RequestAdmissionCache();
        cache.markDegraded(true);
        return new FabricRuntime(server, config, cache, null, null, null, null, true);
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
            case UNKNOWN, DEGRADED -> Text.literal("You are not whitelisted on this server. A whitelist request has been queued automatically. Player: " + identity.exactUsername() + ". Ask a server administrator to approve the request, then reconnect.");
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
        if (outbox != null) outbox.close();
        providers.forEach(ApprovalInterface::stop);
        if (worker != null) worker.close();
        if (repository != null) {
            try { repository.close(); } catch (Exception ignored) {}
        }
    }

    public record GameProfileIdentity(UUID uuid, String exactUsername) {
        public PlayerIdentity toDomain() { return PlayerIdentity.of(uuid, exactUsername); }
    }
}
