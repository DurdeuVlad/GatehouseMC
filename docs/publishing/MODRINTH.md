# GatehouseMC — Modrinth Project Description

```markdown
![GatehouseMC Banner](https://raw.githubusercontent.com/DurdeuVlad/GatehouseMC/feat/undo-and-i18n/assets/gatehousemc_banner.png)

<p align="center">
  <b>Server-side whitelist gatehouse for offline-mode Minecraft servers.</b><br>
  <i>Automatic request creation, multi-platform admin controls via Discord, Telegram & in-game commands.</i>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg" alt="Minecraft 1.21.1">
  <img src="https://img.shields.io/badge/Loader-Fabric-blue.svg" alt="Fabric">
  <img src="https://img.shields.io/badge/Side-Server--Only-orange.svg" alt="Server-Side Only">
  <img src="https://img.shields.io/badge/License-MIT-green.svg" alt="MIT License">
</p>

---

## 🏰 What is GatehouseMC?

**GatehouseMC** is a server-side Fabric mod for Minecraft 1.21.1 that simplifies whitelist administration on **offline-mode (`online-mode=false`)** servers.

Instead of requiring players to sign up on a website, open tickets, or install client-side authentication mods, players simply connect to your server. When Minecraft rejects them because they are not on the whitelist, **GatehouseMC automatically intercepts the denial, records a durable request, and notifies administrators directly in Discord and Telegram.**

Staff can approve, deny, or block requests with a single click—even from their phones!

---

## ✨ Features

- **🚪 Automatic Request Creation:** An unwhitelisted player attempting to connect automatically creates or refreshes a whitelist request. Zero extra client setup.
- **⚡ Discord & Telegram Integrations:** Instant interactive alerts with **Approve**, **Deny**, and **Block** buttons sent straight to your admin channels.
- **↩️ Undo & Reopen:** Made a mistake? The `/gatehouse undo` command instantly reverts approvals, denys, or blocks.
- **🛡️ Native Whitelist Authority:** Approved players are added to vanilla Minecraft's `whitelist.json`. No parallel or incompatible access control system.
- **💾 Crash-Resilient SQLite Outbox:** Never lose a request during server restarts or bot connection outages. Events are buffered and delivered reliably once online.
- **🚫 Spam & Abuse Protection:** Built-in denial cooldowns and permanent identity blocking prevent request floods from unwanted usernames.
- **🌐 Internationalization (i18n):** User-facing player rejection messages and command outputs support English (`en_us`) and Romanian (`ro_ro`).

---

## 🛠️ Server-Side Only

> **Note:** GatehouseMC is 100% server-side! Players connecting to your server **do NOT** need to install this mod.

---

## 📋 In-Game Commands

All commands require admin privileges (default permission level 3) and support `/gatehouse`, `/gh`, and `/wlreq` prefixes:

```text
/gatehouse list [pending|approved|denied|blocked]  - View recent requests
/gatehouse show <request-id|username>             - View request details & history
/gatehouse approve <request-id|username> [reason] - Approve player and add to vanilla whitelist
/gatehouse deny <request-id|username> [reason]    - Deny player request (applies cooldown)
/gatehouse block <request-id|username> [reason]   - Block username from requesting access
/gatehouse unblock <username> [reason]            - Remove username block
/gatehouse undo <request-id|username> [reason]   - Reopen / revert last decision
/gatehouse status                                 - Check worker queue & database health
/gatehouse reload                                 - Reload configuration without restarting
```

---

## ⚙️ Installation & Configuration

1. Download `gatehousemc-1.0.0.jar` and place it in your server's `mods/` directory.
2. Ensure you have **Fabric Loader (0.19.5+)** and **Fabric API** installed.
3. Start the server once to generate default configuration at:
   ```text
   config/gatehousemc/config.json
   ```
4. *(Optional)* Configure your Discord or Telegram bot token in `config.json` (supports `${DISCORD_TOKEN}` and `${TELEGRAM_BOT_TOKEN}` environment variables).

---

## 🔒 Security & Offline Mode Notice

Raw `online-mode=false` servers do not cryptographically verify player identities with Mojang/Microsoft. Approving a username grants access to the offline profile derived from that name. Server administrators should be aware of standard offline-mode identity characteristics. GatehouseMC does not collect or persist player IP addresses.
```
