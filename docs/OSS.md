# Open Source Software (OSS) Guide & Governance

This document defines the open-source operating model for GatehouseMC. It is modeled on the successful governance style of HeapHammer but adapted to this project's security/privacy surface.

---

## 1. Mission and values

GatehouseMC exists to make private/modded Minecraft whitelist administration simple, durable, and extensible while preserving vanilla behavior.

Values:

- **Operational simplicity** — connection attempt becomes the request.
- **Compatibility** — vanilla whitelist remains authoritative.
- **Explicit trust** — raw offline identity is never misrepresented as authenticated.
- **Reliability** — external bot outages do not lose workflow state.
- **Privacy minimization** — store only data needed for the feature; no player IP retention in v1.
- **Empirical engineering** — runtime claims require real-server proof.
- **Extensibility without split-brain** — new interfaces adapt to one core workflow.

---

## 2. Project license

The canonical project license is the **MIT License** (see [`LICENSE`](../LICENSE)).

Rationale:

- simple, permissive, and universally recognized in the Minecraft modding and open-source ecosystems;
- allows frictionless inclusion in modpacks, public servers, and private server networks;
- matches `fabric.mod.json` and artifact publishing policies for Modrinth and CurseForge.

---

## 3. Third-party dependency policy

Runtime dependencies must have licenses compatible with project distribution and be documented.

Known/anticipated dependencies:

| Dependency | Purpose | Research license/source |
|---|---|---|
| Fabric Loader/API | mod runtime/API | upstream Fabric repositories (Apache-2.0 family) |
| JDA | Discord | Apache-2.0 |
| xerial sqlite-jdbc | SQLite JDBC | Apache-2.0 / BSD-2-Clause components |
| Gson | config/JSON if directly declared | Apache-2.0 |

Test-only:

| Dependency | Purpose | License |
|---|---|---|
| PrismarineJS `node-minecraft-protocol` | real offline client E2E | BSD-3-Clause |

Rules:

- do not copy source code from incompatible/restrictive projects;
- reference competitor behavior/architecture cleanly instead of reproducing code;
- keep a dependency/license inventory before publication;
- runtime-bundled dependencies must retain required notices/licenses.

---

## 4. Minecraft/Mojang assets and EULA

Do not redistribute Minecraft server/client jars in the repository or release artifact.

E2E tooling may download/provision Minecraft using official/legal mechanisms and must require/record EULA acceptance for the test environment as appropriate.

The mod's distributed jar contains project code and allowed dependencies only.

---

## 5. Branch/release policy

### 5.1 Long-lived branches

| Branch   | Minecraft | Status              | Notes |
|----------|-----------|---------------------|-------|
| `main`   | 1.21.1    | Active trunk        | Fast-forward mirror of `1.21.1`; all feature work merges here first |
| `1.21.1` | 1.21.1    | LTS / maintenance   | Created from `main` at v1.0.0. Bug-fixes and security patches only. Merge-down from `main` cherry-picked by maintainer. |
| `1.21.4` | 1.21.4    | Active development  | Created from `1.21.1` at v1.0.0. Receives same feature/fix PRs after acceptance on `main`/`1.21.1`. |

Short-lived branches follow this naming convention:

```text
feat/*      feature work (merges to main)
fix/*       bug/security fixes (merges to main, then cherry-pick to version branches)
docs/*      documentation-only changes
chore/*     build, tooling, dependency bumps
```

### 5.2 Merge direction

```
feat/* ──► main ──► 1.21.1 ──cherry-pick──► 1.21.4
                                fix/*  ─────────────►
```

- Features land on `main` first.
- Bug-fixes that apply to all versions are cherry-picked to `1.21.1` and `1.21.4` individually.
- Never merge `1.21.4` back into `main` or `1.21.1`.

### 5.3 Adding future Minecraft versions

When a new Minecraft version becomes a target:

