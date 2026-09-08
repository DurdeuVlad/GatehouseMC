# Research & Technical Sourcebook

This document is the implementation reference so Codex should not need to rediscover foundational facts. Use primary sources first. When a mapping/source detail affects a Mixin, still inspect the exact 1.21.1 Loom-decompiled method before coding.

**Research snapshot:** 2026-09-08.

---

## 1. HeapHammer quality baseline

The target is **not** to build HeapHammer again. HeapHammer is the engineering-quality reference: explicit agent constitution, a canonical product spec, architecture decisions, contribution/test discipline, and OSS governance.

### R-HH-01 — Repository

https://github.com/DurdeuVlad/heaphammer

### R-HH-02 — Agent constitution

https://github.com/DurdeuVlad/heaphammer/blob/master/AGENTS.md

Relevant patterns:

- Tier 0 invariants
- pure core / adapter boundaries
- canonical-owner rule
- RED → GREEN proof
- real-environment verification
- anti-hallucination rule
- `.scratch/` vs permanent docs

### R-HH-03 — Canonical product spec

https://github.com/DurdeuVlad/heaphammer/blob/master/HEAPHAMMER_SPEC.md

Relevant pattern: business rules and product constraints live in one authoritative specification rather than dozens of small requirement files.

### R-HH-04 — Contribution/testing discipline

https://github.com/DurdeuVlad/heaphammer/blob/master/CONTRIBUTING.md

### R-HH-05 — Decision log

https://github.com/DurdeuVlad/heaphammer/blob/master/docs/DECISION.md

### R-HH-06 — OSS governance

https://github.com/DurdeuVlad/heaphammer/blob/master/docs/OSS.md

### R-HH-07 — Current 1.21.1 baseline

https://github.com/DurdeuVlad/heaphammer/blob/master/gradle.properties

At research time the repository pins:

```text
minecraft_version=1.21.1
loader_version=0.19.5
loom_version=1.17-SNAPSHOT
fabric_api_version=0.116.17+1.21.1
java_version=21
```

For this project, Minecraft 1.21.1 / Java 21 are product requirements. Loader/API/Loom pins are a known-good bootstrap reference, not a prohibition on a verified compatible update.

---

## 2. Minecraft 1.21.1 login and whitelist APIs

### R-MC-01 — `PlayerManager` Yarn 1.21.1+build.3

https://maven.fabricmc.net/docs/yarn-1.21.1%2Bbuild.3/net/minecraft/server/PlayerManager.html

Relevant named methods:

```text
@Nullable Text checkCanJoin(SocketAddress address, GameProfile profile)
boolean isWhitelisted(GameProfile profile)
Whitelist getWhitelist()
boolean isWhitelistEnabled()
```

Relevant mappings from the 1.21.1 Yarn docs:

```text
PlayerManager = net/minecraft/class_3324
checkCanJoin = method_14586
isWhitelisted = method_14587
getWhitelist = method_14590
```

The method signature for the proposed Mixin target is therefore strongly anchored, but its method body/branch ordering must still be inspected from the actual Loom workspace before implementation.

### R-MC-02 — `Whitelist` Yarn 1.21.1+build.3

https://maven.fabricmc.net/docs/yarn-1.21.1%2Bbuild.3/net/minecraft/server/Whitelist.html

Relevant API:

```text
boolean isAllowed(GameProfile profile)
```

`Whitelist` extends `ServerConfigList<GameProfile, WhitelistEntry>` and inherits `add`, `remove`, `load`, and `save`.

### R-MC-03 — `WhitelistEntry` Yarn 1.21.1+build.3

https://maven.fabricmc.net/docs/yarn-1.21.1%2Bbuild.3/net/minecraft/server/WhitelistEntry.html

Relevant constructor:

```text
WhitelistEntry(GameProfile profile)
```

### R-MC-04 — `ServerConfigList`

Yarn reference (1.21.1 build may be searched directly from the package; build.1 page is also archived):

https://maven.fabricmc.net/docs/yarn-1.21.1%2Bbuild.1/net/minecraft/server/ServerConfigList.html

Relevant methods:

```text
add(V entry)
save()
load()
remove(...)
```

Implementation task: verify whether `add` itself persists in 1.21.1 or whether the adapter must additionally call `save()`. Do not guess and do not manually write `whitelist.json`.

### R-MC-05 — `ServerLoginNetworkHandler` Yarn 1.21.1+build.3

https://maven.fabricmc.net/docs/yarn-1.21.1%2Bbuild.3/net/minecraft/server/network/ServerLoginNetworkHandler.html

The docs state the login handler listens on Netty and is also ticked on the server thread. Relevant fields/methods include the profile/name and verification lifecycle. This is a key reason not to add blocking persistence/network waits to login handling.

### R-MC-06 — Offline UUID utility

Minecraft 1.21.1 Yarn `Uuids` / `UserCache` references:

https://maven.fabricmc.net/docs/yarn-1.21.1%2Bbuild.3/net/minecraft/util/UserCache.html

