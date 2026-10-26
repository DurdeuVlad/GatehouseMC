package com.gatehousemc.application.admin;

import com.gatehousemc.config.ModConfig;
import com.gatehousemc.config.ConfigWriter;
import com.gatehousemc.domain.AdminPrincipal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Runtime principal registry used by authorization and admin commands. */
public final class ProviderPrincipalRegistry {
    private final ConcurrentMap<String, List<ModConfig.Principal>> principals = new ConcurrentHashMap<>();
    private final ConfigWriter configWriter;
    private final Path configFile;

    public ProviderPrincipalRegistry(ModConfig config) {
        this(config, null, null);
    }

    public ProviderPrincipalRegistry(ModConfig config, ConfigWriter configWriter, Path configFile) {
        this.configWriter = configWriter;
        this.configFile = configFile;
        principals.put("discord", initialDiscord(config.discord()));
        principals.put("telegram", initialTelegram(config.telegram()));
    }

    public Optional<AdminCapability> capability(AdminPrincipal actor) {
        if (actor.provider().equals("console")) return Optional.of(AdminCapability.MANAGE);
        if (!principals.containsKey(actor.provider())) return Optional.empty();
        String kind = actor.externalId().startsWith("role:") ? "ROLE" : "USER";
        String id = actor.externalId().startsWith("role:")
                ? actor.externalId().substring("role:".length()) : actor.externalId();
        return principals.getOrDefault(actor.provider(), List.of()).stream()
                .filter(principal -> principal.kind().equals(kind) && principal.id().equals(id))
                .map(principal -> AdminCapability.valueOf(principal.access()))
                .max(Comparator.comparingInt(Enum::ordinal));
    }

    public List<ModConfig.Principal> list(String provider) {
        return List.copyOf(principals.getOrDefault(normalizeProvider(provider), List.of()));
    }

    public synchronized String add(AdminPrincipal actor, String provider, String rawPrincipal, AdminCapability access) {
        String normalizedProvider = normalizeProvider(provider);
        requireOwner(actor, normalizedProvider);
        Objects.requireNonNull(access, "access");
        ModConfig.Principal principal = parsePrincipal(normalizedProvider, rawPrincipal, access);
        List<ModConfig.Principal> current = new ArrayList<>(list(normalizedProvider));
        current.removeIf(existing -> existing.kind().equals(principal.kind()) && existing.id().equals(principal.id()));
        current.add(principal);
        List<ModConfig.Principal> updated = List.copyOf(current);
        persist(normalizedProvider, updated);
        principals.put(normalizedProvider, updated);
        return "Added " + principal.kind().toLowerCase(Locale.ROOT) + " " + principal.id()
                + " to " + normalizedProvider + " with " + principal.access();
    }

    public synchronized String remove(AdminPrincipal actor, String provider, String rawPrincipal) {
        String normalizedProvider = normalizeProvider(provider);
        requireOwner(actor, normalizedProvider);
        ModConfig.Principal target = parsePrincipal(normalizedProvider, rawPrincipal, AdminCapability.VIEW);
        List<ModConfig.Principal> current = new ArrayList<>(list(normalizedProvider));
        Optional<ModConfig.Principal> existing = current.stream()
                .filter(principal -> principal.kind().equals(target.kind()) && principal.id().equals(target.id()))
                .findFirst();
        if (existing.isEmpty()) return "No such principal is configured for " + normalizedProvider + ".";
        if (actor.provider().equals(normalizedProvider) && existing.get().access().equals("MANAGE")
                && current.stream().filter(principal -> principal.access().equals("MANAGE")).count() <= 1) {
            throw new IllegalStateException("The final MANAGE principal for " + normalizedProvider + " cannot be removed remotely.");
        }
        current.remove(existing.get());
        List<ModConfig.Principal> updated = List.copyOf(current);
        persist(normalizedProvider, updated);
        principals.put(normalizedProvider, updated);
        return "Removed " + target.id() + " from " + normalizedProvider + ".";
    }

    private void persist(String provider, List<ModConfig.Principal> updated) {
        if (configWriter == null || configFile == null) return;
        try {
            if (provider.equals("discord")) configWriter.writeDiscordPrincipals(configFile, updated);
            else configWriter.writeTelegramPrincipals(configFile, updated);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Could not persist " + provider + " principals", error);
        }
    }

    private void requireOwner(AdminPrincipal actor, String provider) {
        if (!actor.provider().equals("minecraft") && !actor.provider().equals("console")
                && !actor.provider().equals(provider)) {
            throw new SecurityException("A remote provider may manage only its own principals.");
        }
    }

    private static ModConfig.Principal parsePrincipal(String provider, String raw, AdminCapability access) {
        String value = Objects.requireNonNull(raw, "principal").trim();
        String kind = "USER";
        if (value.startsWith("role:")) {
            kind = "ROLE";
            value = value.substring("role:".length());
        }
        if (value.isBlank()) throw new IllegalArgumentException("principal must not be blank");
        if (provider.equals("discord") && !value.matches("\\d{17,20}")) {
            throw new IllegalArgumentException("Discord principals must be snowflakes; use role:<snowflake> for roles");
        }
        if (provider.equals("telegram") && (!kind.equals("USER") || !value.matches("\\d+"))) {
            throw new IllegalArgumentException("Telegram principals must be numeric user IDs");
        }
        return new ModConfig.Principal(kind, value, access.name());
    }

    private static String normalizeProvider(String provider) {
        String value = Objects.requireNonNull(provider, "provider").toLowerCase(Locale.ROOT);
        if (!value.equals("discord") && !value.equals("telegram")) throw new IllegalArgumentException("unsupported provider");
        return value;
    }

    private static List<ModConfig.Principal> initialDiscord(ModConfig.Discord config) {
        if (!config.principals().isEmpty()) return config.principals();
        List<ModConfig.Principal> result = new ArrayList<>();
        config.allowedUserIds().forEach(id -> result.add(new ModConfig.Principal("USER", id, "DECIDE")));
        config.allowedRoleIds().forEach(id -> result.add(new ModConfig.Principal("ROLE", id, "DECIDE")));
        return List.copyOf(result);
    }

    private static List<ModConfig.Principal> initialTelegram(ModConfig.Telegram config) {
        if (!config.principals().isEmpty()) return config.principals();
        return config.allowedUserIds().stream().map(id -> new ModConfig.Principal("USER", id, "DECIDE")).toList();
    }
}
