---
name: flux-close-issue
description: Execute and close a repository issue through bounded implementation, meaningful tests, real proof, adversarial review, remediation, and delivery gates. Use when an issue or accepted plan must become a verified change and optionally a PR or merge; do not use for issue planning alone or without an observable acceptance contract.
---

# Flux Close Issue

Coordinate an issue from accepted contract to evidenced delivery. This is a
quality gate and feedback loop, not a promise of subjective perfection. The
main agent manages scope, delegates bounded work when useful, integrates the
result, and owns the final evidence; worker output is untrusted until checked.

## Use when

- The user asks to implement, verify, and close a local or GitHub issue.
- An accepted issue or milestone plan has observable acceptance criteria and
  the repository can be inspected and changed.
- The work needs repeated test, proof, adversarial review, and targeted fixes
  before delivery.

## Do not use when

- The goal still needs decomposition into issues; use `flux-milestone` first.
- The request is only for a code review, test design, research, or documentation
  plan.
- Acceptance criteria, authority, repository access, or a safe rollback path is
  missing; return `needs-input` or `blocked` instead of inventing them.

## Input

- Issue identifier or accepted issue text, including intent, expectation,
  acceptance criteria, non-code context, scope, non-goals, constraints, and
  known risks.
- Repository, branch, current state, relevant prior evidence, and available
  tools or integrations.
- Target branch and repository delivery policy, including required CI,
  security checks, reviewers, code owners, protected-branch rules, and whether
  the issue should be linked for automatic closure.
- User authority for file changes, commits, pushes, PR creation, issue updates,
  and merging into the target branch.
- Effective limits for iterations, retries, time, and scope. If none are
  supplied, use 12 iterations, 2 retries for the same failed action, and zero
  unapproved scope expansions.

## System

1. Adopt the issue contract and inspect the repository, callers, boundaries,
   existing tests, and current branch. Verify that intent, expectation,
   acceptance criteria, and non-code context are present. If the issue is
   incomplete, stop or return to planning rather than silently redefining it.
2. Compile a finite checklist with Goal/System/Constraints/Evaluation. Select
   only the supporting capabilities needed; the host may explicitly run
   `/flux-plan`, `/flux-build`, `/flux-test`, `/flux-verify`,
   `/flux-prove-it-works`, `/flux-audit`, `/flux-pr-review`, or
   `/flux-delivery`. Never claim an automatic skill hook, subagent handoff, or
   completed check that did not occur.
3. Break implementation into bounded slices. Delegate isolated research,
   tracing, implementation, testing, or review to worker agents when that
   reduces risk or context load. Give each worker a narrow contract and inspect
   its diff, evidence, and assumptions before accepting any result.
4. Write or update the smallest meaningful behavior tests before implementation
   when the behavior is changing. Implement only the issue scope, preserving
   unrelated work and recording deviations.
5. Run real verification against the relevant boundary: focused tests first,
   then regression checks and any required API, browser, deployment, migration,
   or operational observation. Apply `/flux-prove-it-works` to select the
   environment closest to production that is safely and authorizedly
   reachable for this observation. Run applicable static analysis, dependency
   and license checks, secret scanning, and dynamic security tests. Record
   each required check as passed, failed, not applicable with rationale, or
   unsupported; a passing test count is not proof if the test cannot observe
   the claimed durable behavior or ran only at a lower fidelity than was
   actually reachable.
6. After green verification, require an independent adversarial review for any
   non-trivial code, configuration, dependency, migration, or security change.
   A self-review by the implementer does not satisfy this gate; use a separate
   reviewer identity or context and report `blocked` or `unsupported` when one
   is required but unavailable. Attack malformed, empty, duplicated, repeated,
   stale, concurrent, timeout, permission, hostile-input, partial-failure,
   rollback, restart, and compatibility cases that apply. Treat P0/P1 findings
   and user-visible or data-integrity P2 findings as blocking.
