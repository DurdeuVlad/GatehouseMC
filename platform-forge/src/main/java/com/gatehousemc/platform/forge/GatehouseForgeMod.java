package com.gatehousemc.platform.forge;

import com.gatehousemc.config.ConfigLoader;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.runtime.ReloadResult;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(GatehouseForgeMod.MOD_ID)
public final class GatehouseForgeMod {
    public static final String MOD_ID = "gatehousemc";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(GatehouseForgeMod.class);
    private static final ExecutorService STARTUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gatehousemc-forge-startup");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean STOPPING = new AtomicBoolean();
    private static final Object RUNTIME_LOCK = new Object();
    private static volatile ForgeRuntime runtime;

    public GatehouseForgeMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerStarted(ServerStartedEvent event) {
        synchronized (RUNTIME_LOCK) {
            STOPPING.set(false);
            runtime = null;
        }
        STARTUP_EXECUTOR.execute(() -> startRuntime(event.getServer(), FMLPaths.CONFIGDIR.get().resolve(MOD_ID)));
    }

    private static void startRuntime(net.minecraft.server.MinecraftServer server, Path configDir) {
        synchronized (RUNTIME_LOCK) {
            if (STOPPING.get()) return;
        }
        ModConfig config = null;
        try {
            config = ConfigLoader.loadOrDefault(configDir);
            Messages.load(config.language());
            ForgeRuntime started = ForgeRuntime.start(server, config);
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
                    if (config != null) Messages.load(config.language());
                    runtime = ForgeRuntime.degraded(server, config == null ? ModConfig.defaults(configDir) : config);
                    LOGGER.error("storage.degraded: GatehouseMC request persistence is unavailable", error);
                }
            }
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ForgeRuntime current;
        synchronized (RUNTIME_LOCK) {
            STOPPING.set(true);
            current = runtime;
            runtime = null;
        }
        if (current != null) current.close();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        GatehouseForgeCommands.register(event.getDispatcher(), () -> runtime);
    }

    public static Component handleWhitelistDenial(GameProfile profile) {
        ForgeRuntime current = runtime;
        if (current == null) return Component.literal(Messages.get("reject.starting"));
        try {
            if (current.server() != null && current.server().getProfileCache() != null && profile != null) {
                current.server().getProfileCache().add(profile);
            }
            return current.onWhitelistDenied(new ForgeRuntime.GameProfileIdentity(profile.getId(), profile.getName()));
        } catch (Exception error) {
            LOGGER.warn("Whitelist denial handling failed for profile {}", profile, error);
            return Component.literal(Messages.get("reject.unavailable"));
        }
    }

    public static ForgeRuntime runtime() { return runtime; }

    public static CompletableFuture<ReloadResult> reloadAsync() {
        CompletableFuture<ReloadResult> result = new CompletableFuture<>();
        STARTUP_EXECUTOR.execute(() -> {
            ForgeRuntime current;
            synchronized (RUNTIME_LOCK) {
                current = runtime;
                if (current == null || STOPPING.get()) {
                    result.complete(ReloadResult.unavailable("runtime is not ready"));
                    return;
                }
            }
            Path configDir = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
            try {
                ModConfig next = ConfigLoader.loadOrDefault(configDir);
                if (!current.degraded() && !samePath(current.config().database().path(), next.database().path())) {
                    LOGGER.warn("Config reload rejected: database.path changes require a server restart");
                    result.complete(ReloadResult.restartRequired());
                    return;
                }

                ForgeRuntime started = ForgeRuntime.start(current.server(), next);
                synchronized (RUNTIME_LOCK) {
                    if (STOPPING.get() || runtime != current) {
                        started.close();
                        result.complete(ReloadResult.unavailable("runtime changed while reload was in progress"));
                        return;
                    }
                    runtime = started;
                }
                current.close();
                LOGGER.info("GatehouseMC configuration reloaded: {}", next.redactedSummary());
                result.complete(ReloadResult.success());
            } catch (Exception error) {
                LOGGER.error("Config reload failed; previous GatehouseMC runtime remains active", error);
                result.complete(ReloadResult.failed(safeMessage(error)));
            }
        });
        return result;
    }

    private static boolean samePath(Path left, Path right) {
        return Objects.equals(left.toAbsolutePath().normalize(), right.toAbsolutePath().normalize());
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
