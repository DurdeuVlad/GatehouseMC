package com.gatehousemc.integration.discord;

import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.i18n.Messages;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.entities.GuildImpl;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** JDA-backed implementation of DiscordTransport. */
final class JdaDiscordTransport implements DiscordTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdaDiscordTransport.class);

    private final JDA jda;
    private final String guildId;
    private final String dmUserId;
    private final ChannelRestFetcher restFetcher;

    @FunctionalInterface
    interface ChannelRestFetcher {
        CompletableFuture<DataObject> fetchChannel(String channelId);
    }

    JdaDiscordTransport(JDA jda, String dmUserId) {
        this(jda, null, dmUserId, defaultRestFetcher(jda));
    }

    JdaDiscordTransport(JDA jda, String guildId, String dmUserId) {
        this(jda, guildId, dmUserId, defaultRestFetcher(jda));
    }

    JdaDiscordTransport(JDA jda, String guildId, String dmUserId, ChannelRestFetcher restFetcher) {
        this.jda = jda;
        this.guildId = guildId;
        this.dmUserId = dmUserId;
        this.restFetcher = restFetcher != null ? restFetcher : defaultRestFetcher(jda);
    }

    private static ChannelRestFetcher defaultRestFetcher(JDA jda) {
        return channelId -> {
            if (jda instanceof JDAImpl) {
                Route.CompiledRoute route = Route.Channels.GET_CHANNEL.compile(channelId);
                return new RestActionImpl<DataObject>(jda, route, (response, request) -> response.getObject()).submit();
            }
            return CompletableFuture.failedFuture(new IllegalStateException("REST channel fetch not supported on test JDA"));
        };
    }

    @Override
    public CompletableFuture<String> sendMessage(String destination, String text, UUID requestId, RequestStatus status) {
        return resolveChannel(destination).thenCompose(channel -> channel.sendMessage(text)
                .addComponents(ActionRow.of(Arrays.asList(actionButtons(requestId, status))))
                .submit())
                .thenApply(message -> message.getId());
    }

    @Override
    public CompletableFuture<Void> editMessage(String destination, String messageId, String text, UUID requestId, RequestStatus status) {
        return resolveChannel(destination).thenCompose(channel -> channel.editMessageById(messageId, text)
                .setComponents(ActionRow.of(Arrays.asList(actionButtons(requestId, disabled))))
                .submit())
                .thenApply(message -> null);
    }

    @Override
    public void start() {}

    @Override
    public void stop() {
        jda.shutdownNow();
    }

    /** Proactively validates reachability and permissions for the destination channel. */
    CompletableFuture<? extends MessageChannel> validateChannel(String destination) {
        return resolveChannel(destination);
    }

    CompletableFuture<? extends MessageChannel> resolveChannel(String destination) {
        if (dmUserId != null && !dmUserId.isBlank()) {
            if (destination != null && !destination.isBlank()) {
                PrivateChannel cached = jda.getPrivateChannelById(destination);
                if (cached != null) return CompletableFuture.completedFuture(cached);
            }
            return jda.retrieveUserById(destination).submit()
                    .thenCompose(user -> user.openPrivateChannel().submit());
        }

        if (destination == null || destination.isBlank()) {
            String errorMsg = "Discord channel ID is not configured";
            LOGGER.error("discord.channel_resolution_failed destination={} guildId={}: {}", destination, guildId, errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        Guild guild = findConfiguredGuild();
        if (guild == null && guildId != null && !guildId.isBlank()) {
            String errorMsg = "Discord bot is not in the guild at all (guild ID: " + guildId + ")";
            LOGGER.error("discord.channel_resolution_failed destination={} guildId={}: {}", destination, guildId, errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        GuildChannel cached = (guild != null) ? guild.getGuildChannelById(destination) : jda.getGuildChannelById(destination);
        if (cached != null) {
            return validateAndReturnGuildChannel(cached, destination);
        }

        // Cache miss: fall back to REST fetch before giving up
        return restFetcher.fetchChannel(destination)
                .handle((data, error) -> {
                    if (error != null) {
                        Throwable root = rootCause(error);
                        String diagnostic;
                        if (root instanceof ErrorResponseException ere) {
                            ErrorResponse errorResponse = ere.getErrorResponse();
                            if (errorResponse == ErrorResponse.UNKNOWN_CHANNEL || ere.getErrorCode() == 10003 || ere.getErrorCode() == 404) {
                                String gId = guild != null ? guild.getId() : (guildId != null ? guildId : "unknown");
                                diagnostic = "Discord guild found (" + gId + ") but channel ID " + destination + " was not found in it";
                            } else if (errorResponse == ErrorResponse.MISSING_ACCESS || ere.getErrorCode() == 50001
                                    || errorResponse == ErrorResponse.MISSING_PERMISSIONS || ere.getErrorCode() == 50013) {
                                diagnostic = "Discord bot lacks permission to access channel " + destination + " (missing access: lacks View Channel/Send Messages permission)";
                            } else {
                                diagnostic = "Discord REST channel lookup failed for channel " + destination + ": " + ere.getMessage();
                            }
                        } else {
                            diagnostic = "Discord channel " + destination + " could not be resolved: " + root.getMessage();
                        }
                        LOGGER.error("discord.channel_resolution_failed destination={} guildId={}: {}", destination, (guild != null ? guild.getId() : guildId), diagnostic);
                        throw new IllegalStateException(diagnostic);
                    }

                    int typeId = data.getInt("type", -1);
                    ChannelType type = ChannelType.fromId(typeId);
                    String channelName = data.getString("name", destination);
                    long fetchedGuildId = data.getUnsignedLong("guild_id", 0L);

                    if (guild != null && fetchedGuildId != 0 && fetchedGuildId != guild.getIdLong()) {
                        String diagnostic = "Discord guild found (" + guild.getId() + ") but channel ID " + destination + " was not found in it (belongs to guild " + fetchedGuildId + ")";
                        LOGGER.error("discord.channel_resolution_failed destination={} guildId={}: {}", destination, guild.getId(), diagnostic);
                        throw new IllegalStateException(diagnostic);
                    }

                    if (type != ChannelType.TEXT && type != ChannelType.NEWS) {
                        String diagnostic = "Discord channel '" + channelName + "' (" + destination + ") found but wrong type (expected standard text or announcement channel, but found " + type + ")";
                        LOGGER.error("discord.channel_resolution_failed destination={} guildId={}: {}", destination, (guild != null ? guild.getId() : guildId), diagnostic);
                        throw new IllegalStateException(diagnostic);
                    }

                    GuildChannel built = null;
                    if (guild instanceof GuildImpl guildImpl && jda instanceof JDAImpl jdaImpl) {
                        built = jdaImpl.getEntityBuilder().createGuildChannel(guildImpl, data);
                    } else if (guild != null) {
                        built = guild.getGuildChannelById(destination);
                    }

                    if (built instanceof StandardGuildMessageChannel stdChannel) {
                        Member selfMember = guild != null ? guild.getSelfMember() : null;
                        boolean hasView = selfMember == null || selfMember.hasPermission(stdChannel, Permission.VIEW_CHANNEL);
                        boolean hasSend = selfMember == null || selfMember.hasPermission(stdChannel, Permission.MESSAGE_SEND);
                        if (!hasView || !hasSend) {
                            String missingPerms = (!hasView && !hasSend) ? "View Channel and Send Messages permissions"
                                    : (!hasView ? "View Channel permission" : "Send Messages permission");
                            String diagnostic = "Discord bot found channel '" + stdChannel.getName() + "' (" + destination + ") but lacks " + missingPerms + " there";
                            LOGGER.error("discord.channel_resolution_failed destination={} guildId={}: {}", destination, (guild != null ? guild.getId() : guildId), diagnostic);
                            throw new IllegalStateException(diagnostic);
                        }
                        return stdChannel;
                    }

                    if (built != null) {
                        String diagnostic = "Discord channel '" + built.getName() + "' (" + destination + ") found but wrong type (expected standard text or announcement channel, but found " + built.getType() + ")";
                        LOGGER.error("discord.channel_resolution_failed destination={} guildId={}: {}", destination, (guild != null ? guild.getId() : guildId), diagnostic);
                        throw new IllegalStateException(diagnostic);
                    }

                    throw new IllegalStateException("Discord channel " + destination + " retrieved via REST but could not be instantiated");
                });
    }

    private CompletableFuture<? extends MessageChannel> validateAndReturnGuildChannel(GuildChannel channel, String destination) {
        if (!(channel instanceof StandardGuildMessageChannel msgChannel)) {
            String errorMsg = "Discord channel '" + channel.getName() + "' (" + destination + ") found but wrong type (expected standard text or announcement channel, but found " + channel.getType() + ")";
            LOGGER.error("discord.channel_resolution_failed destination={} guildId={}: {}", destination, channel.getGuild().getId(), errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }

        Member selfMember = channel.getGuild().getSelfMember();
        boolean hasView = selfMember == null || selfMember.hasPermission(channel, Permission.VIEW_CHANNEL);
        boolean hasSend = selfMember == null || selfMember.hasPermission(channel, Permission.MESSAGE_SEND);
        if (!hasView || !hasSend) {
            String missingPerms;
            if (!hasView && !hasSend) {
                missingPerms = "View Channel and Send Messages permissions";
            } else if (!hasView) {
                missingPerms = "View Channel permission";
            } else {
                missingPerms = "Send Messages permission";
            }
            String errorMsg = "Discord bot found channel '" + channel.getName() + "' (" + destination + ") but lacks " + missingPerms + " there";
            LOGGER.error("discord.channel_resolution_failed destination={} guildId={}: {}", destination, channel.getGuild().getId(), errorMsg);
            return CompletableFuture.failedFuture(new IllegalStateException(errorMsg));
        }
        return CompletableFuture.completedFuture(msgChannel);
    }

    private Guild findConfiguredGuild() {
        if (guildId != null && !guildId.isBlank()) {
            return jda.getGuildById(guildId);
        }
        if (!jda.getGuilds().isEmpty()) {
            return jda.getGuilds().get(0);
        }
        return null;
    }

    private static Button[] actionButtons(UUID requestId, RequestStatus status) {
        return DiscordApprovalInterface.actionButtons(requestId, status);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }
}
