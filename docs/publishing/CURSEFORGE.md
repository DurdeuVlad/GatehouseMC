# GatehouseMC — CurseForge Project Description

```markdown
<p align="center">
  <img src="https://raw.githubusercontent.com/DurdeuVlad/GatehouseMC/feat/undo-and-i18n/assets/gatehousemc_banner.png" alt="GatehouseMC Banner" width="100%">
</p>

# GatehouseMC

**Server-side Fabric mod for Minecraft 1.21.1.** Automatically captures connection denials on offline-mode servers, generates persistent whitelist requests, and allows administrators to approve or deny requests directly from in-game commands, Discord, and Telegram.

---

### ⚠️ Requirements & Environment

- **Server-Side Only:** Players joining your server DO NOT need this mod installed.
- **Minecraft:** Java Edition 1.21.1
- **Mod Loader:** Fabric Loader (>= 0.19.5) + Fabric API
- **Java:** 21+
- **Server Configuration:** `online-mode=false` and `white-list=true`

---

### 🏰 Why GatehouseMC?

On private or semi-private offline-mode Minecraft servers, onboarding new players is often tedious:
- Players must join Discord or a forum and manually ask for whitelist access.
- Admins must copy-paste usernames into console or edit configuration files.
- Misspelled usernames cause confusion and failed logins.

**GatehouseMC eliminates this friction entirely:**
1. A new player joins the server.
2. Minecraft's native whitelist blocks them with a helpful custom message: *"You are not whitelisted. A whitelist request has been queued automatically."*
3. GatehouseMC creates a durable request and immediately sends an alert to your **Discord** and/or **Telegram** staff channel.
4. An admin clicks **Approve** on their phone or desktop.
5. GatehouseMC immediately adds the player's offline profile to vanilla `whitelist.json`.
6. The player reconnects and is playing!

---

### 🚀 Key Features

- **Automatic Request Generation:** Joining the server IS the registration.
- **Discord Bot Integration:** Embeds with interactive Approve / Deny / Block buttons (powered by JDA).
- **Telegram Bot Integration:** Interactive messages with inline buttons.
- **Reversible Decisions:** Full `/gatehouse undo` support to revert mistakes.
- **Vanilla Whitelist Authority:** Directly mutates vanilla Minecraft whitelist; fully compatible with other server management tools.
- **Durable SQLite Storage:** Requests and notifications are persisted in SQLite with an atomic transactional outbox.
- **Spam Control:** Configurable denial cooldowns and permanent username blocks prevent request spam.
- **Zero Client Dependencies:** Only installed on the dedicated server.

---

### 💻 Commands

All commands can be invoked with `/gatehouse`, `/gh`, or `/wlreq`:

- `/gatehouse list [pending|approved|denied|blocked]` — List stored requests
- `/gatehouse show <request-id|username>` — Show detailed request view
- `/gatehouse approve <request-id|username> [reason]` — Approve player access
- `/gatehouse deny <request-id|username> [reason]` — Deny request (starts cooldown)
- `/gatehouse block <request-id|username> [reason]` — Block username from creating requests
- `/gatehouse unblock <username> [reason]` — Unblock a previously blocked username
- `/gatehouse undo <request-id|username> [reason]` — Revert last approval/denial/block
- `/gatehouse status` — Display queue and database status
- `/gatehouse reload` — Hot-reload bot configurations

---

### 📦 Installation

1. Drop `gatehousemc-1.0.0.jar` into your server's `mods/` directory.
2. Start the server once to generate `config/gatehousemc/config.json`.
3. Optionally configure Discord/Telegram tokens and admin IDs in `config.json`.

---

### 📄 License

GatehouseMC is open-source software licensed under the [MIT License](https://opensource.org/licenses/MIT).
```
