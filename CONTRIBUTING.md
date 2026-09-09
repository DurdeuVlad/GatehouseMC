# Contributing to GatehouseMC

GatehouseMC is a server-side access-request workflow for modded Minecraft. Because it touches the login path, persistent state, and privileged admin actions, contributions are held to a high proof standard.

This project intentionally follows the collaboration discipline that worked well for [HeapHammer](https://github.com/DurdeuVlad/heaphammer): narrow architecture boundaries, test-first behavior changes, real-server verification, clear issues, and transparent AI-assisted development.

---

## 1. Read first

Before changing production code, read:

1. [`AGENTS.md`](AGENTS.md)
2. [`WHITELIST_REQUEST_SPEC.md`](WHITELIST_REQUEST_SPEC.md)
3. [`docs/DECISION.md`](docs/DECISION.md)
4. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
5. [`docs/TESTING.md`](docs/TESTING.md)

For Minecraft/API questions, use [`docs/RESEARCH.md`](docs/RESEARCH.md) before doing broad research again.

---

## 2. Development prerequisites

- Java 21 JDK (Temurin/OpenJDK recommended)
- Git 2.30+
- Node.js for E2E protocol tests (version pinned by the implementation repository)
- npm or the package manager chosen for `tools/e2e/`

The Gradle wrapper is authoritative. Do not require a globally installed Gradle.

---

## 3. Architecture rules for contributors

### Pure core

Core domain/application code must remain free of Minecraft/Fabric/JDA/Telegram/JDBC-driver implementation imports.

### Adapters

- Minecraft adapter translates `GameProfile` ↔ pure identity values and owns server-thread scheduling.
- SQLite adapter owns SQL and migrations.
- Discord adapter owns JDA events/rendering/authorization.
- Telegram adapter owns Bot API HTTP/rendering/authorization.
- All adapters call core ports/services; none owns request transitions.

### Canonical decisions

Never resolve a request by editing `whitelist.json` directly from an integration. All decisions flow through `DecisionService` and `VanillaWhitelistPort`.

---

## 4. Branching and commits

Recommended branches:

```text
feat/<short-name>
fix/<issue>-<short-name>
docs/<topic>
test/<surface>
```

Use Conventional Commits:

```text
feat(discord): publish actionable whitelist requests
fix(login): avoid requests for banned profiles
test(e2e): prove offline approval reconnect flow
docs(research): pin 1.21.1 whitelist mappings
```

Do not push directly to protected trunk/release branches.

---

## 5. Test-first workflow

Production behavior changes follow RED → GREEN:

1. Add the narrowest test that proves the missing behavior.
2. Confirm it fails for the intended reason.
3. Implement the fix/feature at the canonical owner.
4. Re-run the targeted test.
5. Run the full required tier(s) from `docs/TESTING.md`.

Login/Mixin/whitelist changes always require live dedicated-server verification.

---

## 6. What a high-value issue contains

A useful issue should include:

- precise title;
- Minecraft/Fabric/Java versions;
- `online-mode` and `white-list` values;
- mod version/commit;
- exact reproduction steps;
- expected vs observed behavior;
- relevant server/client logs with secrets removed;
- request ID if applicable;
- whether Discord/Telegram were enabled and provider health state;
- the smallest reproducible scenario.

Example title:

```text
[1.21.1 Fabric][offline] banned player creates whitelist request before rejection
```

---

## 7. Agent / milestone task contract

When writing an implementation task for Codex or another coding agent, include:

1. **Strategic intent** — why this change matters.
2. **Expected responsibilities** — concrete behavior to implement.
3. **Anti-assumptions** — what the agent must not infer/change.
4. **Expected boundaries** — packages/files when known.
5. **Executable acceptance criteria** — tests/commands that prove completion.

Example:

```markdown
### Intent
Add Discord publication without allowing Discord to own request state.

### Responsibilities
- implement DiscordApprovalInterface
- authorize by configured IDs/roles
- publish Approve/Deny/Block buttons
- route actions to DecisionService
- update resolved message

### Must not
- directly call PlayerManager whitelist methods from Discord code
- perform JDA waits on server thread
- add MESSAGE_CONTENT intent

### Proof
- component tests with fake core
- fallback router tests
- optional live Discord smoke with env token
- full unit suite
```

---

## 8. Pull request checklist

Before requesting review:

- [ ] Relevant spec/ADR docs updated if behavior/architecture changed.
- [ ] Production behavior change has a RED → GREEN test.
- [ ] `./gradlew test` passes.
- [ ] Build succeeds from a clean checkout.
- [ ] Critical Mixin target resolves against Minecraft 1.21.1.
- [ ] Required live-server E2E scenarios pass.
- [ ] No tokens/secrets are present in diff or test artifacts.
- [ ] No unnecessary privileged Discord intents were introduced.
- [ ] SQLite migration changes are forward-only and tested from prior schema.
- [ ] No adapter contains duplicate business transition logic.
- [ ] Final artifact was tested on a clean dedicated server when runtime dependencies changed.
- [ ] AI assistance is disclosed when applicable.

---

## 9. AI-assisted contributions

AI-assisted engineering is welcome, but the contributor remains responsible for correctness.

Suggested disclosure:

```markdown
> 🤖 **AI Disclosure:** This contribution was developed with AI pair-programming assistance (tool/model: <name>). The implementation was reviewed and the documented verification commands were executed against the resulting code.
```

Never merge generated Minecraft/Mixin code without resolving it against the actual 1.21.1 dependencies and booting the server.

“The model said this API exists” is not evidence.

---

## 10. Security-sensitive changes

Treat these as security-sensitive and test/review carefully:

- provider authorization
- callback/custom IDs
- config secret handling
- `/wlreq` permission checks
- path handling for database/config files
- state transition races
- block/unblock semantics

Never publish private bot tokens in issues or CI logs.

---

## 11. Documentation style

Permanent docs describe implemented or accepted behavior, not speculative scratch work.

Use `.scratch/` for temporary plans and investigations. Keep permanent docs concise enough to remain maintainable, but explicit enough that a coding agent does not need to rediscover foundational decisions.
