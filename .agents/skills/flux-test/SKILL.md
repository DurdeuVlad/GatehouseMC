---
name: flux-test
description: Design meaningful tests and evaluation surfaces for a behavior, change, or task contract, choosing the right boundary and separating unit, integration, UI, API, and real-system checks. Use before or during implementation when test coverage needs deliberate design.
---

# Flux Test Design

## Input

Goal, acceptance criteria, constraints, implementation context, and risk.

## Actions

- Test observable behavior at the boundary a caller uses.
- Cover success, failure, edge, authorization, and recovery paths.
- Prefer real collaborators; mock only slow, nondeterministic, or external dependencies.
- Identify tests that cannot be meaningfully asserted and define an observation instead.
- Map each check to an acceptance criterion and risk.

## Contract contribution

- Goal: define meaningful observations that can prove or reject behavior.
- System: choose the narrowest test boundary that exposes the real risk.
- Constraints: do not claim execution or use assertions that cannot observe behavior.
- Evaluation: map every test to an acceptance criterion and failure signal.

## Output

Return test cases, layer, setup, expected result, failure signal, acceptance mapping, and gaps. Do not claim tests passed; execution belongs to `/flux-verify`.

## Evidence

Each test must identify the observable behavior, expected result, and failure signal that would support or reject the relevant acceptance criterion.

## Stop conditions

Stop when behavior cannot be observed or the environment contract is missing; define the gap instead of inventing an assertion.
