package com.gatehousemc.runtime;

import com.gatehousemc.application.AdmissionState;
import com.gatehousemc.application.AdmissionWorker;
import com.gatehousemc.application.ApprovalInterfaceRouter;
import com.gatehousemc.application.DecisionService;
import com.gatehousemc.application.OutboxWorker;
import com.gatehousemc.application.RequestAdmissionCache;
import com.gatehousemc.application.WhitelistRequestService;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.integration.discord.DiscordApprovalInterface;
import com.gatehousemc.integration.telegram.TelegramApprovalInterface;
import com.gatehousemc.persistence.sqlite.SqliteDatabase;
import com.gatehousemc.persistence.sqlite.SqliteWorkflowRepository;
import com.gatehousemc.port.ApprovalInterface;
import com.gatehousemc.port.ClockPort;
import com.gatehousemc.port.VanillaWhitelistPort;
import com.gatehousemc.port.WorkflowRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Shared application wiring used by every Minecraft loader adapter. */
public final class GatehouseRuntime implements AutoCloseable {
    public record AdmissionResponse(AdmissionState.Kind state, String message) {}

    private final ModConfig config;
    private final RequestAdmissionCache cache;
    private final WorkflowRepository repository;
    private final WhitelistRequestService requests;
    private final DecisionService decisions;
    private final AdmissionWorker worker;
    private final ExecutorService decisionExecutor;
    private final ExecutorService commandExecutor;
    private final List<ApprovalInterface> providers;
    private final OutboxWorker outbox;
    private final boolean degraded;

    private GatehouseRuntime(ModConfig config, RequestAdmissionCache cache,
                             WorkflowRepository repository, WhitelistRequestService requests,
                             DecisionService decisions, AdmissionWorker worker,
                             ExecutorService decisionExecutor, ExecutorService commandExecutor,
                             List<ApprovalInterface> providers, OutboxWorker outbox,
                             boolean degraded) {
        this.config = config;
        this.cache = cache;
        this.repository = repository;
        this.requests = requests;
        this.decisions = decisions;
        this.worker = worker;
        this.decisionExecutor = decisionExecutor;
        this.commandExecutor = commandExecutor;
        this.providers = List.copyOf(providers);
        this.outbox = outbox;
        this.degraded = degraded;
    }

    public static GatehouseRuntime start(ModConfig config, VanillaWhitelistPort whitelist)
            throws IOException, SQLException {
        SqliteDatabase database = null;
        WorkflowRepository repository = null;
        AdmissionWorker worker = null;
        ExecutorService decisionExecutor = null;
        ExecutorService commandExecutor = null;
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
            decisionExecutor = daemonExecutor("gatehousemc-decisions");
            commandExecutor = daemonExecutor("gatehousemc-commands");
            DecisionService decisions = new DecisionService(repository, whitelist, clock, cache,
                    denialCooldown, decisionExecutor);
            requests.hydrateCache();
            decisions.recoverInterruptedApprovals().toCompletableFuture().join();
            createProviders(config, decisions, providers);
            ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(providers, config.routing().mode());
            outbox = new OutboxWorker(repository, router, clock);
            providers.forEach(ApprovalInterface::start);
            worker.start();
            outbox.start();
            Messages.load(config.language());
            return new GatehouseRuntime(config, cache, repository, requests, decisions, worker,
                    decisionExecutor, commandExecutor, providers, outbox, false);
        } catch (IOException | SQLException | RuntimeException error) {
            cleanup(outbox, providers, worker, decisionExecutor, commandExecutor, repository, database, error);
            throw error;
        }
    }

    public static GatehouseRuntime degraded(ModConfig config) {
        RequestAdmissionCache cache = new RequestAdmissionCache();
        cache.markDegraded(true);
        return new GatehouseRuntime(config, cache, null, null, null, null, null, null,
                List.of(), null, true);
    }

    private static void createProviders(ModConfig config, DecisionService decisions,
                                        List<ApprovalInterface> providers) {
        for (String provider : config.routing().providers()) {
            if (provider.equalsIgnoreCase("discord")) {
                providers.add(new DiscordApprovalInterface(config.discord(), decisions));
            }
            if (provider.equalsIgnoreCase("telegram")) {
                providers.add(new TelegramApprovalInterface(config.telegram(), decisions));
            }
        }
    }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void cleanup(OutboxWorker outbox, List<ApprovalInterface> providers,
                                AdmissionWorker worker, ExecutorService decisionExecutor,
                                ExecutorService commandExecutor, WorkflowRepository repository,
                                SqliteDatabase database, Throwable error) {
        if (outbox != null) outbox.close();
        providers.forEach(ApprovalInterface::stop);
        if (worker != null) worker.close();
        if (decisionExecutor != null) decisionExecutor.shutdownNow();
        if (commandExecutor != null) commandExecutor.shutdownNow();
        try {
            if (repository != null) repository.close();
            else if (database != null) database.close();
        } catch (Exception closeError) {
            error.addSuppressed(closeError);
        }
    }

    public AdmissionResponse onWhitelistDenied(PlayerIdentity identity) {
        if (degraded || requests == null || worker == null) {
            return new AdmissionResponse(AdmissionState.Kind.DEGRADED, Messages.get("reject.unavailable"));
        }
        AdmissionState known = cache.get(identity.normalizedUsername());
        if (!worker.offer(identity)) {
            return new AdmissionResponse(AdmissionState.Kind.DEGRADED, Messages.get("reject.unavailable"));
        }
        return switch (known.kind()) {
            case BLOCKED -> new AdmissionResponse(known.kind(), Messages.get("reject.blocked"));
            case DENIED -> new AdmissionResponse(known.kind(), Messages.get("reject.denied"));
            case PENDING -> new AdmissionResponse(known.kind(), Messages.get("reject.pending", identity.exactUsername()));
            case UNKNOWN -> new AdmissionResponse(known.kind(), Messages.get("reject.unknown", identity.exactUsername()));
            case DEGRADED -> new AdmissionResponse(known.kind(), Messages.get("reject.unavailable"));
        };
    }

    public DecisionService decisions() { return decisions; }
    public WorkflowRepository repository() { return repository; }
    public ModConfig config() { return config; }
    public ExecutorService commandExecutor() { return commandExecutor; }
    public int queueSize() { return worker == null ? 0 : worker.size(); }
    public boolean degraded() { return degraded || cache.isDegraded(); }
    public Optional<WhitelistRequest> find(UUID id) { return repository == null ? Optional.empty() : repository.findById(id); }
    public Optional<WhitelistRequest> active(String username) {
        return repository == null ? Optional.empty() : repository.findActiveByName(username.toLowerCase(Locale.ROOT));
    }

    @Override
    public void close() {
        providers.forEach(ApprovalInterface::stop);
        if (outbox != null) outbox.close();
        if (worker != null) worker.close();
        shutdown(decisionExecutor);
        shutdown(commandExecutor);
        if (repository != null) {
            try { repository.close(); } catch (Exception ignored) { }
        }
    }

    private static void shutdown(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
