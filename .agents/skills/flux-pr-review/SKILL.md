---
name: flux-pr-review
description: Review a GitHub pull request or explicit commit range for intent, scope, correctness, security, tests, maintainability, and merge readiness. Use when a PR URL, PR number, commit range, or explicit merge-readiness request is provided; do not use for standalone non-PR artifacts.
---

# Flux PR Review

Review the actual proposed change in context. A green check or clean diff is
evidence, not a verdict.

For the research basis behind this skill, read
[references/review-research.md](references/review-research.md).

## Input

- Pull request URL/number, repository, or local base/head range
- PR description, linked issue/design, and intended behavior
- Changed files, commits, dependencies, CI results, and available test output
- Repository guidance, ownership, security requirements, and review scope

## Actions

1. Establish the review surface: base and head, commits, changed files, linked context, ownership, CI, dependency/lockfile changes, and generated artifacts.
2. State the intended behavior and the Goal/System/Constraints/Evaluation contract. If purpose or scope is unavailable, report a blocked review rather than guessing.
3. Start broad, then inspect every changed file and enough surrounding code to understand callers, invariants, data flow, and compatibility. Track what was reviewed.
4. Check design, functionality, complexity, error handling, observability, documentation, and whether the change belongs in this PR. For code, also check it against flux-code skill's anti-patterns reference library as a whole — magic numbers/strings, domain-boundary leakage (a literal or internal shape from one domain inlined into another instead of crossing through a named boundary owned by that domain), god objects, shotgun surgery, primitive obsession, speculative generality, error swallowing, and the rest of that list.
5. Trace changed values and events across trust boundaries: input, validation, business logic, persistence, cache/queue, external effects, retries, rollback, and read paths.
6. Prioritize risk-based checks for authorization, authentication, input validation, injection, secrets, sensitive data, dependency changes, configuration, migrations, concurrency, idempotency, and resource limits.
7. Validate tests and CI against the claimed behavior. Look for missing regression coverage, false confidence from mocks, tests that assert only response text, and unsupported checks. Run relevant checks when authorized and possible.
8. Attack the happy path with concrete counterexamples: empty, malformed, duplicated, reordered, stale, concurrent, repeated, timeout, partial-failure, permission, migration, rollback, restart, and hostile-input cases as applicable.
9. Report only actionable findings. Each finding needs priority (`P0`–`P3`), exact location, trigger, impact, evidence, and remediation. Do not block on personal style; label nits separately.
10. Perform a second pass after checks pass. Decide whether the PR is ready, needs changes, is blocked by missing evidence, or should receive comments without approval.

## Merge gate

Return `ready` only when all of these are true:

- The PR purpose, base/head, scope, and relevant repository guidance are known.
- Every changed source/configuration/migration file was reviewed in context; generated or intentionally unreviewed paths are identified.
- Required checks pass. For behavior changes, meaningful tests or an explicit, evidence-backed exception are present; absent, failed, or unavailable required checks block `ready`.
- No unresolved `P0`–`P2` correctness, security, privacy, authorization, data-integrity, reliability, or release-blocking finding remains. `P3` nits do not block.
- The result is within scope and no authority or evidence gap remains.

Use `changes-requested` for fixable blocking findings and `blocked` when the
review cannot establish the required context or evidence. A green CI result
does not override a finding from the code or data-flow review.

## Contract contribution

- Goal: determine whether the proposed change is safe and ready to merge.
- System: combine contextual diff review, risk-based analysis, and real verification.
- Constraints: preserve scope, authority, confidentiality, and review independence.
- Evaluation: require location-specific findings or evidence-backed merge readiness.

## Output

Return:

- Review surface and coverage, including unreviewed or generated paths.
- Findings ranked by priority with location, trigger, impact, evidence, and fix.
- Tests/checks run, results, environment, and unsupported checks.
- Residual risks, assumptions, and required follow-up.
- Verdict: `ready`, `changes-requested`, `blocked`, or `comment-only`.
- A draft GitHub review summary. Submitting comments, approval, or request-changes remains a separate authorized action.

## Evidence

Use the actual diff, surrounding implementation, linked requirements, test/CI
results, dependency analysis, and reproducible counterexamples. Separate
verified findings from hypotheses and do not treat author claims or checklist
completion as proof.

## Stop conditions

Stop as `blocked` when the base/head, intended behavior, relevant source, or
required evidence is unavailable. Do not approve, request changes, post
comments, or modify the PR unless the user explicitly authorizes that external
action and the required integration is available.
