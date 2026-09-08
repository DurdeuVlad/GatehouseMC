# Whitelist Request Mod — Issue-Style Task Backlog

> Each task below is deliberately written like a production GitHub issue for direct agent handoff. The structure follows the repository's agent-task contract: **strategic intent, expected responsibilities, anti-assumptions, exact boundaries, measurable acceptance criteria, and executable verification**.

---

# M0-01 — Bootstrap the Fabric 1.21.1 project and dependency baseline

**Milestone:** 0 — Repository & Build Foundation  
**Type:** `chore(build)`  
**Priority:** P0  
**Depends on:** none

## Strategic intent
Create the smallest reproducible project capable of compiling, testing, packaging, and launching a server-side Fabric mod on the exact supported runtime: Minecraft 1.21.1, Java 21, and pinned Fabric/Loom dependencies. This establishes the same kind of version certainty used by HeapHammer and prevents later implementation against accidental or floating versions.

## Expected agent responsibilities
- Create Gradle wrapper/build configuration and pin all build/runtime/test dependencies.
- Configure the Fabric mod metadata and server-side entrypoint.
- Set mod id to `whitelistrequest` unless a maintainer changes it before implementation.
- Keep the initial jar free of client-only entrypoints and gameplay content.
- Add JUnit 5 and any minimal test utilities approved by the architecture.
- Add `runServer`/equivalent developer workflow.
- Ensure Java toolchain 21 is explicit.

## Explicit anti-assumptions
- Do **not** target Paper/Spigot.
- Do **not** add NeoForge/Forge in v1.
- Do **not** use dynamic dependency versions such as `latest.release`.
- Do **not** introduce Discord/Telegram code in this task.
- Do **not** claim runtime compatibility until a dedicated server is actually booted in later milestones.

