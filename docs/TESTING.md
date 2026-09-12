# Testing & Empirical Verification Strategy

This project uses the HeapHammer-style rule:

> **Nothing works until it has been proven in a real environment.**

Unit tests are necessary but cannot prove Fabric loader behavior, Mixins, offline-mode protocol login, packaged dependencies, or vanilla whitelist persistence.

---

## 1. Verification tiers

### Tier 1 — Pure unit/domain tests

Fast tests that do not boot Minecraft.

Command:

```bash
./gradlew test
```

Required surfaces:

- username normalization;
- request state model;
- denial cooldown;
- pending dedupe;
- case-variant behavior;
- block/unblock semantics;
- decision CAS outcomes;
- concurrent approve/deny/block races;
- approval `RESOLVING` transitions;
- startup recovery decision logic with fake whitelist port;
- routing modes;
- fallback on provider error;
- outbox retry/backoff;
- config parsing/validation/env expansion/redaction;
- authorization policy value logic;
- architecture import rules if implemented.

### Tier 2 — Component/integration tests without Minecraft process

Use controlled adapters:

- temporary SQLite database;
- real migrations/repositories;
- fake `VanillaWhitelistPort`;
- fake `ServerSchedulerPort`;
- fake Discord transport or JDA-facing boundary;
- local fake Telegram HTTP server;
- fake clock.

Required tests:

- request creation transaction creates request + outbox atomically;
- duplicate attempts update one request;
- partial unique index prevents active duplicates under concurrency;
- deny transaction is atomic;
- block transaction creates `identity_blocks` atomically;
- approve claim prevents concurrent deny;
- approval side-effect failure returns request to pending;
- simulated crash after fake whitelist success recovers approved;
- outbox restart resumes retry;
- provider publication uniqueness prevents duplicate logical publication;
- resolved event updates all known publications in `FANOUT` mode;
- `PRIMARY_FALLBACK` tries Telegram when Discord send fails;
- unauthorized Discord/Telegram principals never reach successful decision.

### Tier 3 — Fabric/Minecraft integration tests

Use Fabric's test support/GameTest where useful for:

- mod initialization;
- config loading;
- command registration;
- server lifecycle;
- `FabricVanillaWhitelistAdapter` operations;
- server-thread scheduling;
- database startup/shutdown.

GameTest is **not sufficient** for the pre-join whitelist admission acceptance test.

### Tier 4 — Real dedicated-server E2E

This is the release gate for login/whitelist behavior.

Boot an actual Minecraft **1.21.1 Fabric dedicated server** using the final built mod artifact.

Required server properties:

```properties
online-mode=false
white-list=true
server-port=<isolated test port>
```

The test harness must use a headless offline Minecraft protocol client.

Selected reference implementation:

https://github.com/PrismarineJS/node-minecraft-protocol

It explicitly supports Minecraft 1.21.1 and offline client auth.

---

## 2. Suggested repository test structure

```text
src/test/java/...                  # Tier 1/2
src/gametest/java/...              # optional Tier 3

tools/e2e/
├── package.json
├── package-lock.json
├── src/
│   ├── server-process.js
│   ├── mc-client.js
│   ├── db-inspect.js
│   ├── scenarios.js
│   └── assertions.js
├── fixtures/
│   ├── base-server.properties
│   └── config.test.json
└── README.md
```

The E2E harness may be Node because it is test-only; production remains Java.

---

## 3. Dedicated server provisioning

The E2E harness should create a disposable directory such as:

```text
build/e2e/server-<run-id>/
```

Provision:

