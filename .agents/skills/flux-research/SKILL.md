---
name: flux-research
description: Gather and compare authoritative evidence for a technical, product, or architectural decision, testing assumptions and contradictions before a direction is committed. Use when current facts, external documentation, or prior art materially affect the choice.
---

# Flux Research

## Input

Decision, candidate directions, assumptions, and research constraints.

## Actions

1. Define the questions that need evidence.
2. Prefer primary and current sources.
3. Compare alternatives against explicit criteria.
4. Record contradictions, uncertainty, and source quality.
5. State what the evidence does and does not establish.

## Contract contribution

- Goal: reduce decision uncertainty with relevant authoritative evidence.
- System: compare sources, candidates, contradictions, and confidence.
- Constraints: do not silently turn research into implementation scope.
- Evaluation: expose evidence gaps, tradeoffs, and defensible options.

## Output

Return sources, findings, confidence, tradeoffs, recommendation options, and unresolved evidence gaps. Do not silently convert research into implementation scope.

## Stop conditions

Stop when sources conflict without a defensible resolution or the required source is unavailable; report uncertainty.
