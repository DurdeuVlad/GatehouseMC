# Whitelist Request Mod — Product & Business Specification

> **Status:** Implementation handoff specification  
> **Working name:** Whitelist Request Mod  
> **Working mod id:** `whitelistrequest`  
> **Initial platform:** Minecraft 1.21.1, Fabric, Java 21  
> **Primary deployment:** `online-mode=false`, `white-list=true`  
> **Quality benchmark:** HeapHammer-level engineering discipline, not HeapHammer functionality.

---

## 1. Executive summary

Whitelist Request Mod is a **server-side Fabric mod for private/modded Minecraft servers running raw offline mode**.

Its purpose is simple:

> When an unwhitelisted player attempts to connect, automatically create a durable whitelist request and expose that request to administrators through Minecraft commands and pluggable approval interfaces such as Discord and Telegram.

The player must not have to:

- know their UUID,
- install an extra client-side whitelist mod,
- use Discord before connecting,
- visit a web page,
- or ask an administrator to manually type their name from memory.

The desired experience is:

```text
Player connects
    ↓
Minecraft creates the offline GameProfile
    ↓
Vanilla whitelist denies access
    ↓
Whitelist Request Mod observes that exact denial
    ↓
Request is created or existing request attempt count is updated
    ↓
Player remains kicked
    ↓
Admin receives actionable request
    ↓
Admin approves
    ↓
Mod adds the exact requested offline profile to vanilla whitelist
    ↓
Player reconnects
    ↓
Vanilla allows the join
```

This is deliberately a **workflow layer around the vanilla whitelist**, not a replacement for it.

---

## 2. Problem statement

Raw `online-mode=false` modded servers commonly use vanilla whitelisting but have poor request ergonomics:

1. A new player tries to connect.
2. Minecraft rejects them as not whitelisted.
3. The player has to contact an admin out of band.
4. The admin must identify the exact offline-mode profile spelling and add it manually.
5. Requests are easy to miss, duplicate, or forget.

Existing projects often solve adjacent problems:

- Discord account linking,
- role-based automatic whitelisting,
- proxy authentication,
- web whitelist management,
- or plugin-based join requests.

This project focuses on the missing modded-server workflow:

> **The Minecraft connection attempt itself is the request.**

---

## 3. Product positioning

### 3.1 What the mod is

- A server-side whitelist request workflow.
- A durable request/audit store.
- A bridge from vanilla whitelist rejection to admin approval interfaces.
- A pluggable approval-interface platform.
- A tool designed for raw `online-mode=false` private/modded servers.

### 3.2 What the mod is not

Version 1 is **not**:

- Mojang/Microsoft authentication.
- A claim that offline usernames are secure identities.
- A proxy.
- A Velocity forwarding layer.
- A client authentication mod.
- A replacement for `whitelist.json`.
- A web dashboard.
- A multi-server sync system.
- A ban-management replacement.
- A player IP tracking system.

---

## 4. Target environment

The initial implementation targets the same modern Minecraft baseline used by HeapHammer's current trunk:

| Component | Target |
|---|---|
| Minecraft | **1.21.1** |
| Loader | Fabric |
| Java | **21** |
| Mappings | Yarn for 1.21.1 (pin exact build in Gradle) |
| Fabric Loader/API | bootstrap from the compatible pins documented in `docs/RESEARCH.md` |

`online-mode=false` and `white-list=true` are the required production test configuration.

The mod may incidentally work in online mode, but online-mode support is not a v1 acceptance requirement.

---

## 5. Terminology

### Offline profile

The `GameProfile` Minecraft uses when the server is not authenticating the account through Mojang/Microsoft. Its UUID is deterministic from the supplied username, but it is **not proof of account ownership**.

### Normalized username

Lowercase (`Locale.ROOT`) form used for deduplication/search/spam control. The exact original username and exact UUID are always retained separately.

### Request

A durable workflow record created from a vanilla whitelist rejection.

### Approval interface

An adapter that publishes requests and/or accepts administrator decisions. Built-in v1 interfaces:

