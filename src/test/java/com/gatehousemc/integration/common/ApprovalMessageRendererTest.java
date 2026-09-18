package com.gatehousemc.integration.common;

import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.domain.RequestView;
import com.gatehousemc.i18n.Messages;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMessageRendererTest {

    @Test
    void pendingMessageShowsUsefulLifecycleContext() {
        Messages.load("en_us");
        RequestView request = new RequestView(
                UUID.randomUUID(), PlayerIdentity.of("ContextPlayer"), RequestStatus.PENDING,
                3, Instant.parse("2026-09-18T10:00:00Z"), Instant.parse("2026-09-18T10:05:00Z"),
                null, null, null);

        String message = ApprovalMessageRenderer.render(request);

        assertTrue(message.contains("First attempt: 2026-09-18T10:00:00Z"));
        assertTrue(message.contains("Last attempt: 2026-09-18T10:05:00Z"));
        assertTrue(message.contains("Status: PENDING"));
    }

    @Test
    void terminalMessageShowsActorTimeAndReason() {
        Messages.load("en_us");
        RequestView request = new RequestView(
                UUID.randomUUID(), PlayerIdentity.of("ContextPlayer"), RequestStatus.DENIED,
                3, Instant.parse("2026-09-18T10:00:00Z"), Instant.parse("2026-09-18T10:05:00Z"),
                Instant.parse("2026-09-18T10:06:00Z"),
                new AdminPrincipal("discord", "123", "Moderator"),
                "duplicate account");

        String message = ApprovalMessageRenderer.render(request);

        assertTrue(message.contains("Resolved at: 2026-09-18T10:06:00Z"));
        assertTrue(message.contains("Resolved by: Moderator (discord)"));
        assertTrue(message.contains("Reason: duplicate account"));
    }
}
