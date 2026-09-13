package com.gatehousemc.platform.neoforge;

import com.gatehousemc.domain.PlayerIdentity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NeoForgeGameProfileIdentityTest {
    @Test
    void onlineModeGameProfileIdentityConvertsToDomainWithoutThrowing() {
        // Real Mojang UUID for player "dwurdy" (does not match offline UUID)
        UUID onlineMojangUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        String username = "dwurdy";

        NeoForgeRuntime.GameProfileIdentity profile = new NeoForgeRuntime.GameProfileIdentity(onlineMojangUuid, username);
        PlayerIdentity identity = profile.toDomain();

        assertEquals(username, identity.exactUsername());
        assertEquals("dwurdy", identity.normalizedUsername());
        assertEquals(PlayerIdentity.offlineUuidFor(username), identity.offlineUuid());
    }

    @Test
    void offlineModeGameProfileIdentityConvertsToDomain() {
        String username = "E2E_Alice";
        UUID offlineUuid = PlayerIdentity.offlineUuidFor(username);

        NeoForgeRuntime.GameProfileIdentity profile = new NeoForgeRuntime.GameProfileIdentity(offlineUuid, username);
        PlayerIdentity identity = profile.toDomain();

        assertEquals(username, identity.exactUsername());
        assertEquals("e2e_alice", identity.normalizedUsername());
        assertEquals(offlineUuid, identity.offlineUuid());
    }

    @Test
    void invalidUsernameThrowsValidationException() {
        UUID randomUuid = UUID.randomUUID();
        NeoForgeRuntime.GameProfileIdentity profile = new NeoForgeRuntime.GameProfileIdentity(randomUuid, "invalid name with spaces");

        assertThrows(IllegalArgumentException.class, profile::toDomain);
    }
}
