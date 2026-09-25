---
name: flux-evaluate
description: Judge a result against its Goal/System/Constraints/Evaluation contract using supplied evidence, distinguishing complete, incomplete, blocked, unsafe, and unsupported outcomes. Use for plans, code, tests, writing, reviews, or any deliverable that needs a defensible decision.
---

# Flux Evaluation

## Input

Task contract, result, checklist, evidence, and known limitations.

## Actions

1. Check every required goal and evaluation.
2. Verify evidence is relevant, current, and sufficient.
3. Check constraints and authority boundaries still hold.
4. Identify missing, contradictory, stale, or self-authored claims.
5. Return the narrowest defensible verdict.

## Verdicts

```text
complete | incomplete | blocked | unsafe | unsupported
```

## Contract contribution

- Goal: decide whether the result satisfies its task contract.
- System: compare each criterion with current, relevant evidence.
- Constraints: do not repair the source or accept self-authored claims as proof.
- Evaluation: return criterion-level results, confidence, failures, and risks.

## Output

Return criterion-level results, evidence references, confidence, failures, residual risks, and the next useful action. Do not silently repair source work or invent proof.

## Stop conditions

Stop with `blocked` when the contract or evidence is missing, contradictory, stale, or insufficient for a defensible verdict.
