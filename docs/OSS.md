# Open Source Software (OSS) Guide & Governance

This document defines the intended open-source operating model for Whitelist Request Mod. It is modeled on the successful governance style of HeapHammer but adapted to this project's security/privacy surface.

---

## 1. Mission and values

Whitelist Request Mod exists to make private/modded Minecraft whitelist administration simple, durable, and extensible while preserving vanilla behavior.

Values:

- **Operational simplicity** — connection attempt becomes the request.
- **Compatibility** — vanilla whitelist remains authoritative.
- **Explicit trust** — raw offline identity is never misrepresented as authenticated.
- **Reliability** — external bot outages do not lose workflow state.
- **Privacy minimization** — store only data needed for the feature; no player IP retention in v1.
- **Empirical engineering** — runtime claims require real-server proof.
- **Extensibility without split-brain** — new interfaces adapt to one core workflow.

---

## 2. Recommended license

Recommended project license: **GNU Lesser General Public License v3.0 (LGPL-3.0)**, matching the broad interoperability goals and the licensing model used by HeapHammer.

Rationale:

- keeps the mod itself free/open;
- allows inclusion in public/private modpacks and servers;
- does not require unrelated mods or server configuration to adopt the same license merely because they run alongside it.

Before public release, add a canonical `LICENSE` file and make repository/package metadata agree with it.

This document is project policy guidance, not legal advice.

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

Initial project can keep a simple model:

```text
master/main         active production trunk for Minecraft 1.21.1
release/v*          release staging when needed
feat/*              feature work
fix/*               fixes
docs/*              documentation
```

Do not create historical Minecraft branches until there is an explicit support commitment.

If additional Minecraft versions become LTS targets later, use a HeapHammer-style version-branch policy rather than an unbounded branch per game version.

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
