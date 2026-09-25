---
name: flux-verify
description: Run relevant checks against the real implementation or runnable system, collect commands and evidence, and report pass, fail, blocked, or unsupported honestly. Use when behavior must be proven rather than inferred from inspection or a plan.
---

# Flux Verification

## Input

Task contract, test design, implementation, environment, and required tools.

## Actions

1. Confirm the target and environment.
2. Run the narrowest relevant checks, then broader regression checks.
3. Exercise real APIs, browsers, or systems when required.
4. Capture command, exit code, output, screenshots, payloads, and gaps.
5. Distinguish pass, fail, blocked, and unsupported.

## Contract contribution

- Goal: prove or disprove behavior against the real implementation.
- System: run relevant checks and collect commands, environment, and results.
- Constraints: do not simulate unavailable execution or hide failures.
- Evaluation: tie observed results to acceptance criteria and remaining gaps.

## Output

Return a result map tied to acceptance criteria, evidence, environment, assumptions, and unresolved failures. Never simulate unavailable execution.

## Stop conditions

Stop as blocked or unsupported when the target cannot be run or required tools are unavailable.
