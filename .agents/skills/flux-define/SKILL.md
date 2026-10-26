---
name: flux-define
description: Convert a problem, direction, and evidence into bounded scope, observable requirements, acceptance criteria, assumptions, and risks. Use when deciding what is in scope and what done means before implementation.
---

# Flux Definition

## Input

Problem, selected direction, evidence, constraints, and repository context.

## Actions

- Define in-scope and out-of-scope behavior.
- Make decisions with one-line rationale.
- Turn each goal into a testable acceptance criterion.
- Record assumptions and risk if they are unvalidated.
- Reject vague “works correctly” criteria.

## Contract contribution

- Goal: define bounded requirements and observable acceptance criteria.
- System: translate direction and evidence into a decision-ready scope.
- Constraints: surface assumptions, risks, and out-of-scope work.
- Evaluation: make every required outcome testable.

## Output

Return bounded scope, decisions, acceptance criteria, assumptions, risks, and unresolved questions. Do not write code or impose a pipeline.

## Stop conditions

Stop when a critical requirement cannot be made observable or a decision depends on missing evidence.
