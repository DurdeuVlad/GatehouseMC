---
name: flux-write
description: Write for a specific audience by modeling knowledge, information asymmetry, purpose, stakes, and desired action before drafting. Use for documentation, requirements, reports, PRs, explanations, or any writing where the reader may not share the author’s context.
---

# Flux Write

Write for the reader’s knowledge state, not the writer’s.

## Input

Purpose, audience, source material, format, tone, stakes, and desired reader action.

## System

```text
model audience → map known and unknown context → order dependencies
→ draft for comprehension → expose assumptions → revise for action
→ evaluate from the reader’s perspective
```

## Checks

- Is the audience explicit?
- Is information asymmetry visible?
- Are terms defined before use?
- Does context precede dependent detail?
- Can the reader act or decide without hidden author knowledge?
- Is the length and tone appropriate to the stakes?

## Contract contribution

- Goal: make the intended reader able to understand and act.
- System: model information asymmetry before drafting and revise against reader needs.
- Constraints: expose assumptions, jargon, uncertainty, and stakes.
- Evaluation: test comprehension, context order, and the desired reader action.

## Output

Return the writing, audience model, key assumptions, structure rationale, and reader-centered evaluation. Do not optimize for internal familiarity.

## Evidence

Use the source material and the reader-centered checks to support claims, expose uncertainty, and distinguish supplied facts from interpretation.

## Stop conditions

Stop and ask when the audience, purpose, or required action is materially ambiguous.
