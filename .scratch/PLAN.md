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

Still required before a public release:

- Boot the produced jar on a clean dedicated Minecraft 1.21.1 server.
- Run and archive the real offline-protocol E2E matrix from `docs/TESTING.md`.
- Complete config reload behavior and provider publication persistence reconciliation.
