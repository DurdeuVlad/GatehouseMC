# Architectural Decision Log (ADR)

This document records the accepted foundational decisions for Whitelist Request Mod. Update it when a decision materially changes; do not create a competing undocumented architecture.

---

## ADR-001 — Minecraft 1.21.1 Fabric / Java 21 initial target

- **Status:** Accepted
- **Context:** The project is intended to match the modern baseline of HeapHammer and target a concrete modded-server environment rather than over-generalizing before the first working release.
- **Decision:** v1 targets Minecraft **1.21.1**, Fabric, Java 21. Bootstrap dependency pins from HeapHammer's 1.21.1 trunk, then pin any necessary compatible updates explicitly.
- **Rejected:** Multi-loader from day one; multiple Minecraft versions in one implementation milestone.
- **Consequences:** Faster convergence, simpler Mixin verification, smaller E2E matrix. Future ports must preserve core boundaries.

---

## ADR-002 — Raw `online-mode=false` is the primary supported deployment

- **Status:** Accepted
- **Context:** The target servers intentionally run without Mojang authentication and without an authentication proxy.
- **Decision:** Raw offline mode is a first-class product requirement and release test configuration.
- **Rejected:** Requiring `online-mode=true`, Velocity, or a client auth mod.
- **Consequences:** Offline username/UUID must be documented as unauthenticated. Username impersonation remains possible by design of Minecraft offline mode.

---

## ADR-003 — Exact offline profile + normalized-name workflow key

- **Status:** Accepted
- **Context:** Offline UUIDs are deterministic from username and therefore casing can produce different UUIDs. Pure UUID deduplication allows casing spam; pure lowercase identity would lose the exact profile Minecraft expects.
- **Decision:** Store both exact username/UUID and `Locale.ROOT` normalized username. The first exact profile owns an active request; the normalized name prevents simultaneous pending case variants.
- **Consequences:** Approval whitelists the first exact requested profile. Later case variants are audit/attempt context, not silent approval-target changes.

---

## ADR-004 — Vanilla whitelist remains canonical access control

- **Status:** Accepted
- **Context:** Replacing vanilla whitelist would reduce compatibility with other mods/tools and create a split-brain access model.
- **Decision:** The workflow DB stores requests, not an alternative allowlist. Approval mutates Minecraft's own `Whitelist` through a Fabric adapter.
- **Rejected:** Custom allow/deny check that bypasses `PlayerManager`; direct JSON ownership of `whitelist.json`.
- **Consequences:** Other vanilla-compatible tools continue to see approved players. Manual admin whitelist changes remain authoritative.

---

## ADR-005 — Hexagonal architecture with a pure Java core

- **Status:** Accepted
- **Context:** Login mappings, Discord APIs, Telegram transport, and persistence will evolve independently.
- **Decision:** Core domain/application logic is pure Java behind ports. Minecraft, SQLite, Discord, and Telegram are adapters.
- **Rejected:** `MainMod.java` orchestration with direct calls from bot listeners to Minecraft/SQL.
- **Consequences:** Unit-testable state machine and future provider/loader portability at the cost of explicit interfaces.

---

## ADR-006 — Observe `PlayerManager#checkCanJoin` whitelist return

- **Status:** Accepted, subject to live 1.21.1 bytecode verification
- **Context:** A generic disconnect event cannot distinguish whitelist denial from bans/server-full/etc. Redirecting the whitelist call is more conflict-prone.
- **Decision:** Preferred Mixin injects at return of 1.21.1 `PlayerManager#checkCanJoin(SocketAddress, GameProfile)`, identifies the vanilla translatable key `multiplayer.disconnect.not_whitelisted`, enqueues the request attempt, and may replace only the rejection message.
- **Rejected:** Generic disconnect listener; wholesale overwrite of `checkCanJoin`; primary `@Redirect` of `isWhitelisted`.
- **Consequences:** Minimal interference with vanilla admission ordering. If compatibility evidence invalidates the return-key approach, update this ADR before changing strategy.

---

## ADR-007 — Async persistence worker + admission cache

