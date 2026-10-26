---
name: flux-intent
description: Turn an ambiguous request into a clear desired outcome, target, authority boundary, and consequential assumptions. Use when the user’s intent is unclear or the task could materially change scope, risk, or next action.
---

# Flux Intent

## Input

User request and available context.

## Actions

1. State the likely intended outcome.
2. Identify the target and authorized action.
3. Separate known constraints from assumptions.
4. Ask only questions whose answers materially change the work.
5. Rewrite the outcome as a postcondition when possible.

## Output

Return `intent`, `target`, `constraints`, `assumptions`, `consequential_questions`, and `next_options`. Do not create a session or force discovery.

## Evidence

Ground the intent in the user’s words and label inferred assumptions separately.

## Contract contribution

- Goal: state the intended outcome and authorized target clearly.
- System: resolve only ambiguities that materially affect the work.
- Constraints: preserve authority boundaries and distinguish assumptions.
- Evaluation: return a testable postcondition when the request permits one.

## Stop conditions

Stop and ask when two plausible interpretations would materially change scope, authority, or risk.
