<p align="center">
  <img src="assets/gatehousemc_logo.png" alt="GatehouseMC logo" width="240">
</p>

# GatehouseMC

<p align="center">
  <a href="https://github.com/DurdeuVlad/GatehouseMC/releases/latest"><img src="https://img.shields.io/github/v/release/DurdeuVlad/GatehouseMC?color=brightgreen&label=Release" alt="Latest GitHub release"></a>
  <a href="https://modrinth.com/mod/gatehousemc"><img src="https://img.shields.io/badge/Modrinth-Submitted%20for%20review-F5A623?logo=modrinth&logoColor=white" alt="Modrinth submitted for review"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/gatehousemc"><img src="https://img.shields.io/badge/CurseForge-Pending%20review-F5A623?logo=curseforge&logoColor=white" alt="CurseForge pending review"></a>
  <img src="https://img.shields.io/badge/Minecraft-1.20.1%20%7C%201.21.1-brightgreen.svg" alt="Current 1.1.x proof targets">
  <img src="https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-blue.svg" alt="Fabric, Forge, and NeoForge">
  <img src="https://img.shields.io/badge/Side-Server--Only-orange.svg" alt="Server-Side Only">
  <img src="https://img.shields.io/badge/License-MIT-green.svg" alt="MIT License">
</p>

Server-side whitelist approvals for private offline-mode Minecraft servers on Fabric, Forge, and NeoForge proof targets. When vanilla rejects an unwhitelisted player, GatehouseMC records one durable request and lets staff approve, deny, block, or undo it in-game or through optional Discord and Telegram adapters.

GatehouseMC is an operational workflow layer around Minecraft's native whitelist. It does not authenticate players, replace `whitelist.json`, retain IP addresses, or require a client mod.

## Requirements

- 1.1.x source-build targets: Fabric 1.21.1, Forge 1.20.1, and NeoForge 1.21.1
- Java 17 for Forge 1.20.1; Java 21 for Fabric/NeoForge 1.21.1
- Historical 1.0.1 Fabric artifacts exist for 1.14.4–1.21.4, but they are not claimed as rebuilt 1.1.x targets until the [support matrix](.github/support-matrix.yml) says so
- `online-mode=false` and `white-list=true` for the supported offline-mode workflow

## Downloads & Distribution

- **Modrinth:** [modrinth.com/mod/gatehousemc](https://modrinth.com/mod/gatehousemc) *(submitted; pending moderation)*
- **CurseForge:** [curseforge.com/minecraft/mc-mods/gatehousemc](https://www.curseforge.com/minecraft/mc-mods/gatehousemc) *(submitted; pending moderation)*
- **GitHub Releases:** [latest release](https://github.com/DurdeuVlad/GatehouseMC/releases/latest) *(latest published line; 1.1.x is staged behind release gates)*
- **Source and issues:** [github.com/DurdeuVlad/GatehouseMC](https://github.com/DurdeuVlad/GatehouseMC) · [issue tracker](https://github.com/DurdeuVlad/GatehouseMC/issues)
- **Publishing copy:** [Modrinth](docs/publishing/MODRINTH.md) · [CurseForge](docs/publishing/CURSEFORGE.md)
- **Operator Publishing Guide:** [`docs/PUBLISHING.md`](docs/PUBLISHING.md)
- **Release branching:** [`docs/RELEASE_BRANCHING.md`](docs/RELEASE_BRANCHING.md)

## Build

```text
./gradlew clean test build
```

On Windows PowerShell:

```powershell
.\gradlew.ps1 clean test build
```

The Fabric 1.21.1 artifact is written to `build/libs/gatehousemc-1.1.0.jar`. NeoForge and Forge artifacts are written to their platform build directories. A release artifact is publishable only when its internal loader metadata and Minecraft dependency match its label; CI enforces this with `tools/release/validate_artifact.py`. Fabric nests its runtime libraries; Forge and NeoForge use loader-provided Gson/SLF4J and nest SQLite, JDA, and JDA's remaining runtime graph.

The Forge lane uses Gradle 8.8 because ForgeGradle 6 rejects Gradle 9+. Run it explicitly with `gradle -PenableForge :platform-forge:build`; the normal wrapper builds Fabric and NeoForge.

## Real-server smoke test

The headless client driver lives in `tools/e2e/` and requires Node.js 22+:

```powershell
Push-Location tools/e2e
npm ci
npm run smoke
Pop-Location
```

The default assertion expects an unwhitelisted offline client to be rejected.
After approving a request on the server, run the join assertion with
`$env:MC_EXPECTED_STATUS = 'joined'`. The complete release matrix, including a
clean standalone server and restart/outage scenarios, is documented in
[`docs/TESTING.md`](docs/TESTING.md).

## Install and configure

Put the file matching your Minecraft version in the server's `mods/` directory and start the server once. GatehouseMC creates:

```text
config/gatehousemc/config.json
config/gatehousemc/requests.sqlite
```

> [!NOTE]
> Existing deployments using legacy `config/whitelistrequest/` are automatically migrated on startup to `config/gatehousemc/`.

Minecraft commands are always available after the server starts:

```text
/gatehouse list [pending|approved|denied|blocked]
/gatehouse show <request-id|username>
/gatehouse approve <request-id|username> [reason...]
/gatehouse deny <request-id|username> [reason...]
/gatehouse block <request-id|username> [reason...]
/gatehouse unblock <username> [reason...]
/gatehouse undo <request-id|username> [reason...]
/gatehouse status
/gatehouse reload
```

*(Commands also respond to `/gh` and `/wlreq` aliases).*

Discord and Telegram are disabled by default. Enable them only after setting stable administrator IDs and provider secrets. Secrets can use `${DISCORD_TOKEN}` and `${TELEGRAM_BOT_TOKEN}`; expanded values stay in memory and are never written back or included in status output.

## Trust warning

Raw offline mode does not verify ownership of a Minecraft name. Approving `Alice` grants access to the offline profile derived from the supplied name; it does not prove that a particular Microsoft/Mojang account owns that identity.

## Verification status

The current branch has passing Fabric/core tests, reproducible builds, artifact validation, and local clean dedicated-server smoke proof for all three 1.1.x proof targets. Storefront publication is gated on a loader-qualified tag and the release workflow; broader repeat-attempt, restart, approval-race, and real mod-stack coverage is still required before calling the release production-ready. See [`docs/TESTING.md`](docs/TESTING.md) and [`.github/support-matrix.yml`](.github/support-matrix.yml) for the exact state.

See [`CONTRIBUTING.md`](CONTRIBUTING.md), [`WHITELIST_REQUEST_SPEC.md`](WHITELIST_REQUEST_SPEC.md), [`docs/HANDOFF.md`](docs/HANDOFF.md), [`docs/OSS.md`](docs/OSS.md), and [`docs/PUBLISHING.md`](docs/PUBLISHING.md) for project policy and release procedures.
