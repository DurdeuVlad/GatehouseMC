# Whitelist Request Mod

Server-side Fabric mod for Minecraft 1.21.1. When vanilla rejects an offline-mode player because they are not whitelisted, the mod records one durable request and exposes it through `/wlreq`, Discord, and Telegram adapters.

This is a workflow layer around Minecraft's native whitelist. It does not authenticate players, replace `whitelist.json`, retain IP addresses, or require a client mod.

## Requirements

- Minecraft Java Edition 1.21.1
- Fabric Loader 0.19.5 and a compatible Fabric API
- Java 21
- `online-mode=false` and `white-list=true` for the supported offline-mode workflow

## Build

```text
./gradlew clean test build
```

On Windows PowerShell:

```powershell
.\gradlew.ps1 clean test build
```

The production artifact is written to `build/libs/`. The build nests SQLite and JDA so the jar can be installed on a clean Fabric server.

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

Put the production jar in the server's `mods/` directory and start the server once. The mod creates:

```text
config/whitelistrequest/config.json
config/whitelistrequest/requests.sqlite
```

Minecraft commands are always available after the server starts:

```text
/wlreq list [pending|approved|denied|blocked]
/wlreq show <request-id|username>
/wlreq approve <request-id|username> [reason...]
/wlreq deny <request-id|username> [reason...]
/wlreq block <request-id|username> [reason...]
/wlreq unblock <username> [reason...]
/wlreq status
```

Discord and Telegram are disabled by default. Enable them only after setting stable administrator IDs and provider secrets. Secrets can use `${DISCORD_TOKEN}` and `${TELEGRAM_BOT_TOKEN}`; expanded values stay in memory and are never written back or included in status output.

## Trust warning

Raw offline mode does not verify ownership of a Minecraft name. Approving `Alice` grants access to the offline profile derived from the supplied name; it does not prove that a particular Microsoft/Mojang account owns that identity.

## Verification status

All unit, component, architecture, router, and fake-transport tests pass. A real Fabric 1.21.1 dedicated server has proven: rejection -> persistence -> approval -> native whitelist mutation -> reconnect -> restart persistence, and distinct player-facing messages for first-request, pending, denied, blocked, and degraded states. The production jar nests SQLite and JDA and runs on a clean server with no additional dependencies.

See [`CONTRIBUTING.md`](CONTRIBUTING.md), [`WHITELIST_REQUEST_SPEC.md`](WHITELIST_REQUEST_SPEC.md), [`docs/HANDOFF.md`](docs/HANDOFF.md), and [`docs/OSS.md`](docs/OSS.md) for project policy and handoff status.
