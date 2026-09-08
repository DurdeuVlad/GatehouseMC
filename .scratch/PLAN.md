# Current implementation plan

Completed in the initial build pass:

- Fabric 1.21.1 / Java 21 Gradle foundation with pinned wrapper and Loom.
- Pure domain/application packages and package-direction boundaries.
- SQLite migrations, request dedupe, denial cooldown, blocks, audit, CAS decisions, and outbox.
- Non-blocking admission queue and the 1.21.1 `PlayerManager#checkCanJoin` return hook.
- Native whitelist adapter, `/wlreq` commands, Discord JDA adapter, and Telegram Bot API adapter.
- Router/fallback logic, configuration secret expansion/redaction, unit/component coverage, and nested runtime dependencies.

M0-01 verification evidence (2026-09-08):

- `./gradlew.ps1 --version` — Gradle 9.5.0, Java 21.0.8, Windows 11.
- `./gradlew.ps1 clean check build --stacktrace` — PASS; Fabric Loom 1.17.20, 10 tasks executed.
- Dependency pin audit — PASS after changing `loom_version` from `1.17-SNAPSHOT` to exact `1.17.20`.
- `runServer` task is present in `./gradlew.ps1 tasks --all`.
- Production jar contains `fabric.mod.json` and `whitelistrequest.mixins.json`, with no client entrypoint; SHA-256: `0557FAAA7C098BDBF4047950461F2CC349CFB647E57E11A62FFF02214DEC9447`.
- Loom reports a non-fatal semver warning for the pinned four-component xerial SQLite version `3.53.2.1`; no dependency is floating.

M0-02 verification evidence (2026-09-08):

- `./gradlew.ps1 test --tests '*ArchitectureTest' --rerun-tasks --stacktrace` — PASS.
- `./gradlew.ps1 test --stacktrace` — PASS.
- `ArchitectureTest` scans `domain`, `application`, and `port` source packages and rejects Minecraft, Fabric, JDA, JDBC, and SQLite imports; direct source inspection found no violations.

M0-03 verification evidence (2026-09-08):

- `./gradlew.ps1 test --tests '*Config*' --rerun-tasks --stacktrace` — PASS after the validation changes.
- RED run of the same targeted configuration suite — 6 expected failures before implementation, covering provider isolation, malformed values, redaction, malformed placeholders, directory paths, and non-object roots.
- Enabled Discord/Telegram providers with invalid credentials, IDs, or authorization are disabled without taking down the core workflow.
- Core configuration rejects duplicate routing providers, blank/invalid database paths, malformed JSON roots, and non-actionable JSON types.
- Environment placeholders are expanded in memory, malformed placeholders are rejected, and record string representations omit Discord/Telegram tokens.
- `.gitignore` now excludes the runtime `logs/` directory so compressed server logs cannot be staged accidentally.

M1-01 verification evidence (2026-09-08):

- RED: `./gradlew.ps1 test --tests '*DomainStateTest' --rerun-tasks --stacktrace` failed at compilation because the transition API did not exist.
- GREEN: `./gradlew.ps1 test --tests '*DomainStateTest' --rerun-tasks --stacktrace` — PASS.
- Required issue gate: `./gradlew.ps1 test --tests '*Domain*' --tests '*State*' --rerun-tasks --stacktrace` — PASS.
- Full regression: `./gradlew.ps1 clean test build --stacktrace` — PASS after correcting an existing test fixture to use a monotonic later decision clock.
- `RequestStatus` now rejects undocumented transitions; `WhitelistRequest` rejects contradictory lifecycle metadata and invalid timestamp ordering, including resolution before creation.
- `PlayerIdentity` rejects names outside Minecraft's ASCII letter/digit/underscore profile syntax while retaining exact and normalized values.
- Domain sources remain free of Minecraft, Fabric, provider, and JDBC imports.

M1-02 verification evidence (2026-09-08):

- RED: `./gradlew.ps1 test --tests '*WhitelistRequestServiceTest' --rerun-tasks --stacktrace` failed to compile because the cache did not accept a workflow clock.
- GREEN: `./gradlew.ps1 test --tests '*WhitelistRequestService*' --rerun-tasks --stacktrace` — PASS.
- Full regression: `./gradlew.ps1 clean test build --stacktrace` — PASS.
- Existing SQLite core tests prove first-attempt creation, repeat/case-variant deduplication, attempt counting, cooldown, and block behavior; the cache now uses the injected `ClockPort` and atomically expires denied entries.
- Fabric runtime wires its single wall clock into the admission cache; no provider or database calls were added to the cache path.

M1-03 verification evidence (2026-09-08):

- RED: `./gradlew.ps1 test --tests '*SqliteWorkflowRepositoryTest' --rerun-tasks --stacktrace` failed the future-schema rejection scenario before version gating was implemented.
- GREEN: `./gradlew.ps1 test --tests '*SqliteWorkflowRepositoryTest' --rerun-tasks --stacktrace` — PASS.
- Required issue gate: `./gradlew.ps1 test --tests '*Sqlite*' --tests '*Repository*' --rerun-tasks --stacktrace` — PASS.
- Full regression: `./gradlew.ps1 clean test build --stacktrace` — PASS.
- Real SQLite tests prove fresh schema migration, request/audit persistence after reopen, rejection of unsupported future schema versions, WAL/foreign-key/busy-timeout setup, and two independent repository connections cannot create duplicate active requests.
- Migration initialization closes its JDBC connection if a migration fails; current version is explicitly gated before applying DDL and future migration steps run sequentially.
- Transient unique-key and lock contention retries the active-request lookup instead of returning a false degraded state; audit JSON escaping now covers control characters.

Still required before a public release:

- Boot the produced jar on a clean dedicated Minecraft 1.21.1 server.
- Run and archive the real offline-protocol E2E matrix from `docs/TESTING.md`.
- Complete config reload behavior and provider publication persistence reconciliation.
