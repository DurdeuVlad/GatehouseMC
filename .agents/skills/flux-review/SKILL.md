---
name: flux-review
description: Adversarially review code, plans, architecture, writing, or agent behavior by trying to break the intended contract with concrete counterexamples. Use for meaningful non-PR work requiring independent challenge; use flux-pr-review when a PR URL, PR number, commit range, or merge-readiness request is provided.
---

# Flux Review

Use `flux-pr-review` when the review is specifically for a GitHub pull request
or commit range and needs PR context, changed-file coverage, and a merge
recommendation. Use this skill for the underlying artifact when that PR-specific
surface is not required.

## Input

Artifact, intended behavior, contract, diff, tests, and available evidence.

## Attack

Check correctness, edge cases, invalid input, scope, security, permissions, retries, concurrency, partial failure, migration, rollback, audience confusion, and test relevance as applicable. For code, also check it against flux-code skill's anti-patterns reference library as a whole — magic numbers/strings, domain-boundary leakage (a literal or internal shape from one domain inlined into another instead of crossing through a named boundary), god objects, shotgun surgery, primitive obsession, speculative generality, error swallowing, and the rest of that list.

Try to produce a reproducible counterexample. Review the actual artifact, not the author’s summary.

## Contract contribution

- Goal: find concrete ways the result can fail its intended behavior.
- System: review independently using counterexamples and boundary cases.
- Constraints: do not edit the reviewed artifact or confuse hypotheses with findings.
- Evaluation: rank findings by severity with reproducible evidence.

## Output

Return findings ranked by severity with location, trigger, impact, evidence, remediation, residual risks, and verdict. Separate verified findings from hypotheses. Do not edit the reviewed artifact.

## Stop conditions

Stop when the review surface or intended behavior is unavailable; return a blocked verdict rather than a weak approval.