1. Create a branch named after the version (e.g. `1.22.1`) from the nearest existing version branch.
2. Update `gradle.properties` with the new `minecraft_version`, `yarn_mappings`, and `fabric_version`.
3. Verify the Mixin target (`PlayerManager#checkCanJoin`) exists in the new mapping set.
4. Run `./gradlew clean test build` and boot a real dedicated server.
5. Record verified version pins in `docs/RESEARCH.md`.
6. Update this table.

### 5.4 Branch deprecation

A version branch is deprecated when the corresponding Minecraft release is no longer widely used in active servers. Deprecated branches receive no further commits; they are archived (not deleted) on GitHub.

### 5.5 Release branches

Releases are tagged directly on the relevant version branch (e.g. `git tag v1.0.0` on `1.21.1`). No separate `release/*` staging branches are needed unless the release workflow requires pre-release review.

---

## 6. Versioning

Use Semantic Versioning for the mod:

- major — breaking behavior/config/API or fundamental architecture change;
- minor — backwards-compatible feature/provider capability;
- patch — backwards-compatible bug/security fix.

Pre-release strategy may be chosen before first public release; do not invent a complex release train before needed.

Every release should record:

- supported Minecraft/Fabric/Java versions;
- config/migration changes;
- security-relevant changes;
- known offline-mode trust limitation;
- checksums/artifacts;
- E2E verification result.

---

## 7. Distribution targets

Expected public distribution:

- GitHub Releases — authoritative source/tag/checksum;
- Modrinth — mod distribution;
- CurseForge — optional/expected secondary distribution.

Do not publish automatically on every commit. Release only after the documented verification gates pass.

---

## 8. Security policy

Security-sensitive areas:

- Discord/Telegram administrator authorization;
- token/config handling;
- request state races;
- command permission checks;
- database path handling;
- callback/custom ID validation;
- denial/block semantics.

Before public release add `SECURITY.md` with a private vulnerability reporting path.

### Offline-mode security disclosure

Documentation and project pages must prominently state:

> On `online-mode=false`, Minecraft does not prove ownership of the supplied username. This mod manages whitelist requests for offline profiles; it does not authenticate Microsoft/Mojang account ownership.

Do not market the project as an authentication/security replacement.

---

## 9. Privacy/data governance

Persisted user-related data in v1:

- exact Minecraft username;
- normalized username;
- offline UUID;
- request timestamps/counts/status;
- administrator provider + stable external ID + display name;
- optional admin reason;
- provider message/container IDs;
- audit events.

Not persisted in v1:

- player IP address;
- chat content unrelated to workflow;
- Discord/Telegram access tokens in DB;
- Microsoft account data.

Operators should be able to back up/delete the SQLite DB as normal server-owned data. A future public admin guide should explain where it lives.

---

## 10. Contribution governance

Contributions follow `../CONTRIBUTING.md` and `../AGENTS.md`.

Expectations:

- respectful community conduct;
- reproducible issues;
- small coherent PRs;
- test proof;
- architecture compliance;
- dependency/license awareness;
- transparent AI assistance.

Before a broad public launch, add a Contributor Covenant `CODE_OF_CONDUCT.md` and optionally DCO sign-off policy.

---

## 11. AI-assisted engineering policy

AI coding/research is allowed and expected to be transparent.

Human contributors remain accountable for:

- licensing;
- security;
- hallucinated APIs;
- test claims;
- final code correctness.

AI-generated code must not bypass the project's anti-hallucination requirement for Minecraft mappings or the real-server test gate.

---

## 12. Publication quality gate

A public release should not be published until:

- unit/component tests pass;
- real dedicated-server E2E passes;
- final clean artifact boots;
- no secrets are present;
- migrations are tested;
- dependency/license inventory is current;
- README/operator docs accurately describe configuration and offline-mode limitations;
- changelog/release notes exist;
- artifact checksum is produced.

This quality gate is intentionally stronger than “Gradle build succeeded.”
