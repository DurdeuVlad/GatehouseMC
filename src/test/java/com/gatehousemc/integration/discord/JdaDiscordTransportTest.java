package com.gatehousemc.integration.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class JdaDiscordTransportTest {
    private static final String GUILD_ID = "111111111111111111";
    private static final String CHANNEL_ID = "222222222222222222";

    @Test
    @DisplayName("Bot not in guild at all returns descriptive error message naming the guild ID")
    void resolveChannel_whenBotNotInGuild_reportsBotNotInGuild() {
        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById")) return null;
            if (method.getName().equals("getGuilds")) return List.of();
            return null;
        });

        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null);

        CompletionException ex = assertThrows(CompletionException.class,
                () -> transport.resolveChannel(CHANNEL_ID).join());

        assertTrue(ex.getCause() instanceof IllegalStateException);
        String msg = ex.getCause().getMessage();
        assertTrue(msg.contains("Discord bot is not in the guild at all"),
                "Expected 'Discord bot is not in the guild at all', got: " + msg);
        assertTrue(msg.contains(GUILD_ID), "Expected message to contain guild ID " + GUILD_ID + ", got: " + msg);
    }

    @Test
    @DisplayName("Guild found but channel ID not found in it returns descriptive error")
    void resolveChannel_whenGuildFoundButChannelNotFound_reportsChannelNotFound() {
        Guild guild = createMockGuild(GUILD_ID, "TestGuild", null, null);
        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById") && GUILD_ID.equals(args[0])) return guild;
            if (method.getName().equals("getGuildChannelById")) return null;
            return null;
        });

        // REST fetch returns UNKNOWN_CHANNEL (10003 / 404)
        Response resp = new Response((okhttp3.Response) null, 404, "Not Found", 0, Set.of());
        ErrorResponseException notFoundEx = ErrorResponseException.create(ErrorResponse.UNKNOWN_CHANNEL, resp);

        JdaDiscordTransport.ChannelRestFetcher restFetcher = channelId -> CompletableFuture.failedFuture(notFoundEx);
        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null, restFetcher);

        CompletionException ex = assertThrows(CompletionException.class,
                () -> transport.resolveChannel(CHANNEL_ID).join());

        assertTrue(ex.getCause() instanceof IllegalStateException);
        String msg = ex.getCause().getMessage();
        assertTrue(msg.contains("Discord guild found (" + GUILD_ID + ") but channel ID " + CHANNEL_ID + " was not found in it"),
                "Expected guild found but channel not found message, got: " + msg);
    }

    @Test
    @DisplayName("Channel found but lacks VIEW_CHANNEL permission names View Channel permission")
    void resolveChannel_whenChannelFoundButLacksViewChannel_reportsMissingViewChannel() {
        Member selfMember = createMockMember(false, true); // VIEW_CHANNEL = false, MESSAGE_SEND = true
        Guild[] guildHolder = new Guild[1];
        StandardGuildMessageChannel channel = createMockChannel(CHANNEL_ID, "secret-room", ChannelType.TEXT, () -> guildHolder[0]);
        Guild guild = createMockGuild(GUILD_ID, "TestGuild", channel, selfMember);
        guildHolder[0] = guild;

        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById")) return guild;
            if (method.getName().equals("getGuildChannelById")) return channel;
            return null;
        });

        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null);

        CompletionException ex = assertThrows(CompletionException.class,
                () -> transport.resolveChannel(CHANNEL_ID).join());

        assertTrue(ex.getCause() instanceof IllegalStateException);
        String msg = ex.getCause().getMessage();
        assertTrue(msg.contains("Discord bot found channel 'secret-room' (" + CHANNEL_ID + ") but lacks View Channel permission there"),
                "Expected missing View Channel permission message, got: " + msg);
    }

    @Test
    @DisplayName("Channel found but lacks MESSAGE_SEND permission names Send Messages permission")
    void resolveChannel_whenChannelFoundButLacksSendMessages_reportsMissingSendMessages() {
        Member selfMember = createMockMember(true, false); // VIEW_CHANNEL = true, MESSAGE_SEND = false
        Guild[] guildHolder = new Guild[1];
        StandardGuildMessageChannel channel = createMockChannel(CHANNEL_ID, "read-only", ChannelType.TEXT, () -> guildHolder[0]);
        Guild guild = createMockGuild(GUILD_ID, "TestGuild", channel, selfMember);
        guildHolder[0] = guild;

        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById")) return guild;
            if (method.getName().equals("getGuildChannelById")) return channel;
            return null;
        });

        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null);

        CompletionException ex = assertThrows(CompletionException.class,
                () -> transport.resolveChannel(CHANNEL_ID).join());

        assertTrue(ex.getCause() instanceof IllegalStateException);
        String msg = ex.getCause().getMessage();
        assertTrue(msg.contains("Discord bot found channel 'read-only' (" + CHANNEL_ID + ") but lacks Send Messages permission there"),
                "Expected missing Send Messages permission message, got: " + msg);
    }

    @Test
    @DisplayName("Channel found but lacks both permissions names View Channel and Send Messages")
    void resolveChannel_whenChannelFoundButLacksBothPermissions_reportsMissingBoth() {
        Member selfMember = createMockMember(false, false); // Both false
        Guild[] guildHolder = new Guild[1];
        StandardGuildMessageChannel channel = createMockChannel(CHANNEL_ID, "locked-down", ChannelType.TEXT, () -> guildHolder[0]);
        Guild guild = createMockGuild(GUILD_ID, "TestGuild", channel, selfMember);
        guildHolder[0] = guild;

        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById")) return guild;
            if (method.getName().equals("getGuildChannelById")) return channel;
            return null;
        });

        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null);

        CompletionException ex = assertThrows(CompletionException.class,
                () -> transport.resolveChannel(CHANNEL_ID).join());

        assertTrue(ex.getCause() instanceof IllegalStateException);
        String msg = ex.getCause().getMessage();
        assertTrue(msg.contains("Discord bot found channel 'locked-down' (" + CHANNEL_ID + ") but lacks View Channel and Send Messages permissions there"),
                "Expected missing both permissions message, got: " + msg);
    }

    @Test
    @DisplayName("Channel found but is VoiceChannel returns wrong type error naming VOICE")
    void resolveChannel_whenChannelFoundButWrongType_reportsWrongType() {
        Guild[] guildHolder = new Guild[1];
        VoiceChannel voiceChannel = mock(VoiceChannel.class, (proxy, method, args) -> {
            if (method.getName().equals("getId")) return CHANNEL_ID;
            if (method.getName().equals("getName")) return "General Voice";
            if (method.getName().equals("getType")) return ChannelType.VOICE;
            if (method.getName().equals("getGuild")) return guildHolder[0];
            return null;
        });
        Guild guild = createMockGuild(GUILD_ID, "TestGuild", voiceChannel, null);
        guildHolder[0] = guild;

        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById")) return guild;
            if (method.getName().equals("getGuildChannelById")) return voiceChannel;
            return null;
        });

        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null);

        CompletionException ex = assertThrows(CompletionException.class,
                () -> transport.resolveChannel(CHANNEL_ID).join());

        assertTrue(ex.getCause() instanceof IllegalStateException);
        String msg = ex.getCause().getMessage();
        assertTrue(msg.contains("found but wrong type (expected standard text or announcement channel, but found VOICE)"),
                "Expected wrong type error message, got: " + msg);
    }

    @Test
    @DisplayName("Channel found and is NewsChannel (announcements) with permissions succeeds")
    void resolveChannel_whenNewsChannelWithPermissions_succeeds() {
        Guild[] guildHolder = new Guild[1];
        NewsChannel newsChannel = mock(NewsChannel.class, (proxy, method, args) -> {
            if (method.getName().equals("getId")) return CHANNEL_ID;
            if (method.getName().equals("getName")) return "server-news";
            if (method.getName().equals("getType")) return ChannelType.NEWS;
            if (method.getName().equals("getGuild")) return guildHolder[0];
            return null;
        });
        Member selfMember = createMockMember(true, true);
        Guild guild = createMockGuild(GUILD_ID, "TestGuild", newsChannel, selfMember);
        guildHolder[0] = guild;

        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById")) return guild;
            if (method.getName().equals("getGuildChannelById")) return newsChannel;
            return null;
        });

        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null);

        assertDoesNotThrow(() -> {
            var channel = transport.resolveChannel(CHANNEL_ID).join();
            assertNotNull(channel);
            assertEquals(CHANNEL_ID, channel.getId());
        });
    }

    @Test
    @DisplayName("Channel found and is standard TextChannel with permissions succeeds")
    void resolveChannel_whenTextChannelWithPermissions_succeeds() {
        Guild[] guildHolder = new Guild[1];
        TextChannel textChannel = mock(TextChannel.class, (proxy, method, args) -> {
            if (method.getName().equals("getId")) return CHANNEL_ID;
            if (method.getName().equals("getName")) return "whitelist-approvals";
            if (method.getName().equals("getType")) return ChannelType.TEXT;
            if (method.getName().equals("getGuild")) return guildHolder[0];
            return null;
        });
        Member selfMember = createMockMember(true, true);
        Guild guild = createMockGuild(GUILD_ID, "TestGuild", textChannel, selfMember);
        guildHolder[0] = guild;

        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById")) return guild;
            if (method.getName().equals("getGuildChannelById")) return textChannel;
            return null;
        });

        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null);

        var channel = transport.resolveChannel(CHANNEL_ID).join();
        assertNotNull(channel);
        assertEquals(CHANNEL_ID, channel.getId());
    }

    @Test
    @DisplayName("Cache miss falls back to REST fetch and succeeds when channel is retrieved")
    void resolveChannel_whenCacheMisses_fallsBackToRestAndSucceeds() {
        Guild[] guildHolder = new Guild[1];
        TextChannel textChannel = mock(TextChannel.class, (proxy, method, args) -> {
            if (method.getName().equals("getId")) return CHANNEL_ID;
            if (method.getName().equals("getName")) return "whitelist-requests";
            if (method.getName().equals("getType")) return ChannelType.TEXT;
            if (method.getName().equals("getGuild")) return guildHolder[0];
            return null;
        });
        Member selfMember = createMockMember(true, true);

        Map<String, GuildChannel> channelCache = new HashMap<>();
        Guild guild = mock(Guild.class, (proxy, method, args) -> {
            if (method.getName().equals("getId")) return GUILD_ID;
            if (method.getName().equals("getIdLong")) return Long.parseLong(GUILD_ID);
            if (method.getName().equals("getName")) return "TestGuild";
            if (method.getName().equals("getGuildChannelById")) return channelCache.get(args[0]);
            if (method.getName().equals("getSelfMember")) return selfMember;
            return null;
        });
        guildHolder[0] = guild;

        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById")) return guild;
            if (method.getName().equals("getGuildChannelById")) return channelCache.get(args[0]);
            return null;
        });

        // REST fetcher simulates retrieving channel and populating it
        JdaDiscordTransport.ChannelRestFetcher restFetcher = channelId -> {
            channelCache.put(channelId, textChannel);
            DataObject json = DataObject.empty()
                    .put("id", channelId)
                    .put("type", 0) // TEXT
                    .put("name", "whitelist-requests")
                    .put("guild_id", GUILD_ID);
            return CompletableFuture.completedFuture(json);
        };

        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null, restFetcher);

        var channel = transport.resolveChannel(CHANNEL_ID).join();
        assertNotNull(channel);
        assertEquals(CHANNEL_ID, channel.getId());
    }

    @Test
    @DisplayName("REST fetch failure due to MISSING_ACCESS reports actionable permission error")
    void resolveChannel_whenRestFetchReportsMissingAccess_reportsActionableError() {
        Guild guild = createMockGuild(GUILD_ID, "TestGuild", null, null);
        JDA jda = mock(JDA.class, (proxy, method, args) -> {
            if (method.getName().equals("getGuildById")) return guild;
            if (method.getName().equals("getGuildChannelById")) return null;
            return null;
        });

        Response resp = new Response((okhttp3.Response) null, 403, "Forbidden", 0, Set.of());
        ErrorResponseException missingAccessEx = ErrorResponseException.create(ErrorResponse.MISSING_ACCESS, resp);

        JdaDiscordTransport.ChannelRestFetcher restFetcher = channelId -> CompletableFuture.failedFuture(missingAccessEx);
        JdaDiscordTransport transport = new JdaDiscordTransport(jda, GUILD_ID, null, restFetcher);

        CompletionException ex = assertThrows(CompletionException.class,
                () -> transport.resolveChannel(CHANNEL_ID).join());

        assertTrue(ex.getCause() instanceof IllegalStateException);
        String msg = ex.getCause().getMessage();
        assertTrue(msg.contains("Discord bot lacks permission to access channel " + CHANNEL_ID + " (missing access: lacks View Channel/Send Messages permission)"),
                "Expected missing access diagnostic message, got: " + msg);
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private static <T> T mock(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static StandardGuildMessageChannel createMockChannel(String id, String name, ChannelType type, java.util.function.Supplier<Guild> guildSupplier) {
        return mock(StandardGuildMessageChannel.class, (proxy, method, args) -> {
            if (method.getName().equals("getId")) return id;
            if (method.getName().equals("getName")) return name;
            if (method.getName().equals("getType")) return type;
            if (method.getName().equals("getGuild")) return guildSupplier.get();
            return null;
        });
    }

    private static net.dv8tion.jda.api.entities.SelfMember createMockMember(boolean viewPerm, boolean sendPerm) {
        return mock(net.dv8tion.jda.api.entities.SelfMember.class, (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) {
                Object lastArg = args[args.length - 1];
                if (lastArg instanceof Permission[] perms) {
                    for (Permission p : perms) {
                        if (p == Permission.VIEW_CHANNEL && !viewPerm) return false;
                        if (p == Permission.MESSAGE_SEND && !sendPerm) return false;
                    }
                    return true;
                } else if (lastArg instanceof Permission p) {
                    if (p == Permission.VIEW_CHANNEL) return viewPerm;
                    if (p == Permission.MESSAGE_SEND) return sendPerm;
                    return true;
                }
            }
            return null;
        });
    }

    private static Guild createMockGuild(String guildId, String guildName, GuildChannel channel, Member selfMember) {
        return mock(Guild.class, (proxy, method, args) -> {
            if (method.getName().equals("getId")) return guildId;
            if (method.getName().equals("getIdLong")) return Long.parseLong(guildId);
            if (method.getName().equals("getName")) return guildName;
            if (method.getName().equals("getGuildChannelById")) {
                if (channel != null && channel.getId().equals(args[0])) return channel;
                return null;
            }
            if (method.getName().equals("getSelfMember")) return selfMember;
            return null;
        });
    }
}
