package com.gatehousemc.runtime;

import com.gatehousemc.application.AdmissionState;
import com.gatehousemc.application.AdmissionWorker;
import com.gatehousemc.application.ActiveRequestCounter;
import com.gatehousemc.application.ApprovalInterfaceRouter;
import com.gatehousemc.application.DecisionService;
import com.gatehousemc.application.OutboxWorker;
import com.gatehousemc.application.GatehouseStatusService;
import com.gatehousemc.application.SetupSessionStore;
import com.gatehousemc.application.RequestAdmissionCache;
import com.gatehousemc.application.WhitelistRequestService;
import com.gatehousemc.application.admin.AdminAuthorizationService;
import com.gatehousemc.application.admin.AdminCapability;
import com.gatehousemc.application.admin.AdminCommandService;
import com.gatehousemc.application.admin.DefaultRequestResolver;
import com.gatehousemc.application.admin.ProviderPrincipalRegistry;
import com.gatehousemc.application.admin.SetupService;
import com.gatehousemc.application.admin.ProviderRuntimeControl;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.config.ConfigWriter;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.function.Supplier;

/** Shared application wiring used by every Minecraft loader adapter. */
public final class GatehouseRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatehouseRuntime.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    public record AdmissionResponse(AdmissionState.Kind state, String message) {}

    private final ModConfig config;
    private final RequestAdmissionCache cache;
    private final WorkflowRepository repository;
    private final WhitelistRequestService requests;
    private final DecisionService decisions;
    private final AdminCommandService adminCommands;
    private final GatehouseStatusService statusService;
    private final AdmissionWorker worker;
    private final ExecutorService decisionExecutor;
    private final ExecutorService commandExecutor;
    private final List<ApprovalInterface> providers;
    private final OutboxWorker outbox;
    private final boolean degraded;
    private final ProviderRuntimeControl providerControl;

    private GatehouseRuntime(ModConfig config, RequestAdmissionCache cache,
                             WorkflowRepository repository, WhitelistRequestService requests,
                             DecisionService decisions, AdminCommandService adminCommands, AdmissionWorker worker,
                             GatehouseStatusService statusService,
                             ExecutorService decisionExecutor, ExecutorService commandExecutor,
                             List<ApprovalInterface> providers, OutboxWorker outbox,
                             boolean degraded, ProviderRuntimeControl providerControl) {
        this.config = config;
        this.cache = cache;
        this.repository = repository;
        this.requests = requests;
        this.decisions = decisions;
        this.adminCommands = adminCommands;
        this.statusService = statusService;
        this.worker = worker;
        this.decisionExecutor = decisionExecutor;
        this.commandExecutor = commandExecutor;
        this.providers = List.copyOf(providers);
        this.outbox = outbox;
        this.degraded = degraded;
        this.providerControl = providerControl;
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
            SetupSessionStore setupSessions = new SetupSessionStore(clock, new java.security.SecureRandom(),
                    new com.gatehousemc.persistence.sqlite.SqliteSetupSessionPersistence(database));
            RequestAdmissionCache cache = new RequestAdmissionCache(clock);
            Duration denialCooldown = Duration.ofMinutes(config.requests().denialCooldownMinutes());
            WhitelistRequestService requests = new WhitelistRequestService(repository, clock, denialCooldown, cache);
            ActiveRequestCounter activeRequests = new ActiveRequestCounter(repository.countActiveRequests());
            worker = new AdmissionWorker(config.requests().queueCapacity(), requests, clock, activeRequests,
                    config.requests().newRequestRatePerMinute(), config.requests().newRequestBurst(),
                    config.requests().maxPendingRequests(), config.requests().attemptCoalesceSeconds());
            decisionExecutor = daemonExecutor("gatehousemc-decisions");
            commandExecutor = daemonExecutor("gatehousemc-commands");
            DecisionService decisions = new DecisionService(repository, whitelist, clock, cache,
                    denialCooldown, decisionExecutor, activeRequests);
            java.nio.file.Path configFile = config.database().path().toAbsolutePath().normalize().getParent().resolve("config.json");
            ProviderPrincipalRegistry principalRegistry = new ProviderPrincipalRegistry(config, new ConfigWriter(), configFile);
            ProviderRuntimeControl providerControl = new ProviderRuntimeControl(providers);
            AdminAuthorizationService authorization = new AdminAuthorizationService(actor -> {
                if (actor.provider().equals("minecraft")) {
                    int level = 4;
                    if (actor.externalId().startsWith("perm:")) {
                        try { level = Integer.parseInt(actor.externalId().substring("perm:".length())); }
                        catch (NumberFormatException ignored) { level = 0; }
                    }
                    if (level >= config.minecraft().managePermissionLevel()) return Optional.of(AdminCapability.MANAGE);
                    if (level >= config.minecraft().decisionPermissionLevel()) return Optional.of(AdminCapability.DECIDE);
                    if (level >= config.minecraft().viewPermissionLevel()) return Optional.of(AdminCapability.VIEW);
                    return Optional.empty();
                }
                return principalRegistry.capability(actor);
            });
            AdminCommandService adminCommands = new AdminCommandService(repository, decisions,
                    new DefaultRequestResolver(repository), authorization, principalRegistry,
                    new SetupService(config, setupSessions), providerControl);
            requests.hydrateCache();
            decisions.recoverInterruptedApprovals().toCompletableFuture().join();
            createProviders(config, decisions, adminCommands, providers);
            GatehouseStatusService statusService = new GatehouseStatusService(config, repository, worker, providers, false);
            ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(providers, config.routing().mode());
            outbox = new OutboxWorker(repository, router, clock,
                    Duration.ofSeconds(config.requests().providerRefreshSeconds()));
            providers.forEach(ApprovalInterface::start);
            worker.start();
            outbox.start();
            Messages.load(config.language());
            return new GatehouseRuntime(config, cache, repository, requests, decisions, adminCommands, worker, statusService,
                    decisionExecutor, commandExecutor, providers, outbox, false, providerControl);
        } catch (IOException | SQLException | RuntimeException error) {
            cleanup(outbox, providers, worker, decisionExecutor, commandExecutor, repository, database, error);
            throw error;
        }
    }

    public static GatehouseRuntime degraded(ModConfig config) {
        RequestAdmissionCache cache = new RequestAdmissionCache();
        cache.markDegraded(true);
        return new GatehouseRuntime(config, cache, null, null, null, null, null,
                new GatehouseStatusService(config, null, null, List.of(), true), null, null,
                List.of(), null, true, null);
    }

    private static void createProviders(ModConfig config, DecisionService decisions,
                                        com.gatehousemc.application.admin.AdminCommandService adminCommands,
                                        List<ApprovalInterface> providers) {
        for (String provider : config.routing().providers()) {
            if (provider.equalsIgnoreCase("discord")) {
                providers.add(new DiscordApprovalInterface(config.discord(), decisions, null, adminCommands));
            }
            if (provider.equalsIgnoreCase("telegram")) {
                providers.add(new TelegramApprovalInterface(config.telegram(), decisions, null, adminCommands));
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
        AdmissionWorker.OfferResult offered = worker.offer(identity, known.kind());
        if (offered.status() == AdmissionWorker.OfferStatus.DEGRADED) {
            return new AdmissionResponse(AdmissionState.Kind.DEGRADED, withSupport(Messages.get("reject.unavailable")));
        }
        if (offered.status() == AdmissionWorker.OfferStatus.THROTTLED) {
            return new AdmissionResponse(AdmissionState.Kind.UNKNOWN, withSupport(Messages.get("reject.throttled")));
        }
        if (offered.status() == AdmissionWorker.OfferStatus.CAPACITY) {
            return new AdmissionResponse(AdmissionState.Kind.UNKNOWN, withSupport(Messages.get("reject.capacity")));
        }
        return switch (known.kind()) {
            case BLOCKED -> new AdmissionResponse(known.kind(), withSupport(Messages.get("reject.blocked", identity.exactUsername())));
            case DENIED -> new AdmissionResponse(known.kind(), withSupport(Messages.get("reject.denied", remaining(known.until()))));
            case PENDING -> new AdmissionResponse(known.kind(), Messages.get("reject.pending", identity.exactUsername()));
            case UNKNOWN -> new AdmissionResponse(known.kind(), Messages.get("reject.unknown", identity.exactUsername()));
            case DEGRADED -> new AdmissionResponse(known.kind(), withSupport(Messages.get("reject.unavailable")));
        };
    }

    private String withSupport(String message) {
        String support = config.requests().supportMessage();
        return support.isBlank() ? message : message + "\n" + support;
    }

    private static String remaining(Instant until) {
        long seconds = Math.max(0, Duration.between(Instant.now(), until).getSeconds());
        long minutes = Math.max(1, (seconds + 59) / 60);
        if (minutes >= 60) return (minutes / 60) + "h " + (minutes % 60) + "m";
        return minutes + "m";
    }

    public DecisionService decisions() { return decisions; }
    public AdminCommandService adminCommands() { return adminCommands; }
    public GatehouseStatusService status() { return statusService; }
    public WorkflowRepository repository() { return repository; }
    public ModConfig config() { return config; }
    public ExecutorService commandExecutor() { return commandExecutor; }
    public int queueSize() { return worker == null ? 0 : worker.size(); }
    public boolean degraded() { return degraded || cache.isDegraded(); }
    public void setReloadHandler(Supplier<java.util.concurrent.CompletionStage<com.gatehousemc.application.admin.AdminCommandResult>> handler) {
        if (providerControl != null) providerControl.setReloadHandler(handler);
    }
    public Optional<WhitelistRequest> find(UUID id) { return repository == null ? Optional.empty() : repository.findById(id); }
    public Optional<WhitelistRequest> active(String username) {
        return repository == null ? Optional.empty() : repository.findActiveByName(username.toLowerCase(Locale.ROOT));
    }

    /**
     * Called synchronously from each platform's server-stopping hook, on the Minecraft server
     * thread. Provider shutdown (JDA/Telegram) does network I/O and must never be allowed to hang
     * that thread, so the real work runs on a daemon thread with a bounded wait; a provider that
     * doesn't finish in time is abandoned rather than blocking vanilla server shutdown.
     */
    @Override
    public void close() {
        boolean finished = runBounded(this::closeNow, CLOSE_TIMEOUT, "gatehousemc-shutdown");
        if (!finished) {
            LOGGER.warn("gatehousemc.shutdown_timeout: providers did not finish shutting down within {}s; " +
                    "letting the server continue stopping.", CLOSE_TIMEOUT.getSeconds());
        }
    }

    /**
     * Runs {@code work} on a daemon thread and waits up to {@code timeout} for it to finish.
     * Returns {@code false} (leaving the thread running in the background) instead of blocking the
     * caller past the bound; a hung provider is abandoned, never allowed to hang the caller. Package
     * -private so the timeout behavior itself can be tested directly with a deliberately hanging
     * task, without needing a live JDA connection to reproduce a stuck shutdown.
     */
    static boolean runBounded(Runnable work, Duration timeout, String threadName) {
        Thread worker = new Thread(work, threadName);
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(timeout.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return !worker.isAlive();
    }

    private void closeNow() {
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
