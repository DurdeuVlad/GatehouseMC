# Core engineering principles

Four checkable lenses for judging a piece of code or a design decision. They regularly conflict with each other — the skill is picking the right trade-off for the situation, not satisfying all four at once.

## SOLID

- **Single responsibility** — a unit (class, module, function) should have one reason to change. Violation signal: a change request for one concern forces edits across unrelated concerns in the same unit.
- **Open/closed** — extend behavior without modifying working, tested code. Achieved through composition, interfaces, or configuration, not by anticipating every future case. Violation signal: adding a new case requires an `if`/`switch` edit in a function that already handles several unrelated cases.
- **Liskov substitution** — a subtype must be usable anywhere its supertype is expected, without surprising the caller. Violation signal: a subclass overrides a method to throw, no-op, or narrow its contract.
- **Interface segregation** — prefer several small, specific interfaces over one broad one. Violation signal: an implementer is forced to stub out methods it has no meaningful behavior for.
- **Dependency inversion** — depend on abstractions, not concrete implementations, especially across architectural boundaries (business logic should not import infrastructure directly). Violation signal: a unit test needs a real database, network call, or filesystem to run.

## DRY (don't repeat yourself)

Every piece of knowledge should have a single, authoritative representation. This is about duplicated *knowledge* (a business rule, a constant, a validation rule), not duplicated *text* — two functions that happen to look similar today but change for unrelated reasons are not a DRY violation, and merging them creates false coupling. Violation signal: fixing a bug or rule change requires editing the same logic in more than one place.

## KISS (keep it simple)

Prefer the solution a teammate can understand in one read. Cleverness that requires a comment to explain what the code does (not why) is a signal to simplify, not to document. Violation signal: explaining the code out loud takes longer than the code itself, or the design needs a diagram before anyone can review it.

## YAGNI (you aren't gonna need it)

Don't build for a requirement that hasn't materialized. Generality is a cost (more surface to maintain, more decisions to get wrong) paid up front for a benefit that may never arrive. Violation signal: a config option, extension point, or abstraction layer with exactly one real caller or one real value.

## Working the trade-offs

- SOLID and YAGNI pull against each other: SOLID's abstractions are only worth their cost once there's a real second case to accommodate. Applying dependency inversion or open/closed for a single, unlikely-to-change caller is over-engineering, not good design.
- DRY and KISS pull against each other: deduplicating two superficially similar blocks into one parameterized function can make each call site harder to read than the duplication was. Prefer duplication over a wrong abstraction.
- When two principles conflict, name the one that matters more for *this* piece of code and say why — that's the trade-off note this skill's parent asks for, not a silent pick.
