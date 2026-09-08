package com.gatehousemc.whitelistrequest;

import com.gatehousemc.whitelistrequest.domain.PlayerIdentity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OfflineIdentityTest {
    @Test
    void matchesMinecraft1211OfflineUuidHelperForKnownUsername() {
        assertEquals(UUID.fromString("b3778d72-9634-3114-b99f-0d158f68186e"),
                PlayerIdentity.offlineUuidFor("E2E_Alice"));
    }

    @Test
    void caseVariationChangesOfflineUuidButSharesWorkflowNormalization() {
        UUID upperUuid = PlayerIdentity.offlineUuidFor("Alice");
        UUID lowerUuid = PlayerIdentity.offlineUuidFor("alice");

        assertNotEquals(upperUuid, lowerUuid);
        assertEquals("alice", PlayerIdentity.of(upperUuid, "Alice").normalizedUsername());
        assertEquals("alice", PlayerIdentity.of(lowerUuid, "alice").normalizedUsername());
    }
}
