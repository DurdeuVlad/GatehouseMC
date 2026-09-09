# GatehouseMC — Agent Constitution

> Official project name: **GatehouseMC**  
> Canonical mod id: `gatehousemc`  
> Quality benchmark: [HeapHammer](https://github.com/DurdeuVlad/heaphammer)  
> Product behavior is defined by [`WHITELIST_REQUEST_SPEC.md`](WHITELIST_REQUEST_SPEC.md).

This file is the canonical operating contract for AI coding agents and human developers working in this repository. It is intentionally opinionated. The goal is not to imitate HeapHammer's feature set; the goal is to reproduce the engineering discipline that made HeapHammer reliable: explicit invariants, canonical ownership, strict architecture, test-first changes, and empirical proof on real dedicated servers.

---

## 1. Authority order

When documents disagree, use this precedence unless a human maintainer explicitly overrides it:

1. **This `AGENTS.md`** for agent behavior, safety, proof, and engineering invariants.
2. **`WHITELIST_REQUEST_SPEC.md`** for product/business requirements and acceptance behavior.
3. **`docs/DECISION.md`** for accepted architectural decisions and rejected alternatives.
4. **`docs/ARCHITECTURE.md`** for concrete package boundaries, ports, state machines, threading, persistence, and adapter contracts.
5. **`docs/TESTING.md`** for required proof and completion gates.
6. **`docs/RESEARCH.md`** for researched external facts, mappings, source links, and implementation references.
7. **`CONTRIBUTING.md`** and **`docs/OSS.md`** for collaboration, governance, release, and open-source policy.

Do not silently reinterpret a higher-authority document to match a lower-authority one. If a contradiction is real, record it in `.scratch/` and ask for a decision only if it blocks implementation.

---

## 2. Project philosophy and non-negotiable product invariants

The mod exists to make whitelisting on a **raw `online-mode=false` modded Minecraft server** operationally pleasant without requiring the player to install an extra client authentication mod or manually register somewhere else first.

### Tier 0 product invariants

1. **Minecraft target**: Minecraft Java Edition **1.21.1**, Fabric, Java 21.
2. **Primary deployment**: dedicated server with `online-mode=false` and `white-list=true`.
3. **Server-side only**: ordinary players do not need this mod on the client.
4. **Automatic request creation**: a player attempting to connect and being rejected by the vanilla whitelist creates or updates a whitelist request automatically.
5. **Offline identity is not authentication**: an offline-mode username/UUID does not prove ownership of a Mojang/Microsoft account. Never describe it as authenticated identity.
6. **Vanilla whitelist is authoritative**: approval ultimately changes Minecraft's own whitelist. The mod must not replace normal whitelist enforcement with a parallel access-control system.
7. **Integrations are interfaces, not owners**: Discord, Telegram, and Minecraft commands send decisions to one canonical application service. They never directly mutate the whitelist or persistence state.
8. **No integration dependency for correctness**: Discord and Telegram may both be offline and the request workflow must still persist and remain recoverable.
9. **No remote lookup requirement**: the core path does not require Mojang/Microsoft APIs.
10. **No IP collection by default**: do not persist client IP addresses in v1.

If a proposed implementation violates one of these invariants, do not implement it as a shortcut.

---

## 3. Architecture invariants

The project follows **hexagonal / ports-and-adapters architecture**.

### 3.1 Pure core rule

Packages under the core domain/application boundary must have **zero imports** from:

- `net.minecraft.*`
- `net.fabricmc.*`
- JDA classes
- Telegram transport classes
- JDBC driver implementation classes

Core types contain Java primitives/value objects (`UUID`, `String`, `Instant`, enums, records) and interfaces only.

Minecraft, SQLite, Discord, and Telegram are adapters behind explicit ports.

### 3.2 Canonical owner rule

There is exactly one owner for each business decision:

- request creation/deduplication → `WhitelistRequestService`
- terminal decisions/races → `DecisionService`
- vanilla whitelist mutation → `VanillaWhitelistPort`
- persistence → repository/outbox ports
- routing → `ApprovalInterfaceRouter`
- provider-specific auth → provider adapter

Do not create a second path that “also works.” Fix the canonical owner instead.

### 3.3 No blocking external I/O on Minecraft threads

Never perform the following on the Minecraft server thread or Netty event loop:

- JDBC queries/transactions
- Discord REST/gateway waits
- Telegram HTTP requests/long polling
- sleeps/retries
- unbounded loops
- file scans unrelated to normal vanilla whitelist operations

The login hook must perform only bounded in-memory work and enqueue a command/event to a dedicated worker.

**Allowed exception:** the actual vanilla whitelist mutation must run on the Minecraft server thread because it touches Minecraft-owned state. Do not combine that operation with database or network waits.

### 3.4 Exact whitelist-rejection rule

A request must be created **only** for the vanilla whitelist denial path. Do not create requests for:

- bans
- IP bans
- server full
- duplicate login
- incompatible mod/client state
- malformed login
- server shutdown
- generic disconnects

The preferred Fabric 1.21.1 hook is documented in `docs/ARCHITECTURE.md` and sourced in `docs/RESEARCH.md`. Verify the exact bytecode/mapping before committing a Mixin.

### 3.5 First terminal decision wins

Concurrent decisions must be deterministic. If Discord approves while Telegram denies, exactly one state transition wins. The loser receives an “already resolved / resolving” result.

Do not rely on in-memory synchronization alone. The persistence layer must enforce the compare-and-set transition.

### 3.6 Crash-aware approval rule

Approval spans two systems: SQLite workflow state and Minecraft's whitelist. There is no distributed transaction. Follow the documented `PENDING -> RESOLVING -> APPROVED` flow and startup recovery semantics. Do not simplify this into an unsafe sequence.

---

## 4. Minecraft 1.21.1 anti-hallucination rule

Minecraft internals change frequently. Never trust an API name because it existed in a nearby version.

Before writing a Mixin or Minecraft adapter:

1. Resolve the project with the pinned Minecraft 1.21.1 mappings.
2. Inspect/decompile the actual target method from the Loom workspace.
3. Cross-check the relevant Yarn/mappings source listed in `docs/RESEARCH.md`.
4. Use a narrow target and `require = 1` (or equivalent fail-fast configuration) for critical injectors.
5. Boot a dedicated server and confirm the Mixin applies.

Known 1.21.1 reference points include:

- `net.minecraft.server.PlayerManager#checkCanJoin(SocketAddress, GameProfile)`
- `PlayerManager#isWhitelisted(GameProfile)`
- `PlayerManager#getWhitelist()`
- `net.minecraft.server.Whitelist`
- `net.minecraft.server.WhitelistEntry`
- `net.minecraft.util.Uuids#getOfflinePlayerUuid(String)`

These are references, not permission to skip local verification.

---

## 5. Development scratchpad and permanent docs

Follow the same discipline used successfully in HeapHammer:

- Temporary implementation plans, investigations, failed approaches, task breakdowns, and logs go in **`.scratch/`**.
- Permanent polished documentation goes in **`docs/`** or the root files defined by this handoff.
- Do not commit transient planning noise as permanent documentation.
- When an architectural decision changes, update `docs/DECISION.md` in the same change.
- When product behavior changes, update `WHITELIST_REQUEST_SPEC.md` and relevant tests in the same change.

Recommended temporary files:

```text
.scratch/
├── PLAN.md
├── MILESTONES.md
├── TASKS.md
├── MIXIN_INVESTIGATION.md
└── E2E_NOTES.md
```

The handoff bundle includes initial issue-style `MILESTONES.md` and `TASKS.md`. Codex may refine them as implementation reveals new facts, but it must not use task-plan edits to override higher-authority product, architecture, decision, or testing documents.

---

## 6. Test-first and empirical proof

**Nothing works until it has been executed and proven.**

### 6.1 RED → GREEN requirement

For a production behavior change:

1. **RED** — add a targeted test or reproducible E2E scenario that demonstrates the missing/broken behavior.
2. Confirm it fails for the expected reason.
3. **GREEN** — implement the smallest coherent change at the canonical owner.
4. Re-run the targeted test.
5. Run the complete required verification suite.

Do not write a test after the fact merely to mirror the implementation.

### 6.2 Completion is not `./gradlew build`

A feature is not complete merely because it compiles or unit tests pass.

For any change touching login handling, request creation, approval, persistence, or whitelist mutation, completion requires the relevant live dedicated-server scenarios from `docs/TESTING.md`.

At minimum the release gate must prove on a real Minecraft 1.21.1 Fabric dedicated server:

- `online-mode=false`
- `white-list=true`
- an unknown offline client is rejected
- a request is persisted
- repeat attempts do not create duplicate pending requests
- approval updates vanilla whitelist
- the same client reconnects successfully
- restart preserves workflow state
- bot outages do not lose requests
- concurrent decisions produce one winner

### 6.3 Honest test reporting

Never state “tests pass,” “server boots,” “the Mixin works,” or “E2E is green” unless the exact command was executed and its result inspected.

When handing work back, include:

- commands executed
- exit codes/results
- live server log location
- E2E summary
- any skipped tests and why

---

## 7. Dependency and secret discipline

1. Pin runtime/test dependency versions; do not use dynamic `latest.*` versions.
2. Runtime dependencies required by the final mod must be bundled/nested correctly and proven on a **clean** Fabric server.
3. Never log Discord/Telegram tokens.
4. Environment-variable expansion is allowed for secrets; expanded values must never be written back to disk.
5. Avoid adding a library when JDK 21 provides a small reliable implementation (Telegram HTTP is intentionally implemented using Java `HttpClient` in v1).
6. Do not enable Discord privileged intents unless the implementation proves they are required.

---

## 8. Git and working-tree safety

- Never run destructive commands such as `git reset --hard` or `git clean -fd` in a working checkout without explicit authorization.
- Inspect before editing; do not delete unknown changes.
- Work on topic branches (`feat/*`, `fix/*`, `docs/*`).
- Do not push directly to protected release/trunk branches.
- Use Conventional Commits.
- Keep changes scoped and reviewable.

See `CONTRIBUTING.md` for the full collaboration contract.

---

## 9. Definition of done for Codex

Codex should treat the implementation task as unfinished until all of the following are true:

- [ ] Project builds from a clean checkout with the documented JDK.
- [ ] Pure core has no Minecraft/Fabric/JDA/Telegram implementation imports.
- [ ] Critical Mixin target is verified against Minecraft 1.21.1.
- [ ] SQLite migrations initialize and survive restart.
- [ ] Minecraft command interface works.
- [ ] Discord adapter works against a fake transport and optional live smoke when credentials exist.
- [ ] Telegram adapter works against a fake HTTP server and optional live smoke when credentials exist.
- [ ] Primary/fallback routing works; configurable fanout works.
- [ ] All business state transitions are covered by unit/component tests.
- [ ] Real dedicated-server E2E suite passes.
- [ ] Clean-server artifact test proves runtime dependencies are packaged.
- [ ] No tokens or test secrets are committed.
- [ ] `README.md` / operator docs are generated or updated from the implemented behavior before release.
- [ ] Test evidence and any known limitations are reported truthfully.

---

## 10. Reference quality bar

Use HeapHammer as an engineering-quality benchmark, especially its:

- [AGENTS.md](https://github.com/DurdeuVlad/heaphammer/blob/master/AGENTS.md)
- [HEAPHAMMER_SPEC.md](https://github.com/DurdeuVlad/heaphammer/blob/master/HEAPHAMMER_SPEC.md)
- [CONTRIBUTING.md](https://github.com/DurdeuVlad/heaphammer/blob/master/CONTRIBUTING.md)
- [docs/DECISION.md](https://github.com/DurdeuVlad/heaphammer/blob/master/docs/DECISION.md)
- [docs/OSS.md](https://github.com/DurdeuVlad/heaphammer/blob/master/docs/OSS.md)

Do not copy HeapHammer implementation code or product-specific behavior. Reuse the **engineering system**: explicit invariants, canonical owners, narrow adapters, reproducible tests, and live-server proof.