## Exact file boundaries
**[NEW]** expected build files such as:
- `settings.gradle[.kts]`
- `build.gradle[.kts]`
- `gradle.properties`
- `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- `src/main/resources/fabric.mod.json`
- minimal server initializer under `src/main/java/...`
- `src/test/java/.../BuildSmokeTest.java`

**[MODIFY]**
- `README.md` only if needed to record bootstrap commands

## Acceptance criteria
- [ ] Project targets Minecraft 1.21.1 and Java 21 exactly.
- [ ] Fabric Loader, Fabric API, Loom, and test libraries are pinned.
- [ ] No client entrypoint is required.
- [ ] `./gradlew clean test build` exits 0.
- [ ] Produced jar contains valid Fabric metadata.
- [ ] No Minecraft gameplay registry entries are introduced.

## Verification
```bash
./gradlew --version
./gradlew clean test build
```

## Evidence required
Record dependency versions and build output in `.scratch/PLAN.md` or task notes.

---

# M0-02 — Establish package/module boundaries and architectural guard tests

**Milestone:** 0  
**Type:** `chore(architecture)`  
**Priority:** P0  
**Depends on:** M0-01

## Strategic intent
Make the ports-and-adapters boundary executable instead of aspirational. The core must remain pure Java so business behavior can be tested without booting Minecraft or connecting to external providers.

## Expected agent responsibilities
- Create canonical packages for domain, application/services, ports, persistence adapter, Fabric adapter, Discord adapter, and Telegram adapter.
- Add architectural tests that fail when core imports Minecraft, Fabric, JDA, Telegram, or JDBC implementation types.
- Define only minimal placeholder ports/types required to express boundaries; avoid speculative machinery.

## Explicit anti-assumptions
- Do not move business logic into Mixins or bot handlers.
- Do not add duplicate service classes per adapter.
- Do not over-modularize into separate Gradle subprojects unless there is a demonstrated need.

## Exact file boundaries
**[NEW]**
- core package skeleton
- adapter package skeleton
- architecture test(s), preferably using classpath/package inspection without requiring Minecraft runtime

## Acceptance criteria
- [ ] Pure core compiles under normal unit tests without requiring a server process.
- [ ] Guard test fails if `net.minecraft.*`, `net.fabricmc.*`, JDA, or Telegram classes are imported into the core boundary.
- [ ] Adapter packages may depend inward; core never depends outward.

## Verification
```bash
./gradlew test
```

---

# M0-03 — Implement configuration loading, environment substitution, and validation

**Milestone:** 0  
**Type:** `feat(config)`  
**Priority:** P1  
**Depends on:** M0-01, M0-02

## Strategic intent
Provide a secure, deterministic configuration contract before integrations are implemented. Secrets must be referenceable through environment variables rather than hard-coded into a distributable modpack configuration.

## Expected agent responsibilities
- Implement config model and loader in the Fabric/config adapter boundary.
- Support `${ENV_VAR}` secret substitution.
- Validate routing mode, provider settings, channel/chat/user/role IDs, database path, retry settings, and request policies.
- Fail startup clearly on structurally invalid config while redacting secrets.
- Generate documented defaults on first run.

## Explicit anti-assumptions
- Do not log resolved bot tokens.
- Do not silently ignore malformed critical values.
- Do not perform external network calls during config parsing.

## Acceptance criteria
- [ ] Valid config loads with documented defaults.
- [ ] Missing optional providers do not break core workflow.
- [ ] Invalid required provider fields produce actionable errors.
- [ ] Secret values are redacted from logs/exceptions.
- [ ] Environment substitution has unit tests.

## Verification
```bash
./gradlew test --tests '*Config*'
```

---

# M1-01 — Implement the domain model and request state machine

**Milestone:** 1 — Core Workflow & Persistence  
**Type:** `feat(domain)`  
**Priority:** P0  
**Depends on:** M0-02

## Strategic intent
Define one canonical representation of an offline player request and its lifecycle before persistence or adapters exist.

## Expected agent responsibilities
Implement immutable/value-oriented core types for at least:
- `OfflineIdentity`
- `WhitelistRequest`
- request identifier
- request status (`PENDING`, `RESOLVING`, `APPROVED`, `DENIED`, `BLOCKED`)
- `AdminPrincipal`
- decision action/command/result
- timestamps and attempt counters
- publication/outbox identifiers where required

Document legal transitions in code/tests consistently with `docs/ARCHITECTURE.md`.

## Explicit anti-assumptions
- Offline UUID is **not** authenticated account ownership.
- Do not store Minecraft `GameProfile` inside the pure domain.
- Do not persist IP addresses in v1.
- Do not add terminal states not justified by the specification.

## Acceptance criteria
- [ ] All legal transitions are covered by tests.
- [ ] Illegal transitions are rejected deterministically.
- [ ] Core types contain no Minecraft/provider imports.
- [ ] Offline identity stores exact username, normalized username, and exact offline UUID.

## Verification
```bash
./gradlew test --tests '*Domain*' --tests '*State*'
```

---

# M1-02 — Implement `WhitelistRequestService` and deduplication rules

**Milestone:** 1  
**Type:** `feat(requests)`  
**Priority:** P0  
**Depends on:** M1-01

## Strategic intent
Make repeated rejected logins converge on one canonical pending request rather than generating notification/database spam.

## Expected agent responsibilities
- Implement create-or-update behavior by normalized offline identity.
- Increment attempt count and update `lastAttemptAt` for repeat attempts.
- Preserve exact username/offline UUID from the latest or canonicalized profile according to the spec.
- Define behavior for attempts after DENIED/BLOCKED/APPROVED using product rules.
- Emit core events/commands for integration publication through ports rather than calling providers directly.

## Explicit anti-assumptions
- Do not dedupe solely by raw case-sensitive offline UUID.
- Do not query Mojang/Microsoft.
- Do not directly call SQLite from domain logic.

## Acceptance criteria
- [ ] First attempt creates one request with attempts=1.
- [ ] Repeat equivalent attempts update the same pending request.
- [ ] Case-variation behavior matches the spec and is tested.
- [ ] BLOCKED identity does not create request spam.
- [ ] Service remains deterministic under concurrent calls when backed by a repository enforcing uniqueness.

## Verification
```bash
./gradlew test --tests '*WhitelistRequestService*'
```

---

# M1-03 — Implement SQLite schema, migrations, repositories, and audit trail

**Milestone:** 1  
**Type:** `feat(storage)`  
**Priority:** P0  
**Depends on:** M1-01, M1-02

## Strategic intent
Persist workflow state safely across server restarts without making SQLite a business-logic owner.

## Expected agent responsibilities
- Implement versioned schema migration mechanism.
- Create tables/indexes for requests, publications, audit records, and outbox.
- Enforce uniqueness and compare-and-set semantics in SQL where required.
- Use a configured data directory and safe connection lifecycle.
- Add temporary-database integration tests.

## Explicit anti-assumptions
- Do not let provider adapters execute arbitrary workflow SQL.
- Do not keep a JDBC connection open on Minecraft's server thread.
- Do not persist raw bot tokens or client IPs.

## Acceptance criteria
- [ ] Fresh DB migrates from zero to current schema.
- [ ] Restart/reopen preserves requests and audit history.
- [ ] Concurrent pending-request insertion cannot create duplicate active requests for one normalized identity.
- [ ] Repository tests use real SQLite, not only mocks.

## Verification
```bash
./gradlew test --tests '*Sqlite*' --tests '*Repository*'
```

---

# M1-04 — Implement crash-aware `DecisionService` with first-writer-wins semantics

**Milestone:** 1  
**Type:** `feat(decision)`  
**Priority:** P0  
**Depends on:** M1-01, M1-03

## Strategic intent
Provide one canonical owner for approve/deny/block decisions and eliminate split-brain behavior across Minecraft commands, Discord, and Telegram.

## Expected agent responsibilities
- Implement atomic `PENDING -> RESOLVING` compare-and-set transition.
- Invoke the whitelist port for approval only after winning the transition.
- Finalize to `APPROVED`, `DENIED`, or `BLOCKED` according to action.
- Record actor/provider/audit metadata.
- Return explicit `already resolved`/`already resolving` outcomes to losing callers.
- Implement compensation/recovery semantics documented in `docs/ARCHITECTURE.md` if whitelist mutation fails after `RESOLVING`.

## Explicit anti-assumptions
- Do not synchronize only in memory.
- Do not let Discord/Telegram call `VanillaWhitelistPort` directly.
- Do not mark approval complete before Minecraft's whitelist mutation succeeds.

## Acceptance criteria
- [ ] Parallel approve/deny tests produce exactly one terminal winner.
- [ ] Approval calls whitelist port exactly once.
- [ ] Failed whitelist mutation does not leave a falsely `APPROVED` request.
- [ ] Audit trail records actor and origin provider.

## Verification
```bash
./gradlew test --tests '*DecisionService*' --tests '*Concurrency*'
```

---

# M1-05 — Implement persistent integration outbox and retry scheduler

**Milestone:** 1  
**Type:** `feat(reliability)`  
**Priority:** P1  
**Depends on:** M1-03

## Strategic intent
Ensure external notification outages can never lose a whitelist request or require blocking the Minecraft login path.

## Expected agent responsibilities
- Persist notification/update jobs transactionally with workflow changes where appropriate.
- Implement bounded asynchronous worker(s), retry/backoff, attempt counters, next-attempt timestamps, and terminal dead-letter/error state if defined.
- Make delivery idempotent by request/provider/publication identity.
- Expose provider health to routing logic without turning health into correctness state.

## Explicit anti-assumptions
- Never sleep/retry on the Minecraft thread.
- Do not drop jobs because a provider is offline at creation time.
- Do not create unbounded retry loops.

## Acceptance criteria
- [ ] Request persistence succeeds when all providers are offline.
- [ ] Outbox survives process restart.
- [ ] Failed jobs retry according to deterministic policy.
- [ ] Duplicate worker execution does not duplicate terminal business decisions.

## Verification
```bash
./gradlew test --tests '*Outbox*' --tests '*Retry*'
```

---

# M2-01 — Verify and implement the exact Minecraft 1.21.1 whitelist-rejection hook

**Milestone:** 2 — Minecraft 1.21.1 Fabric Integration  
**Type:** `feat(platform)`  
**Priority:** P0  
**Depends on:** M0-01, M1-02

## Strategic intent
Observe exactly the vanilla whitelist denial branch—no more and no less—on the actual pinned Minecraft 1.21.1 runtime.

## Expected agent responsibilities
- Decompile/inspect the exact Loom-mapped 1.21.1 login/admission method before coding.
- Confirm the relevant `PlayerManager#checkCanJoin` / whitelist branch and exact signatures against the local workspace and `docs/RESEARCH.md`.
- Implement the narrowest reliable Mixin/hook.
- Use fail-fast injector requirements for critical hooks.
- Capture an immutable offline identity payload and enqueue request work without database/network blocking in the login hook.
- Prove bans/full/other disconnects do not trigger requests.

