package com.gatehousemc.platform.fabric;

import com.gatehousemc.config.ConfigLoader;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.platform.fabric.command.GatehouseCommands;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import com.gatehousemc.application.admin.AdminCommandResult;
import com.gatehousemc.application.admin.AdminCommandResultCode;

public final class GatehouseMod implements ModInitializer {
    public static final String MOD_ID = "gatehousemc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ExecutorService STARTUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gatehousemc-startup");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean STOPPING = new AtomicBoolean();
    private static final AtomicBoolean RELOADING = new AtomicBoolean();
    private static final Object RUNTIME_LOCK = new Object();
    private static volatile FabricRuntime runtime;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                GatehouseCommands.register(dispatcher, () -> runtime));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            synchronized (RUNTIME_LOCK) {
                STOPPING.set(false);
                runtime = null;
            }
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
            Path legacyConfigDir = FabricLoader.getInstance().getConfigDir().resolve("whitelistrequest");
            if (!java.nio.file.Files.exists(configDir) && java.nio.file.Files.exists(legacyConfigDir)) {
                try {
                    java.nio.file.Files.createDirectories(configDir);
                    try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(legacyConfigDir)) {
                        for (Path src : stream.toList()) {
                            Path dst = configDir.resolve(src.getFileName());
                            java.nio.file.Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    LOGGER.info("Migrated legacy whitelistrequest configuration to gatehousemc");
                } catch (Exception ex) {
                    LOGGER.warn("Failed to migrate legacy whitelistrequest directory, proceeding with defaults if needed", ex);
                }
            }
            STARTUP_EXECUTOR.execute(() -> startRuntime(server, configDir));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            FabricRuntime current;
            synchronized (RUNTIME_LOCK) {
                STOPPING.set(true);
                current = runtime;
                runtime = null;
            }
            if (current != null) current.close();
        });
    }

    private static void startRuntime(net.minecraft.server.MinecraftServer server, Path configDir) {
        synchronized (RUNTIME_LOCK) {
            if (STOPPING.get()) return;
        }
        ModConfig config = null;
        try {
            config = ConfigLoader.loadOrDefault(configDir);
            Messages.load(config.language());
            FabricRuntime started = FabricRuntime.start(server, config);
            started.setReloadHandler(GatehouseMod::reloadAsync);
            synchronized (RUNTIME_LOCK) {
                if (STOPPING.get() || runtime != null) {
                    started.close();
                    return;
                }
                runtime = started;
            }
            LOGGER.info("GatehouseMC started: {}", config.redactedSummary());
        } catch (Exception error) {
            synchronized (RUNTIME_LOCK) {
                if (!STOPPING.get() && runtime == null) {
                    LOGGER.error("storage.degraded: GatehouseMC request persistence is unavailable", error);
                    if (config != null) Messages.load(config.language());
                    runtime = FabricRuntime.degraded(server, config == null ? ModConfig.defaults(configDir) : config);
                }
            }
        }
    }

    public static Text handleWhitelistDenial(GameProfile profile) {
        FabricRuntime current = runtime;
        if (current == null) return Text.literal(Messages.get("reject.not_whitelisted"));
        try {
            if (current.server() != null && current.server().getUserCache() != null && profile != null) {
                current.server().getUserCache().add(profile);
            }
            return current.onWhitelistDenied(new FabricRuntime.GameProfileIdentity(profile.getId(), profile.getName()));
        } catch (Exception error) {
            LOGGER.warn("Whitelist denial handling failed for profile {}", profile, error);
            return Text.literal(Messages.get("reject.unavailable"));
        }
    }

    public static FabricRuntime runtime() {
        return runtime;
    }

    /**
     * Reloads non-structural configuration without requiring a Minecraft
     * restart. The SQLite path is intentionally structural because moving it
     * while the server is running could split the workflow store.
     * Runs asynchronously to avoid blocking the server thread on recovery.
     */
    public static CompletionStage<AdminCommandResult> reloadAsync() {
        CompletableFuture<AdminCommandResult> result = new CompletableFuture<>();
        if (!RELOADING.compareAndSet(false, true)) {
            result.complete(AdminCommandResult.error(AdminCommandResultCode.INVALID_STATE,
                    "A Gatehouse reload is already in progress."));
            return result;
        }
        STARTUP_EXECUTOR.execute(() -> {
            try {
                FabricRuntime current;
                synchronized (RUNTIME_LOCK) {
                    current = runtime;
                    if (current == null || current.degraded()) {
                        result.complete(AdminCommandResult.error(AdminCommandResultCode.UNAVAILABLE,
                                "Gatehouse runtime is unavailable; reload cannot start."));
                        return;
                    }
                }
                Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
                FabricRuntime started = null;
                boolean installed = false;
                try {
                    ModConfig next = ConfigLoader.loadOrDefault(configDir);
                    if (!samePath(current.config().database().path(), next.database().path())) {
                        LOGGER.warn("Config reload rejected: database.path changes require a server restart");
                        result.complete(AdminCommandResult.error(AdminCommandResultCode.INVALID_ARGUMENT,
                                "database.path changes require a server restart"));
                        return;
                    }
                    started = FabricRuntime.start(current.server(), next);
                    started.setReloadHandler(GatehouseMod::reloadAsync);
                    synchronized (RUNTIME_LOCK) {
                        if (STOPPING.get()) {
                            started.close();
                            result.complete(AdminCommandResult.error(AdminCommandResultCode.UNAVAILABLE,
                                    "Gatehouse server is stopping."));
                            return;
                        }
                        runtime = started;
                        installed = true;
                    }
                    Messages.load(next.language());
                    // closeAndAwait(), not close(): this runs on STARTUP_EXECUTOR, off the Minecraft
                    // main thread, so blocking here is fine -- and it matters, because `started`
                    // already opened a new connection to the same database file above. close()'s
                    // bounded abandon-on-timeout (needed for the server-stop path) would let that
                    // overlap run unbounded in the background instead of ending here.
                    current.closeAndAwait();
                    LOGGER.info("GatehouseMC configuration reloaded: {}", next.redactedSummary());
                    result.complete(AdminCommandResult.success("Gatehouse configuration reloaded."));
                } catch (Exception error) {
                    // Transactional reload: a failed candidate must never replace a
                    // working runtime with a degraded one.
                    if (started != null && !installed) started.close();
                    Messages.load(current.config().language());
                    LOGGER.error("Config reload failed; keeping the current GatehouseMC runtime", error);
                    result.complete(AdminCommandResult.error(AdminCommandResultCode.FAILED,
                            "Config reload failed; the current runtime was kept."));
                }
            } finally {
                RELOADING.set(false);
            }
        });
        return result;
    }

    private static boolean samePath(Path left, Path right) {
        return Objects.equals(left.toAbsolutePath().normalize(), right.toAbsolutePath().normalize());
    }
}
