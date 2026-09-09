# Changelog

All notable changes will be documented here.

## 1.0.0

### Core workflow

- Durable SQLite-backed whitelist request workflow with automatic request creation on vanilla whitelist rejection.
- One deduplicated pending request per normalized username; repeat attempts increment count and update last-attempt time.
- Case-variant usernames share a normalized workflow key without replacing the original exact profile.
- Blocked identities create no new requests; denial cooldown is durable.
- Lifecycle states: PENDING, RESOLVING, APPROVED, DENIED, BLOCKED with enforced legal transitions and timestamp ordering.
- Crash-aware approval: PENDING -> RESOLVING -> vanilla whitelist mutation -> APPROVED, with startup recovery for interrupted approvals.
- Compare-and-set decision semantics ensure exactly one winner for concurrent approve/deny/block.

### Fabric integration

- Exact Mixin hook on `PlayerManager#checkCanJoin` return value, verified against Minecraft 1.21.1 mappings.
- Only `multiplayer.disconnect.not_whitelisted` rejections create requests; bans, IP bans, and other rejections are passed through.
- Vanilla `Whitelist.add(new WhitelistEntry(profile))` remains the authoritative mutation path.
- Server-thread scheduling for Minecraft-owned state; all SQLite/provider work runs off the server thread.
- Player-facing messages distinguish first request, pending, denied, blocked, and degraded states.

### Commands

- `/wlreq` command tree: list, show, approve, deny, block, unblock, status, reload.
- All command SQLite queries dispatched to a dedicated off-thread executor; feedback posted back on the server thread.
- Permission-gated; degraded-mode guard prevents NPE.

### Provider integrations

- Discord adapter via JDA with button-based approve/deny/block interactions.
- Telegram adapter via Java 21 HttpClient with inline keyboard callback interactions.
- Authorization based on stable user/role/guild IDs (Discord) and user/chat IDs (Telegram).
- Routing modes: PRIMARY_FALLBACK (default), FANOUT, FIRST_SUCCESS.
- STARTING providers skipped until healthy; provider failure does not mutate request business state.
- Fake transport tests for both providers; no live credentials required for test suite.

### Reliability

- Persistent outbox with exclusive claim semantics, retry, and PROCESSING recovery on restart.
- Request creation and outbox insertion are atomic.
- Provider outage does not lose request state.
- Startup recovery runs off the server thread; runtime publication coordinated under a lock.

### Build and dependencies

- All versions pinned: Fabric Loader 0.19.5, Fabric API 0.116.17+1.21.1, Loom 1.17.20, SQLite JDBC 3.53.2.1, JDA 6.5.0, JUnit 5.12.2.
- `fabric.mod.json` pins Fabric API dependency to tested version.
- SQLite and JDA nested in the production jar for clean-server deployment.

### Documentation

- README, SECURITY, CODE_OF_CONDUCT, CONTRIBUTING, AGENTS, spec, architecture, decision, testing, and OSS docs.
- Real Fabric dedicated-server E2E proof for rejection, persistence, approval, reconnect, restart, and message states.
