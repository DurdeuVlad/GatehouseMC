# GatehouseMC — Modrinth publication copy

This is the current audience-facing copy for the GatehouseMC Modrinth project.
The project is public but remains **Under review** until Modrinth moderation is
complete. Keep the status badges and release claims in the README aligned with
that state.

## Media and settings

- Icon: `assets/gatehousemc_logo.png`.
- Gallery: use the square logo as the featured image until a real, non-AI
  gameplay or administration screenshot is available.
- Categories: Management and Utility; feature both as the featured tags.
- Environment: Dedicated servers only.
- License: MIT.
- Disclosures: AI-generated content (code, assets, and text) and external
  system interactions are enabled with explanations. Paid features,
  advertisements, telemetry, derivative content, photosensitivity, and archive
  are not applicable.
- Source: <https://github.com/DurdeuVlad/GatehouseMC>
- Issues: <https://github.com/DurdeuVlad/GatehouseMC/issues>

Modrinth rejects AI-generated gallery images. Do not replace the accepted logo
with generated artwork unless the platform policy changes and the asset is
truthfully disclosed.

## Description

# 🏰 GatehouseMC

Run a private Fabric server in offline mode? **GatehouseMC** turns whitelist requests into a simple approval workflow for your staff.

When an unknown player tries to join:

1. GatehouseMC detects the vanilla whitelist rejection.
2. A durable request is saved automatically.
3. Staff review it in-game or receive optional Discord and Telegram notifications.
4. Approving the request adds the player to Minecraft's native whitelist.
5. The player reconnects and gets in.

Players do **not** need a client-side mod, Discord account, or external registration.

## ✨ Features

- 🚪 **Automatic requests:** A rejected, unwhitelisted join creates or refreshes a request.
- 🛡️ **Native whitelist authority:** Approval updates Minecraft's own `whitelist.json`.
- 💬 **Optional Discord and Telegram workflows:** Review and resolve requests from your staff channels.
- ⚡ **In-game administration:** Use `/gatehouse`, `/gh`, or `/wlreq`.
- ↩️ **Safe reversals:** Approve, deny, block, unblock, undo, reload, and inspect request status.
- 💾 **Durable storage:** SQLite persistence and an outbox keep requests recoverable across restarts and provider outages.
- 🚫 **No client dependency:** Install GatehouseMC on the dedicated server only.

## 💻 Commands

- `/gatehouse list [pending|approved|denied|blocked]` — List requests.
- `/gatehouse show <request-id|username>` — View request details and history.
- `/gatehouse approve <request-id|username> [reason]` — Approve and add to the vanilla whitelist.
- `/gatehouse deny <request-id|username> [reason]` — Deny a request.
- `/gatehouse block <request-id|username> [reason]` — Block future requests.
- `/gatehouse unblock <username> [reason]` — Remove a username block.
- `/gatehouse undo <request-id|username> [reason]` — Reopen or reverse the last decision.
- `/gatehouse status` — Check worker and database health.
- `/gatehouse reload` — Reload configuration.

## ⚙️ Installation

1. Download the file matching your Minecraft version and place it in the server's `mods/` directory.
2. Install Fabric Loader and Fabric API for that same Minecraft version.
3. Start the server once to generate `config/gatehousemc/config.json` and the request database.
4. Optionally configure Discord or Telegram. Provider secrets support environment variables such as `${DISCORD_TOKEN}` and `${TELEGRAM_BOT_TOKEN}`.

Verified release builds currently cover Minecraft **1.19.2, 1.19.4, 1.20.1, 1.20.4, 1.20.6, 1.21.1, and 1.21.4**. Legacy targets 1.14.4–1.18.2 remain held pending compatibility fixes; do not advertise them as available.

## 🔒 Offline-Mode Notice

Raw `online-mode=false` servers do not verify Minecraft identity ownership with Mojang or Microsoft. Approving a username grants access to the offline profile derived from that name; it does not authenticate an account. GatehouseMC does not store client IP addresses by default.

## 🔗 Links

- **Source:** <https://github.com/DurdeuVlad/GatehouseMC>
- **Issue tracker:** <https://github.com/DurdeuVlad/GatehouseMC/issues>
- **Setup guide:** <https://github.com/DurdeuVlad/GatehouseMC/blob/main/README.md>