## Explicit anti-assumptions
- Do not reuse a Paper event API.
- Do not hook generic disconnect and infer the reason from text.
- Do not trust method names from 1.20.x or 1.21.4 without local verification.
- Do not perform JDBC/network operations inside the hook.

## Exact file boundaries
**[NEW/MODIFY]** only Fabric/platform/mixin/config resources plus minimal port invocation.

## Acceptance criteria
- [ ] Mixin applies on real Fabric 1.21.1 dedicated server.
- [ ] Whitelist rejection produces exactly one queued request attempt.
- [ ] Ban rejection produces zero requests.
- [ ] Server-full/generic failure paths produce zero requests where testable.
- [ ] Critical Mixin failure is visible at startup rather than silently disabling behavior.

## Verification
```bash
./gradlew build
./gradlew runServer
# plus dedicated-server scenarios from docs/TESTING.md
```

## Evidence required
Store decompiled target/signature notes in `.scratch/MIXIN_INVESTIGATION.md` and live server logs in the E2E artifact directory.

---

# M2-02 — Implement offline identity capture and normalization

**Milestone:** 2  
**Type:** `feat(identity)`  
**Priority:** P0  
**Depends on:** M2-01, M1-01

## Strategic intent
Represent raw `online-mode=false` identity exactly as Minecraft sees it while preventing trivial case-variation request duplication.

