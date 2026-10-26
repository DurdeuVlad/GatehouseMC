---
name: flux-goal
description: Pursue a user-defined goal through a bounded feedback loop: compile a checklist, select useful Flux skills, execute, evaluate evidence, and continue until complete, blocked, unsafe, or out of bounds. Use when the user wants the agent to keep working until a result is properly finished.
---

# Flux Goal Executor

`flux-goal` is a feedback loop, not a fixed workflow.

## Input

- Desired outcome
- Repository or task context
- User authority and constraints
- Optional existing checklist, evidence, or prior skill output

## Loop

1. Convert the request into a testable postcondition.
2. Compile Goal, System, Constraints, and Evaluation.
3. Create or update a finite checklist with `/flux-checklist`.
4. Select the next useful Flux capability.
5. Execute it and capture its output and evidence.
6. Evaluate progress against the contract.
7. Continue, revise, stop, or escalate.

## Limits

Before the first loop iteration, record effective limits. If the caller did not
provide limits, use these conservative defaults:

- 12 loop iterations total.
- 2 retries for the same failed action or checklist item.
- 0 unapproved scope expansions; a new requirement becomes `needs-input`.
- No action that requires authority the user did not grant.

Count an iteration even when a selected skill returns no progress. When any
limit is reached, return `limit-exceeded` with the counter, the last evidence,
and the smallest useful next option. Never interpret an omitted limit as
permission to continue indefinitely.

## Contract contribution

- Goal: pursue the requested postcondition until it is evidenced or stopped.
- System: choose and sequence atomic skills through bounded feedback.
- Constraints: enforce limits, authority, safety, and zero silent scope growth.
- Evaluation: require a complete checklist and sufficient evidence for `done`.

## Completion

Report `done` only when every required item is complete, every required evaluation passes, constraints still hold, and evidence supports the claim.

## Stop states

```text
done | blocked | needs-input | failed | limit-exceeded | unsafe
```

Stop immediately when a hard constraint is violated, authority is insufficient, or the next action is unsafe. Respect explicit retry, resource, time, and scope limits. Never loop forever or silently expand the goal.

## Output

Return the final result, checklist, evidence, stop state, unresolved risks, and any recommended follow-up. Do not create sessions, dashboards, or workflow artifacts.
