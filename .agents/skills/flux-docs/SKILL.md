---
name: flux-docs
description: "Design, create, and maintain a repository's documentation base as an evidence-backed specification: start with Business.md and Decision.md, add API, Collaboration, OSS, and project-specific documents when justified, and ask for missing project information. Use when a repo's docs are missing, fragmented, or need a durable document map; do not use for prose polishing alone."
---

# Flux Docs

Design the smallest document base that makes a repository understandable, buildable, and maintainable. Treat documentation as a specification surface, not as a pile of Markdown files.

## Use when

- A new or existing repository lacks a coherent documentation base.
- The user wants to create, update, inventory, or detail project documents.
- Requirements, business rules, decisions, APIs, collaboration, or OSS expectations are scattered or implicit.
- Implementation should be driven by written specifications and traceable acceptance criteria.

## Do not use when

- The task is only prose editing, tone, grammar, or audience adaptation; use `flux-write`.
- The user wants an implementation plan or code change without a documentation-base problem; use the relevant execution skill.
- There is no authority to create or modify repository files. Return the proposed document map and questions instead.

## Input

- Repository path and current file tree, code, configuration, and documentation.
- User description of the project, audience, visibility, goals, constraints, and desired outcome.
- Ownership mode: single-developer or collaborative; visibility: private, public, or open source.
- Existing documents, known decisions, interfaces, deployment context, and any requested document names.
- Optional prior contract, checklist, research, or specification.

## Actions

1. Inventory before writing. Identify existing documents, equivalent documents with different names, repository maturity, public/private status, exposed interfaces, and obvious documentation gaps. Preserve existing content and do not overwrite or rename it without authority.
2. Classify two independent dimensions before choosing documents: ownership is `single-developer` or `collaborative`; visibility is `private`, `public`, or `open-source`. Do not treat a public solo project as a team project. Use [document-catalog.md](references/document-catalog.md) for the mode-specific map.
3. Use progressive disclosure. For a blank or underdefined repository, read [blank-repo-question-set.md](references/blank-repo-question-set.md), ask only the first questions needed to establish the next document, then ask follow-up questions as gaps become relevant. Do not dump the whole questionnaire on the user.
4. Fill documents in dependency order: establish the business/problem and intended behavior, record durable decisions and unresolved questions, define outcome-based milestones, then document conditional interfaces, collaboration, OSS, architecture, operations, data, security, UX, or testing concerns.
5. Make `Business.md` concrete. Capture the problem, affected users or customers, value, solution shape, actors, business flow, rules, success and failure paths, assumptions, non-goals, and measurable outcomes. For a non-commercial project, document intended value and beneficiaries instead of inventing a business model.
6. Make `Decision.md` durable. Record the decision, context, alternatives considered, rationale, consequences, status, owner/date when known, and conditions that would justify revisiting it. Separate facts, assumptions, decisions, and open questions.
7. Make `Milestones.md` outcome-based. Record meaningful checkpoints, scope boundaries, dependencies, target horizons, owner, acceptance evidence, risks, and current status. Treat it as a project specification, not a task dump or mandatory Flux workflow.
8. Write for specification-based development. Link important requirements to acceptance criteria, test surfaces, implementation areas, milestones, or operational checks when those are known. Do not let code silently depend on an inferred business rule; surface the missing specification.
9. Create or update only the documents in the agreed map. Keep documents useful to their intended audience, cross-link related specifications, preserve provenance for externally supplied facts, and mark drafts or unknowns explicitly.
10. Evaluate the result against coverage and evidence. Check that the selected documents answer who, why, what, how, constraints, decisions, interfaces, milestones, and proof questions relevant to this project. Report omissions rather than filling them with plausible text.

## Operating modes

- **Single-developer:** optimize for one decision-maker and the future version of that developer. Record ownership, personal constraints, handoff/re-entry context, and proof for milestone outcomes. Do not create team governance or contribution ceremony without evidence.
- **Collaborative:** make decision authority, roles, ownership, review, communication, escalation, and contribution boundaries explicit. Use `Collaboration.md` when multiple contributors, teams, or public contributions are in scope.
- **Public or open-source:** treat visibility separately from ownership. A solo public project may stay single-developer, but public repositories still need `Collaboration.md` as a small maintainer, contribution, and support policy. An open-source project also needs `OSS.md` when release, governance, support, or community expectations matter.

## Contract contribution

- Goal: produce a justified, usable document base that makes the project and its specifications explicit.
- System: inventory first, classify ownership and visibility, disclose questions progressively, compose a minimal document set, and update documents in dependency order.
- Constraints: preserve existing documents and authority boundaries; do not invent project facts, create empty ceremony, or turn documentation into a mandatory workflow.
- Evaluation: every selected document has its required information, milestones describe verifiable outcomes, unresolved gaps are visible, and important requirements are traceable to acceptance or verification evidence.

## Output

Return:

- the repository/document inventory and the proposed document map;
- the ownership/visibility classification and mode-specific rationale;
- required, conditional, deferred, and unknown documents with the reason for each;
- milestone outcomes, horizons, dependencies, evidence, and status when milestones apply;
- the questions needed for the next disclosure step;
- created or updated documents and a concise change summary;
- specification gaps, assumptions, open decisions, evidence, status, blockers, and the next useful action.

Use status values such as `draft`, `partial`, `complete`, `needs-input`, or `blocked`. Never call a document complete merely because the file exists.

## Evidence

Evidence includes the inspected repository tree and existing documents, user-provided answers, linked requirements or acceptance criteria, recorded decisions, and checks showing that references and stated interfaces are consistent. Distinguish observed facts from assumptions and user-approved decisions.

## Stop conditions

Stop and ask when a material business rule, audience, authority boundary, public/private decision, API contract, or document scope cannot be resolved safely. Stop without writing when the user has not authorized repository changes. Report partial work when an existing document is contradictory, stale, inaccessible, or too large to validate rather than silently rewriting it.