- Minecraft commands
- Discord
- Telegram

### Provider

A concrete external interface (`discord`, `telegram`, future adapters).

### Vanilla whitelist

Minecraft's normal whitelist represented by `PlayerManager` / `Whitelist` / `whitelist.json`. This remains authoritative for admission.

---

## 6. Business rules

### BR-001 — Raw offline mode is a first-class requirement

The product must work on a dedicated Fabric server with:

```properties
online-mode=false
white-list=true
```

No authentication proxy is required.

### BR-002 — Server-side only

Ordinary clients must connect without installing Whitelist Request Mod.

### BR-003 — Connection attempt creates the request

A player does not pre-register. The request originates when vanilla rejects their login **because of the whitelist**.

### BR-004 — Only whitelist denials create requests

Ban/server-full/duplicate/incompatible/generic disconnects must not create requests.

### BR-005 — Offline identity is explicitly unauthenticated

Admin interfaces must label the identity as offline/unauthenticated. Documentation must never imply that the UUID proves Microsoft/Mojang account ownership.

### BR-006 — Preserve exact profile

Store:

- exact supplied username,
- normalized username,
- exact offline UUID observed from Minecraft.

Approval adds the **exact requested profile** to vanilla whitelist.

### BR-007 — Deduplicate pending requests

There may be at most one active `PENDING`/`RESOLVING` request per normalized username.

Repeated attempts update counters/timestamps rather than producing duplicate pending requests.

Case variations must not be able to create unlimited simultaneous pending requests. The first exact profile for the active request remains the requested profile; later case variants are recorded as attempts/audit context but do not silently change the approval target.

### BR-008 — Vanilla whitelist is authoritative

The mod does not create a separate “allowed” table that bypasses vanilla admission. Approval calls the Minecraft whitelist adapter.

### BR-009 — Integrations never own business state

Discord/Telegram messages are projections of core state. Deleting a message does not delete a request. A provider outage does not erase a request.

### BR-010 — Exactly one terminal decision wins

Concurrent approve/deny/block actions use an atomic compare-and-set persistence transition.

### BR-011 — Approval is crash-aware

Approval uses a transient `RESOLVING` state while the Minecraft whitelist side effect is applied. Startup recovery resolves interrupted approval safely.

### BR-012 — Denial has a cooldown

A denied identity does not immediately create new request spam. Default denial cooldown: **24 hours** (configurable).

After cooldown, a new whitelist rejection may create a new request unless blocked.

### BR-013 — Block is an internal request block, not a vanilla ban

`BLOCKED` prevents new whitelist requests for the normalized username until an admin unblocks it. It does **not** automatically add a vanilla ban entry in v1.

### BR-014 — No network/database wait in login path

Login handling performs bounded memory/cache work and enqueues persistence asynchronously.

### BR-015 — Request persistence is independent of provider health

If Discord and Telegram are down, request creation still succeeds and outbound publication remains retryable through the outbox.

### BR-016 — Discord primary, Telegram backup by default

Default routing mode is `PRIMARY_FALLBACK`:

1. Discord is primary.
2. Telegram is fallback.
3. If the primary provider is disabled/unhealthy or publication fails, Telegram is attempted.

The configuration must also support `FANOUT`, which publishes to every enabled provider.

### BR-017 — Minecraft commands always remain available

External bot failure must not prevent an operator from listing/resolving requests from the server console or authorized Minecraft command source.

### BR-018 — No IP retention in v1

The request database must not persist the player's IP address.

### BR-019 — Secrets are never logged

Discord/Telegram tokens may be loaded from environment substitutions and must never appear in logs, exceptions, status dumps, or generated diagnostics.

### BR-020 — Manual whitelist changes are respected

The mod must never automatically remove entries from vanilla whitelist because its workflow disagrees with them. External/manual whitelist additions are allowed. Pending workflow reconciliation may be added later, but v1 must not fight vanilla/operator changes.

---

## 7. Player experience

### 7.1 First attempt

For a normal first whitelist rejection, return a custom message derived from the vanilla whitelist rejection:

