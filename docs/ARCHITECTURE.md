# Architecture — Whitelist Request Mod

This document defines the implementation blueprint for the Minecraft 1.21.1 Fabric release. Product semantics live in `../WHITELIST_REQUEST_SPEC.md`; accepted rationale lives in `DECISION.md`.

---

## 1. System shape

The architecture is hexagonal / ports-and-adapters:

```text
                            ┌────────────────────────┐
                            │      Core Domain       │
                            │ requests / decisions   │
                            └───────────▲────────────┘
                                        │
                            ┌───────────┴────────────┐
                            │ Application Services  │
                            │ Request / Decision /  │
                            │ Routing / Recovery    │
                            └───────────▲────────────┘
                                        │ ports
         ┌──────────────────────────────┼──────────────────────────────┐
         │                              │                              │
         ▼                              ▼                              ▼
┌────────────────┐             ┌─────────────────┐          ┌────────────────────┐
│ Fabric adapter │             │ SQLite adapter  │          │ Approval interfaces │
│ login/commands │             │ repo + outbox   │          │ Discord / Telegram │
│ vanilla list   │             │ audit/migrate   │          │ future adapters     │
└────────────────┘             └─────────────────┘          └────────────────────┘
```

Dependency direction always points inward.

---

## 2. Recommended package layout

A single Gradle module is acceptable for v1 if package boundaries are strict. Do not create a multi-module build solely for aesthetics.

```text
src/main/java/<base>/whitelistrequest/
├── domain/
│   ├── WhitelistRequest.java
│   ├── RequestStatus.java
│   ├── PlayerIdentity.java
│   ├── DecisionAction.java
│   ├── AdminPrincipal.java
│   ├── Publication.java
│   └── audit/...
│
├── application/
│   ├── WhitelistRequestService.java
│   ├── DecisionService.java
│   ├── ApprovalInterfaceRouter.java
│   ├── ApprovalRecoveryService.java
│   ├── RequestAdmissionCache.java
│   └── worker/...
│
├── port/
│   ├── WhitelistRequestRepository.java
│   ├── OutboxRepository.java
│   ├── AuditRepository.java
│   ├── VanillaWhitelistPort.java
│   ├── ServerSchedulerPort.java
│   ├── ApprovalInterface.java
│   └── ClockPort.java
│
├── persistence/sqlite/
│   ├── SqliteDatabase.java
│   ├── SqliteWhitelistRequestRepository.java
│   ├── SqliteOutboxRepository.java
│   ├── SqliteAuditRepository.java
│   └── MigrationRunner.java
│
├── platform/fabric/
│   ├── WhitelistRequestMod.java
│   ├── FabricVanillaWhitelistAdapter.java
│   ├── FabricServerScheduler.java
│   ├── command/WhitelistRequestCommands.java
│   └── mixin/PlayerManagerMixin.java
│
├── integration/discord/
│   ├── DiscordApprovalInterface.java
│   ├── DiscordRenderer.java
│   ├── DiscordAuthorization.java
│   └── DiscordInteractionListener.java
│
├── integration/telegram/
│   ├── TelegramApprovalInterface.java
│   ├── TelegramBotApiClient.java
│   ├── TelegramPoller.java
│   ├── TelegramRenderer.java
│   └── TelegramAuthorization.java
│
└── config/
    ├── ModConfig.java
    ├── ConfigLoader.java
    ├── EnvExpander.java
    └── ConfigValidator.java
```

Pure packages (`domain`, `application`, `port`) must not import Minecraft, Fabric, JDA, or concrete JDBC-driver classes.

---

## 3. Core domain model

### 3.1 `PlayerIdentity`

Pure value object:

```java
record PlayerIdentity(
    UUID offlineUuid,
    String exactUsername,
    String normalizedUsername
) {}
```

Normalization:

```java
normalizedUsername = exactUsername.toLowerCase(Locale.ROOT);
```

The UUID must be the exact UUID Minecraft supplied in the rejected `GameProfile`; do not independently re-generate it for the primary workflow if the profile already exists.

### 3.2 `WhitelistRequest`

