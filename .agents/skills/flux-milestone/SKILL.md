---
name: flux-milestone
description: Turn a repository goal into an evidence-backed issue plan organized into one or more milestones for local or GitHub handoff. Use when a goal needs codebase review, targeted research, decision resolution, scope boundaries, acceptance criteria, and executable issues; do not use for implementing or closing the issues.
---

# Flux Milestone

Turn a desired outcome into a small, ordered set of issues that another agent
or developer can execute without guessing. A milestone is an outcome boundary,
not a date bucket or a pile of tasks.

## Use when

- The user provides a target goal and wants issues or milestones written for a
  repository.
- The work needs codebase inspection, documentation review, web research, or a
  consequential design decision before implementation can be handed off.
- A local issue plan or GitHub-ready issue set is needed.

## Do not use when

- The user wants the issue implemented, tested, reviewed, or merged; use the
  execution and delivery capabilities instead.
- The request is only prose polishing or project-document maintenance.
- The repository, target outcome, or authority boundary is too unclear to
  define an executable issue.

## Input

- Target goal and desired user or system outcome.
- Repository path, current branch, relevant code, documents, and constraints.
- Optional existing issues, milestones, decisions, research, or acceptance
  evidence.
- Ownership and planning metadata when known: assignee, issue type, priority,
  project, iteration, target horizon, and repository labels.
- Desired handoff target: local files, GitHub, or another issue system; and
  explicit authority for creating or modifying external records.

## Actions

1. Compile the Goal/System/Constraints/Evaluation contract and inspect the
   repository before proposing work. Separate observed facts, user decisions,
   assumptions, and unknowns.
2. Select only the supporting capabilities needed. The host may explicitly run
   `/flux-discovery`, `/flux-research`, `/flux-wayfind`, `/flux-define`,
   `/flux-audit`, or `/flux-test`; do not claim that this skill mechanically
   invokes another skill or that a handoff happened without its output.
3. Research the code, existing documentation, and current authoritative web
   sources when they materially affect the goal. Record sources and
   contradictions; do not turn research suggestions into requirements without
   a decision.
4. Map the current state, affected components, ownership, dependencies,
   interfaces, migration or rollback concerns, risks, and the smallest useful
   scope. Do not invent dates, estimates, owners, or priorities; mark them
   unknown or propose them for approval.
5. Decompose the goal into one or more milestones. Each milestone must name its
   outcome, scope boundary, dependencies, risks, and proof of completion.
6. Write independently executable issues. Each issue must include a clear
   title, problem, intended behavior, affected area, non-goals, acceptance
   criteria, test or verification surface, dependencies, suggested labels or
   priority, owner or explicit unassigned status, issue type, project or
   milestone placement, and unresolved decisions. Include iteration or target
   dates only when the target system supports them and the user has supplied or
   approved the planning horizon. Prefer a few coherent issues over either one
   vague epic or a list of implementation chores.
7. Read [issue-template.md](references/issue-template.md) and use its general
   structure when writing an issue. Intent, expectation, acceptance criteria,
   and context that cannot be inferred from code are mandatory. If a mandatory
   fact is unknown, write that it is unknown and mark the issue `needs-input`;
   never hide the gap behind a detailed technical plan.
8. For every issue that may reach a pull request, require the PR body to repeat
   the mandatory contract: intent, expectation, acceptance criteria, non-code
   context, scope and non-goals, affected areas and ownership, dependencies,
   verification evidence, and assumptions or open decisions. A link to the
   issue does not replace this context; use `Not applicable`, `None`, or
   `Unknown—blocked` explicitly where appropriate.
9. Produce local Markdown or GitHub-ready issue text. Create or update local or
   GitHub records only when the target and authority are explicit and the
   required integration is available; otherwise return drafts and say so.
10. Run a handoff audit: an implementer should be able to start from each
    issue, and `/flux-close-issue` should be able to consume the contract
    without inventing acceptance criteria or non-code context.

## Contract contribution

- Goal: turn a repository goal into an executable, evidence-backed issue and
  milestone plan.
- System: inspect, research, resolve consequential uncertainty, decompose, and
  verify handoff completeness.
- Constraints: do not invent project facts, hide uncertainty, create external
  records without authority, or impose a mandatory Flux workflow.
- Evaluation: every milestone has an outcome and proof; every issue has scope,
  acceptance criteria, dependencies, ownership status, and a verification
  surface, with target-system metadata represented accurately. Every issue and
  resulting PR carries the intent, expectation, and non-code context needed by
  a new owner.

## Output

Return:

- the compiled contract and repository facts;
- research sources, decisions, assumptions, contradictions, and unknowns;
- milestones with outcome, scope, dependencies, risks, and evidence;
- issue drafts with title, body, labels or priority, implementation boundaries,
  acceptance criteria, tests, dependencies, ownership, issue type, milestone
  or project metadata, and handoff notes;
- the mandatory PR contract when the issue is intended for pull-request
  delivery;
- local-file or external-record actions taken, or the exact draft status when
  integration or authority is unavailable;
- unresolved decisions, residual risks, and the next useful action.

Use `draft`, `needs-input`, `blocked`, or `ready-for-handoff` honestly. Do not
call a plan ready merely because it is detailed; the acceptance evidence and
authority boundary must be usable.

## Evidence

Evidence includes the inspected repository and existing issues or documents,
source links and dates when web research matters, user-approved decisions,
changed-file or component mapping, and criterion-level acceptance or test
observations. Distinguish evidence from proposed work.

## Stop conditions

Stop and ask when a material product rule, architecture choice, ownership
boundary, compatibility requirement, or acceptance criterion cannot be resolved
safely. Return a draft rather than creating external issues when integration or
authority is missing. Report `blocked` when the repository or required source
cannot be inspected; never fill the gap with plausible detail.
