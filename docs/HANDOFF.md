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

- Primary 1.2.0 source-build targets: Fabric 1.21.1 (Java 21), Forge 1.20.1
  (Java 17), and NeoForge 1.21.1 (Java 21).
- Historical Fabric 1.0.1 artifacts exist for Minecraft 1.14.4 through 1.21.4;
  current rebuild/publish state is authoritative in `.github/support-matrix.yml`.
- Server-side mod id: `gatehousemc`.
- Offline-mode deployment: `online-mode=false`, `white-list=true`.
- Vanilla `whitelist.json` remains authoritative.
- SQLite workflow store and persistent outbox.
- `/gatehouse` command interface; `/gh` and `/wlreq` are intentionally not
  registered in the 1.2.0 contract.
- Discord JDA and Telegram Bot API adapters behind one core decision service.
- No IP persistence and no claim that offline usernames are authenticated.
- Active maintenance branches are `support/fabric/1.21.1`,
  `support/forge/1.20.1`, and `support/neoforge/1.21.1`.
- Canonical release tags are `v<mod-version>-<loader>-mc<minecraft-version>`;
  each tag releases one loader/version artifact.

## Publication status

- GitHub is public and contains the source repository.
- Modrinth and CurseForge submissions contain the earlier files. The 1.2.0
  workflow now builds, tests, and stages one loader/version file per qualified
  tag; a qualified tag publishes automatically once the configured storefront
  secrets and release gates succeed.
- GitHub Release `v1.1.0-mc1.21.1` is retained as the earlier combined release.
  New loader-specific files use the qualified tags documented in
  `docs/RELEASE_BRANCHING.md`.
- GitHub Release `v1.0.0` is retained for history but is not production-ready;
  its non-1.21.1 labelled assets declare Minecraft 1.21.1 internally.

## Verification already executed

The 1.2.0 local evidence includes passing root/Fabric and NeoForge tests/builds,
a passing Forge Gradle 8.8 build/test lane, artifact validation for all three
jars, and the complete isolated clean-server M9 matrix: 29 checks each on
Fabric 1.21.1/Java 21, Forge 1.20.1/Java 17, and NeoForge 1.21.1/Java 21.
Machine-readable summaries are in `build/e2e/artifacts/`; retained server logs
are in `build/e2e/logs/`. The runs used loopback-only disposable servers and no
provider credentials.

## What Devin should do next

The local issue backlog and the public GitHub issue tracker are the source of
execution order. Start with the open P0 issues, not with broad refactoring.

The remaining release actions are:

1. Run the packaged artifact against a representative real mod stack; clean
   server proof is not enough for compatibility claims.
2. Complete guided provider setup/binding and exercise provider adapters with
   fake transports, proving authorization,
   publication reconciliation, and provider outage recovery.
3. Review migrations, dependency licenses, release metadata, and checksums.
4. Make CI run the appropriate unit/component gates and archive E2E evidence.
5. Obtain maintainer authorization before publication.

## Important known risks

- The local matrix does not cover arbitrary third-party mod stacks; compatibility
  claims still require a representative modpack test.
- Real Discord and Telegram credentials were not used. Their adapters need
  fake-transport coverage and optional secret-backed smoke tests.
- The full M9 acceptance matrix is automated by `tools/e2e/src/m9-matrix.mjs`.
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
