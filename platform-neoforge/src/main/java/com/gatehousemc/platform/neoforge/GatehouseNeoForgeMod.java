package com.gatehousemc.platform.neoforge;

import com.gatehousemc.application.admin.AdminCommandResult;
import com.gatehousemc.application.admin.AdminCommandResultCode;
import com.gatehousemc.config.ConfigLoader;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.i18n.Messages;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(GatehouseNeoForgeMod.MOD_ID)
public final class GatehouseNeoForgeMod {
    public static final String MOD_ID = "gatehousemc";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(GatehouseNeoForgeMod.class);
    private static final ExecutorService STARTUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gatehousemc-neoforge-startup");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean STOPPING = new AtomicBoolean();
    private static final AtomicBoolean RELOADING = new AtomicBoolean();
    private static final Object RUNTIME_LOCK = new Object();
    private static volatile NeoForgeRuntime runtime;

    public GatehouseNeoForgeMod() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerStarted(ServerStartedEvent event) {
        STOPPING.set(false);
        STARTUP_EXECUTOR.execute(() -> startRuntime(event.getServer(), FMLPaths.CONFIGDIR.get().resolve(MOD_ID)));
    }

    private static void startRuntime(net.minecraft.server.MinecraftServer server, Path configDir) {
        if (STOPPING.get()) return;
        ModConfig config = null;
        try {
            config = ConfigLoader.loadOrDefault(configDir);
            Messages.load(config.language());
            NeoForgeRuntime started = NeoForgeRuntime.start(server, config);
            started.setReloadHandler(GatehouseNeoForgeMod::reloadAsync);
            synchronized (RUNTIME_LOCK) {
                if (STOPPING.get() || runtime != null) {
                    started.close();
                    return;
                }
                runtime = started;
            }
        } catch (Exception error) {
            synchronized (RUNTIME_LOCK) {
                if (!STOPPING.get() && runtime == null) {
                    runtime = NeoForgeRuntime.degraded(server, config == null ? ModConfig.defaults(configDir) : config);
                    System.err.println("GatehouseMC failed to start: " + error.getMessage());
                }
            }
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        NeoForgeRuntime current;
        synchronized (RUNTIME_LOCK) {
            STOPPING.set(true);
            current = runtime;
            runtime = null;
        }
        if (current != null) current.close();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        GatehouseNeoForgeCommands.register(event.getDispatcher(), () -> runtime);
    }

    public static Component handleWhitelistDenial(GameProfile profile) {
        NeoForgeRuntime current = runtime;
        if (current == null) return Component.translatable("multiplayer.disconnect.not_whitelisted");
        try {
            if (current.server() != null && current.server().getProfileCache() != null && profile != null) {
                current.server().getProfileCache().add(profile);
            }
            return current.onWhitelistDenied(new NeoForgeRuntime.GameProfileIdentity(profile.getId(), profile.getName()));
        } catch (Exception error) {
            LOGGER.warn("Whitelist denial handling failed for profile {}", profile, error);
            return Component.translatable("multiplayer.disconnect.not_whitelisted");
        }
    }

    public static NeoForgeRuntime runtime() { return runtime; }

    public static CompletionStage<AdminCommandResult> reloadAsync() {
        CompletableFuture<AdminCommandResult> result = new CompletableFuture<>();
        if (!RELOADING.compareAndSet(false, true)) {
            result.complete(AdminCommandResult.error(AdminCommandResultCode.INVALID_STATE,
                    "A Gatehouse reload is already in progress."));
            return result;
        }
        STARTUP_EXECUTOR.execute(() -> {
            try {
                NeoForgeRuntime current;
                synchronized (RUNTIME_LOCK) {
                    current = runtime;
                    if (current == null || current.degraded()) {
                        result.complete(AdminCommandResult.error(AdminCommandResultCode.UNAVAILABLE,
                                "Gatehouse runtime is unavailable; reload cannot start."));
                        return;
                    }
                }
                Path configDir = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
                NeoForgeRuntime started = null;
                boolean installed = false;
                try {
                    ModConfig next = ConfigLoader.loadOrDefault(configDir);
                    if (!samePath(current.config().database().path(), next.database().path())) {
                        LOGGER.warn("Config reload rejected: database.path changes require a server restart");
                        result.complete(AdminCommandResult.error(AdminCommandResultCode.INVALID_ARGUMENT,
                                "database.path changes require a server restart"));
                        return;
                    }
                    started = NeoForgeRuntime.start(current.server(), next);
                    started.setReloadHandler(GatehouseNeoForgeMod::reloadAsync);
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
