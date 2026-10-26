---
name: flux-plan
description: Turn bounded requirements into an implementation contract with architecture, dependency ordering, file boundaries, test surfaces, risks, and rollback considerations. Use before a meaningful code change when implementation needs coordination.
---

# Flux Plan

## Input

Requirements, acceptance criteria, repository context, constraints, and evidence.

## Actions

- Choose the smallest coherent architecture.
- Break work into dependency-ordered slices.
- Declare files and boundaries that may change.
- Map every acceptance criterion to a test or observation.
- Record risks, rollback, and required capabilities.

## Contract contribution

- Goal: define an executable implementation approach and file boundary.
- System: sequence work around dependencies without imposing a Flux pipeline.
- Constraints: keep scope, authority, and testability explicit.
- Evaluation: tie each planned change to acceptance evidence.

## Output

Return an implementation contract, slices, test map, file boundary, risks, and unresolved decisions. The plan is an input to execution, not a mandatory stage.

## Stop conditions

Stop when architecture, scope, or testability depends on an unresolved decision; do not invent a plan around the gap.