The nearby Yarn API for `net.minecraft.util.Uuids` documents:

```text
UUID getOfflinePlayerUuid(String nickname)
```

Archived 1.21 docs showing the same stable named/intermediary member:

https://maven.fabricmc.net/docs/yarn-1.21%2Bbuild.7/net/minecraft/util/Uuids.html

Implementation consequence:

- offline-mode UUID is deterministic from the supplied username;
- username casing matters to the derived offline identity;
- the mod should retain the exact `GameProfile` values observed from Minecraft and separately normalize username for request dedupe/spam control.

### R-MC-07 — Whitelist denial translation key

Minecraft 1.21.1 language assets include:

```text
multiplayer.disconnect.not_whitelisted
```

Reference asset browser:

https://mcasset.cloud/1.21.1-rc1/assets/minecraft/lang/en_gb.json

The English rendered string is not a stable detection API; the proposed hook detects the translatable key/content, not localized text.

### R-MC-08 — Mojang mappings browser

https://mappings.dev/1.21.1/net/minecraft/server/players/PlayerList.html

This provides cross-mapping names for the same Minecraft 1.21.1 player-list methods and is useful when comparing Mojang vs Yarn naming.

### R-MC-09 — Decompiled-source browser

https://mcsrc.dev/

mcsrc.dev downloads the Minecraft jar from Mojang to the browser and decompiles client-side. It can be used for source inspection without relying on a stale pasted decompilation. The primary implementation proof should still be the local Loom workspace used to compile the mod.

---

## 3. Fabric/Mixin references

### R-FAB-01 — Login events

Fabric's server login event family exists for login-stage lifecycle access:

https://maven.fabricmc.net/docs/fabric-api-0.110.0%2B1.21.1/net/fabricmc/fabric/api/networking/v1/ServerLoginConnectionEvents.html

It exposes `INIT`, `QUERY_START`, and `DISCONNECT` events. These are useful context, but a generic disconnect is not sufficient to identify whitelist rejection, which is why the chosen design observes `PlayerManager#checkCanJoin`.

### R-FAB-02 — Mixin tutorial using 1.21.1

https://wiki.fabricmc.net/tutorial%3Amixin_your_first_mixin

and the archived draft:

https://wiki.fabricmc.net/tutorial%3Amixin_making_your_first_mixin

Important implementation rule: critical injectors should fail fast when their target stops matching; do not silently run with the whitelist hook missing.

### R-FAB-03 — Fabric automated testing

https://docs.fabricmc.net/develop/automatic-testing

Fabric supports unit testing and Minecraft GameTest/client game-test configurations. For this project, GameTest is useful but **does not replace** the custom dedicated-server network E2E suite because the acceptance surface is pre-join/login admission.

### R-FAB-04 — Fabric API test DSL

https://docs.fabricmc.net/develop/loom/fabric-api

Documents `fabricApi { configureTests { ... } }` and server/client game-test setup.

---

## 4. Discord research

### R-DIS-01 — Discord interactions

https://docs.discord.com/developers/platform/interactions

Discord interactions support application commands, message components/buttons, modals, and Gateway or HTTP delivery.

### R-DIS-02 — Component/button reference

https://docs.discord.com/developers/components/reference

Relevant constraints at research time:

- interactive button `custom_id` is developer-defined;
- custom IDs are limited to 1–100 characters;
- buttons can be disabled;
- multiple action buttons can be grouped in an action row.

The request UUID + action prefix comfortably fits.

### R-DIS-03 — Receiving/responding

https://docs.discord.com/developers/interactions/receiving-and-responding

Use this when implementing interaction acknowledgement/error behavior.

### R-DIS-04 — JDA

Repository:

https://github.com/discord-jda/JDA

Releases:

https://github.com/discord-jda/JDA/releases

At the 2026 research snapshot, **JDA 6.5.0** is listed as the latest release. Pin an exact tested JDA 6.x version in Gradle rather than using a dynamic version.

JDA is Apache-2.0 licensed.

Implementation notes:

- no voice/audio feature is required;
- do not enable `MESSAGE_CONTENT` for button-based approval;
- avoid privileged intents unless role authorization empirically requires additional data beyond the interaction payload;
- use stable Discord user/role IDs for authorization.

---

## 5. Telegram research

### R-TG-01 — Official Bot API

https://core.telegram.org/bots/api

Relevant objects/methods:

- `InlineKeyboardMarkup`
- `InlineKeyboardButton`
- `CallbackQuery`
- `answerCallbackQuery`
- `editMessageText`
- `editMessageReplyMarkup`
- `getUpdates`

The callback sender has a stable numeric user ID, which should be used for authorization rather than display name.

### R-TG-02 — Telegram Bots FAQ / update delivery

https://core.telegram.org/bots/faq

Telegram supports long polling or webhooks. For `getUpdates`, confirm updates by advancing `offset` to `last update_id + 1`.