## Expected agent responsibilities
- Capture exact login username and exact offline UUID used by Minecraft.
- Normalize username for workflow dedupe using the documented normalization policy.
- Cross-check offline UUID behavior against the actual 1.21.1 `Uuids` implementation/source.
- Never label the identity authenticated.

## Acceptance criteria
- [ ] Known usernames produce the same UUID as Minecraft's 1.21.1 offline UUID helper.
- [ ] Case-variation dedupe behavior is explicit and tested.
- [ ] Exact profile information needed for whitelist insertion remains available through platform/value translation.

## Verification
```bash
./gradlew test --tests '*OfflineIdentity*'
```

---

# M2-03 — Implement `VanillaWhitelistPort` and server-thread mutation scheduling

**Milestone:** 2  
**Type:** `feat(platform)`  
**Priority:** P0  
**Depends on:** M1-04, M2-02

## Strategic intent
Make Minecraft's vanilla whitelist the only authoritative access-control state while keeping Minecraft internals out of core services.

## Expected agent responsibilities
- Implement Fabric adapter for checking/adding/removing whitelist entries as required by the spec.
- Translate pure identity values into the exact 1.21.1 profile/whitelist types.
- Schedule Minecraft-owned state mutation on the server thread.
- Surface success/failure back to `DecisionService` asynchronously without blocking provider threads indefinitely.
- Preserve vanilla `whitelist.json` behavior.

## Explicit anti-assumptions
- Do not directly edit `whitelist.json` unless vanilla APIs make it technically unavoidable and the ADR is updated first.
- Do not let SQLite become the source of access truth.

## Acceptance criteria
- [ ] Approval adds the exact offline profile to vanilla whitelist.
- [ ] Reconnect succeeds because vanilla accepts the player.
- [ ] Whitelist survives Minecraft restart using vanilla persistence.
- [ ] Core packages contain no `GameProfile`/Minecraft whitelist types.

## Verification
Real server required; see M6 and `docs/TESTING.md`.

---

# M2-04 — Implement player-facing rejection/pending/denied/blocked messages

**Milestone:** 2  
**Type:** `feat(ux)`  
**Priority:** P1  
**Depends on:** M2-01, M1-02

## Strategic intent
Give players a deterministic explanation of what happened without requiring Discord, a website, UUID knowledge, or a client mod.

