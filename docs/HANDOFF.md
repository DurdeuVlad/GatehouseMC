# Devin handoff

This repository is the implementation handoff for GatehouseMC.
The attached handoff archive supplied the product specification, architecture,
testing strategy, OSS policy, and issue-style task backlog. Those documents are
requirements/context; they are not a replacement for the user's request or for
the acceptance evidence below.

Public GitHub repository: [DurdeuVlad/GatehouseMC](https://github.com/DurdeuVlad/GatehouseMC)

The tracker contains the imported implementation, compatibility, and release
backlog covering M0 through M7. Use the live GitHub tracker for the current
open/closed issue count. Dependabot is enabled for the Gradle wrapper and the
Node E2E client.

## Current baseline

- Primary 1.1.x source-build targets: Fabric 1.21.1 (Java 21), Forge 1.20.1
  (Java 17), and NeoForge 1.21.1 (Java 21).
- Historical Fabric 1.0.1 artifacts exist for Minecraft 1.14.4 through 1.21.4;
  current rebuild/publish state is authoritative in `.github/support-matrix.yml`.
- Server-side mod id: `gatehousemc`.
- Offline-mode deployment: `online-mode=false`, `white-list=true`.
- Vanilla `whitelist.json` remains authoritative.
- SQLite workflow store and persistent outbox.
- `/gatehouse` (with `/gh` and `/wlreq` aliases) command interface.
- Discord JDA and Telegram Bot API adapters behind one core decision service.
- No IP persistence and no claim that offline usernames are authenticated.

## Publication status

- GitHub is public and contains the source repository.
- Modrinth and CurseForge submissions contain the earlier files. The 1.1.x
  workflow now stages loader-specific files; storefront publication remains a
  guarded manual dispatch until the broader acceptance matrix is complete.
- GitHub Release `v1.0.0` is retained for history but is not production-ready;
  its non-1.21.1 labelled assets declare Minecraft 1.21.1 internally.

## Verification already executed

```text
./gradlew.ps1 test --stacktrace                 PASS
./gradlew.ps1 build --stacktrace                PASS
tools/e2e: npm ci                               PASS
tools/e2e: npm run smoke                        PASS (unwhitelisted rejection)
tools/e2e: MC_EXPECTED_STATUS=joined npm run smoke PASS (approved reconnect)
```

The current local implementation has passed the Fabric/core test suite,
reproducibly builds the three 1.1.x proof artifacts, and has local clean-server
smoke proof on Fabric 1.21.1, Forge 1.20.1, and NeoForge 1.21.1. Compilation
alone is not being reported as production proof.

## What Devin should do next

The local issue backlog and the public GitHub issue tracker are the source of
execution order. Start with the open P0 issues, not with broad refactoring.

The remaining production gates are:

1. Run the full clean-artifact E2E matrix from `docs/TESTING.md` for Fabric,
   Forge, and NeoForge, extending the verified smoke paths with the full
   workflow scenarios.
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

- The new Forge and NeoForge packaged jars have build/metadata validation and
  local clean standalone server smoke proof. CI now provisions all three proof
  lanes, but that workflow still needs to run successfully on the hosted
  repository before it is treated as release evidence.
- Real Discord and Telegram credentials were not used. Their adapters need
  fake-transport coverage and optional secret-backed smoke tests.
- The full acceptance matrix is not automated yet.
- GitHub Actions is configured, but the private repository's jobs currently
  cannot start because GitHub reports an account billing/spending-limit
  failure. Re-run the workflow after the account billing state is fixed.
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
