# Flux Docs document catalog

Use this catalog to choose documents from project evidence. The names below are Flux defaults, not permission to create every possible file. If an equivalent document already exists, map and improve it before creating a duplicate or renaming it.

## Baseline project documents

### `Business.md`

Required baseline for every repository. It explains the problem, affected users or beneficiaries, desired outcome, value, solution shape, actors, business or usage flow, rules, success and failure paths, assumptions, non-goals, and measurable outcomes. A non-commercial project should state its intended value plainly instead of manufacturing revenue logic.

### `Decision.md`

Required baseline for every repository. It is the durable decision log: decision, context, alternatives, rationale, consequences, status, owner/date when known, and revisit conditions. Keep open questions and rejected options visible when they affect future work.

### `Milestones.md`

Required baseline for an active project or planned release. It defines outcome-based checkpoints rather than implementation tasks: intended result, scope boundary, dependencies, target horizon, owner, acceptance evidence, risks, and current status. If a repository is archival, a template, or has no planned project outcome, record that fact in the document map instead of creating an empty file.

## Operating modes

Classify ownership separately from visibility. The same project can be solo and public.

- **Single-developer/private:** use `Business.md`, `Decision.md`, and `Milestones.md` for active work. Focus on the owner's constraints, future re-entry, and evidence. Do not add `Collaboration.md` or `OSS.md` without a real contributor or release need.
- **Single-developer/public:** keep the solo operating model, but always add a focused `Collaboration.md` covering maintainer ownership, contribution boundaries, support, and release expectations. Keep it small when there are no active collaborators. Add `OSS.md` when open-source governance or release expectations exist.
- **Collaborative/private:** use the baseline documents plus `Collaboration.md` for roles, ownership, review, escalation, and decisions across people or teams.
- **Collaborative/public or open source:** use the baseline documents plus `Collaboration.md`; add `OSS.md` for governance, licensing intent, support, releases, security reporting, and community expectations.

## Conditional documents

### `API.md`

Use when the repository exposes or consumes a meaningful API, protocol, CLI contract, webhook, event schema, or other integration boundary. Document actors, endpoints or commands, inputs, outputs, errors, authentication, compatibility/versioning, examples, and contract tests. Do not create it for an entirely internal module with no stable boundary.

### `Collaboration.md`

Use when the repository is public, shared by multiple teams, or expected to receive contributions. Document roles, communication, contribution flow, review expectations, ownership, escalation, and how disagreements are resolved. Keep contributor mechanics aligned with any `CONTRIBUTING.md` rather than duplicating it blindly.

### `OSS.md`

Use for an open-source project or a project preparing for public release. Document project governance, license intent, support expectations, release and security-reporting posture, community norms, and links to `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, and license files when they exist. Do not pretend the project is open source before that decision is made.

## Add only when justified

- `Architecture.md` — important components, boundaries, dependencies, and quality attributes.
- `Data.md` — meaningful data entities, ownership, lifecycle, privacy, retention, or migrations.
- `Operations.md` — deployment, environments, observability, recovery, and operational ownership.
- `Security.md` — threat model, trust boundaries, controls, secrets, and incident expectations.
- `UX.md` — user journeys, interaction rules, accessibility, and content behavior.
- `Testing.md` — test strategy, risk coverage, fixtures, environments, and release gates.

Choose a project-specific document when the concern is substantial enough to need a stable home. Prefer linking to authoritative external or existing documents over copying them.