## Expected agent responsibilities
- Render distinct first-request, pending, denied, and blocked messages.
- Keep messaging configurable where practical without embedding business logic in translation strings.
- Avoid leaking internal exception data or admin identities unnecessarily.

## Acceptance criteria
- [ ] First rejected login explains automatic request creation.
- [ ] Repeat pending login does not imply a duplicate request was created.
- [ ] Denied/blocked states are distinguishable.
- [ ] Messages render correctly on vanilla/modded clients without this mod installed client-side.

---

# M2-05 — Implement startup reconciliation and crash recovery

**Milestone:** 2  
**Type:** `feat(recovery)`  
**Priority:** P0  
**Depends on:** M1-04, M2-03

## Strategic intent
Recover safely when a server crashes between SQLite state changes and vanilla whitelist mutation.

## Expected agent responsibilities
- On startup, inspect `RESOLVING` requests and reconcile them against actual vanilla whitelist state.
- Finalize to `APPROVED` only when whitelist truth confirms approval.
- Return unresolved/failed cases to a safe recoverable state according to ADR semantics.
- Emit audit records for automatic recovery.

## Acceptance criteria
- [ ] Simulated crash after whitelist add but before DB finalize recovers to APPROVED.
- [ ] Simulated crash before whitelist add does not falsely grant access.
- [ ] Recovery is idempotent across repeated restarts.

---

# M3-01 — Implement `/wlreq` command tree and permissions

**Milestone:** 3 — Minecraft Administration Interface  
**Type:** `feat(command)`  
**Priority:** P1  
**Depends on:** M1-04, M2-03

## Strategic intent
Ensure the complete workflow is operable even with Discord and Telegram disabled or broken.

## Expected agent responsibilities
Provide a command tree covering at minimum:
- list pending requests
- show one request
- approve
- deny
- block
- unblock/reopen only if defined by spec
- retry/requeue publication where defined
- integration/provider health summary where useful

All actions must invoke canonical services.

## Explicit anti-assumptions
- Commands must not manipulate repository rows or whitelist entries directly.
- Do not grant commands to ordinary players by default.

## Acceptance criteria
- [ ] Authorized server admin can fully resolve a request without external providers.
- [ ] Unauthorized source is rejected.
- [ ] Decision feedback handles already-resolved races cleanly.

---

# M3-02 — Implement command-side request rendering and decision feedback

**Milestone:** 3  
**Type:** `feat(command)`  
**Priority:** P2  
**Depends on:** M3-01

## Strategic intent
Make operational state legible enough that admins can debug the workflow from the server console/in-game command interface.

## Acceptance criteria
- [ ] Request view shows request id, exact/normalized name, offline UUID, status, attempt count, timestamps, and resolution metadata where safe.
- [ ] Provider publication failures are distinguishable from request failure.
- [ ] Secrets and tokens never appear.

---

# M4-01 — Implement `ApprovalInterface` SPI and routing policies

**Milestone:** 4 — Approval Interface Framework & Discord  
**Type:** `feat(integration)`  
**Priority:** P0  
**Depends on:** M1-04, M1-05

## Strategic intent
Create a provider-neutral approval interface so Discord, Telegram, Minecraft commands, and future adapters cannot diverge in business behavior.

## Expected agent responsibilities
- Implement provider SPI/ports for lifecycle, publish, update, health, and inbound decision translation.
- Implement routing policies documented in architecture: FANOUT, PRIMARY_FALLBACK, FIRST_SUCCESS, and ALL_REQUIRED if retained in the spec.
- Default to the selected v1 policy (FANOUT unless spec changed).
- Ensure publication references are persisted for later synchronization.

## Acceptance criteria
- [ ] Fake providers prove routing behavior deterministically.
- [ ] Provider failure cannot mutate request business state incorrectly.
- [ ] Providers never receive direct repository/whitelist authority.

---

# M4-02 — Implement Discord bot lifecycle and request publication

**Milestone:** 4  
**Type:** `feat(discord)`  
**Priority:** P1  
**Depends on:** M4-01, M0-03

## Strategic intent
Make Discord the primary polished approval surface while preserving full provider isolation.

