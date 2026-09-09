package com.gatehousemc.platform.fabric;

import com.gatehousemc.application.*;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.*;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.persistence.sqlite.SqliteDatabase;
import com.gatehousemc.persistence.sqlite.SqliteWorkflowRepository;
import com.gatehousemc.port.ClockPort;
import com.gatehousemc.port.ApprovalInterface;
import com.gatehousemc.port.WorkflowRepository;
import com.gatehousemc.application.ApprovalInterfaceRouter;
import com.gatehousemc.application.OutboxWorker;
import com.gatehousemc.integration.discord.DiscordApprovalInterface;
import com.gatehousemc.integration.telegram.TelegramApprovalInterface;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.LiteralText;
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
    private final ExecutorService commandExecutor;
    private final List<ApprovalInterface> providers;
    private final OutboxWorker outbox;
    private final boolean degraded;

    private FabricRuntime(MinecraftServer server, ModConfig config, RequestAdmissionCache cache,
                           WorkflowRepository repository, WhitelistRequestService requests,
                           DecisionService decisions, AdmissionWorker worker, ExecutorService decisionExecutor,
                           ExecutorService commandExecutor, boolean degraded) {
        this.server = server;
        this.config = config;
        this.cache = cache;
        this.repository = repository;
        this.requests = requests;
        this.decisions = decisions;
        this.worker = worker;
        this.decisionExecutor = decisionExecutor;
        this.commandExecutor = commandExecutor;
        this.degraded = degraded;
        this.providers = List.of();
        this.outbox = null;
    }

    private FabricRuntime(MinecraftServer server, ModConfig config, RequestAdmissionCache cache,
                           WorkflowRepository repository, WhitelistRequestService requests,
                           DecisionService decisions, AdmissionWorker worker, ExecutorService decisionExecutor,
                           ExecutorService commandExecutor,
                           List<ApprovalInterface> providers, OutboxWorker outbox) {
        this.server = server;
        this.config = config;
        this.cache = cache;
        this.repository = repository;
        this.requests = requests;
        this.decisions = decisions;
        this.worker = worker;
        this.decisionExecutor = decisionExecutor;
        this.commandExecutor = commandExecutor;
        this.degraded = false;
        this.providers = List.copyOf(providers);
        this.outbox = outbox;
    }

    static FabricRuntime start(MinecraftServer server, ModConfig config) throws IOException, SQLException {
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
            decisionExecutor = newDaemonExecutor("gatehousemc-decisions");
            commandExecutor = newDaemonExecutor("gatehousemc-commands");
            DecisionService decisions = new DecisionService(repository, new FabricVanillaWhitelistAdapter(server), clock, cache,
                    denialCooldown, decisionExecutor);
            requests.hydrateCache();
            decisions.recoverInterruptedApprovals().toCompletableFuture().join();
            createProviders(config, decisions, providers);
            ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(providers, config.routing().mode());
            outbox = new OutboxWorker(repository, router, clock);
            providers.forEach(ApprovalInterface::start);
            worker.start();
            outbox.start();
            return new FabricRuntime(server, config, cache, repository, requests, decisions, worker, decisionExecutor, commandExecutor, providers, outbox);
        } catch (IOException | SQLException | RuntimeException error) {
            cleanup(outbox, providers, worker, decisionExecutor, commandExecutor, repository, database, error);
            throw error;
        }
    }

    private static void createProviders(ModConfig config, DecisionService decisions, List<ApprovalInterface> providers) {
        for (String provider : config.routing().providers()) {
            if (provider.equalsIgnoreCase("discord")) providers.add(new DiscordApprovalInterface(config.discord(), decisions));
            if (provider.equalsIgnoreCase("telegram")) providers.add(new TelegramApprovalInterface(config.telegram(), decisions));
        }
    }

    private static ExecutorService newDaemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void cleanup(OutboxWorker outbox, List<ApprovalInterface> providers, AdmissionWorker worker,
                                ExecutorService decisionExecutor, ExecutorService commandExecutor,
                                WorkflowRepository repository, SqliteDatabase database, Throwable error) {
        if (outbox != null) outbox.close();
        providers.forEach(ApprovalInterface::stop);
        if (worker != null) worker.close();
        if (decisionExecutor != null) decisionExecutor.shutdownNow();
        if (commandExecutor != null) commandExecutor.shutdownNow();
        if (repository != null) {
            try { repository.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
        } else if (database != null) {
            try { database.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
        }
    }

    static FabricRuntime degraded(MinecraftServer server, ModConfig config) {
        RequestAdmissionCache cache = new RequestAdmissionCache();
        cache.markDegraded(true);
        return new FabricRuntime(server, config, cache, null, null, null, null, null, null, true);
    }

    public Text onWhitelistDenied(GameProfileIdentity profile) {
        if (degraded || requests == null || worker == null) return messagesUnavailable(profile.exactUsername());
        PlayerIdentity identity = profile.toDomain();
        AdmissionState known = cache.get(identity.normalizedUsername());
        if (!worker.offer(identity)) return messagesUnavailable(identity.exactUsername());
        return switch (known.kind()) {
            case BLOCKED -> new LiteralText(Messages.get("reject.blocked"));
            case DENIED -> new LiteralText(Messages.get("reject.denied"));
            case PENDING -> new LiteralText(Messages.get("reject.pending", identity.exactUsername()));
            case UNKNOWN -> new LiteralText(Messages.get("reject.unknown", identity.exactUsername()));
            case DEGRADED -> messagesUnavailable(identity.exactUsername());
        };
    }

    public DecisionService decisions() { return decisions; }
    public WorkflowRepository repository() { return repository; }
    public ModConfig config() { return config; }
    public ExecutorService commandExecutor() { return commandExecutor; }
    public MinecraftServer server() { return server; }
    public int queueSize() { return worker == null ? 0 : worker.size(); }
    public boolean degraded() { return degraded || cache.isDegraded(); }

    public Optional<WhitelistRequest> find(UUID id) { return repository == null ? Optional.empty() : repository.findById(id); }
    public Optional<WhitelistRequest> active(String username) { return repository == null ? Optional.empty() : repository.findActiveByName(username.toLowerCase(java.util.Locale.ROOT)); }

    private static Text messagesUnavailable(String username) {
        return new LiteralText(Messages.get("reject.unavailable"));
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
        if (commandExecutor != null) {
            commandExecutor.shutdown();
            try {
                if (!commandExecutor.awaitTermination(5, TimeUnit.SECONDS)) commandExecutor.shutdownNow();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                commandExecutor.shutdownNow();
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
