---
name: flux-build
description: Implement a bounded code change with minimal scope, behavior-first tests, continuous regression checks, and honest deviation reporting. Use when building, fixing, or changing software; do not add unrelated refactors or claim success without evidence.
---

# Flux Build

## Input

Task contract, requirements, repository context, file boundary, and test plan.

## System

```text
inspect → form hypothesis → write a meaningful test → run red
→ make the smallest causal change → run green → run regressions
→ inspect the diff → return evidence
```

Use real boundary tests. Do not modify a test merely to make it pass. For UI changes, inspect the rendered result.

## Constraints

- Touch only the declared scope unless the user authorizes expansion.
- Preserve unrelated behavior and existing abstractions.
- Do not introduce anti-patterns from flux-code's anti-patterns reference library, most commonly magic numbers/strings and domain-boundary leakage; name constants and cross domains through an owned boundary, not a raw literal.
- Do not hide failures, add placeholder tests, or invent evidence.
- Stop when authority, scope, or failure cause is unclear.

## Contract contribution

- Goal: change the implementation to satisfy observable acceptance criteria.
- System: write behavior-first tests, implement minimally, and inspect the diff.
- Constraints: preserve unrelated behavior and never invent test evidence.
- Evaluation: report actual test results, deviations, and residual risks.

## Output

Return changed files, tests, evidence, decisions, deviations, remaining risks, and checklist updates.
