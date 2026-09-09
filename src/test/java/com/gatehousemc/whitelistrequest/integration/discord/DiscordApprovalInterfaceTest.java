package com.gatehousemc.whitelistrequest.integration.discord;

import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.config.ModConfig;
import com.gatehousemc.whitelistrequest.integration.common.CallbackActionParser;
import com.gatehousemc.whitelistrequest.port.ProviderHealth;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class DiscordApprovalInterfaceTest {

    @Test
    void publishReturnsPublicationRefWithMessageId() {
        UUID requestId = UUID.randomUUID();
        RequestView request = new RequestView(requestId, PlayerIdentity.of("TestPlayer"), RequestStatus.PENDING,
                1, Instant.EPOCH, Instant.EPOCH, null, null, null);
        FakeDiscordTransport transport = new FakeDiscordTransport();
        transport.nextMessageId = "msg123";
        ModConfig.Discord config = new ModConfig.Discord(true, "token", "guild1", "channel1", List.of(), List.of());
        DiscordApprovalInterface discord = new DiscordApprovalInterface(config, null, transport);

        PublicationRef ref = discord.publish(request).toCompletableFuture().join();

        assertEquals("discord", ref.provider());
        assertEquals("channel1", ref.containerId());
        assertEquals("msg123", ref.messageId());
        assertTrue(transport.lastText.contains("TestPlayer"));
        assertTrue(transport.lastText.contains("OFFLINE / UNAUTHENTICATED"));
        assertEquals(requestId, transport.lastRequestId);
        assertFalse(transport.lastDisabled);
    }

    @Test
    void publishFailsWhenTransportUnavailable() {
        RequestView request = new RequestView(UUID.randomUUID(), PlayerIdentity.of("TestPlayer"), RequestStatus.PENDING,
                1, Instant.EPOCH, Instant.EPOCH, null, null, null);
        ModConfig.Discord config = new ModConfig.Discord(true, "token", "guild1", "channel1", List.of(), List.of());
        DiscordApprovalInterface discord = new DiscordApprovalInterface(config, null, null);

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> discord.publish(request).toCompletableFuture().join());
    }

    @Test
    void parseActionDecodesValidButtonIds() {
        UUID id = UUID.randomUUID();
        DiscordApprovalInterface.ParsedAction approve = DiscordApprovalInterface.parseAction("wr:a:" + id);
        assertEquals(DecisionAction.APPROVE, approve.action());
        assertEquals(id, approve.requestId());

        DiscordApprovalInterface.ParsedAction deny = DiscordApprovalInterface.parseAction("wr:d:" + id);
        assertEquals(DecisionAction.DENY, deny.action());

        DiscordApprovalInterface.ParsedAction block = DiscordApprovalInterface.parseAction("wr:b:" + id);
        assertEquals(DecisionAction.BLOCK, block.action());

        DiscordApprovalInterface.ParsedAction undo = DiscordApprovalInterface.parseAction("wr:u:" + id);
        assertEquals(DecisionAction.UNDO, undo.action());
        assertEquals(id, undo.requestId());
    }

    @Test
    void parseActionRejectsMalformedIds() {
        assertNull(DiscordApprovalInterface.parseAction(null));
        assertNull(DiscordApprovalInterface.parseAction("wr:x:" + UUID.randomUUID()));
        assertNull(DiscordApprovalInterface.parseAction("wr:a:not-a-uuid"));
        assertNull(DiscordApprovalInterface.parseAction("invalid"));
    }

    @Test
    void parseCallbackHandlesConfirmAndCancelFormats() {
        UUID id = UUID.randomUUID();
        CallbackActionParser.ParsedCallback primary = CallbackActionParser.parse("wr:a:" + id);
        assertEquals(CallbackActionParser.CallbackKind.PRIMARY, primary.kind());
        assertEquals(DecisionAction.APPROVE, primary.action());
        assertEquals(id, primary.requestId());

        CallbackActionParser.ParsedCallback confirm = CallbackActionParser.parse("wr:c:d:" + id);
        assertEquals(CallbackActionParser.CallbackKind.CONFIRM, confirm.kind());
        assertEquals(DecisionAction.DENY, confirm.action());
        assertEquals(id, confirm.requestId());

        CallbackActionParser.ParsedCallback cancel = CallbackActionParser.parse("wr:x:" + id);
        assertEquals(CallbackActionParser.CallbackKind.CANCEL, cancel.kind());
        assertEquals(id, cancel.requestId());
    }

    @Test
    void formatCallbacksProduceCorrectStrings() {
        UUID id = UUID.randomUUID();
        assertEquals("wr:a:" + id, CallbackActionParser.formatPrimary(DecisionAction.APPROVE, id));
        assertEquals("wr:c:a:" + id, CallbackActionParser.formatConfirm(DecisionAction.APPROVE, id));
        assertEquals("wr:x:" + id, CallbackActionParser.formatCancel(id));
    }

    @Test
    void legacyParseActionIgnoresConfirmAndCancel() {
        UUID id = UUID.randomUUID();
        assertNull(CallbackActionParser.parseAction("wr:c:a:" + id));
        assertNull(CallbackActionParser.parseAction("wr:x:" + id));
    }

    @Test
    void healthIsHealthyWithInjectedTransport() {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        ModConfig.Discord config = new ModConfig.Discord(true, "token", "guild1", "channel1", List.of(), List.of());
        DiscordApprovalInterface discord = new DiscordApprovalInterface(config, null, transport);
        assertEquals(ProviderHealth.HEALTHY, discord.health());
    }

    @Test
    void disabledProviderReportsUnavailableOnStart() {
        ModConfig.Discord config = new ModConfig.Discord(false, "", "guild1", "channel1", List.of(), List.of());
        DiscordApprovalInterface discord = new DiscordApprovalInterface(config, null);
        discord.start();
        assertEquals(ProviderHealth.UNAVAILABLE, discord.health());
    }

    private static final class FakeDiscordTransport implements DiscordTransport {
        String nextMessageId = "default";
        String lastText;
        UUID lastRequestId;
        boolean lastDisabled;

        @Override
        public CompletableFuture<String> sendMessage(String channelId, String text, UUID requestId, boolean disabled) {
            lastText = text;
            lastRequestId = requestId;
            lastDisabled = disabled;
            return CompletableFuture.completedFuture(nextMessageId);
        }

        @Override
        public CompletableFuture<Void> editMessage(String channelId, String messageId, String text, UUID requestId, boolean disabled) {
            lastText = text;
            lastRequestId = requestId;
            lastDisabled = disabled;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}
    }
}