Conceptual fields:

```text
id                    UUID
normalizedUsername    String
requestedUsername     String
requestedOfflineUuid  UUID
status                RequestStatus
createdAt             Instant
updatedAt             Instant
firstAttemptAt        Instant
lastAttemptAt         Instant
attemptCount          long
resolvedAt            Instant?
resolvedBy            AdminPrincipal?
resolutionReason      String?
resolvingAction       DecisionAction?
resolvingToken        UUID?
```

### 3.3 Request status

```java
enum RequestStatus {
    PENDING,
    RESOLVING,  // transient internal status, v1 used for approval
    APPROVED,
    DENIED,
    BLOCKED
}
```

### 3.4 Decision action

```java
enum DecisionAction {
    APPROVE,
    DENY,
    BLOCK
}
```

### 3.5 Admin principal

```java
record AdminPrincipal(
    String provider,          // minecraft, discord, telegram
    String externalId,        // stable ID / console marker
    String displayName
) {}
```

Authorization happens in the adapter; core receives an already-authenticated principal.

---

## 4. Login interception design

### 4.1 Minecraft 1.21.1 reference

Yarn 1.21.1 exposes:

```text
net.minecraft.server.PlayerManager
  @Nullable Text checkCanJoin(SocketAddress address, GameProfile profile)
```

with intermediary method `method_14586`.

It also exposes:

```text
boolean isWhitelisted(GameProfile profile)
Whitelist getWhitelist()
```

See `RESEARCH.md` for source URLs.

### 4.2 Preferred Mixin approach

Use a **read-mostly return hook** into `PlayerManager#checkCanJoin` rather than replacing the whitelist call.

Preferred shape:

```java
@Inject(method = "checkCanJoin", at = @At("RETURN"), cancellable = true, require = 1)
private void wlreq$afterCheckCanJoin(
    SocketAddress address,
    GameProfile profile,
    CallbackInfoReturnable<Text> cir
) {
    Text result = cir.getReturnValue();
    if (!isVanillaWhitelistDenial(result)) {
        return;
    }

    PlayerIdentity identity = toIdentity(profile);
    AdmissionSnapshot snapshot = admissionFacade.recordAttemptNonBlocking(identity);
    cir.setReturnValue(renderKick(identity, snapshot));
}
```

`isVanillaWhitelistDenial` should identify the translatable content key:

```text
multiplayer.disconnect.not_whitelisted
```

not compare rendered English text.

Use the 1.21.1 `Text` content API after verifying the exact class names in the Loom workspace. The implementation must be tested against the actual vanilla return value.

### Why return hook

- observes vanilla result instead of reimplementing ban/full/whitelist ordering;
- does not redirect `isWhitelisted`, reducing compatibility conflicts;
- leaves vanilla enforcement unchanged;
- can replace only the player-facing text while keeping denial semantics.

### Compatibility fallback

If live testing proves another common mod rewrites the returned `Text` before this hook and therefore hides the whitelist translation key, investigate a narrower injection around the whitelist branch. Any change to the hook strategy must update `DECISION.md` and add regression E2E coverage.

Do not use a generic disconnect listener as the primary signal.

---

## 5. Admission cache and non-blocking login path

The login hook must never query SQLite.

`RequestAdmissionCache` provides a small immutable snapshot keyed by normalized username:

```text
NONE
PENDING
DENIED_UNTIL <instant>
BLOCKED
DEGRADED
```

Flow:

```text
PlayerManagerMixin
      │
      ├─ read in-memory cache
      ├─ offer LoginAttemptCommand to bounded queue
      └─ return custom Text immediately
```

The request worker processes the queue asynchronously and performs transactional deduplication.

Use a bounded queue. On saturation:

- never block the Minecraft thread;
- emit rate-limited error telemetry;
- return a degraded/request-unavailable message;
- do not claim persistence succeeded.

The worker updates the cache after successful state changes.

---

## 6. Request creation transaction

Input:

```text
LoginAttemptCommand(identity, observedAt)
```

Worker algorithm:

