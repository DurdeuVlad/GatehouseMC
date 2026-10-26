package com.gatehousemc.application.admin;

import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.domain.WhitelistRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the canonical operator-facing rendering of {@link AdminCommandResult}.
 * Every transport adapter (Minecraft loaders, Discord, Telegram) must delegate to
 * {@link AdminResultRenderer} so list payloads cannot drift or silently disappear.
 */
class AdminResultRendererTest {
    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

    private static WhitelistRequest request(String name, long attempts) {
        return new WhitelistRequest(UUID.randomUUID(), PlayerIdentity.of(name), RequestStatus.PENDING,
                NOW, NOW, NOW, NOW, attempts, null, null, null, null, null);
    }

    @Test
    void requestListRendersEveryEntryWithUsableReference() {
        WhitelistRequest first = request("Alice", 1);
        WhitelistRequest second = request("Bob", 7);
        AdminCommandResult result = AdminCommandResult.success(AdminCommand.Kind.REQUESTS,
                List.of(first, second), "Requests page 1");

        String rendered = AdminResultRenderer.render(result);

        assertTrue(rendered.contains("Requests page 1"), rendered);
        assertTrue(rendered.contains("Alice"), rendered);
        assertTrue(rendered.contains("Bob"), rendered);
        assertTrue(rendered.contains(first.id().toString().substring(0, 8)), rendered);
        assertTrue(rendered.contains("PENDING"), rendered);
        assertTrue(rendered.contains("attempts=7"), rendered);
        // one row per request plus the header line
        assertEquals(3, rendered.split("\n").length, rendered);
    }

    @Test
    void emptyRequestListRendersHonestEmptyState() {
        AdminCommandResult result = AdminCommandResult.success(AdminCommand.Kind.REQUESTS,
                List.of(), "Requests page 1");

        String rendered = AdminResultRenderer.render(result);

        assertTrue(rendered.contains("Requests page 1"), rendered);
        assertTrue(rendered.length() > "Requests page 1".length(), rendered);
        assertFalse(rendered.contains("attempts="), rendered);
    }

    @Test
    void singleRequestShowsFullyResolvableDetails() {
        WhitelistRequest request = request("Carol", 4);
        AdminCommandResult result = AdminCommandResult.success(AdminCommand.Kind.SHOW,
                request, "Request details");

        String rendered = AdminResultRenderer.render(result);

        assertTrue(rendered.contains("Request details"), rendered);
        assertTrue(rendered.contains(request.id().toString()), rendered);
        assertTrue(rendered.contains("Carol"), rendered);
        assertTrue(rendered.contains(request.identity().offlineUuid().toString()), rendered);
        assertTrue(rendered.contains("attempts=4"), rendered);
    }

    @Test
    void principalListRendersEveryPrincipal() {
        AdminCommandResult result = AdminCommandResult.success(AdminCommand.Kind.ADMIN_LIST,
                List.of(new ModConfig.Principal("USER", "12345", "MANAGE"),
                        new ModConfig.Principal("ROLE", "6789", "VIEW")),
                "Configured principals for discord");

        String rendered = AdminResultRenderer.render(result);

        assertTrue(rendered.contains("Configured principals for discord"), rendered);
        assertTrue(rendered.contains("USER"), rendered);
        assertTrue(rendered.contains("12345"), rendered);
        assertTrue(rendered.contains("MANAGE"), rendered);
        assertTrue(rendered.contains("ROLE"), rendered);
        assertTrue(rendered.contains("6789"), rendered);
        assertTrue(rendered.contains("VIEW"), rendered);
    }

    @Test
    void messageOnlyResultsPassThroughUnchanged() {
        assertEquals("Gatehouse status ok",
                AdminResultRenderer.render(AdminCommandResult.success("Gatehouse status ok")));
    }

    @Test
    void errorResultsPassThroughUnchanged() {
        AdminCommandResult result = AdminCommandResult.error(AdminCommandResultCode.NOT_FOUND, "Request not found");
        assertEquals("Request not found", AdminResultRenderer.render(result));
    }
}