## Expected agent responsibilities
- Use the pinned JDA version from build/research docs.
- Implement safe bot startup/shutdown/reconnect.
- Publish request embed/message with Approve, Deny, Block controls.
- Persist message/channel reference returned by Discord.
- Edit/update message after resolution.
- Respect rate limits and avoid synchronous waits on Minecraft threads.

## Acceptance criteria
- [ ] New request publishes to configured channel.
- [ ] Restart reconnects bot without duplicating already-published requests beyond idempotency policy.
- [ ] Resolved request updates the existing message when possible.
- [ ] Invalid token/provider outage leaves request intact and outbox pending.

---

# M4-03 — Implement Discord authorization and decision interactions

**Milestone:** 4  
**Type:** `feat(discord)`  
**Priority:** P0  
**Depends on:** M4-02, M1-04

## Strategic intent
Allow only configured Discord users/roles to make workflow decisions and translate every interaction into the canonical `DecisionService` contract.

## Expected agent responsibilities
- Validate guild/channel context as configured.
- Authorize by configured role/user allowlist.
- Use opaque/validated interaction identifiers rather than trusting arbitrary callback payload business data.
- Handle stale/already-resolved interactions gracefully.

## Acceptance criteria
- [ ] Authorized role/user can decide.
- [ ] Unauthorized user receives rejection and causes zero state mutation.
- [ ] Stale button cannot override terminal state.
- [ ] Audit principal records provider + stable external actor id + safe display name.

---

# M4-04 — Implement cross-provider publication synchronization

**Milestone:** 4  
**Type:** `feat(integration)`  
**Priority:** P1  
**Depends on:** M4-01, M4-02

## Strategic intent
Ensure all external surfaces converge on the same visible terminal state regardless of where the decision originated.

## Acceptance criteria
- [ ] A resolution event queues updates for every known publication.
- [ ] Provider update failures retry through outbox without reverting business decision.
- [ ] Already-deleted external messages do not crash the workflow.

---

# M5-01 — Implement Telegram bot lifecycle, publication, and callbacks

**Milestone:** 5 — Telegram, Fanout & Reliability  
**Type:** `feat(telegram)`  
**Priority:** P1  
**Depends on:** M4-01, M0-03

## Strategic intent
Provide a second independent approval surface and prove the strategy architecture supports multiple providers cleanly.

## Expected agent responsibilities
- Implement the chosen pinned Telegram Bot API client approach from `docs/RESEARCH.md`.
- Publish request messages with inline buttons.
- Use compact opaque callback tokens mapped server-side to request/action.
- Validate allowed users/chats.
- Edit/update terminal request messages.
- Handle polling/webhook lifecycle according to the accepted ADR/configuration.

## Acceptance criteria
- [ ] Authorized Telegram admin can approve/deny/block.
- [ ] Unauthorized callback causes zero state mutation.
- [ ] Callback payload cannot be forged into an arbitrary request/action without server-side validation.
- [ ] Provider restart/outage leaves request/outbox safe.

---

# M5-02 — Implement provider health, routing, retry, and fallback behavior

**Milestone:** 5  
**Type:** `feat(reliability)`  
**Priority:** P1  
**Depends on:** M1-05, M4-01, M5-01

## Strategic intent
Make integration delivery behavior explicit under partial outages rather than accidental.

## Acceptance criteria
- [ ] FANOUT attempts both enabled providers independently.
- [ ] PRIMARY_FALLBACK and FIRST_SUCCESS behave exactly as documented if enabled.
- [ ] Health status influences routing only; it never determines request truth.
- [ ] Restart resumes pending jobs.
- [ ] Retry/backoff is bounded and tested with fake clock/providers.

---

# M5-03 — Prove concurrent Discord/Telegram decisions converge correctly

**Milestone:** 5  
**Type:** `test(concurrency)`  
**Priority:** P0  
**Depends on:** M4-03, M5-01

## Strategic intent
Prove the architecture's most important multi-interface invariant: two admins acting at the same time cannot split the system.

## Acceptance criteria
- [ ] Concurrent Discord APPROVE and Telegram DENY produce one winning transition.
- [ ] Loser gets already-resolved/resolving result.
- [ ] Vanilla whitelist state matches the winning business state.
- [ ] Both provider messages converge to the final state.
- [ ] Audit trail contains the winner and rejected competing action where specified.

