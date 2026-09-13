package com.gatehousemc.config;

import com.gatehousemc.port.RoutingMode;

import java.nio.file.Path;
import java.util.List;

public record ModConfig(Requests requests, Database database, Routing routing, Discord discord, Telegram telegram, String language) {
    public record Requests(long denialCooldownMinutes, int queueCapacity, int commandPermissionLevel) {}
    public record Database(Path path, int busyTimeoutMs) {}
    public record Routing(RoutingMode mode, List<String> providers) {
        public Routing { providers = List.copyOf(providers); }
    }
    public record Discord(boolean enabled, String token, String guildId, String channelId, String dmUserId,
                          List<String> allowedUserIds, List<String> allowedRoleIds) {
        public Discord(boolean enabled, String token, String guildId, String channelId,
                       List<String> allowedUserIds, List<String> allowedRoleIds) {
            this(enabled, token, guildId, channelId, "", allowedUserIds, allowedRoleIds);
        }

        public Discord { allowedUserIds = List.copyOf(allowedUserIds); allowedRoleIds = List.copyOf(allowedRoleIds); }

        @Override
        public String toString() {
            return "Discord{" +
                    "enabled=" + enabled +
                    ", guildId='" + guildId + '\'' +
                    ", channelId='" + channelId + '\'' +
                    ", dmUserId='" + dmUserId + '\'' +
                    ", allowedUserIds=" + allowedUserIds +
                    ", allowedRoleIds=" + allowedRoleIds +
                    '}';
        }
    }
    public record Telegram(boolean enabled, String token, String chatId, List<String> allowedUserIds) {
        public Telegram { allowedUserIds = List.copyOf(allowedUserIds); }

        @Override
        public String toString() {
            return "Telegram{" +
                    "enabled=" + enabled +
                    ", chatId='" + chatId + '\'' +
                    ", allowedUserIds=" + allowedUserIds +
                    '}';
        }
    }

    public static ModConfig defaults(Path configDir) {
        return new ModConfig(
                new Requests(1440, 10_000, 3),
                new Database(configDir.resolve("requests.sqlite"), 5000),
                new Routing(RoutingMode.PRIMARY_FALLBACK, List.of("discord", "telegram")),
                new Discord(false, "", "", "", "", List.of(), List.of()),
                new Telegram(false, "", "", List.of()),
                "en_us"
        );
    }

    public String redactedSummary() {
        return "ModConfig{" +
                "denialCooldownMinutes=" + requests.denialCooldownMinutes +
                ", queueCapacity=" + requests.queueCapacity +
                ", databasePath='" + database.path + '\'' +
                ", routingMode=" + routing.mode +
                ", providers=" + routing.providers +
                ", discordEnabled=" + discord.enabled +
                ", telegramEnabled=" + telegram.enabled +
                ", language=" + language +
                '}';
    }
}