```text
You are not whitelisted on this server.
A whitelist request has been queued automatically.
Player: <exact username>
Ask a server administrator to approve the request, then reconnect.
```

The message must not promise that the request is already durably persisted if the persistence worker is in a known fatal/unavailable state. In a degraded state, use:

```text
You are not whitelisted on this server.
The whitelist request service is temporarily unavailable.
Please contact a server administrator.
```

### 7.2 Pending repeat attempt

```text
Your whitelist request is still pending.
Player: <exact username>
Ask a server administrator to approve it, then reconnect.
```

### 7.3 Denied

```text
Your whitelist request was denied recently.
Please contact a server administrator if you need another review.
```

### 7.4 Blocked

```text
Whitelist requests for this username are blocked.
Please contact a server administrator.
```

### 7.5 Approved

The player is not pushed into the server mid-login. They reconnect normally and vanilla whitelist admission succeeds.

---

## 8. Request lifecycle

### 8.1 User-visible states

```text
PENDING ──approve──> APPROVED
   │
   ├──deny────────> DENIED
   │
   └──block───────> BLOCKED
```

### 8.2 Internal approval state

Approval requires a transient state:

```text
PENDING
  │ CAS
  ▼
RESOLVING (action=APPROVE)
  │
  ├─ whitelist add succeeds ─> APPROVED
  │
  └─ side effect fails ──────> PENDING + failure audit
```

Startup recovery for an interrupted `RESOLVING` approval:

- if exact requested profile is already whitelisted → finalize `APPROVED`;
- otherwise → reset to `PENDING`, record recovery audit, allow another decision.

### 8.3 Denial cooldown

A later whitelist rejection for a normalized username whose latest request is `DENIED`:

- before cooldown expires → no new request, show denied message;
- after cooldown expires → may create a new `PENDING` request.

### 8.4 Block state

Blocking also creates an `identity_blocks` record keyed by normalized username.

Unblock removes that record. The historical request remains `BLOCKED` for audit.

---

## 9. Administrator interfaces

### 9.1 Minecraft command interface

Recommended root command:

```text
/wlreq
```

Required subcommands:

```text
/wlreq list [pending|approved|denied|blocked]
/wlreq show <request-id|username>
/wlreq approve <request-id|username> [reason...]
/wlreq deny <request-id|username> [reason...]
/wlreq block <request-id|username> [reason...]
/wlreq unblock <username> [reason...]
/wlreq status
/wlreq reload
```

Requirements:

- console is allowed;
- player command source requires configurable permission level (default operator level 3);
- name lookups that are ambiguous because multiple historical requests exist must require request ID or choose the single active pending request only;
- commands call the same `DecisionService` used by bots.

### 9.2 Discord interface

Request message contains at minimum:

```text
Whitelist request
Player: Vlad
Offline UUID: <uuid>
Identity: OFFLINE / UNAUTHENTICATED
Attempts: 1
First seen: <timestamp>
Last attempt: <timestamp>
Request: <short display id>
```

Buttons:

```text
[Approve] [Deny] [Block]
```

Authorization supports:

- explicit Discord user ID allowlist;
- Discord role ID allowlist.

At least one configured allow rule must match unless an explicit “allow any guild admin” option is later added.

After resolution, edit the message to show the terminal state and actor, and disable/remove action buttons.

### 9.3 Telegram interface

Equivalent request message with inline keyboard:

```text
[✅ Approve]
[❌ Deny]
[🚫 Block]
```

Authorization supports:

- allowed Telegram user IDs;
- allowed chat/supergroup IDs.

The callback must be acknowledged with `answerCallbackQuery` and the message updated after resolution.

### 9.4 Callback/custom ID contract

Provider callback identifiers may contain only opaque operation metadata such as action + request UUID. Do not include secrets, tokens, reasons, or usernames.

Example logical form:

```text
wr:a:<request-uuid>
wr:d:<request-uuid>
wr:b:<request-uuid>
```

The adapter must re-fetch core state and re-authorize the actor on every interaction. Never trust message text or callback state as authoritative.

---