1. pinned Minecraft 1.21.1 Fabric server launcher/loader;
2. compatible Fabric API jar;
3. the **built production mod jar** from `build/libs`;
4. `eula=true` (test harness must document that enabling it accepts Mojang's EULA for the test environment);
5. generated `server.properties`;
6. test `config/gatehousemc/config.json`;
7. empty whitelist/database unless scenario requires fixture state.

Do not run the E2E against the developer's personal Minecraft server directory.

### Clean artifact proof

The test server must not have the IDE/Gradle runtime classpath. It must prove that the distributed mod jar has all required runtime dependencies packaged/nested correctly.

---

## 4. Server process control

The harness launches Java as a child process, captures stdout/stderr, and waits for the normal server-ready marker (`Done (...)!` or a robust equivalent).

It must:

- enforce startup timeout;
- fail on early process exit;
- retain logs on failure;
- write admin commands through server stdin where practical;
- send `stop` and wait for graceful exit;
- force-kill only after a bounded shutdown timeout.

Store logs under:

```text
build/e2e/artifacts/<scenario>/server.log
```

---

## 5. Headless client behavior

Pinned `minecraft-protocol` client example:

```js
const mc = require('minecraft-protocol')

const client = mc.createClient({
  host: '127.0.0.1',
  port,
  username: 'E2E_Alice',
  version: '1.21.1',
  auth: 'offline'
})
```

The harness should distinguish:

- rejection/kick before successful join;
- successful login/play state;
- timeout/protocol error.

Do not treat any disconnect as “expected whitelist rejection.” Assert the kick reason contains the mod's expected whitelist-request UX or the vanilla whitelist key/meaning.

---

## 6. Core E2E scenario matrix

### E2E-01 — Unknown player creates request

Setup:

- clean DB;
- empty whitelist;
- providers disabled or fake.

Action:

- connect as `E2E_Alice`.

Assert:

- rejected;
- server stays healthy;
- DB contains exactly one `PENDING` request;
- requested name is `E2E_Alice`;
- UUID is populated;
- attempt count = 1;
- no IP field exists.

### E2E-02 — Repeat attempts dedupe

Connect the same profile 3 additional times.

Assert:

- one active request;
- attempt count = 4;
- last-attempt timestamp advances.

### E2E-03 — Case variant does not create second active request

While `E2E_Alice` is pending, connect `e2e_alice` if accepted by the protocol/name rules.

Assert:

- no second active request for normalized identity;
- original requested exact name/UUID remain approval target;
- audit may record variant.

If Minecraft itself rejects that case variant before whitelist evaluation due naming rules, document the actual observed behavior and adapt this test to a valid casing variation.

### E2E-04 — Approve via console command

Write:

```text
wlreq approve <request-id>
```

to server stdin.

Assert:

- DB reaches `APPROVED`;
- vanilla whitelist contains exact requested profile;
- reconnect `E2E_Alice` succeeds.

This is the critical end-to-end proof.

### E2E-05 — Deny

New player `E2E_Bob` → request → deny.

Assert:

- status `DENIED`;
- reconnect remains rejected;
- no new pending request before cooldown.

### E2E-06 — Block/unblock

Player `E2E_Charlie` → request → block.

Assert:

- identity block exists;
- repeat attempts create no new request.

Then:

```text
wlreq unblock E2E_Charlie
```

and attempt again.

Assert a new pending request can be created.

### E2E-07 — Restart pending persistence

Create pending request, stop server cleanly, restart same E2E directory.

Assert:

- pending request remains;
- repeat join updates same request.

### E2E-08 — Restart approved vanilla behavior

Approve player, restart server, reconnect.

Assert vanilla whitelist still allows join.

### E2E-09 — Unrelated rejection does not create request

Create a ban or another deterministic non-whitelist denial for a profile already outside whitelist as appropriate.

Assert no whitelist request is created because of the non-whitelist rejection path.

The exact setup should be chosen after inspecting vanilla `checkCanJoin` ordering in 1.21.1.

### E2E-10 — Interrupted approval recovery

Provide a deterministic test hook/fixture available only in test builds or component layer to simulate crash boundaries:

Case A:

- DB has `RESOLVING APPROVE`;
- profile is already in vanilla whitelist;
- restart;
- assert `APPROVED`.

Case B:

- DB has `RESOLVING APPROVE`;
- profile not whitelisted;
- restart;
- assert `PENDING`.

Do not add a dangerous production command solely for this test.

---

## 7. Provider/router component scenarios

Real Discord/Telegram internet access is not required for every CI run. External services are inherently flaky and require secrets. Provider logic must be testable behind boundaries.

### P-01 — Discord success

Fake Discord transport returns a message reference.

Assert:

- publication saved;
- outbox completed.

### P-02 — Discord failure → Telegram fallback

Routing `PRIMARY_FALLBACK`.

- Discord fake throws/reports send failure.
- Telegram fake succeeds.

Assert Telegram publication and fallback audit.

### P-03 — Both fail

Assert request remains durable and outbox retries.

### P-04 — FANOUT

Both succeed; request has two publication records.

Resolve from Discord and assert both publications receive update calls.

### P-05 — Unauthorized Discord actor

Adapter rejects before core decision succeeds.

### P-06 — Unauthorized Telegram actor/chat

Adapter rejects and answers callback safely.

### P-07 — Stale button

Request already resolved; button action returns already resolved and does not mutate state.

---

## 8. Optional live provider smoke tests

These are recommended before a public release but should be opt-in because they require secrets.

Example environment variables:

```text
WLREQ_TEST_DISCORD_TOKEN
WLREQ_TEST_DISCORD_GUILD_ID
WLREQ_TEST_DISCORD_CHANNEL_ID
WLREQ_TEST_DISCORD_DM_USER_ID
WLREQ_TEST_TELEGRAM_TOKEN
WLREQ_TEST_TELEGRAM_CHAT_ID
```

Live smoke tests must:

- send a request message;
- verify message ID returned;
- update/resolve message;
- never print token values;
- clean up test messages when practical.

DM-mode smoke is optional because Discord recipient privacy and mutual-guild
rules can reject a bot DM even when the configured user and bot are members of
the same test guild. The release gate uses a private test guild channel and
must not claim DM delivery based on a channel-mode result.

Do not make PR CI fail because a third-party service has a transient outage unless the repository explicitly decides to maintain dedicated integration infrastructure.

---

## 9. Performance / thread-safety tests

### Login enqueue test

Benchmark/inspect the admission hook path to prove it does not call JDBC/HTTP/JDA.

### Queue saturation

Configure tiny queue in a component test, saturate it, and assert:

- producer returns immediately/bounded;
- degraded state/message is emitted;
- server thread would not block.

### Concurrent decision stress

Run many threads attempting random terminal actions on one request against temporary SQLite.

Assert one terminal winner.

---

## 10. Migration tests

Every schema change must test:

- fresh DB → latest schema;
- previous schema fixture → migration → latest;
- preserved request/history data;
- migration is not silently destructive.

Keep migration SQL/version files immutable after release.

---

## 11. CI/release gate

A high-confidence pipeline should run:

```text
1. compile/static checks
2. unit tests
3. component/SQLite tests
4. build production jar
5. optional Fabric GameTests
6. provision clean Minecraft 1.21.1 Fabric server
7. run core E2E scenario matrix
8. archive server logs + E2E report
9. verify no unexpected ERROR/FATAL patterns
10. publish checksum/artifact for release staging
```

Exact Gradle/task names may differ after implementation, but the proof surfaces may not be silently removed.

---

## 12. Test evidence

Each E2E run should write a machine-readable summary, e.g.:

```json
{
  "minecraft": "1.21.1",
  "java": "21",
  "fabricLoader": "...",
  "fabricApi": "...",
  "modJarSha256": "...",
  "scenarios": [
    {"id": "E2E-01", "status": "PASS", "durationMs": 1234}
  ]
}
```

Archive:

```text
build/e2e/artifacts/
├── summary.json
├── mod.sha256
├── E2E-01/server.log
├── E2E-04/server.log
└── ...
```

Never claim release readiness if a required scenario was skipped without explicit maintainer acceptance.
