# Whitelist Request Mod — Milestones

> This file is the initial execution roadmap for Codex and human contributors. It is intentionally stored in `.scratch/` because milestones may evolve during implementation. Permanent product and architecture requirements remain authoritative in `WHITELIST_REQUEST_SPEC.md`, `docs/ARCHITECTURE.md`, `docs/DECISION.md`, and `docs/TESTING.md`.

## Milestone 0 — Repository & Build Foundation

**Outcome:** A clean Minecraft 1.21.1 / Fabric / Java 21 project skeleton with reproducible builds, package boundaries, test scaffolding, and no business behavior yet.

**Exit gate:** `./gradlew clean test build` succeeds on a clean checkout and package-level architectural tests prevent Minecraft/integration imports from leaking into the pure core.

Issues:
- M0-01 — Bootstrap the Fabric 1.21.1 project and dependency baseline
- M0-02 — Establish package/module boundaries and architectural guard tests
- M0-03 — Implement configuration loading, environment substitution, and validation

---

## Milestone 1 — Core Workflow & Persistence

**Outcome:** The request lifecycle works entirely in pure Java against SQLite/fakes, independent of Minecraft, Discord, or Telegram.

**Exit gate:** Request deduplication, state transitions, persistence, first-terminal-decision-wins semantics, audit records, and outbox reliability pass unit/component tests.

Issues:
- M1-01 — Implement the domain model and request state machine
- M1-02 — Implement `WhitelistRequestService` and deduplication rules
- M1-03 — Implement SQLite schema, migrations, repositories, and audit trail
- M1-04 — Implement crash-aware `DecisionService` with first-writer-wins semantics
- M1-05 — Implement persistent integration outbox and retry scheduler

---

## Milestone 2 — Minecraft 1.21.1 Fabric Integration

**Outcome:** A real Fabric dedicated server running `online-mode=false` and `white-list=true` automatically creates requests only on vanilla whitelist rejection and applies approved identities through Minecraft's own whitelist.

**Exit gate:** Real dedicated-server tests prove rejected login → persisted request → approval → successful reconnect. Non-whitelist rejection paths must not create requests.

Issues:
- M2-01 — Verify and implement the exact 1.21.1 whitelist-rejection hook
- M2-02 — Implement offline identity capture and normalization
- M2-03 — Implement `VanillaWhitelistPort` and server-thread mutation scheduling
- M2-04 — Implement player-facing rejection/pending/denied/blocked messages
- M2-05 — Implement startup reconciliation and crash recovery

---

## Milestone 3 — Minecraft Administration Interface

**Outcome:** Server operators can fully inspect and resolve workflow state from Minecraft commands even when all external integrations are disabled.

**Exit gate:** Admin commands list/show/approve/deny/block/unblock/retry requests through the same canonical services used by external integrations.

Issues:
- M3-01 — Implement `/wlreq` command tree and permissions
- M3-02 — Implement command-side request rendering and decision feedback

---

## Milestone 4 — Approval Interface Framework & Discord

**Outcome:** External approval is formalized as a provider strategy, and Discord is a complete first-class adapter without owning business state.

**Exit gate:** Discord receives actionable requests, authorizes admins, resolves requests via `DecisionService`, updates stale messages, survives reconnects, and never blocks Minecraft threads.

Issues:
- M4-01 — Implement `ApprovalInterface` SPI and routing policies
- M4-02 — Implement Discord bot lifecycle and request publication
- M4-03 — Implement Discord authorization and decision interactions
- M4-04 — Implement cross-provider publication synchronization

---

## Milestone 5 — Telegram, Fanout & Reliability

**Outcome:** Telegram provides a second approval surface, and Discord + Telegram operate together under configurable routing with persistent delivery semantics.

**Exit gate:** Default FANOUT publishes to both providers; either may resolve a request; both reflect the terminal state; outages and retries are proven by tests.

Issues:
- M5-01 — Implement Telegram bot lifecycle, publication, and callbacks
- M5-02 — Implement provider health, routing, retry, and fallback behavior
- M5-03 — Prove concurrent Discord/Telegram decisions converge correctly

---

## Milestone 6 — Dedicated-Server E2E Automation

**Outcome:** The project can prove its own critical behavior by provisioning and controlling a real Fabric 1.21.1 dedicated server and a headless offline-mode Minecraft client.

**Exit gate:** The automated matrix in `docs/TESTING.md` passes from a clean environment and stores machine-readable evidence/logs.

Issues:
- M6-01 — Build the repeatable Fabric dedicated-server test harness
- M6-02 — Implement headless offline-mode client login driver
- M6-03 — Automate the full acceptance matrix and failure scenarios
- M6-04 — Produce deterministic test evidence and CI artifacts

---

## Milestone 7 — OSS, CI & Release Readiness

**Outcome:** The repository is safe to hand to contributors and publish as a high-quality open-source mod.

**Exit gate:** CI runs required verification tiers, security/OSS docs are complete, dependency licenses are reviewed, release artifacts are reproducible enough for maintainers, and server-admin documentation is accurate against a tested build.

Issues:
- M7-01 — Implement CI quality gates and E2E workflow
- M7-02 — Finalize README, security, admin, and troubleshooting documentation
- M7-03 — Finalize license/dependency review and release workflow
- M7-04 — Execute release-candidate verification and produce handoff report

---

# Dependency graph

```text
M0
└── M1
    ├── M2
    │   └── M3
    └── M4
        └── M5

M2 + M3 + M5
└── M6
    └── M7
```

Parallelism is encouraged only where architecture permits it. Do not parallelize work that creates competing owners for the same domain behavior.

# Release definition of done

The first publishable v1 is complete only when all milestone exit gates are satisfied and the real dedicated-server acceptance matrix is green. A successful Gradle build alone is never sufficient evidence.
