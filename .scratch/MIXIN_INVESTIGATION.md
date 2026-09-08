# M2-01 Minecraft 1.21.1 Mixin Investigation

Date: 2026-09-08

## Pinned workspace

- Minecraft: `1.21.1`
- Yarn mappings: `1.21.1+build.3`
- Loom: `1.17.20`
- Java: `21.0.8`

The local Loom cache contains the mapped common sources/classes under:

```text
.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-9b5ff62f35/1.21.1-net.fabricmc.yarn.1_21_1.1.21.1+build.3-v2/
```

`./gradlew.ps1 genSourcesWithVineflower --stacktrace` completed successfully and decompiled 5,363 Minecraft classes (4,064 common and 1,299 client-only cache misses).

## Exact target signature

`javap -p` against the mapped common jar reports:

```text
public net.minecraft.text.Text checkCanJoin(java.net.SocketAddress, com.mojang.authlib.GameProfile);
public boolean isWhitelisted(com.mojang.authlib.GameProfile);
public net.minecraft.server.Whitelist getWhitelist();
public boolean isWhitelistEnabled();
```

The current Mixin target matches the mapped method exactly:

```java
@Inject(method = "checkCanJoin", at = @At("RETURN"), cancellable = true, require = 1)
private void whitelistrequest$afterCheckCanJoin(
        SocketAddress address,
        GameProfile profile,
        CallbackInfoReturnable<Text> callback)
```

`whitelistrequest.mixins.json` has `required: true` and `defaultRequire: 1`.

## Actual bytecode branch order

`javap -c -p net.minecraft.server.PlayerManager` against the same mapped jar shows:

1. `bannedProfiles.contains(profile)` → `multiplayer.disconnect.banned.reason`.
2. `isWhitelisted(profile)` → `multiplayer.disconnect.not_whitelisted` when false.
3. IP-ban check → `multiplayer.disconnect.banned_ip.reason`.
4. player-limit check → `multiplayer.disconnect.server_full`.
5. otherwise returns `null`.

The Mixin return predicate checks only `TranslatableTextContent.getKey()` for `multiplayer.disconnect.not_whitelisted`, so ban, IP-ban, server-full, and successful joins are excluded by construction. It does not compare localized rendered text and does not use a generic disconnect event.

## Commands and evidence

```powershell
.\gradlew.ps1 genSourcesWithVineflower --stacktrace
# BUILD SUCCESSFUL in 1m 27s

jar tf .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-9b5ff62f35/1.21.1-net.fabricmc.yarn.1_21_1.1.21.1+build.3-v2/minecraft-common-9b5ff62f35-1.21.1-net.fabricmc.yarn.1_21_1.1.21.1+build.3-v2-sources.jar
# contains net/minecraft/server/PlayerManager.java

javap -classpath <mapped-common-jar> -p net.minecraft.server.PlayerManager
javap -classpath <mapped-common-jar> -c -p net.minecraft.server.PlayerManager
```

## Offline identity verification

`javap -c -p net.minecraft.util.Uuids` against the same mapped common jar reports that `getOfflinePlayerUuid(String)` concatenates the username with the `OfflinePlayer:` prefix, encodes UTF-8, and calls `UUID.nameUUIDFromBytes`.

The pure-domain helper `PlayerIdentity.offlineUuidFor` implements that exact algorithm for deterministic cross-checks. The live login path does not regenerate or replace the observed UUID: `WhitelistRequestMod` captures `GameProfile.getId()` and `GameProfile.getName()`, then `FabricRuntime.GameProfileIdentity.toDomain()` retains both exact values while `PlayerIdentity` separately derives the lowercase `Locale.ROOT` workflow key.

`./gradlew.ps1 test --tests '*OfflineIdentity*' --rerun-tasks --stacktrace` — PASS. The tests cover the known `E2E_Alice` UUID and case-variant UUID differences with shared normalized workflow identity.

## Clean packaged-server runtime evidence

Disposable server directory:

```text
build/e2e/m2-01-clean-server/
```

