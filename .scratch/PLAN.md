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

M1-04 verification evidence (2026-09-08):

- RED: `./gradlew.ps1 test --tests '*DecisionServiceTest' --tests '*Concurrency*' --rerun-tasks --stacktrace` failed the approval-cache assertion before the terminal cache fix; the initial run also exposed a test-only package-access mistake, corrected before the behavioral RED result.
- GREEN: `./gradlew.ps1 test --tests '*DecisionServiceTest' --tests '*Concurrency*' --rerun-tasks --stacktrace` — PASS.
- Full regression: `./gradlew.ps1 clean test build --stacktrace` — PASS.
- Decision tests prove approval calls the vanilla port once, successful approval clears the pending admission cache, failed whitelist mutation returns the request to PENDING, actor origin is recorded, and approve/deny races have one winner.
- Recovery tests prove an interrupted approval finalizes when the exact profile is whitelisted, resets when it is absent, and resets safely when the whitelist check fails; recovery continuations run on the decision worker.
- Decision persistence and approval continuations use a dedicated decision executor rather than the Minecraft/server thread or admission worker; CAS losers refresh the cache from their durable request snapshot.
- The application still uses the repository compare-and-set claim/finalize/reset flow; outbox publication and Fabric startup ordering remain tracked by later issues.

M1-05 verification evidence (2026-09-08):

- RED: the new outbox repository suite initially exposed the SELECT-then-CAS claim path; implementation now returns only rows whose PROCESSING claim actually changed state.
- Required gate: `./gradlew.ps1 test --tests '*Outbox*' --tests '*Retry*' --rerun-tasks --stacktrace` — PASS.
- Full regression: `./gradlew.ps1 clean test build --stacktrace` — PASS.
- Real SQLite tests prove concurrent outbox claim exclusivity, PROCESSING requeue after reopen, deterministic retry availability/attempt increment, and REQUEST_UPDATED/approval-failure event durability.
- Outbox retry errors are reduced to exception type names before persistence; repository update failures emit structured warning events instead of being silent.

M2-01/M2-02 verification evidence (2026-09-08):

- Exact mapped Minecraft 1.21.1 `PlayerManager#checkCanJoin(SocketAddress, GameProfile)` signature and bytecode branch order are recorded in `.scratch/MIXIN_INVESTIGATION.md`; Mixin uses translatable key detection and `require=1`.
- Clean packaged Fabric server proof: Loader 0.19.5, Minecraft 1.21.1, Fabric API 0.116.17+1.21.1, offline mode, whitelist enabled; server reached `Done` and applied the Mixin without startup failure.
- Packaged offline client smoke rejected `E2E_Alice` with the custom queued-request message; SQLite contained one PENDING request at attempt count 1 and `whitelist.json` remained empty.
- `./gradlew.ps1 test --tests '*OfflineIdentity*' --rerun-tasks --stacktrace` — PASS; mapped `Uuids#getOfflinePlayerUuid` bytecode and known UUID formula are documented.

M2-03/M2-04 live evidence (2026-09-08/09):

- Clean packaged server `build/e2e/m2-03-clean-server/` booted twice with Loader 0.19.5, Minecraft 1.21.1, Fabric API 0.116.17+1.21.1, `online-mode=false`, `white-list=true`, port 25572; logs are retained in that directory.
- `E2E_M2Player` was rejected, approved by `wlreq approve` over local test RCON, joined, then joined again after graceful server restart; `whitelist.json` retained the exact UUID/name.
- First and repeat `E2E_Pending` attempts rendered first-request and pending messages; E2E_Denied and E2E_Blocked rendered distinct denied/blocked messages after their decisions.
- Final workflow query: `E2E_Blocked|BLOCKED|1`, `E2E_Denied|DENIED|1`, `E2E_M2Player|APPROVED|1`, `E2E_Pending|PENDING|2`; block key `e2e_blocked`.

M2-05 verification evidence (2026-09-09):

- `DecisionServiceTest` covers simulated crash after whitelist add (`RESOLVING` + whitelist true → `APPROVED`), before whitelist add (`false` → `PENDING`), failed whitelist inspection (`PENDING`), cache refresh, and repeated recovery idempotence.
- `./gradlew.ps1 test --tests '*DecisionService*' --tests '*Concurrency*' --rerun-tasks --stacktrace` — PASS.
- `./gradlew.ps1 clean test build --stacktrace` — PASS.
- Fabric runtime construction now runs on a daemon startup executor, awaits recovery before publishing the runtime/providers, preloads pending/blocked/active-denial cache state, and coordinates start/stop publication under a lock with partial-resource cleanup.
- Final packaged startup smoke (`build/e2e/m2-05-clean-server`, port 25573) used mod jar SHA-256 `7F5607F2FEB7003D79214AEDB1FC8DC14037FFBB5ABB5957B39BD71E8554CB0D`, logged `Whitelist Request started` on `whitelistrequest-startup` after the real Fabric server reached `Done`, and rejected the offline client with a queued request message.

Still required before a public release:

- Boot the produced jar on a clean dedicated Minecraft 1.21.1 server.
- Run and archive the real offline-protocol E2E matrix from `docs/TESTING.md`.
- Complete config reload behavior and provider publication persistence reconciliation.
