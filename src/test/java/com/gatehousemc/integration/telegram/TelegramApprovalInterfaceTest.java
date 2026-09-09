package com.gatehousemc.integration.telegram;

import com.gatehousemc.domain.*;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.port.ProviderHealth;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class TelegramApprovalInterfaceTest {

    @Test
    void publishReturnsPublicationRefWithMessageId() {
        UUID requestId = UUID.randomUUID();
        RequestView request = new RequestView(requestId, PlayerIdentity.of("TestPlayer"), RequestStatus.PENDING,
                1, Instant.EPOCH, Instant.EPOCH, null, null, null);
        FakeTelegramTransport transport = new FakeTelegramTransport();
        transport.nextMessageId = "42";
        ModConfig.Telegram config = new ModConfig.Telegram(true, "token", "chat1", List.of("user1"));
        TelegramApprovalInterface telegram = new TelegramApprovalInterface(config, null, transport);

        PublicationRef ref = telegram.publish(request).toCompletableFuture().join();

        assertEquals("telegram", ref.provider());
        assertEquals("chat1", ref.containerId());
        assertEquals("42", ref.messageId());
        assertTrue(transport.lastPayload.contains("TestPlayer"));
        assertTrue(transport.lastPayload.contains("OFFLINE / UNAUTHENTICATED"));
        assertTrue(transport.lastPayload.contains("wr:a:" + requestId));
    }

    @Test
    void publishFailsWhenTransportNull() {
        RequestView request = new RequestView(UUID.randomUUID(), PlayerIdentity.of("TestPlayer"), RequestStatus.PENDING,
                1, Instant.EPOCH, Instant.EPOCH, null, null, null);
        ModConfig.Telegram config = new ModConfig.Telegram(true, "token", "chat1", List.of());
        TelegramApprovalInterface telegram = new TelegramApprovalInterface(config, null);

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> telegram.publish(request).toCompletableFuture().join());
    }

    @Test
    void disabledProviderReportsUnavailableOnStart() {
        ModConfig.Telegram config = new ModConfig.Telegram(false, "", "chat1", List.of());
        TelegramApprovalInterface telegram = new TelegramApprovalInterface(config, null);
        telegram.start();
        assertEquals(ProviderHealth.UNAVAILABLE, telegram.health());
    }

    @Test
    void healthIsHealthyWithInjectedTransport() {
        FakeTelegramTransport transport = new FakeTelegramTransport();
        ModConfig.Telegram config = new ModConfig.Telegram(true, "token", "chat1", List.of("user1"));
        TelegramApprovalInterface telegram = new TelegramApprovalInterface(config, null, transport);
        assertEquals(ProviderHealth.HEALTHY, telegram.health());
    }

    private static final class FakeTelegramTransport implements TelegramTransport {
        String nextMessageId = "1";
        String lastPayload;

        @Override
        public CompletableFuture<JsonObject> post(String method, String payload) {
            lastPayload = payload;
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            JsonObject message = new JsonObject();
            message.addProperty("message_id", nextMessageId);
            result.add("result", message);
            return CompletableFuture.completedFuture(result);
        }
    }
}
