---
name: flux-code
description: Apply core software engineering principles and a linked reference library of design patterns, per-language idioms and frameworks, and anti-patterns when writing, refactoring, or reviewing code, picking the best trade-off for the actual situation instead of the most familiar habit. Use when writing new code, choosing a pattern or framework approach, refactoring, or reviewing code for engineering soundness; do not use for product requirements, project planning, or non-code writing.
---

# Flux Code

Lightweight core-principles skill for the act of writing code, backed by a reference library covering design patterns, per-language idioms and frameworks, and anti-patterns. The library is deliberately index-heavy: most files exist so a specific fact can be looked up, not so the whole thing gets read. Follow a link only when the current problem actually needs it.

## Input

- The code task: new code, a refactor, a pattern/framework choice, or a code review target.
- The language(s) and framework(s) already in use, if any.
- Any existing project convention that should override generic guidance here.

## Actions

1. Name the concrete problem the code must solve and the constraints already fixed by the codebase (language, framework, existing patterns, style). Do not reach for a pattern or abstraction before this is explicit — most bad structural decisions come from skipping this step.
2. Stop and weigh the problem against [references/principles.md](references/principles.md) (SOLID, DRY, KISS, YAGNI). Identify which principles are actually in tension — most real code decisions trade one against another, they are not a checklist to satisfy simultaneously.
3. If a structural or design decision is needed, read [references/design-patterns.md](references/design-patterns.md) — an index only — and follow it to the one category file that matches. Pick the pattern that resolves the tension found in step 2, not the most familiar one, and note the alternative considered and rejected.
4. If the language or framework choice matters, read the matching entry linked from [references/languages.md](references/languages.md) for idioms, standard-library-first defaults, and common framework trade-offs. Skip every language not actually in play.
5. Write the code, then check it against [references/anti-patterns.md](references/anti-patterns.md) before calling it done. Fix what it finds, or state why a flagged item is a deliberate, documented trade-off rather than an oversight.

Stop after step 1 or 2 and ask the user when the problem, constraints, or trade-off are genuinely ambiguous. Do not invent requirements the codebase doesn't already establish.

## Contract contribution

- Goal: correct code where every non-obvious structural decision traces back to a named principle, pattern, or explicit trade-off rather than habit.
- System: identify the real trade-off before picking a solution; read reference material selectively, never exhaustively, and never as a substitute for understanding the actual codebase.
- Constraints: do not add an abstraction, pattern, or dependency the problem doesn't need; do not apply a pattern from the reference library reflexively; do not let generic guidance override an existing, working project convention; never treat this skill's checklist as a substitute for running tests or a real review.
- Evaluation: the resulting code or review names which principle, pattern, or anti-pattern applied and why, and states the alternative rejected when a non-trivial structural choice was made.

## Output

Return the code (or review comments) plus a short trade-off note: the principle or pattern applied, the alternative considered and rejected, and anything the anti-pattern check surfaced. The trade-off note plus the code itself is the evidence for "done" — a bare claim of best practice is not.

## Stop conditions

Stop and ask rather than guess when the problem, constraints, or target language/framework are unclear; when generic guidance here conflicts with an existing project convention (the convention wins — confirm before overriding it); or when the task isn't actually about writing or reviewing code (route to flux-plan, flux-design, or flux-write instead). Never claim a pattern or principle was applied without stating which one and why.