1. Check `identity_blocks(normalized_name)`.
2. If blocked: append audit/attempt metric if desired; update cache `BLOCKED`; stop.
3. Query active request for normalized name (`PENDING` or `RESOLVING`).
4. If active exists:
   - increment `attempt_count`;
   - set `last_attempt_at`/`updated_at`;
   - record case-variant audit if exact profile differs;
   - do **not** replace the request's original exact username/UUID.
5. Else check latest denial time.
6. If denial cooldown active: update cache `DENIED_UNTIL`; stop.
7. Else create new `PENDING` request and outbox `REQUEST_CREATED` event in the same transaction.
8. Commit.
9. Update cache.

A partial unique index provides a final database guard against duplicate active requests.

---

## 7. Decision service

All decision origins call:

```java
DecisionResult decide(
    UUID requestId,
    DecisionAction action,
    AdminPrincipal actor,
    Optional<String> reason
);
```

### 7.1 Deny

Single DB transaction:

```text
UPDATE request
SET status=DENIED, resolution fields...
WHERE id=? AND status=PENDING
```

If affected rows = 0, return already resolved/resolving/not found.

Insert audit and outbox `REQUEST_RESOLVED` in the same transaction.

### 7.2 Block

Single DB transaction:

- CAS `PENDING -> BLOCKED`;
- upsert `identity_blocks(normalized_name, ...)`;
- write audit;
- write `REQUEST_RESOLVED` outbox.

### 7.3 Approve — two-system workflow

Approval must prevent contradictory races while calling a Minecraft-owned side effect.

#### Step A — claim

DB transaction:

```text
PENDING -> RESOLVING
resolving_action = APPROVE
resolving_token = random UUID
actor/reason recorded as pending resolution metadata
```

CAS means only one action wins.

#### Step B — schedule vanilla whitelist mutation

On a worker thread call `ServerSchedulerPort.submit(...)` and wait **there**, not on Minecraft's thread.

Server-thread task:

```text
VanillaWhitelistPort.addExactProfile(identity)
```

The adapter constructs/uses the exact `GameProfile` and Minecraft's `Whitelist` / `WhitelistEntry` APIs. Verify whether `Whitelist.add` already persists or whether `save()` is additionally required against the pinned 1.21.1 implementation. Do not write `whitelist.json` manually.

#### Step C — finalize

On whitelist success:

```text
RESOLVING(token) -> APPROVED
```

and write audit/outbox.

On failure:

```text
RESOLVING(token) -> PENDING
```

write failure audit and return error to adapter.

### 7.4 Startup recovery

After Minecraft server state and SQLite are ready, find `RESOLVING` requests.

For `APPROVE`:

- schedule a server-thread `isWhitelisted(exactProfile)` check;
- if true: finalize `APPROVED`;
- if false: reset to `PENDING` and audit recovery.

No request may remain indefinitely stuck in `RESOLVING` after a clean startup.

---

## 8. Vanilla whitelist adapter

Port:

```java
public interface VanillaWhitelistPort {
    CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity);
    CompletableFuture<Void> add(PlayerIdentity identity);
}
```

The Fabric implementation must ensure Minecraft calls happen on the server thread.

Reference 1.21.1 classes:

- `PlayerManager#getWhitelist()`
- `Whitelist#isAllowed(GameProfile)`
- `WhitelistEntry(GameProfile)`
- inherited `ServerConfigList#add(...)`
- `ServerConfigList#save()` if required by the verified implementation

Do not directly edit `whitelist.json` with Gson/file I/O.

---

## 9. Persistence model

SQLite schema should be migration-driven. Suggested v1 schema:

