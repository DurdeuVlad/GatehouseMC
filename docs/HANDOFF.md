# Devin handoff

This repository is the implementation handoff for Whitelist Request Mod.
The attached handoff archive supplied the product specification, architecture,
testing strategy, OSS policy, and issue-style task backlog. Those documents are
requirements/context; they are not a replacement for the user's request or for
the acceptance evidence below.

Private GitHub repository: [DurdeuVlad/GatehouseMC](https://github.com/DurdeuVlad/GatehouseMC)

The tracker contains 31 imported implementation, compatibility, and release issues covering
M0 through M7. Dependabot is enabled for the Gradle wrapper and the Node E2E
client.

## Current baseline

- Minecraft 1.21.1, Fabric, Java 21.
- Server-side mod id: `whitelistrequest`.
- Offline-mode deployment: `online-mode=false`, `white-list=true`.
- Vanilla `whitelist.json` remains authoritative.
- SQLite workflow store and persistent outbox.
- `/wlreq` command interface.
- Discord JDA and Telegram Bot API adapters behind one core decision service.
- No IP persistence and no claim that offline usernames are authenticated.

## Verification already executed

```text
./gradlew.ps1 test --stacktrace                 PASS
./gradlew.ps1 build --stacktrace                PASS
tools/e2e: npm ci                               PASS
tools/e2e: npm run smoke                        PASS (unwhitelisted rejection)
tools/e2e: MC_EXPECTED_STATUS=joined npm run smoke PASS (approved reconnect)
```

A real Fabric 1.21.1 dedicated server booted successfully with the Mixin
applied. `E2E_Alice` was rejected, persisted as `PENDING`, approved through the
server command, added to vanilla `whitelist.json`, and reconnected to the
running server. `E2E_Bob` was independently rejected and persisted as pending.

## What Devin should do next

The local issue backlog and the private GitHub issue tracker are the source of
execution order. Start with the open P0 issues, not with broad refactoring.

The remaining production gates are:

1. Run the full clean-artifact E2E matrix from `docs/TESTING.md`, not only the
   smoke path.
2. Add repeat-attempt, deny, block/unblock, restart persistence, non-whitelist
   rejection, and interrupted-approval scenarios.
3. Run the packaged artifact against a representative real mod stack; the
   clean Fabric smoke server is not enough for compatibility claims.
4. Exercise provider adapters with fake transports and prove authorization,
   publication reconciliation, and provider outage recovery.
5. Review migrations, dependency licenses, release metadata, and checksums.
6. Make CI run the appropriate unit/component gates and archive E2E evidence.
7. Do not label the project production-ready until M6/M7 acceptance criteria
   are green or explicitly accepted by a maintainer.

## Important known risks

- The current local server proof used Loom's development launch path. The
  packaged jar contents were inspected, but a clean standalone server process
  using only the release jar still needs to be made the repeatable CI gate.
- Real Discord and Telegram credentials were not used. Their adapters need
  fake-transport coverage and optional secret-backed smoke tests.
- The full acceptance matrix is not automated yet.
- Release licensing and dependency notices need maintainer confirmation before
  publication.

## Safe handoff commands

```powershell
.\gradlew.ps1 clean test build
Push-Location tools/e2e
npm ci
npm run smoke
Pop-Location
```

Never commit `run/`, `build/`, SQLite files, logs, bot tokens, or server
credentials. The repository's `.gitignore` is intentionally conservative about
runtime state.
