---
name: flux-prove-it-works
description: Require proof of working behavior in the environment closest to production that is safely and authorizedly reachable, refusing to let a mocked or unit-only check stand in for that proof when a higher-fidelity environment is available. Use before claiming a change works, especially before delivery or merge; do not use to replace flux-verify's test execution mechanics, only to set the required environment fidelity.
---

# Flux Prove It Works

A policy over where proof happens, not a replacement for `/flux-verify`'s
mechanics. `/flux-verify` runs the checks and collects evidence; this skill
decides which environment that evidence must come from before it counts as
proof the change works.

## Input

- The change, its acceptance criteria, and any test/verification results
  already produced (for example by `/flux-test` or `/flux-verify`).
- The environments actually reachable with current authority and tools:
  local dev, a prod-parity local stack, a staging or pre-prod environment,
  or real production behind a safe guardrail.

## Actions

1. Rank the reachable environments from closest to production to farthest:
   real production behind a safe guardrail (feature flag, canary, read-only
   path) > a staging or pre-prod environment that mirrors production
   configuration > a local prod-parity stack (same containers, schema, and
   integration boundaries) > an isolated local dev server > mocked or
   unit-only checks.
2. Select the highest-ranked environment actually reachable with current
   authority and tools. Do not default to the easiest environment when a
   closer one is available and permitted.
3. Run the relevant checks in that environment and capture real command,
   output, and observed behavior — logs, screenshots, response payloads, or
   whichever artifact the boundary produces.
4. If only a lower-fidelity environment was reachable, name the higher one
   that exists and why it wasn't used (no access, no authority, cost,
   destructive risk). Never silently settle for less proof than was actually
   available.
5. If nothing beyond mocks or unit tests is reachable at all, say so plainly
   and mark the affected acceptance criteria `unsupported` rather than
   implying production-level proof occurred.

## Contract contribution

- Goal: the strongest reachable evidence that the change behaves correctly,
  not merely that its narrowest unit passes.
- System: rank and select the closest-to-production reachable environment
  before accepting a check as proof.
- Constraints: do not claim production-level proof from a mock or unit test;
  do not take a destructive, irreversible, or unauthorized action against a
  real production system merely to obtain proof.
- Evaluation: the environment actually used is named and justified against
  the higher-fidelity environments that were available but not used.

## Output

Return the environment used, the environments considered and why each was or
wasn't used, the commands and observations gathered there, and which
acceptance criteria are proven at that fidelity versus still `unsupported`.

## Evidence

Real command output, logs, screenshots, or observed system state from the
environment actually used, plus the named environment and its relationship
to production. Distinguish this from evidence gathered at a lower fidelity.

## Stop conditions

Stop and report `unsupported` rather than fabricate production-fidelity
proof when no such environment is reachable. Never escalate privilege or
take a destructive, irreversible, or unauthorized action against a real
production system to obtain proof; ask for authority or a safer environment
first.