## Verification
Run repeatedly (not once) in component tests and at least once in real-server E2E.

---

# M6-01 — Build the repeatable Fabric dedicated-server test harness

**Milestone:** 6 — Dedicated-Server E2E Automation  
**Type:** `test(e2e)`  
**Priority:** P0  
**Depends on:** M2-03, M3-01

## Strategic intent
Turn "works on a real server" into an executable project property rather than a manual claim.

## Expected agent responsibilities
- Provision a clean Minecraft 1.21.1 Fabric server with pinned loader/API and the freshly built mod jar.
- Generate `eula=true` and test-specific `server.properties` with `online-mode=false` and `white-list=true`.
- Start in an isolated temporary directory/port.
- Wait for a real ready condition.
- Capture stdout/stderr/logs.
- Shut down gracefully and hard-fail on timeout.
- Make harness rerunnable without relying on developer-local state.

## Explicit anti-assumptions
- Do not test against a hand-maintained personal server folder.
- Do not infer readiness from process existence alone.
- Do not leave zombie Java processes after failure.

## Acceptance criteria
- [ ] Harness provisions from clean state.
- [ ] Server reaches ready state with the built jar.
- [ ] Server shuts down cleanly.
- [ ] Logs and runtime versions are archived per test run.

---

# M6-02 — Implement headless offline-mode client login driver

**Milestone:** 6  
**Type:** `test(e2e)`  
**Priority:** P0  
**Depends on:** M6-01

## Strategic intent
Exercise the real Minecraft network login path without requiring a GUI client or Microsoft account.

## Expected agent responsibilities
- Use the pinned `node-minecraft-protocol` approach documented in `docs/TESTING.md`/`docs/RESEARCH.md`, or update the ADR first if technical verification disproves it.
- Connect with deterministic offline usernames.
- Capture disconnect reason, successful login/join, and timeout/failure states.
- Expose CLI/JSON output suitable for automated assertions.

## Acceptance criteria
- [ ] Unknown offline username can attempt a real 1.21.1 login.
- [ ] Harness captures whitelist rejection.
- [ ] Approved username subsequently reaches successful join state.
- [ ] Test client requires no Mojang/Microsoft authentication.

---

# M6-03 — Automate the full acceptance matrix and failure scenarios

**Milestone:** 6  
**Type:** `test(e2e)`  
**Priority:** P0  
**Depends on:** M6-01, M6-02, M5-03

## Strategic intent
Make the business specification executable end to end.

## Required scenarios
At minimum automate:
- unknown player → rejected + request created
- repeat attempt → same request + attempts increment
- approve → vanilla whitelist updated
- approved reconnect → join succeeds
- deny → remains rejected
- block → no request spam
- pending request survives restart
- approved player survives restart via vanilla whitelist
- Discord unavailable → request preserved
- Telegram unavailable → request preserved
- both unavailable → request + outbox preserved
- provider recovery → pending notification delivered
- unauthorized Discord actor rejected
- unauthorized Telegram actor rejected
- concurrent Discord/Telegram conflicting decisions → one winner
- case-variation identity behavior
- non-whitelist rejection does not create request where deterministic reproduction is available

## Acceptance criteria
- [ ] Every required scenario emits machine-readable PASS/FAIL.
- [ ] Failure output contains server log path and relevant DB/provider evidence.
- [ ] Test cleanup is reliable after failures.

---

# M6-04 — Produce deterministic test evidence and CI artifacts

**Milestone:** 6  
**Type:** `test(evidence)`  
**Priority:** P1  
**Depends on:** M6-03

## Strategic intent
Allow maintainers and agents to prove exactly what build/environment passed rather than reporting an unsupported "E2E green" claim.

## Acceptance criteria
Each E2E run records at least:
- git commit SHA
- built jar name + SHA-256
- Minecraft version
- Fabric Loader/API versions
- Java version
- Node/protocol-client version
- effective server properties
- scenario results
- server logs
- SQLite assertion summary
- skipped scenarios and reasons

---

# M7-01 — Implement CI quality gates and E2E workflow

**Milestone:** 7 — OSS, CI & Release Readiness  
**Type:** `ci`  
**Priority:** P0  
**Depends on:** M6-04

