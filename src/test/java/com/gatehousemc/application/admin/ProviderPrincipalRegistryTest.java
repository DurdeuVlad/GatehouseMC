package com.gatehousemc.application.admin;

import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.AdminPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderPrincipalRegistryTest {
    @Test
    void legacyAllowListsBecomeDecisionPrincipals() {
        ModConfig defaults = ModConfig.defaults(Path.of("build", "tmp-principals"));
        ModConfig config = new ModConfig(defaults.requests(), defaults.database(), defaults.routing(),
                defaults.discord(), new ModConfig.Telegram(false, "", "", List.of("123")), "en_us");
        ProviderPrincipalRegistry registry = new ProviderPrincipalRegistry(config);
        assertEquals(AdminCapability.DECIDE,
                registry.capability(new AdminPrincipal("telegram", "123", "")).orElseThrow());
    }

    @Test
    void remoteProviderCannotRemoveItsFinalManager() {
        ModConfig config = new ModConfig(configRequests(), configDatabase(), configRouting(),
                new ModConfig.Discord(true, "token", "123456789012345678", "123456789012345679", "",
                        List.of(), List.of(), List.of(new ModConfig.Principal("USER", "123456789012345678", "MANAGE"))),
                new ModConfig.Telegram(false, "", "", List.of()),
                new ModConfig.Minecraft(2, 3, 4), "en_us");
        ProviderPrincipalRegistry registry = new ProviderPrincipalRegistry(config);
        assertThrows(IllegalStateException.class, () -> registry.remove(
                new AdminPrincipal("discord", "123456789012345678", "admin"),
                "discord", "123456789012345678"));
    }

    @Test
    void adminChangesPersistCanonicalPrincipalsWithoutExpandingSecrets(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("config.json");
        Files.writeString(file, "{\"discord\":{\"token\":\"${DISCORD_TOKEN}\",\"allowedUserIds\":[]}}\n");
        ProviderPrincipalRegistry registry = new ProviderPrincipalRegistry(ModConfig.defaults(temp),
                new com.gatehousemc.config.ConfigWriter(), file);

        registry.add(AdminPrincipal.console(), "discord", "123456789012345678", AdminCapability.MANAGE);

        String written = Files.readString(file);
        assertTrue(written.contains("\"principals\""));
        assertFalse(written.contains("allowedUserIds"));
        assertTrue(written.contains("${DISCORD_TOKEN}"));
    }

    private static ModConfig.Requests configRequests() { return new ModConfig.Requests(1, 1, 3); }
    private static ModConfig.Database configDatabase() { return new ModConfig.Database(Path.of("requests.sqlite"), 1); }
    private static ModConfig.Routing configRouting() { return new ModConfig.Routing(com.gatehousemc.port.RoutingMode.PRIMARY_FALLBACK, List.of()); }
}
