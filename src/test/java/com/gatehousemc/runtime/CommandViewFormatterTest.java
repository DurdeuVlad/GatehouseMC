package com.gatehousemc.runtime;

import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.i18n.Messages;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandViewFormatterTest {

    @Test
    void listEntryUsesCompleteResolvableRequestId() {
        Messages.load("en_us");
        WhitelistRequest request = deniedRequest();

        String text = CommandViewFormatter.listEntry(request);

        assertTrue(text.contains(request.id().toString()));
        assertTrue(text.contains("DENIED"));
        assertTrue(text.contains("UxPlayer"));
    }

    @Test
    void detailsExposeResolutionContext() {
        Messages.load("en_us");
        WhitelistRequest request = deniedRequest();

        String text = CommandViewFormatter.details(request);

        assertTrue(text.contains("Reason: duplicate account"));
        assertTrue(text.contains("Resolved by: Console"));
        assertTrue(text.contains("First attempt:"));
        assertTrue(text.contains("Last attempt:"));
    }

    private static WhitelistRequest deniedRequest() {
        Instant created = Instant.parse("2026-09-18T10:00:00Z");
        Instant resolved = created.plusSeconds(30);
        return new WhitelistRequest(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                PlayerIdentity.of("UxPlayer"),
                RequestStatus.DENIED,
                created,
                resolved,
                created,
                created.plusSeconds(5),
                2,
                resolved,
                AdminPrincipal.console(),
                "duplicate account",
                null,
                null);
    }
}