The server was launched with only the Fabric 1.21.1 launcher, Fabric API jar, and the packaged `whitelist-request-0.1.0-SNAPSHOT.jar` (no Loom development classpath). It used `online-mode=false`, `white-list=true`, and port `25571`.

Artifact SHA-256 values:

```text
fabric-server-launch.jar                         F5EBBE78F110CD2577440039634B11E6EAD12955D9B9E79F663EB2144334ABB1
fabric-api-0.116.17+1.21.1.jar                  79AC44B40780ACBD884B34C50BE1E39AF682847E5F5CB3B1FDDEEAA768DCE800
whitelist-request-0.1.0-SNAPSHOT.jar            6524F12D94738DF58DE6B0BA899E6EAE144E8AEA26313D06F472D9D1D2D4E96F
```

The complete server log is at `build/e2e/m2-01-clean-server/logs/latest.log`. It records:

- Fabric Loader `0.19.5`, Minecraft `1.21.1`, Fabric API `0.116.17+1.21.1`;
- `whitelistrequest` loaded with nested JDA and SQLite dependencies;
- `Done (5.109s)!` readiness;
- the mod startup line after readiness;
- `E2E_Alice` rejected with the custom whitelist-request message.

The pinned offline client command was executed from `tools/e2e`:

```powershell
$env:MC_PORT='25571'; $env:MC_USERNAME='E2E_Alice'; $env:MC_EXPECTED_STATUS='rejected'; npm run smoke
```

Observed result:

```json
{"status":"rejected","username":"E2E_Alice","reason":"...whitelist request has been queued automatically..."}
```

After the worker settled, SQLite inspection showed exactly one durable pending request and one request-created outbox event:

```text
PENDING|E2E_Alice|1
REQUEST_CREATED|1
REQUEST_CREATED|READY
```

`whitelist.json` remained `[]`. The live bytecode branch proof above establishes that ban/IP-ban/server-full returns occur before or after the exact whitelist-denial branch and therefore do not satisfy the Mixin predicate. A full ban/full/generic network matrix remains part of M6.

## Vanilla whitelist mutation and restart evidence

A second disposable clean-server run used:

```text
build/e2e/m2-03-clean-server/
```

It launched the same packaged artifact with Fabric Loader `0.19.5`, Minecraft `1.21.1`, Fabric API `0.116.17+1.21.1`, `online-mode=false`, `white-list=true`, and port `25572`. Artifact hashes for this run were:

```text
fabric-server-launch.jar                         F5EBBE78F110CD2577440039634B11E6EAD12955D9B9E79F663EB2144334ABB1
fabric-api-0.116.17+1.21.1.jar                  79AC44B40780ACBD884B34C50BE1E39AF682847E5F5CB3B1FDDEEAA768DCE800
whitelist-request-0.1.0-SNAPSHOT.jar            CD74FB6551B82A4712B2819ABFF1B568DDD7F544F367F52DE6A825DAB5D0E105
```

The packaged client first rejected `E2E_M2Player` with the queued-request message. An authorized local RCON console command (`wlreq approve 86b32f8d-97e2-45e3-9625-939ff174326a`) then completed the decision. The running server wrote:

```text
APPROVED|E2E_M2Player|1
whitelist.json: uuid=f3d10e7e-b571-3786-aaeb-91cfb7f88cf2, name=E2E_M2Player
```

The client reconnected successfully (`npm run smoke`, `MC_EXPECTED_STATUS=joined`, exit code 0). After a graceful RCON `stop`, the same clean directory was restarted; the client reconnected successfully again, proving vanilla `whitelist.json` persistence across restart. The second-run log is at `build/e2e/m2-03-clean-server/logs/latest.log`; rotated logs are retained in the same directory.

`javap` against `ServerConfigList` also confirmed `ServerConfigList.add` puts the entry in the map and invokes `save()` internally, with IOException logged by vanilla. Therefore the adapter's `Whitelist.add(new WhitelistEntry(...))` uses the canonical vanilla persistence path and does not write `whitelist.json` directly.