Decision for v1: long polling using Java 21 `HttpClient`, because the Minecraft server is already a long-running process and should not need a public webhook endpoint.

---

## 6. SQLite research

### R-SQL-01 — SQLite transaction semantics

https://sqlite.org/lang_transaction.html

SQLite supports explicit transactions, commit/rollback, and single-writer behavior. Use transactions for request creation/deduplication, terminal decision CAS, block creation, and outbox insertion.

### R-SQL-02 — xerial sqlite-jdbc

Repository:

https://github.com/xerial/sqlite-jdbc

Releases:

https://github.com/xerial/sqlite-jdbc/releases

At the 2026 research snapshot, release **3.53.2.1** is listed (2026-07-27). Pin the exact version actually tested in the build.

Licensing shown by the project includes Apache-2.0 / BSD-2-Clause components.

---

## 7. E2E Minecraft protocol client research

### R-E2E-01 — PrismarineJS `node-minecraft-protocol`

https://github.com/PrismarineJS/node-minecraft-protocol

The project explicitly supports:

- Minecraft **1.21.1**;
- client online/offline mode;
- `auth: 'offline'`;
- protocol login/disconnect events;
- automated use against Minecraft server jars.

At research time its release page reports **1.66.2** as the latest release (May 2026). Pin the exact E2E dependency in `tools/e2e/package-lock.json`.

API reference:

https://github.com/PrismarineJS/node-minecraft-protocol/blob/master/docs/API.md

Recommended E2E connection:

```js
const mc = require('minecraft-protocol')
const client = mc.createClient({
  host: '127.0.0.1',
  port: 25565,
  username: 'E2E_Alice',
  version: '1.21.1',
  auth: 'offline'
})
```

This avoids requiring a GUI Minecraft client or Microsoft account in CI.

`node-minecraft-protocol` is BSD-3-Clause licensed and should be test-only, not bundled in the mod.

### Alternative — MCProtocolLib

https://github.com/GeyserMC/MCProtocolLib

A Java protocol client/server library (MIT). It can be considered if the team strongly prefers a Java-only test harness. PrismarineJS is selected for v1 because its documentation explicitly lists 1.21.1 and offline client support in the current research snapshot.

---

## 8. Existing / adjacent projects

The following are references, not code dependencies.

### R-COMP-01 — AutoWhitelist

https://modrinth.com/mod/autowhitelist

Server-side Fabric Discord/whitelist project. Useful reference for vanilla-whitelist compatibility and Discord integration. Its primary product flow is Discord account/role linking, not automatic request creation from a failed Minecraft join.

### R-COMP-02 — AstralBot

https://github.com/Erdragh/AstralBot

Useful reference for server-side bot architecture and historical multi-loader separation. Its product is account-linking/Discord oriented, not this exact join-attempt-first workflow.

### R-COMP-03 — Yurushi

https://modrinth.com/mod/yurushi

Fabric Discord whitelist approval workflow. Useful UI reference; request initiation is Discord-centric rather than the failed join itself.

### R-COMP-04 — Advanced Whitelist

https://modrinth.com/plugin/advanced-whitelist

A Paper/Purpur plugin, not a Fabric mod. Important competitive proof that “attempt to join → create request → admin approves” is a useful workflow. It does not satisfy the modded-Fabric requirement.

### R-COMP-05 — Whitelist Sync 2

https://www.curseforge.com/minecraft/mc-mods/whitelistsync2

Adjacent whitelist synchronization project. Useful reference for modern modded whitelist manipulation but not a request/approval replacement.

### Research conclusion

A survey found multiple projects solving pieces of the problem (Discord linking, approval buttons, whitelist management, synchronization) and a plugin implementing a close join-request flow. No mature, clearly dominant Fabric 1.21.1 mod was identified that combines the exact target requirements:

```text
raw online-mode=false
server-side only
failed vanilla whitelist join creates request automatically
persistent request state
Minecraft commands
Discord primary + Telegram fallback
extensible approval interface
```

That is sufficient justification to implement a dedicated mod rather than adapting an unrelated plugin workflow.

---

## 9. Implementation verification checklist for Codex

Before coding the critical hook, verify locally:

- [ ] exact Yarn build selected for 1.21.1;
- [ ] `PlayerManager#checkCanJoin` signature/descriptors;
- [ ] the returned whitelist `Text` is translatable with `multiplayer.disconnect.not_whitelisted`;
- [ ] return-hook injection fires exactly once per whitelist denial;
- [ ] banned player path does not pass the whitelist-detection predicate;
- [ ] `Whitelist.add` persistence behavior in the exact 1.21.1 implementation;
- [ ] server-thread requirement for whitelist mutation;
- [ ] final built jar contains/nests JDA + sqlite-jdbc runtime requirements;
- [ ] Telegram callback-data representation remains inside official size constraints;
- [ ] Discord role authorization works without enabling unnecessary privileged intents.

Record discoveries that materially differ from this research in `.scratch/MIXIN_INVESTIGATION.md`, then update permanent docs/ADR if the accepted design must change.
