---
name: flux-checklist
description: Compile a request and selected Flux skills into a finite evidence-bearing checklist with Goal/System/Constraints/Evaluation fields and clear done, blocked, failed, skipped, and escalation states. Use when work needs bounded execution tracking without a workflow.
---

# Flux Checklist

## Input

- Request or goal
- Selected skill contributions
- Context and authority
- Existing checklist and evidence, if any

## Build

1. State the goal as an observable postcondition.
2. State the operating system as a feedback loop, not a rigid stage list.
3. Separate hard constraints from preferences.
4. Define evaluations and required evidence.
5. Create the smallest checklist that covers the contract.

## States

```text
pending | active | done | blocked | failed | skipped-with-reason | needs-decision
```

Every factual or quality claim needs evidence. An item is not `done` because the agent says it is done.

## Contract contribution

- Goal: turn the desired postcondition into finite observable items.
- System: track progress through evidence-bearing states without forcing order.
- Constraints: keep scope, authority, and stopping rules visible.
- Evaluation: require criterion-level evidence before marking an item `done`.

## Output

Return the contract, checklist, evidence requirements, stop conditions, and unresolved decisions. Update only the checklist state; do not turn it into a persistent session or mandatory pipeline.