```sql
CREATE TABLE schema_migrations (
    version INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
);

CREATE TABLE whitelist_requests (
    id TEXT PRIMARY KEY,
    normalized_name TEXT NOT NULL,
    requested_name TEXT NOT NULL,
    requested_uuid TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    first_attempt_at INTEGER NOT NULL,
    last_attempt_at INTEGER NOT NULL,
    attempt_count INTEGER NOT NULL,

    resolved_at INTEGER,
    resolved_by_provider TEXT,
    resolved_by_external_id TEXT,
    resolved_by_display_name TEXT,
    resolution_reason TEXT,

    resolving_action TEXT,
    resolving_token TEXT
);

CREATE UNIQUE INDEX uq_whitelist_request_active_normalized
ON whitelist_requests(normalized_name)
WHERE status IN ('PENDING', 'RESOLVING');

CREATE INDEX ix_whitelist_request_status_updated
ON whitelist_requests(status, updated_at DESC);

CREATE INDEX ix_whitelist_request_normalized_history
ON whitelist_requests(normalized_name, created_at DESC);

CREATE TABLE identity_blocks (
    normalized_name TEXT PRIMARY KEY,
    source_request_id TEXT,
    blocked_at INTEGER NOT NULL,
    blocked_by_provider TEXT NOT NULL,
    blocked_by_external_id TEXT NOT NULL,
    blocked_by_display_name TEXT,
    reason TEXT,
    FOREIGN KEY(source_request_id) REFERENCES whitelist_requests(id)
);

CREATE TABLE request_publications (
    request_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    external_container_id TEXT,
    external_message_id TEXT,
    state TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_error TEXT,
    PRIMARY KEY (request_id, provider),
    FOREIGN KEY(request_id) REFERENCES whitelist_requests(id)
);

CREATE TABLE integration_outbox (
    id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    state TEXT NOT NULL,
    attempts INTEGER NOT NULL,
    available_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_error TEXT
);

CREATE INDEX ix_outbox_ready
ON integration_outbox(state, available_at);

CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id TEXT,
    event_type TEXT NOT NULL,
    actor_provider TEXT,
    actor_external_id TEXT,
    actor_display_name TEXT,
    details_json TEXT,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(request_id) REFERENCES whitelist_requests(id)
);
```

Use epoch milliseconds consistently or an explicitly documented time encoding.

### SQLite operational settings

At connection setup, evaluate and test:

```sql
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = <configured>;
PRAGMA journal_mode = WAL;
```

WAL is recommended for resilience/concurrency but must be proven on the supported filesystem/runtime. Database access is serialized through the dedicated persistence worker in v1, so high concurrency is not required.

---

## 10. Outbox and provider publication

Outbound provider operations are driven from a persistent outbox.

Event types:

```text
REQUEST_CREATED
REQUEST_UPDATED
REQUEST_RESOLVED
BLOCK_REMOVED
```

The outbox worker:

1. fetches ready events;
2. calls router/provider asynchronously;
3. records/updates `request_publications`;
4. marks event complete or schedules retry with bounded exponential backoff + jitter;
5. rate-limits repeated errors.

Provider sends must be idempotent where possible. The unique `(request_id, provider)` publication key prevents duplicate logical publications after restart/retry.

For a `REQUEST_RESOLVED` event, update every existing publication for that request, independent of which provider originated the decision.

---

## 11. Approval interface SPI

Core-facing contract:

```java
public interface ApprovalInterface {
    String id();
    ProviderHealth health();
    CompletionStage<PublicationReceipt> publish(RequestView request);
    CompletionStage<Void> update(PublicationRef ref, RequestView request);
    void start();
    void stop();
}
```

The interface must not expose JDA/Telegram types.

### Router

```java
enum RoutingMode {
    PRIMARY_FALLBACK,
    FANOUT,
    FIRST_SUCCESS
}
```

Default provider order:

```text
discord priority 100
telegram priority 50
```

### Provider health

Suggested states:

```text
STARTING
HEALTHY
DEGRADED
UNAVAILABLE
STOPPED
```

Routing must treat explicit send failure as a reason to try fallback even if the provider was recently considered healthy.

---

## 12. Discord adapter

Use JDA as the gateway/REST client. The initial researched pin is listed in `RESEARCH.md`; pin an exact version in Gradle.

Responsibilities:

- connect/start/stop JDA;
- publish request message;
- create Approve/Deny/Block buttons;
- authorize interaction by stable user/role IDs;
- map event → `AdminPrincipal` + `DecisionAction`;
- call `DecisionService` through application facade;
- acknowledge interaction promptly;
- render success/already-resolved/error response;
- update publication on core state change.

