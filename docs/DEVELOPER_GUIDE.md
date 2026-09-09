# Whitelist Request Mod — Developer Guide

> **For**: Ionut (zkamper) — second developer onboarding
> **From**: Vlad (DurdeuVlad)
> **Repo**: https://github.com/DurdeuVlad/GatehouseMC (private)
> **Date**: September 2026

---

## 1. What this mod does

A **server-side-only Fabric mod** for Minecraft 1.21.1 (Java 21). When a player tries to join an `online-mode=false` server with `white-list=true` and gets rejected by the vanilla whitelist, the mod:

1. **Catches the rejection** via a Mixin into `PlayerManager#checkCanJoin`.
2. **Creates a durable whitelist request** in SQLite (username + offline UUID + status).
3. **Notifies admins** via Discord or Telegram (configurable) with inline Approve/Deny/Block buttons.
4. **On approval**, adds the player to Minecraft's vanilla whitelist and they can reconnect.
5. **Survives restarts and crashes** — all state is in SQLite with crash-aware recovery.

The mod does **not** replace vanilla whitelist enforcement. It sits on top of it.

---

## 2. Quick local test (no Discord/Telegram needed)

### Prerequisites

- **JDK 21** (`java -version` must show 21.x)
- A Minecraft 1.21.1 client (vanilla or any loader — the mod is server-side only)

### Steps

1. **Get the build jar** — `whitelist-request-1.0.0.jar` (~14 MB, includes nested SQLite + JDA deps)

2. **Set up a Fabric server**:
   ```
   server/
   ├── server.jar              (vanilla 1.21.1 server jar)
   ├── fabric-loader jars      (Fabric Loader 0.19.5 + libraries)
   ├── server.properties       (see below)
   ├── eula.txt                (eula=true)
   └── mods/
       ├── fabric-api-0.116.17+1.21.1.jar
       └── whitelist-request-1.0.0.jar
   ```

3. **server.properties** (key settings):
   ```properties
   online-mode=false
   white-list=true
   server-port=25565
   rcon.port=25575
   enable-rcon=true
   rcon.password=test
   ```

4. **Start the server** (Fabric KnotServer with classpath):
   ```powershell
   java -Xmx2G -cp "server.jar;<fabric-libs>" net.fabricmc.loader.impl.launch.knot.KnotServer nogui
   ```

5. **Join from your Minecraft client** with any username not on the whitelist. You'll see:
   ```
   You are not whitelisted on this server. A whitelist request has been queued
   automatically. Player: <yourname>. Ask a server administrator to approve the
   request, then reconnect.
   ```

6. **Approve via RCON** (or in-game console):
   ```
   wlreq list
   wlreq approve <full-uuid-from-list>
   ```
   Then reconnect — you'll get in.

### All commands

| Command | Description |
|---------|-------------|
| `wlreq list [pending\|approved\|denied\|blocked]` | List requests |
| `wlreq show <request-id-or-username>` | Show request details |
| `wlreq approve <request-id-or-username> [reason]` | Approve (adds to vanilla whitelist) |
| `wlreq deny <request-id-or-username> [reason]` | Deny request |
| `wlreq block <request-id-or-username> [reason]` | Block (prevents future requests from that username) |
| `wlreq unblock <username> [reason]` | Remove block |
| `wlreq status` | Show health/queue/outbox status |
| `wlreq reload` | Reload config |

---

## 3. Configuration

The mod generates `config/whitelistrequest/config.json` on first boot:

```json
{
  "requests": {
    "denialCooldownMinutes": 1440,
    "queueCapacity": 10000,
    "commandPermissionLevel": 3
  },
  "database": {
    "path": ".\\config\\whitelistrequest\\requests.sqlite",
    "busyTimeoutMs": 5000
  },
  "routing": {
    "mode": "PRIMARY_FALLBACK",
    "providers": ["discord", "telegram"]
  },
  "discord": {
    "enabled": false,
    "token": "",
    "guildId": "",
    "channelId": "",
    "allowedUserIds": [],
    "allowedRoleIds": []
  },
  "telegram": {
    "enabled": false,
    "token": "",
    "chatId": "",
    "allowedUserIds": []
  }
}
```

### Environment variable expansion

Tokens support `${ENV_VAR}` expansion so you don't put secrets in the config file:

```json
{
  "discord": {
    "enabled": true,
    "token": "${DISCORD_BOT_TOKEN}",
    "guildId": "123456789012345678",
    "channelId": "987654321098765432",
    "allowedUserIds": ["111111111111111111"],
    "allowedRoleIds": []
  }
}
```

Then set the env var before starting the server:
```powershell
$env:DISCORD_BOT_TOKEN = "your-bot-token-here"
```

### Routing modes

| Mode | Behavior |
|------|----------|
| `PRIMARY_FALLBACK` | Try Discord first, fall back to Telegram if Discord fails |
| `FANOUT` | Send to all enabled providers simultaneously |

---

## 4. Discord setup (production)

1. Create a bot at https://discord.com/developers/applications
2. Enable **Message Content Intent** (Privileged Gateway Intents)
3. Invite the bot to your server with `applications.commands` + `bot` scopes
4. Get the **Guild ID** (right-click server → Copy ID, needs Developer Mode)
5. Get the **Channel ID** (right-click channel → Copy ID)
6. Get your **User ID** (right-click yourself → Copy ID)
7. Put them in `config.json` or use env vars as shown above

