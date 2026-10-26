---
name: flux-delivery
description: Prepare a verified change for delivery by checking scope, evidence, review findings, release impact, and explicit authority for commit, publish, deploy, or merge. Use when work is ready to leave the agent’s workspace; never infer permission for external side effects.
---

# Flux Delivery

## Input

Completed result, diff, evaluations, verification evidence, review findings, and delivery authority.

## Checks

- Requested behavior is complete and evidence-backed.
- Required tests and reviews passed or are explicitly accepted.
- Diff is focused and contains no secrets or debug artifacts.
- Release, migration, rollback, and compatibility risks are understood.
- Commit, push, merge, publish, and deploy authority is explicit.

## Contract contribution

- Goal: establish whether a result is ready to leave the workspace.
- System: check scope, evidence, review, verification, and release impact.
- Constraints: never infer permission to commit, publish, deploy, or merge.
- Evaluation: return a delivery recommendation tied to verified evidence.

## Output

Return delivery readiness, release notes or message, unresolved risks, and the exact external action still requiring authorization. Do not perform that action implicitly.

## Stop conditions

Stop when required evidence is missing, a release-blocking risk remains, or external authority is not explicit.
