---
name: flux
description: Route a request through the smallest useful set of composable Flux skills, compile their Goal/System/Constraints/Evaluation contract, and return the next useful action. Use when a task needs clearer intent, structured execution, writing, testing, review, or evidence without imposing a workflow.
---

# Flux Router

Flux is a skill library, not a pipeline.

## Input

- User request
- Available repository, tool, and conversation context
- Any existing task contract, checklist, prior result, or evidence

## Route

1. Decide whether Flux adds value. Do not add ceremony to a trivial request.
2. Identify the desired postcondition, risk, audience, and work shape.
3. Use `/flux-how-others-do-it` before an implementation, refactor, or design decision that involves a new dependency, an unfamiliar pattern or API, or a security/auth-relevant choice — unless an existing project convention already answers it. Skip it for trivial, mechanical, or purely exploratory requests per step 1.
4. Select the smallest useful set of `flux-*` capabilities.
5. Use `/flux-checklist` when execution needs explicit tracking.
6. Use `/flux-docs` when the task is to design, create, or maintain a repository documentation base or specification set.
7. Use `/flux-design` when the task is to review or build the visual design of a UI, screen, or Figma artifact against design principles.
8. Use `/flux-code` when the task is writing, refactoring, or reviewing code and needs core engineering principles or pattern/language/anti-pattern trade-offs.
9. Use `/flux-write` when the task produces reader-facing prose — documentation, requirements, reports, PR descriptions, or an explanation — and the reader may not share the author's context.
10. Use `/flux-milestone` when a target goal needs evidence-backed issues and milestone boundaries for handoff.
11. Use `/flux-close-issue` when a single accepted issue needs bounded implementation, proof, adversarial review, and delivery evaluation.
12. Use `/flux-prove-it-works` before claiming a change works or is ready for delivery, to require proof from the environment closest to production that is safely and authorizedly reachable, rather than settling for a mocked or unit-only check.
13. Use `/flux-pr-flow` when the user wants an entire goal carried end-to-end through milestone planning and every resulting issue's implementation, PR, review, and merge.
14. Use `/flux-goal` only when the user wants continued pursuit until the goal is complete.
15. Explain the selected skills and their contributions briefly.

Prefer one primary skill plus supporting skills. Skills may be used in any order. Skip irrelevant capabilities.

## Contract

Compile:

```text
Goal:        what must become true
System:      how the agent should operate
Constraints: what must not be violated
Evaluation:  what evidence proves success
```

## Contract contribution

- Goal: make the desired postcondition and next useful action explicit.
- System: recommend the smallest useful composition of skills.
- Constraints: preserve authority, safety, scope, and user intent.
- Evaluation: identify the evidence required before completion is claimed.

## Output

Return the selected capabilities, compiled contract, immediate next action, assumptions, and unresolved blockers. Do not create sessions, dashboards, stage state, or mandatory handoffs.

## Hard rules

- Host skill activation is advisory; never claim Flux mechanically controls it.
- The agent may choose its path, but may not violate the compiled contract.
- Do not claim completion without the required evaluation evidence.
- Stop or ask when authority, safety, or scope is unclear.