The bot will post whitelist requests to the configured channel with three buttons: **Approve**, **Deny**, **Block**. Only users in `allowedUserIds` (or roles in `allowedRoleIds`) can use the buttons.

---

## 5. Telegram setup (testing)

1. Create a bot via [@BotFather](https://t.me/BotFather) → `/newbot` → get the token
2. Send any message to your bot (this creates a chat)
3. Visit `https://api.telegram.org/bot<TOKEN>/getUpdates` to find:
   - Your **user ID** (the `from.id` field)
   - The **chat ID** (the `chat.id` field — same as your user ID for DMs)
4. Configure:
   ```json
   {
     "telegram": {
       "enabled": true,
       "token": "${TELEGRAM_BOT_TOKEN}",
       "chatId": "your-chat-id",
       "allowedUserIds": ["your-user-id"]
     }
   }
   ```
5. Set the env var: `$env:TELEGRAM_BOT_TOKEN = "..."`

---

## 6. Architecture (hexagonal / ports-and-adapters)

```
src/main/java/com/gatehousemc/whitelistrequest/
├── domain/              # Pure domain model (no platform imports)
│   ├── PlayerIdentity   #   username + offline UUID (validated)
│   ├── WhitelistRequest #   request state machine
│   ├── DecisionAction   #   APPROVE / DENY / BLOCK
│   └── ...
├── application/         # Use cases / orchestration
│   ├── WhitelistRequestService  # Request creation + deduplication
│   ├── DecisionService          # Terminal decisions + crash-aware approval
│   ├── ApprovalInterfaceRouter  # Provider routing (fallback/fanout)
│   └── OutboxWorker             # Async delivery to providers
├── port/                # Interfaces (no implementation)
│   ├── WorkflowRepository       # Persistence port
│   ├── ApprovalInterface        # Provider port (Discord/Telegram)
│   ├── VanillaWhitelistPort     # MC whitelist mutation port
│   ├── ClockPort                # Time abstraction
│   └── StorageException         # Honest storage failures
├── integration/         # Provider adapters
│   ├── common/                  # Shared rendering + callback parsing
│   ├── discord/                 # JDA-based Discord adapter
│   └── telegram/                # HttpClient-based Telegram adapter
├── persistence/sqlite/  # SQLite adapter
│   ├── SqliteWorkflowRepository # Thin facade
│   ├── RequestSqlMapper         #   requests table SQL
│   ├── OutboxSqlMapper          #   outbox table SQL
│   ├── BlockStore               #   identity_blocks table SQL
│   ├── AuditStore               #   audit_log table SQL
│   └── TransactionHelper        #   connection/transaction management
├── platform/fabric/     # Minecraft/Fabric adapter
│   ├── WhitelistRequestMod     # Entrypoint + Mixin hook
│   ├── FabricRuntime           # Server lifecycle
│   └── command/                # wlreq commands
└── config/              # Config loading + env expansion
```

### Key invariants (do not violate)

1. **Pure core**: `domain/` and `application/` must NOT import `net.minecraft.*`, `net.fabricmc.*`, JDA, Telegram, or JDBC classes. `ArchitectureTest` enforces this.
2. **Canonical owners**: One owner per decision — `WhitelistRequestService` for requests, `DecisionService` for decisions, `VanillaWhitelistPort` for whitelist mutation.
3. **No blocking I/O on MC thread**: JDBC, Discord, Telegram all run on dedicated threads. Only vanilla whitelist mutation runs on the server thread.
4. **Crash-aware approval**: `PENDING → RESOLVING → APPROVED` with startup recovery. Never simplify this.
5. **Vanilla whitelist is authoritative**: The mod doesn't replace whitelist enforcement, it feeds into it.

---

## 7. Building from source

```powershell
git clone https://github.com/DurdeuVlad/GatehouseMC.git
cd GatehouseMC
.\gradlew.ps1 clean test build
```

Output jar: `build/libs/whitelist-request-1.0.0.jar`

Tests: 61 tests, 0 failures (as of this writing).

---

## 8. Git workflow

- **main** is protected — you need a PR + Vlad's approval to merge
- Branch naming: `feat/*`, `fix/*`, `docs/*`, `chore/*`
- Commit style: [Conventional Commits](https://www.conventionalcommits.org/)
- See `AGENTS.md` for the full engineering contract

### To contribute

```powershell
git checkout -b feat/your-feature
# make changes
git add -A
git commit -m "feat(scope): description"
git push origin feat/your-feature
# open PR on GitHub
```

Vlad reviews and merges. Don't push directly to main.

---

## 9. Key files to read first

| File | What it tells you |
|------|-------------------|
| `AGENTS.md` | Engineering contract, invariants, authority order |
| `WHITELIST_REQUEST_SPEC.md` | Product behavior and acceptance criteria |
| `docs/ARCHITECTURE.md` | Package boundaries, state machines, threading |
| `docs/DECISION.md` | Accepted/rejected architectural decisions |
| `docs/TESTING.md` | Required proof and E2E scenarios |
| `README.md` | User-facing documentation |

Read `AGENTS.md` first. It's the constitution.
