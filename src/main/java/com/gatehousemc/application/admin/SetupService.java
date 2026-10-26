package com.gatehousemc.application.admin;

import com.gatehousemc.application.SetupSessionStore;
import com.gatehousemc.config.ModConfig;

import java.util.Locale;
import java.util.Objects;

/**
 * Shared, provider-neutral setup/session boundary. Provider adapters may add
 * credentials and destinations later, but session issuance and replay safety
 * stay in the application layer.
 */
public final class SetupService {
    private final ModConfig config;
    private final SetupSessionStore sessions;

    public SetupService(ModConfig config, SetupSessionStore sessions) {
        this.config = Objects.requireNonNull(config, "config");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public AdminCommandResult status() {
        return AdminCommandResult.success("Gatehouse setup status: discord=" + state("discord")
                + " telegram=" + state("telegram"));
    }

    public AdminCommandResult issue(String provider) {
        String normalized = provider(provider);
        SetupSessionStore.Issued issued = sessions.issue(normalized);
        return AdminCommandResult.success(issued,
                "Setup session for " + normalized + ": " + issued.code()
                        + " (expires in 10 minutes; use /gatehouse setup bind " + normalized + " <code>)");
    }

    public AdminCommandResult bind(String provider, String code) {
        String normalized = provider(provider);
        SetupSessionStore.ConsumeResult result = sessions.consume(normalized, code);
        return switch (result.status()) {
            case CONSUMED -> AdminCommandResult.success(
                    "Setup session accepted for " + normalized + ". Supply the provider credentials and destination, then reload.");
            case WRONG_PROVIDER -> AdminCommandResult.error(AdminCommandResultCode.INVALID_ARGUMENT,
                    "That setup code belongs to another provider.");
            case EXPIRED -> AdminCommandResult.error(AdminCommandResultCode.INVALID_STATE,
                    "That setup code has expired. Start setup again.");
            case REPLAY -> AdminCommandResult.error(AdminCommandResultCode.INVALID_STATE,
                    "That setup code has already been consumed.");
            case MISSING -> AdminCommandResult.error(AdminCommandResultCode.NOT_FOUND,
                    "Setup code not found. Check the code or start setup again.");
        };
    }

    public AdminCommandResult cancel(String provider) {
        String normalized = provider(provider);
        sessions.cancel(normalized);
        return AdminCommandResult.success("Cancelled setup sessions for " + normalized);
    }

    private String state(String provider) {
        if (provider.equals("discord")) {
            ModConfig.Discord value = config.discord();
            return value.enabled() && !value.token().isBlank() && (!value.channelId().isBlank() || !value.dmUserId().isBlank())
                    ? "CONFIGURED" : (value.enabled() ? "SETUP_REQUIRED" : "DISABLED");
        }
        ModConfig.Telegram value = config.telegram();
        return value.enabled() && !value.token().isBlank() && !value.chatId().isBlank()
                ? "CONFIGURED" : (value.enabled() ? "SETUP_REQUIRED" : "DISABLED");
    }

    private static String provider(String value) {
        String normalized = Objects.requireNonNull(value, "provider").trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("discord") && !normalized.equals("telegram")) {
            throw new IllegalArgumentException("provider must be discord or telegram");
        }
        return normalized;
    }
}