- **Status:** Accepted
- **Context:** The login check occurs on latency-sensitive Minecraft/network execution paths. SQLite/network calls must not block them.
- **Decision:** Login hook reads an in-memory admission cache and offers a bounded command to a dedicated worker. SQLite transactions run off-thread.
- **Rejected:** JDBC call inside Mixin; waiting for Discord/Telegram before returning kick response.
- **Consequences:** Very short crash window exists between enqueue and DB commit; queue saturation must fail visibly/degraded rather than block.

---

## ADR-008 — SQLite workflow store with persistent outbox

- **Status:** Accepted
- **Context:** Single-server deployment needs durable requests, audit, restart recovery, and provider retry without external infrastructure.
- **Decision:** Use SQLite with migrations and a persistent integration outbox.
- **Rejected:** JSON flat files; external database as mandatory v1 dependency; integration-only message state.
- **Consequences:** Simple deployment, transactional compare-and-set, easy backup. DB I/O must be isolated from Minecraft threads.

---

## ADR-009 — `RESOLVING` approval state for cross-system consistency

- **Status:** Accepted
- **Context:** Approval must atomically win against other decisions but also perform a Minecraft-side whitelist mutation outside the SQLite transaction.
- **Decision:** CAS `PENDING -> RESOLVING`, perform vanilla whitelist mutation on server thread, then finalize `APPROVED`; on failure reset `PENDING`. Startup recovery inspects interrupted `RESOLVING` requests.
- **Rejected:** Whitelist first then update DB; DB approve first then best-effort whitelist with no recovery.
- **Consequences:** Slightly more state complexity, but no contradictory approve/deny race and crash behavior is recoverable.

---

## ADR-010 — Strategy-based approval interfaces; Discord primary / Telegram fallback

- **Status:** Accepted
- **Context:** External admin interfaces must be extensible. Discord is preferred operationally; Telegram is desired as backup, with more providers later.
- **Decision:** Define an `ApprovalInterface` port and router. Default `PRIMARY_FALLBACK` order is Discord → Telegram. Also support configurable `FANOUT`.
- **Rejected:** Hard-coded Discord bot; duplicated per-provider business logic.
- **Consequences:** Core does not care which UI resolves a request. Additional providers can be added without changing decision semantics.

---

## ADR-011 — JDA for Discord, JDK `HttpClient` for Telegram

- **Status:** Accepted
- **Context:** Discord gateway protocol is complex and mature JDA support exists; Telegram Bot API is simple HTTP/long polling and does not require a wrapper for the v1 surface.
- **Decision:** Use pinned JDA 6.x for Discord. Implement Telegram with Java 21 `HttpClient` + JSON handling against official Bot API.
- **Rejected:** Hand-written Discord gateway; extra Telegram framework dependency.
- **Consequences:** Mature Discord behavior with minimal Telegram dependency surface.

---

## ADR-012 — JSON configuration + environment secret expansion

- **Status:** Accepted
- **Context:** The config needs nested provider settings and secret references. Adding a TOML parser is not necessary for v1.
- **Decision:** `config/whitelistrequest/config.json`, with `${ENV_VAR}` expansion performed in memory and strict validation.
- **Rejected:** Storing secrets only as literals; adding a config parser solely for syntax preference.
- **Consequences:** Low dependency count, predictable schema. Expanded tokens must never be persisted or logged.

---

## ADR-013 — `BLOCKED` controls request creation, not vanilla bans

- **Status:** Accepted
- **Context:** “Block request spam” and “ban a player from the server” are different administrative actions.
- **Decision:** Block inserts `identity_blocks` for normalized username and prevents future request creation. It does not write vanilla ban lists in v1.
- **Consequences:** Clear separation of concerns and no accidental punishment beyond request workflow.

---

## ADR-014 — No player IP persistence

- **Status:** Accepted
- **Context:** IP address is unnecessary for the core request workflow and increases privacy/security responsibility.
- **Decision:** Do not store IP addresses in v1.
- **Consequences:** Smaller data footprint; operators needing network-level controls use existing server/firewall/ban tooling.

---

## ADR-015 — Real dedicated-server E2E is a release gate

- **Status:** Accepted
- **Context:** Unit tests cannot prove Mixins, loader packaging, offline login protocol, or vanilla whitelist behavior.
- **Decision:** The acceptance suite must boot a real Minecraft 1.21.1 Fabric dedicated server and connect an offline-mode protocol client. Build success alone is never release proof.
- **Consequences:** CI is slower and more operationally involved, but runtime confidence is materially higher.