## 10. Routing strategy

The approval interface router supports:

### `PRIMARY_FALLBACK` — default

Try providers in configured priority order until publication succeeds.

Default priority:

1. Discord
2. Telegram

### `FANOUT`

Publish to every enabled provider. A decision in one provider updates all existing publications.

### `FIRST_SUCCESS`

Equivalent to primary/fallback without semantic “primary” naming; useful for future providers.

`ALL_REQUIRED` is not required for v1.

Inbound decisions always go to the same `DecisionService`, independent of routing mode.

---

## 11. Persistence expectations

SQLite is the v1 workflow store.

Durable data includes:

- request identity and timestamps;
- attempt count;
- status/resolution actor/reason;
- blocks;
- publication references;
- audit events;
- integration outbox.

The canonical schema and transactions are defined in `docs/ARCHITECTURE.md`.

The database is server-level, not player-world data. Recommended default location:

```text
config/whitelistrequest/requests.sqlite
```

---

## 12. Reliability behavior

### Discord down, Telegram up

- request persists;
- Discord publish fails/health is unavailable;
- router publishes to Telegram;
- audit/outbox records what happened.

### Discord + Telegram both down

- request persists;
- outbound event remains retryable;
- Minecraft commands still work;
- no login-thread wait for bot recovery.

### Bot reconnect/restart

- provider resumes processing outbox items;
- duplicate sends are avoided with request/provider publication uniqueness and idempotent reconciliation.

### Minecraft restart

- SQLite state loads before integrations accept actions;
- request admission cache is rebuilt;
- interrupted approval recovery runs after vanilla whitelist is available;
- integrations then start and outbox resumes.

### SQLite fatal startup error

Fail safe:

- log a clear error without secrets;
- do not pretend requests are durable;
- keep vanilla whitelist enforcement untouched;
- show degraded kick message;
- expose failure through `/wlreq status` where possible.

---

## 13. Configuration contract

Use a single human-editable JSON file for v1 to avoid an additional configuration-parser dependency:

```text
config/whitelistrequest/config.json
```

Recommended shape:

```json
{
  "requests": {
    "denialCooldownMinutes": 1440,
    "queueCapacity": 10000,
    "commandPermissionLevel": 3
  },
  "database": {
    "path": "config/whitelistrequest/requests.sqlite",
    "busyTimeoutMs": 5000
  },
  "routing": {
    "mode": "PRIMARY_FALLBACK",
    "providers": ["discord", "telegram"]
  },
  "discord": {
    "enabled": true,
    "token": "${DISCORD_TOKEN}",
    "guildId": "123456789012345678",
    "channelId": "123456789012345678",
    "allowedUserIds": [],
    "allowedRoleIds": ["123456789012345678"]
  },
  "telegram": {
    "enabled": true,
    "token": "${TELEGRAM_BOT_TOKEN}",
    "chatId": "-1001234567890",
    "allowedUserIds": ["123456789"]
  }
}
```

Requirements:

- unknown enum values fail validation with actionable errors;
- unresolved `${ENV_VAR}` secrets fail provider startup, not the whole Minecraft server;
- expanded secret values are never written back to disk;
- `/wlreq reload` may reload non-structural provider/config values; database path changes may require restart;
- config status logs redact secrets.

---

## 14. Security and trust model

### 14.1 Offline-mode warning

The server does not verify that a connecting user owns the supplied name. Therefore:

> Whitelisting `Vlad` in raw offline mode grants access to the offline profile corresponding to that supplied name, not to a cryptographically authenticated person.

This limitation is inherent in raw offline mode and must be documented prominently.

### 14.2 Admin trust

Decisions are privileged operations.

- Minecraft command source must satisfy permission checks.
- Discord user/role IDs must satisfy configured authorization.
- Telegram user/chat IDs must satisfy configured authorization.
- display names are audit metadata only; stable external IDs are the authorization key.

### 14.3 No secret data in interaction identifiers

Provider callback IDs are not trusted storage. They identify a request/action only. Core state is reloaded before applying a decision.

### 14.4 Privacy

