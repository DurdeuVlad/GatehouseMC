---
name: flux-how-others-do-it
description: Run a quick, lightweight research pass on how others solve the current kind of task and identify the best known approach before applying it, checking existing codebase convention first. Use before a non-trivial implementation, refactor, or design decision where an established or best-practice approach likely exists; skip for trivial, mechanical, or purely exploratory requests.
---

# Flux How Others Do It

A lightweight lookup, not a research project. Spend minutes, not hours: find
the established way to solve this kind of problem, then hand the choice to
the skill that implements it.

## Input

- The task or decision, and the constraints already fixed by the codebase.
- Any existing local convention, prior decision, or style guide that might
  already answer the question.
- The time or scope budget available for this pass (default: minutes, not an
  open-ended investigation).

## Actions

1. Check the codebase and project docs first. If an existing local convention
   already answers the question, use it and stop — do not research past a
   convention the project has already chosen.
2. Frame one narrow question: what is the established or best-known way to
   solve this specific kind of problem, not the whole surrounding task.
3. Look for how others solve it via current authoritative sources —
   language/framework docs, widely adopted implementations, standards, or
   well-known write-ups — rather than relying on memory alone.
4. Compare two or three candidate approaches with their trade-offs; do not
   stop at the first result found.
5. Select the approach that best fits this codebase and context, not simply
   the most popular or most recent one.
6. State the chosen approach, the alternatives rejected, and why.

## Contract contribution

- Goal: enter implementation with a deliberately chosen, best-fit approach
  instead of the first habit that comes to mind.
- System: check local convention first, then compare a small number of
  real external approaches before choosing.
- Constraints: do not invent a requirement the task or codebase does not
  already have; do not let generic external practice override an existing,
  working project convention without flagging the conflict.
- Evaluation: the chosen approach names its source or rationale and the
  alternative it was chosen over.

## Output

Return the chosen approach, the alternatives considered and rejected, sources
or rationale, and hand the result to the skill that implements it (for
example `/flux-code`, `/flux-build`, or `/flux-design`).

## Evidence

Cite sources with links or names when external research informed the choice.
When no authoritative source is found within the lightweight budget, say so
plainly and mark the choice as judgment-based rather than sourced.

## Stop conditions

Skip this skill entirely for trivial or purely mechanical requests. Stop
researching and proceed on best judgment, stating that explicitly, if no
authoritative source turns up quickly — this is a lightweight pass, never an
open-ended investigation. Never let it block the task indefinitely or
override an existing project convention without surfacing the conflict.
