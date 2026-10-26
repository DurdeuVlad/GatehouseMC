---
name: flux-audit
description: Inspect an implementation or deliverable for correctness, completeness, scope discipline, structure, tests, security hygiene, and unsupported claims. Use when reviewing a change before acceptance; it is read-only and does not replace real verification.
---

# Flux Audit

## Input

Implementation, requirements, contract, diff, tests, review findings, and evidence.

## Actions

- Compare the result with the requested behavior and boundaries.
- Check changed files, structure, dependencies, and hygiene.
- Check changed code against flux-code skill's anti-patterns reference library as a whole, not only magic numbers/strings and domain-boundary leakage (a literal or internal shape from one domain inlined into another instead of crossing through a named boundary) — also god objects, shotgun surgery, primitive obsession, speculative generality, error swallowing, and the rest of that list.
- Inspect whether tests exercise the claimed behavior.
- Find placeholders, stale assumptions, missing failure paths, and unverified claims.
- Route fixes to the responsible executor; do not edit the target during audit.

## Contract contribution

- Goal: expose correctness, scope, structure, and hygiene failures.
- System: inspect independently and report findings without repairing them.
- Constraints: preserve the reviewed artifact and do not infer missing evidence.
- Evaluation: return a binary or explicitly blocked verdict grounded in findings.

## Output

Return a binary or explicitly blocked verdict, findings with file/line references, evidence, required fixes, and residual risks. Never pass because no issue was noticed.

## Stop conditions

Stop when the reviewed scope or source evidence is unavailable; report the gap instead of inferring a pass.
