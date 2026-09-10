# Changelog

All notable changes to **GatehouseMC** will be documented in this file.

## 1.0.1 — Compatibility and release integrity

- Fixed legacy Fabric startup by bundling the SLF4J API required by older
  servers and aligned all artifact metadata with the actual Java bytecode.
- Verified clean standalone Fabric server boot for every supported target from
  Minecraft 1.14.4 through 1.21.4.
- Release publishing now uses target-specific tags and validates the internal
  `fabric.mod.json` Minecraft and Java metadata before uploading.

## 1.0.0 — Initial Release

### GatehouseMC Brand & Identity
- Rebranded official mod name to **GatehouseMC** (mod ID `gatehousemc`).
- Introduced custom pixel art brand identity: gatehouse tower banner and iron door approval logo.
- Bundled 256×256 mod icon inside the distribution jar (`assets/gatehousemc/icon.png`).
- Added automatic configuration migration: existing servers running `config/whitelistrequest/` automatically migrate files to `config/gatehousemc/` on first startup.

### Core Workflow
- Durable SQLite-backed whitelist request workflow with automatic request creation on vanilla whitelist rejection.
- One deduplicated pending request per normalized username; repeat attempts increment attempt counters and refresh last-attempt timestamps.
- Case-variant usernames share a normalized workflow key without replacing the original exact profile.
- Blocked identities create no new requests; denial cooldown is durable.
- Lifecycle states: `PENDING`, `RESOLVING`, `APPROVED`, `DENIED`, `BLOCKED` with enforced legal transitions and timestamp ordering.
- Crash-aware approval: `PENDING` -> `RESOLVING` -> vanilla whitelist mutation -> `APPROVED`, with automatic startup recovery for interrupted approvals.
- Compare-and-set decision semantics ensure exactly one winner for concurrent approve/deny/block decisions across interfaces.

### Fabric Integration
- Exact Mixin hook on `PlayerManager#checkCanJoin` return value, verified against Minecraft 1.21.1 mappings.
- Exact denial matching: only `multiplayer.disconnect.not_whitelisted` rejections create requests; bans, IP bans, full server, and other rejections pass through untouched.
- Vanilla `Whitelist.add(new WhitelistEntry(profile))` remains the authoritative mutation path.
- Non-blocking architecture: all SQLite and bot operations run off the Minecraft server thread; only whitelist mutations run on the server thread.
- Player-facing rejection messages clearly distinguish first request, pending, denied, blocked, and degraded states.
- Internationalization (i18n): full localization support with English (`en_us`) and Romanian (`ro_ro`) language bundles.

### In-Game Commands
- Primary command tree: `/gatehouse` with backward-compatible aliases `/gh` and `/wlreq`.
- Actions supported: `list`, `show`, `approve`, `deny`, `block`, `unblock`, `undo`, `status`, `reload`.
- `/gatehouse undo <player>` allows admins to instantly reopen requests and revert approvals (removing from whitelist) or denys/blocks.
- All database queries dispatched asynchronously off the server thread; feedback rendered safely on the server thread.
- Permission-gated (default permission level 3); degraded-mode safety checks prevent NPEs.

### Provider Integrations
- **Discord**: Discord bot integration via JDA with interactive Approve, Deny, and Block button components.
- **Telegram**: Telegram bot integration via standard Java 21 `HttpClient` with inline keyboard callbacks.
- Access control based on verified administrator user/role IDs (Discord) and user/chat IDs (Telegram).
- Routing modes: `PRIMARY_FALLBACK` (default), `FANOUT`, and `FIRST_SUCCESS`.
- `STARTING` providers skipped until healthy; provider failure never corrupts request business state.
- Fake transport test suites for both providers allowing full CI test coverage without live tokens.

### Reliability & Outbox
- Persistent transactional outbox with exclusive claim semantics, exponential retry, and uncommitted processing recovery on restart.
- Request creation and outbox event insertion are atomic in SQLite.
- External bot outages never drop or lose player whitelist requests.
- Live `/gatehouse reload` supports updating bot credentials and settings without server restarts.

### Build & Packaging
- Fabric Loader 0.19.5, Fabric API 0.116.17+1.21.1, Loom 1.17.20, Java 21 target.
- Production jar (`gatehousemc-1.0.0.jar`) nests SQLite JDBC and JDA dependencies for plug-and-play server installation.
- Automated CI and release pipeline supporting GitHub Releases, Modrinth, and CurseForge publishing.
