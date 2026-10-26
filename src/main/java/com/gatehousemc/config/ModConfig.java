package com.gatehousemc.config;

import com.gatehousemc.port.RoutingMode;

import java.nio.file.Path;
import java.util.List;

public record ModConfig(Requests requests, Database database, Routing routing, Discord discord, Telegram telegram,
                         Minecraft minecraft, String language) {
    /** Backward-compatible constructor for 1.1 callers and tests. */
    public ModConfig(Requests requests, Database database, Routing routing, Discord discord,
                     Telegram telegram, String language) {
        this(requests, database, routing, discord, telegram,
                Minecraft.fromLegacy(requests.commandPermissionLevel()), language);
    }

    public record Minecraft(int viewPermissionLevel, int decisionPermissionLevel, int managePermissionLevel) {
        public Minecraft {
            if (viewPermissionLevel < 0 || viewPermissionLevel > 4
                    || decisionPermissionLevel < 0 || decisionPermissionLevel > 4
                    || managePermissionLevel < 0 || managePermissionLevel > 4
                    || viewPermissionLevel > decisionPermissionLevel
                    || decisionPermissionLevel > managePermissionLevel) {
                throw new IllegalArgumentException("minecraft permission levels must be ordered integers from 0 to 4");
            }
        }

        public static Minecraft fromLegacy(int decision) {
            return new Minecraft(Math.max(0, decision - 1), decision, Math.min(4, decision + 1));
        }
    }

    /** Stable provider principal. Display names are deliberately not authorization inputs. */
    public record Principal(String kind, String id, String access) {
        public Principal {
            kind = kind == null ? "" : kind.trim().toUpperCase(java.util.Locale.ROOT);
            id = id == null ? "" : id.trim();
            access = access == null ? "" : access.trim().toUpperCase(java.util.Locale.ROOT);
        }

        public boolean grants(String required) {
            int actual = rank(access);
            return actual >= rank(required);
        }

        private static int rank(String value) {
            return switch (value) {
                case "VIEW" -> 1;
                case "DECIDE" -> 2;
                case "MANAGE" -> 3;
                default -> 0;
            };
        }
    }

    public record Requests(long denialCooldownMinutes, int queueCapacity, int commandPermissionLevel,
                           int attemptCoalesceSeconds, int providerRefreshSeconds,
                           int newRequestRatePerMinute, int newRequestBurst, int maxPendingRequests,
                           int pendingStaleAfterHours, String supportMessage) {
        public Requests {
            supportMessage = supportMessage == null ? "" : supportMessage;
        }

        public Requests(long denialCooldownMinutes, int queueCapacity, int commandPermissionLevel) {
            this(denialCooldownMinutes, queueCapacity, commandPermissionLevel,
                    5, 30, 30, 10, 500, 168, "");
        }
    }
    public record Database(Path path, int busyTimeoutMs) {}
    public record Routing(RoutingMode mode, List<String> providers) {
        public Routing { providers = List.copyOf(providers); }
    }
    public record Discord(boolean enabled, String token, String guildId, String channelId, String dmUserId,
                          List<String> allowedUserIds, List<String> allowedRoleIds, List<Principal> principals) {
        public Discord(boolean enabled, String token, String guildId, String channelId,
                       List<String> allowedUserIds, List<String> allowedRoleIds) {
            this(enabled, token, guildId, channelId, "", allowedUserIds, allowedRoleIds, List.of());
        }

        public Discord(boolean enabled, String token, String guildId, String channelId, String dmUserId,
                       List<String> allowedUserIds, List<String> allowedRoleIds) {
            this(enabled, token, guildId, channelId, dmUserId, allowedUserIds, allowedRoleIds, List.of());
        }

        public Discord {
            allowedUserIds = List.copyOf(allowedUserIds);
            allowedRoleIds = List.copyOf(allowedRoleIds);
            principals = List.copyOf(principals);
        }

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
    public record Telegram(boolean enabled, String token, String chatId, List<String> allowedUserIds,
                           List<Principal> principals) {
        public Telegram(boolean enabled, String token, String chatId, List<String> allowedUserIds) {
            this(enabled, token, chatId, allowedUserIds, List.of());
        }

        public Telegram { allowedUserIds = List.copyOf(allowedUserIds); principals = List.copyOf(principals); }

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
                new Requests(1440, 2_000, 3, 5, 30, 30, 10, 500, 168, ""),
                new Database(configDir.resolve("requests.sqlite"), 5000),
                new Routing(RoutingMode.PRIMARY_FALLBACK, List.of("discord", "telegram")),
                new Discord(false, "", "", "", "", List.of(), List.of(), List.of()),
                new Telegram(false, "", "", List.of(), List.of()),
                new Minecraft(2, 3, 4),
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
