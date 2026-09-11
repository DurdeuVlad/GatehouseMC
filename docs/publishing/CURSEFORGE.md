# GatehouseMC — CurseForge publication copy

This is the current audience-facing copy for the GatehouseMC CurseForge
project. The project and its existing files are submitted and **Under Review**;
CurseForge will not expose the project or synchronize its files until moderator
approval.

## Media and settings

- Project name: Gatehouse. CurseForge rejects project names containing `mc`,
  while the product and mod remain branded **GatehouseMC** in the description,
  files, source repository, and slug (`gatehousemc`).
- Class/category: Mods → Server Utility.
- License: MIT.
- Source code: GitHub — <https://github.com/DurdeuVlad/GatehouseMC>.
- Comments: enabled; issue tracking remains the public GitHub issue tracker.
- Files: only artefacts whose internal loader and Minecraft metadata match
  their labels may be published. The old v1.0.0 multi-version submission
  remains on hold because its non-1.21.1 labelled files contain 1.21.1 metadata.
- Media: the project has a neutral multi-version banner. Keep the square
  project logo available as the canonical icon.

CurseForge's current author UI does not expose a project-level Environment
field, so the preview may show **Not Set** even though every uploaded file has
the correct **Server** environment tag. Recheck this after moderation.

## Description

GatehouseMC

Run a private Fabric, Forge, or NeoForge server in offline mode? GatehouseMC turns whitelist requests into a simple approval workflow for your staff.

When an unknown player tries to join:

1. GatehouseMC detects the vanilla whitelist rejection.
2. A durable request is saved automatically.
3. Staff review it in-game or receive optional Discord and Telegram notifications.
4. Approval adds the player to Minecraft's native whitelist.
5. The player reconnects and gets in.

Players do not need a client-side mod, Discord account, or external registration.

FEATURES

- Automatic whitelist request creation
- Optional Discord and Telegram approval workflows
- In-game commands: `/gatehouse`, `/gh`, and `/wlreq`
- Approve, deny, block, unblock, undo, status, and reload actions
- SQLite persistence and restart/outage recovery
- Minecraft's native whitelist remains authoritative
- Server-side only; no client IP addresses stored by default

INSTALLATION

1. Download the file matching both your Minecraft version and loader, then place it in the server's `mods/` directory.
2. Install the matching Fabric API, Forge, or NeoForge runtime for that same Minecraft version.
3. Start the server once to generate `config/gatehousemc/config.json` and the request database.
4. Optionally configure Discord or Telegram integrations. Provider secrets support environment variables such as `${DISCORD_TOKEN}` and `${TELEGRAM_BOT_TOKEN}`.

The 1.1.x source-build targets are Fabric 1.21.1, Forge 1.20.1, and NeoForge 1.21.1. Historical Fabric 1.0.1 files cover 1.14.4–1.21.4. Choose only the exact loader/version file marked public in the [support matrix](../../.github/support-matrix.yml).

OFFLINE-MODE NOTICE

Raw `online-mode=false` servers do not verify Minecraft identity ownership with Mojang or Microsoft. Approving a username grants access to the offline profile derived from that name; it does not authenticate an account.

LINKS

Source: <https://github.com/DurdeuVlad/GatehouseMC>

Issue tracker: <https://github.com/DurdeuVlad/GatehouseMC/issues>

Setup guide: <https://github.com/DurdeuVlad/GatehouseMC/blob/main/README.md>
