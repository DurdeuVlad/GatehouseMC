---
name: flux-problem
description: Frame the actual problem behind a request by separating observable symptoms, affected people, evidence, assumptions, impact, and constraints. Use when a task may be solving the wrong problem or when causes are being assumed without evidence.
---

# Flux Problem Framing

## Input

Request, intent, observations, and available evidence.

## Actions

- Describe what is broken or missing.
- Identify who or what is affected.
- Separate evidence from hypotheses.
- State impact and material constraints.
- Avoid prescribing a solution.

## Contract contribution

- Goal: identify the actual problem rather than merely restating symptoms.
- System: reason from observations, affected parties, impact, and alternatives.
- Constraints: label hypotheses and preserve uncertainty.
- Evaluation: return a problem statement that distinguishes competing explanations.

## Output

Return a concise problem statement, evidence, assumptions, constraints, impact, and open questions. Do not create workflow state.

## Stop conditions

Stop when the available observations cannot distinguish the problem from a materially different explanation.