7. Fix verified blocking findings at the strongest trustworthy boundary, add
   the smallest regression coverage, and repeat all affected verification and
   independent review on the latest change. A new commit invalidates any
   evidence or approval that no longer covers the reviewed diff. Stop at the
   iteration or retry limit; do not loop forever or label an unproven result
   perfect.
8. Apply the delivery gate: acceptance criteria are evidenced, scope is clean,
   required checks passed on the exact proposed head, current approvals and
   resolved conversations cover the final diff, code-owner or specialist review
   exists where required, and commit/PR, issue update, push, or merge authority
   is explicit. Confirm the proposed branch is based on the current target and
   respect protected-branch or merge-queue rules. Prepare delivery locally when
   authority or integration is absent; perform external actions only when
   authorized and available. If closing a GitHub issue, link the PR with a
   supported closing keyword only when closure is intended, target the default
   branch, and verify the merged receipt before marking the issue closed;
   otherwise leave it open with the exact remaining state.

Every PR body must carry the issue’s intent, expectation, acceptance criteria,
non-code context, scope, non-goals, verification evidence, and residual risks.
Do not treat an issue link as a substitute; if this context is missing, return
`needs-input` before requesting review.

## Quality gate

The strongest defensible closure state requires:

- every acceptance criterion mapped to current evidence, gathered from the
  environment closest to production that was safely and authorizedly
  reachable per `/flux-prove-it-works`, with any lower-fidelity gap named;
- the PR body contains intent, expectation, acceptance criteria, and the
  non-code context a diff cannot establish;
- no unresolved P0/P1 or applicable user-visible, data-integrity, reliability,
  or security P2 finding;
- focused and regression checks passing, with unsupported checks called out;
- applicable static analysis, dependency, secret, license, and dynamic security
  checks recorded with failures triaged;
- an inspected final diff containing no unrelated changes or secrets;
- an independent review from a different identity or context, with approval
  current for the final diff;
- target-branch protection, required status checks, resolved conversations, and
  code-owner or specialist approvals satisfied where applicable;
- rollback, migration, compatibility, and operational risks understood where
  relevant;
- a clear receipt of what was changed and what was actually delivered.

This is a quality decision, not an absolute guarantee. The main agent must
report evidence and residual uncertainty rather than asserting that the work is
flawless.

## Contract contribution

- Goal: turn an accepted issue into a verified, review-cleared change or a
  precise blocked result.
- System: manage bounded worker contributions through implementation, testing,
  real verification, adversarial review, remediation, and delivery evaluation.
- Constraints: preserve issue scope and authority; do not trust self-reported
  worker success, hide failures, merge without permission, or loop indefinitely.
- Evaluation: require criterion-level proof, current functional and security
  checks, an independent final review, protected-delivery evidence, explicit
  residual risks, and a defensible delivery state.

## Output

Return:

- issue contract and finite checklist with criterion-level status;
- changed files, worker contributions, decisions, and deviations;
- commands, environments, verification results, and unsupported checks;
- adversarial cases, findings, fixes, repeat-review result, and residual risks;
- delivery state: `done`, `blocked`, `needs-input`, `failed`,
  `limit-exceeded`, or `unsafe`;
- exact issue, PR, commit, push, or merge actions performed, prepared, or still
  awaiting authority.

Do not mark an issue closed merely because code exists, tests are green, a PR
was opened, or a worker reported success. Closure requires the full evidence
map and the authorized delivery outcome.

## Evidence

Evidence includes the issue or milestone contract, repository and diff
inspection, worker outputs that were independently verified, test commands and
results, real-system observations, adversarial findings and fixes, review
status, delivery authority, and the final receipt. Separate observed facts,
user decisions, hypotheses, and remaining uncertainty.

## Stop conditions

Stop immediately as `unsafe` for a hard security, data-loss, authorization, or
scope violation. Stop as `blocked` or `needs-input` when acceptance criteria,
authority, required tools, environment, or rollback information is missing.
Stop as `limit-exceeded` at the configured loop or retry bound. Do not push,
create external records, or merge into the target branch without explicit
authority, and do not claim closure when any required evidence is absent.