## Strategic intent
Make the repository refuse unproven changes and preserve the project's empirical-verification standard.

## Expected agent responsibilities
- Configure CI for clean build, unit/component tests, architecture checks, packaging, and dedicated-server E2E.
- Cache only safe immutable/downloader dependencies; never cache runtime test state that can hide failures.
- Upload E2E logs/results/artifacts on both success and failure where feasible.
- Add timeouts to prevent hung server/client jobs.

## Acceptance criteria
- [ ] A failing unit/component/E2E scenario fails CI.
- [ ] CI exposes enough evidence to diagnose server/Mixin failures.
- [ ] Release workflow cannot publish from a red verification state.

---

# M7-02 — Finalize README, security, admin, and troubleshooting documentation

**Milestone:** 7  
**Type:** `docs`  
**Priority:** P1  
**Depends on:** M6-03

## Strategic intent
Document the implementation that actually exists, not a hypothetical design.

## Expected agent responsibilities
- Write/update public `README.md` with product positioning, install, basic config, and screenshots/examples only if truthful.
- Add `SECURITY.md` with private vulnerability reporting guidance and supported version policy.
- Add permanent server-admin/configuration/troubleshooting docs if the implemented system justifies them.
- Ensure command/config examples are copied from tested behavior.

## Acceptance criteria
- [ ] Fresh server operator can install and configure from docs alone.
- [ ] Docs clearly state offline-mode identity is not authenticated ownership.
- [ ] No secret token examples use real credentials.

---

# M7-03 — Finalize license/dependency review and release workflow

**Milestone:** 7  
**Type:** `chore(oss)`  
**Priority:** P1  
**Depends on:** M7-01

## Strategic intent
Publish a clean open-source artifact without accidental license contamination or undocumented runtime dependencies.

## Expected agent responsibilities
- Select/apply project license according to maintainer decision and `docs/OSS.md`.
- Inventory direct runtime/build/test dependencies and licenses.
- Verify copied/reference implementations were not imported from incompatible sources.
- Define semantic versioning/release tagging and Modrinth/CurseForge/GitHub publication flow as applicable.

## Acceptance criteria
- [ ] License file and metadata agree.
- [ ] Dependency licenses reviewed and documented.
- [ ] Build output contains only intentional bundled dependencies.
- [ ] Release procedure names exact verification gate.

---

# M7-04 — Execute release-candidate verification and produce handoff report

**Milestone:** 7  
**Type:** `release`  
**Priority:** P0  
**Depends on:** M7-01, M7-02, M7-03

## Strategic intent
Close the implementation loop with one auditable final proof package rather than stopping after coding.

## Expected agent responsibilities
- Start from a clean checkout/worktree.
- Run the complete documented verification suite.
- Build the release candidate jar.
- Execute the full real-server E2E matrix.
- Verify clean shutdown/restart behavior.
- Produce a concise final report with commands, versions, results, jar SHA-256, known limitations, and any intentionally deferred items.

## Explicit anti-assumptions
- Do not mark complete with skipped critical E2E tests.
- Do not suppress intermittent failures; investigate or document/block release.
- Do not claim Discord/Telegram live behavior if only fake adapters were exercised; use the required provider test mode/fixtures or real test bots as documented.

## Acceptance criteria
- [ ] All release-gating tests pass.
- [ ] No Tier 0 invariant in `AGENTS.md` is violated.
- [ ] All spec acceptance criteria are either proven or explicitly blocked with maintainer approval.
- [ ] Release candidate jar SHA-256 and evidence locations are recorded.
- [ ] Final working tree contains no transient secrets or local runtime artifacts.

---

# Global agent completion checklist

Before closing any issue above:

- [ ] I inspected existing owners/symbols before adding a new abstraction.
- [ ] I did not move business logic into an adapter.
- [ ] I wrote or identified the RED test/reproduction first for behavior changes.
- [ ] I ran the targeted verification command and inspected the result.
- [ ] I ran the broader required suite for the affected surface.
- [ ] I updated permanent docs/ADR when behavior or architecture changed.
- [ ] I recorded any unresolved assumption in `.scratch/` rather than silently guessing.
- [ ] I did not claim real-server/provider behavior without executing it.
