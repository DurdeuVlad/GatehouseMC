<p align="center">
  <img src="assets/gatehousemc_logo.png" alt="GatehouseMC logo" width="240">
</p>

# GatehouseMC

<p align="center">
  <a href="https://github.com/DurdeuVlad/GatehouseMC/releases/latest"><img src="https://img.shields.io/github/v/release/DurdeuVlad/GatehouseMC?color=brightgreen&label=Release" alt="Latest GitHub release"></a>
  <a href="https://modrinth.com/mod/gatehousemc"><img src="https://img.shields.io/badge/Modrinth-Submitted%20for%20review-F5A623?logo=modrinth&logoColor=white" alt="Modrinth submitted for review"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/gatehousemc"><img src="https://img.shields.io/badge/CurseForge-Pending%20review-F5A623?logo=curseforge&logoColor=white" alt="CurseForge pending review"></a>
  <img src="https://img.shields.io/badge/Minecraft-1.14.4--1.21.4-brightgreen.svg" alt="Minecraft 1.14.4 through 1.21.4">
  <img src="https://img.shields.io/badge/Loader-Fabric-blue.svg" alt="Fabric">
  <img src="https://img.shields.io/badge/Side-Server--Only-orange.svg" alt="Server-Side Only">
  <img src="https://img.shields.io/badge/License-MIT-green.svg" alt="MIT License">
</p>

Server-side Fabric whitelist approvals for private offline-mode Minecraft servers. When vanilla rejects an unwhitelisted player, GatehouseMC records one durable request and lets staff approve, deny, block, or undo it in-game or through optional Discord and Telegram adapters.

GatehouseMC is an operational workflow layer around Minecraft's native whitelist. It does not authenticate players, replace `whitelist.json`, retain IP addresses, or require a client mod.

## Requirements

- Minecraft Java Edition 1.14.4 through 1.21.4 (use the exact matching file)
- Fabric Loader and Fabric API for that same Minecraft version
- Java matching the selected Minecraft version:
  - 1.14.4, 1.15.2, 1.16.5 — Java 8
  - 1.17.1 — Java 16
  - 1.18.2, 1.19.2, 1.19.4, 1.20.1, 1.20.4 — Java 17
  - 1.20.6, 1.21.1, 1.21.4 — Java 21
- `online-mode=false` and `white-list=true` for the supported offline-mode workflow

## Downloads & Distribution

- **Modrinth:** [modrinth.com/mod/gatehousemc](https://modrinth.com/mod/gatehousemc) *(submitted; pending moderation)*
- **CurseForge:** [curseforge.com/minecraft/mc-mods/gatehousemc](https://www.curseforge.com/minecraft/mc-mods/gatehousemc) *(submitted; pending moderation)*
- **GitHub Releases:** [latest release](https://github.com/DurdeuVlad/GatehouseMC/releases/latest) *(v1.0.0; 12 JARs and checksums)*
- **Source and issues:** [github.com/DurdeuVlad/GatehouseMC](https://github.com/DurdeuVlad/GatehouseMC) · [issue tracker](https://github.com/DurdeuVlad/GatehouseMC/issues)
- **Publishing copy:** [Modrinth](docs/publishing/MODRINTH.md) · [CurseForge](docs/publishing/CURSEFORGE.md)
- **Operator Publishing Guide:** [`docs/PUBLISHING.md`](docs/PUBLISHING.md)

## Build

```text
./gradlew clean test build
```

On Windows PowerShell:

```powershell
.\gradlew.ps1 clean test build
```

The primary 1.21.1 production artifact is written to `build/libs/gatehousemc-1.0.0.jar`. Published compatibility artifacts use version-labelled filenames and are listed on the storefronts. The build nests SQLite and JDA so the jar can be installed on a clean Fabric server without external dependency mods.

## Real-server smoke test

The headless client driver lives in `tools/e2e/` and requires Node.js 20+:

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

Unit, component, architecture, router, and fake-transport tests pass, and a real Fabric 1.21.1 dedicated server has proven rejection -> persistence -> approval -> native whitelist mutation -> reconnect -> restart persistence. The full clean-artifact multi-version matrix, representative mod-stack compatibility tests, and credential-backed Discord/Telegram smokes remain release gates; see [`docs/TESTING.md`](docs/TESTING.md) and [`docs/HANDOFF.md`](docs/HANDOFF.md). Do not treat a successful Gradle build as proof of production readiness.

See [`CONTRIBUTING.md`](CONTRIBUTING.md), [`WHITELIST_REQUEST_SPEC.md`](WHITELIST_REQUEST_SPEC.md), [`docs/HANDOFF.md`](docs/HANDOFF.md), [`docs/OSS.md`](docs/OSS.md), and [`docs/PUBLISHING.md`](docs/PUBLISHING.md) for project policy and release procedures.