Persist only information needed for the workflow:

- username
- offline UUID
- timestamps/counts
- admin external ID/display name/provider
- reason/audit events
- provider message identifiers

Do not persist client IPs in v1.

---

## 15. Acceptance criteria

The implementation is accepted only if the relevant automated and live-server tests in `docs/TESTING.md` prove the following.

### AC-001 — First request

Given a clean dedicated server with `online-mode=false`, `white-list=true`, and player `E2E_Alice` not whitelisted, when `E2E_Alice` attempts to connect, then:

- the client is rejected;
- exactly one `PENDING` request exists;
- exact username and offline UUID are stored;
- attempt count is `1`;
- no unrelated disconnect creates a request.

### AC-002 — Repeat request dedupe

Three more attempts from the same exact profile must keep one active request and increment attempt count to `4`.

### AC-003 — Case-spam guard

A case-variant attempt for the same normalized username does not create a second simultaneous pending request and does not silently replace the original requested exact profile.

### AC-004 — Approve from Minecraft command

Approving the pending request:

- results in `APPROVED`;
- writes the exact requested profile to vanilla whitelist;
- a subsequent connection succeeds.

### AC-005 — Approve from Discord adapter

An authorized Discord interaction produces the same core behavior as AC-004. An unauthorized user cannot resolve the request.

### AC-006 — Approve from Telegram adapter

An authorized Telegram callback produces the same core behavior as AC-004. An unauthorized user cannot resolve the request.

### AC-007 — Concurrent terminal decision

If approve and deny race, exactly one wins. The request cannot end in a contradictory state.

### AC-008 — Denial cooldown

A denied identity cannot immediately spam a fresh request; after configured cooldown a new rejection may create a new request.

### AC-009 — Block/unblock

Blocked normalized username creates no new requests. After authorized unblock, a later whitelist rejection can create a new request.

### AC-010 — Provider fallback

With Discord publication forced to fail and Telegram healthy in `PRIMARY_FALLBACK`, Telegram receives the request and the core request remains intact.

### AC-011 — Complete provider outage

With both external providers unavailable, request creation/persistence still succeeds and an outbox item remains retryable.

### AC-012 — Restart persistence

Pending/denied/blocked state survives a server restart and cache reconstruction preserves behavior.

### AC-013 — Interrupted approval recovery

A test simulating an approval crash point proves recovery behavior:

- already whitelisted → finalize approved;
- not whitelisted → safely return pending.

### AC-014 — Vanilla enforcement preserved

Removing/disabling this mod does not replace/corrupt Minecraft's native whitelist semantics. Existing whitelist entries remain normal vanilla entries.

### AC-015 — Clean artifact

A clean dedicated server containing only the pinned Fabric loader/API and the produced mod artifact starts without missing runtime dependencies.

### AC-016 — No blocking network I/O in login path

Tests/code inspection prove that request creation hook does not synchronously call JDBC, Discord, or Telegram.

---

## 16. Explicit v1 non-goals

Do not block v1 on:

- NeoForge/Forge ports;
- web dashboard;
- authentication or client keypairs;
- Microsoft OAuth;
- Velocity/proxy support;
- MySQL/PostgreSQL;
- multi-server request sharing;
- automatic Discord account linking;
- LuckPerms integration;
- IP reputation/storage;
- automatic vanilla ban entries for `BLOCKED`;
- a public REST API.

The architecture must make future adapters possible without implementing them now.

---

## 17. Future extension direction

The `ApprovalInterface` / port design should make future providers feasible:

- web dashboard
- Matrix
- Slack
- generic webhook
- REST control plane
- multi-server routing

Potential future identity providers may add authenticated proxy/client modes, but raw offline mode remains a supported identity kind and must not be retroactively mislabeled as authenticated.

---

## 18. Implementation references

Technical source links, mappings, dependency references, competitor analysis, and specific 1.21.1 notes are maintained in [`docs/RESEARCH.md`](docs/RESEARCH.md).

Implementation boundaries, schema, exact service contracts, threading model, and Mixin strategy are maintained in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
