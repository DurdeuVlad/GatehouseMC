# Current implementation plan

Completed in the initial build pass:

- Fabric 1.21.1 / Java 21 Gradle foundation with pinned wrapper and Loom.
- Pure domain/application packages and package-direction boundaries.
- SQLite migrations, request dedupe, denial cooldown, blocks, audit, CAS decisions, and outbox.
- Non-blocking admission queue and the 1.21.1 `PlayerManager#checkCanJoin` return hook.
- Native whitelist adapter, `/wlreq` commands, Discord JDA adapter, and Telegram Bot API adapter.
- Router/fallback logic, configuration secret expansion/redaction, unit/component coverage, and nested runtime dependencies.

Still required before a public release:

- Boot the produced jar on a clean dedicated Minecraft 1.21.1 server.
- Run and archive the real offline-protocol E2E matrix from `docs/TESTING.md`.
- Complete config reload behavior and provider publication persistence reconciliation.
