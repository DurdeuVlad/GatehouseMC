package com.gatehousemc.whitelistrequest.platform.fabric;

import com.gatehousemc.whitelistrequest.config.ConfigLoader;
import com.gatehousemc.whitelistrequest.config.ModConfig;
import com.gatehousemc.whitelistrequest.platform.fabric.command.WhitelistRequestCommands;
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
import java.util.concurrent.atomic.AtomicBoolean;

public final class WhitelistRequestMod implements ModInitializer {
    public static final String MOD_ID = "whitelistrequest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ExecutorService STARTUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "whitelistrequest-startup");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean STOPPING = new AtomicBoolean();
    private static final Object RUNTIME_LOCK = new Object();
    private static volatile FabricRuntime runtime;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                WhitelistRequestCommands.register(dispatcher, () -> runtime));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            synchronized (RUNTIME_LOCK) {
                STOPPING.set(false);
                runtime = null;
            }
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
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
            FabricRuntime started = FabricRuntime.start(server, config);
            synchronized (RUNTIME_LOCK) {
                if (STOPPING.get() || runtime != null) {
                    started.close();
                    return;
                }
                runtime = started;
            }
            LOGGER.info("Whitelist Request started: {}", config.redactedSummary());
        } catch (Exception error) {
            synchronized (RUNTIME_LOCK) {
                if (!STOPPING.get() && runtime == null) {
                    LOGGER.error("storage.degraded: whitelist request persistence is unavailable", error);
                    runtime = FabricRuntime.degraded(server, config == null ? ModConfig.defaults(configDir) : config);
                }
            }
        }
    }

    public static Text handleWhitelistDenial(GameProfile profile) {
        FabricRuntime current = runtime;
        if (current == null) return Text.literal("You are not whitelisted on this server. Please contact a server administrator.");
        return current.onWhitelistDenied(new FabricRuntime.GameProfileIdentity(profile.getId(), profile.getName()));
    }

    public static FabricRuntime runtime() {
        return runtime;
    }

    /**
     * Reloads non-structural configuration without requiring a Minecraft
     * restart. The SQLite path is intentionally structural because moving it
     * while the server is running could split the workflow store.
     */
    public static String reload() {
        synchronized (RUNTIME_LOCK) {
            FabricRuntime current = runtime;
        if (current == null) return "Whitelist Request is not running";
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        try {
            ModConfig next = ConfigLoader.loadOrDefault(configDir);
            if (!samePath(current.config().database().path(), next.database().path())) {
                return "Config reload rejected: database.path changes require a server restart";
            }
            runtime = null;
            current.close();
            runtime = FabricRuntime.start(current.server(), next);
            LOGGER.info("Whitelist Request configuration reloaded: {}", next.redactedSummary());
            return "Whitelist Request configuration reloaded";
        } catch (Exception error) {
            LOGGER.error("Config reload failed; request workflow is degraded until the server is restarted", error);
            runtime = FabricRuntime.degraded(current.server(), current.config());
            return "Config reload failed; workflow is degraded: " + safeMessage(error);
        }
        }
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
