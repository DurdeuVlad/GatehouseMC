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

### 5.1 Long-lived branches and artifact targets

The repository tracks a version branch for each target, with `main` and
`1.21.1` as the primary implementation baseline. The matrix below is the target
branch map, not a release approval list. Retained maintenance branches are not
a promise of equal future feature support; only versions with clean-server
evidence may be published.

| Target   | Minecraft | Java Runtime | Loader | Status | Notes |
|----------|-----------|--------------|--------|--------|-------|
| `main`   | 1.21.1    | Java 21     | Fabric | Active trunk | Fast-forward mirror of `1.21.1`; all feature work merges here first |
| `1.21.4` | 1.21.4    | Java 21     | Fabric | Active support | Separate binary build (`1.21.4+build.8`, API `0.119.4+1.21.4`) |
| `1.21.1` | 1.21.1    | Java 21     | Fabric | LTS / primary | Baseline release target (`1.21.1+build.3`, API `0.116.17+1.21.1`) |
| `1.20.6` | 1.20.6    | Java 21     | Fabric | Active support | Short-lived Java 21 release (`1.20.6+build.3`, API `0.100.8+1.20.6`) |
| `1.20.4` | 1.20.4    | Java 17     | Fabric | Active support | Java 17 toolchain (`1.20.4+build.3`, API `0.97.3+1.20.4`) |
| `1.20.1` | 1.20.1    | Java 17     | Fabric | LTS / popular | Legacy gold standard for 1.20 modpacks (`1.20.1+build.10`, API `0.92.12+1.20.1`) |
| `1.19.4` | 1.19.4    | Java 17     | Fabric | Active support | Raw `Text` sendFeedback (`1.19.4+build.2`, API `0.87.2+1.19.4`) |
| `1.19.2` | 1.19.2    | Java 17     | Fabric | LTS / popular | Primary 1.19 modpack LTS (`1.19.2+build.28`, API `0.77.0+1.19.2`) |
| `1.18.2` | 1.18.2    | Java 17     | Fabric | LTS / popular | `LiteralText`, `TranslatableText`, v1 CommandRegistration (`1.18.2+build.4`, API `0.77.0+1.18.2`) |
| `1.17.1` | 1.17.1    | Java 16     | Fabric | Active support | Gson 2.8 compatible parsing (`1.17.1+build.65`, API `0.46.1+1.17`) |
| `1.16.5` | 1.16.5    | Java 8      | Fabric | LTS / popular | Runtime server thread dispatch (`1.16.5+build.10`, API `0.42.0+1.16`) |
| `1.15.2` | 1.15.2    | Java 8      | Fabric | Maintenance | Pure Brigadier suggestions (`1.15.2+build.17`, API `0.28.5+1.15`) |
| `1.14.4` | 1.14.4    | Java 8      | Fabric | Maintenance | Initial official Fabric release (`1.14.4+build.18`, API `0.28.5+1.14`) |

Short-lived branches follow this naming convention:

```text
feat/*      feature work (merges to main)
fix/*       bug/security fixes (merges to main, then cherry-pick to version branches)
docs/*      documentation-only changes
chore/*     build, tooling, dependency bumps
```

### 5.2 Merge direction

```
feat/* ──► main ──► 1.21.1
                         └─► version branches only when a target is actively maintained
fix/*  ────────────────► applicable maintained targets
```

- Features land on `main` first.
- Bug-fixes that apply to all versions are cherry-picked across individual version branches.
- Never merge older version branches back into `main` or newer versions.

### 5.3 Minecraft 1.12.2 Roadmap & Support Boundary

Official Fabric **does not support versions earlier than 1.14**. 
Supporting Minecraft 1.12.2 requires either:
1. **Minecraft Forge (Recommended for 1.12.2)**: 99% of 1.12.2 servers run Forge. Requires a dedicated Forge platform adapter (`@Mod`, Forge events, `CommandBase` replacing Brigadier, `ITextComponent` replacing `Text`).
2. **Legacy Fabric**: An unofficial community backport using `legacy-looming` and `net.legacyfabric.legacy-fabric-api`.
3. **Runtime Environment**: 1.12.2 runs strictly on Java 8, requiring either running under modern Java launchers (such as CleanroomMC) or backporting core domain records and `java.net.http.HttpClient` to Java 8 class standards.

### 5.4 Adding future Minecraft versions

When a new Minecraft version becomes a target:

1. Create a branch named after the version (e.g. `1.22.1`) from the nearest existing version branch.
2. Update `gradle.properties` with the new `minecraft_version`, `yarn_mappings`, and `fabric_version`.
3. Verify the Mixin target (`PlayerManager#checkCanJoin`) exists in the new mapping set.
4. Run `./gradlew clean test build` and boot a real dedicated server.
5. Record verified version pins in `docs/RESEARCH.md`.
6. Update this table.

### 5.5 Branch deprecation

A version branch is deprecated when the corresponding Minecraft release is no longer widely used in active servers. Deprecated branches receive no further commits; they are archived (not deleted) on GitHub.

### 5.6 Release branches

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

- GitHub repository — public source and issue tracker; GitHub Releases is the
  authoritative tagged source/checksum channel. `v1.0.0` is published.
- Modrinth — submitted mod distribution; currently pending moderation.
- CurseForge — submitted secondary distribution; currently pending moderation.

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
