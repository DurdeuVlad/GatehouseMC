---
name: flux-pr-flow
description: Drive a repository goal end to end through milestone planning and then, issue by issue, through implementation, testing, pull request, adversarial review, remediation, and merge, repeating until every issue in the milestone is delivered or blocked. Use when the user wants a whole goal carried through Flux's planning and delivery skills; do not use for a single already-accepted issue or for planning alone.
---

# Flux PR Flow

A thin orchestration over existing skills, not a new quality gate. It sequences
`/flux-milestone` and `/flux-close-issue`; it does not reimplement their
implementation, test, review, or delivery logic.

## Use when

- The user wants an entire goal carried from planning through every issue's
  merge, not just one accepted issue closed.
- A milestone plan already exists and its issues still need to be driven to
  delivery one by one.

## Do not use when

- Only a single already-accepted issue needs closing; use `/flux-close-issue`
  directly.
- Only planning is wanted, with no intent to implement yet; use
  `/flux-milestone` alone.

## Input

- Target goal, or an already-accepted milestone/issue plan.
- Repository, target branch, and delivery policy (required checks, reviewers,
  protected-branch rules).
- Explicit authority boundaries for commits, pushes, PR creation, issue
  updates, and merging.
- Iteration and retry limits; default to `flux-close-issue`'s defaults (12
  iterations, 2 retries per failed action) when none are supplied.

## Loop

1. Run `/flux-milestone` to produce the issue and milestone plan, unless an
   accepted plan already exists. If it returns `draft`, `needs-input`, or
   `blocked` instead of `ready-for-handoff`, stop and resolve that gap before
   starting any issue — do not begin execution against an unresolved plan.
2. For each issue, in dependency order, run `/flux-close-issue`. It owns
   implementation (optionally via `/flux-code`), test design and verification
   (`/flux-test`, `/flux-verify`), PR preparation, mandatory independent
   adversarial review (`/flux-pr-review`), fix of blocking findings,
   repeat-review-until-clean, the delivery gate, and — when it reaches `done`
   with merge authority explicit — the merge itself, through to one of its
   terminal states.
3. Confirm the outcome `flux-close-issue` reports for the issue.
   `flux-close-issue` performs the merge as part of reaching `done`;
   `flux-pr-flow` never merges independently of it. If it reports anything
   other than `done`, leave the issue at that terminal state — `blocked`,
   `needs-input`, `failed`, `limit-exceeded`, or `unsafe` — and record why,
   without inventing a fix or forcing a merge it did not authorize.
4. Advance to the next issue only after the current one reaches a terminal
   state. Do not run issues concurrently unless the user explicitly allows it.
5. Repeat until the milestone has no remaining issues, or a stop condition
   applies to the milestone as a whole.

## Contract contribution

- Goal: carry a milestone's issues from plan to merged, evidenced delivery,
  or an honest per-issue blocked state.
- System: sequence `/flux-milestone` once and `/flux-close-issue` per issue;
  never duplicate their implementation, test, or review logic here.
- Constraints: preserve each issue's own scope and authority boundary; never
  merge independently of `flux-close-issue`'s own delivery gate and authority
  check; never claim the milestone
  complete while an issue is not `done` or explicitly accepted as blocked.
- Evaluation: every issue in the milestone reaches a terminal state with the
  evidence `flux-close-issue` produced for it.

## Output

Return the milestone plan reference, each issue's terminal state, PR and
merge receipts for issues that reached `done`, aggregate residual risks, and
the next useful action for any issue that did not reach `done`.

## Evidence

Inherits `flux-milestone`'s planning evidence and each issue's
`flux-close-issue` evidence (verification results, adversarial findings and
fixes, review status, delivery receipt). Do not re-derive or duplicate that
evidence here.

## Stop conditions

Use the same terminal-state vocabulary as `flux-close-issue` — `done`,
`blocked`, `needs-input`, `failed`, `limit-exceeded`, `unsafe` — applied per
issue and, when it prevents any further progress, to the milestone as a
whole. Stop at the configured iteration or retry limit rather than looping
forever. Never push, create external records, or merge independently of
`flux-close-issue`'s own delivery gate and authority check for that specific
issue.