Do not enable voice/audio modules. Do not enable `MESSAGE_CONTENT` unless an explicitly accepted feature later requires it.

Do not rely on Discord display names for authorization.

---

## 13. Telegram adapter

Use Java 21 `HttpClient` against the official Telegram Bot API rather than adding a Telegram wrapper dependency in v1.

### Outbound methods

At minimum:

- `sendMessage`
- `editMessageText` and/or `editMessageReplyMarkup`
- `answerCallbackQuery`

### Inbound

Use long polling (`getUpdates`) on a dedicated thread/executor.

Maintain the offset as:

```text
lastProcessedUpdateId + 1
```

so updates are confirmed correctly.

Use `allowed_updates` to limit traffic to callback queries (and commands only if later needed).

Authorization keys:

- callback sender user ID;
- configured target chat/supergroup ID.

Telegram callback data has a small size limit; the action + canonical request UUID fits. Validate before sending.

---

## 14. Configuration architecture

`ConfigLoader`:

1. read JSON from `config/whitelistrequest/config.json`;
2. expand `${ENV_VAR}` placeholders in memory;
3. validate all fields;
4. produce immutable `ModConfig`;
5. redact secrets in `toString`/diagnostics.

Provider config validation errors disable that provider and surface status, rather than crashing Minecraft unless a core-required config (e.g. database path) is unusable.

`/wlreq reload` should restart affected providers safely. Do not change database path on live reload.

---

## 15. Server lifecycle

Recommended order:

### Mod initialization

- register config/lifecycle listeners;
- register commands;
- Mixin is already applied by loader.

### Server starting/started

1. load + validate config;
2. initialize SQLite and migrations;
3. initialize repositories/workers;
4. load admission cache;
5. run interrupted-resolution recovery when `PlayerManager` is available;
6. start outbox worker;
7. start enabled providers;
8. mark service healthy.

### Server stopping

1. set admission facade to stopping/degraded (no new durable claim messages);
2. stop provider input/polling;
3. stop outbox scheduling;
4. drain request queue for bounded timeout;
5. close DB;
6. release executors.

Do not hang server shutdown indefinitely waiting for Discord/Telegram.

---

## 16. Logging and observability

Use structured, searchable log messages with stable event names where practical.

Useful events:

```text
request.created
request.attempt.updated
request.denied.cooldown
request.blocked
request.decision.claimed
request.approved
request.approval.failed
request.recovered
provider.started
provider.publish.failed
provider.fallback.used
outbox.retry
storage.degraded
```

Never log provider tokens. Avoid logging full callback payloads if they may include sensitive metadata.

`/wlreq status` should report:

- service health;
- DB health/path (not credentials; SQLite only);
- request queue depth;
- outbox ready/retrying counts;
- provider health;
- routing mode;
- Minecraft mode warnings (`online-mode`, whitelist enabled).

---

## 17. Build/dependency packaging

Initial baseline from HeapHammer's current 1.21.1 trunk:

```text
minecraft_version=1.21.1
java_version=21
loader_version=0.19.5
fabric_api_version=0.116.17+1.21.1
loom_version=1.17-SNAPSHOT
```

The **Minecraft target is fixed** for v1. Loader/API/Loom may be updated only if necessary for resolution/security/compatibility, with exact versions pinned and the change recorded in `DECISION.md` or commit notes.

Runtime dependencies expected:

- Fabric API
- JDA (Discord)
- sqlite-jdbc
- Gson (may use the compatible runtime/library already supplied if explicitly declared and tested; avoid accidental undeclared dependency assumptions)

The final distributable must include/nest any non-Fabric runtime libraries it requires. A clean-server E2E artifact test is mandatory.

---

## 18. Architectural test rules

Add automated architecture checks if practical (e.g. ArchUnit or package scan) proving:

- core packages do not import Minecraft/Fabric/JDA;
- Discord package does not import `net.minecraft.*`;
- Telegram package does not import `net.minecraft.*`;
- only Fabric adapter calls Minecraft whitelist APIs.

The tests are guardrails, not substitutes for code review/live testing.
