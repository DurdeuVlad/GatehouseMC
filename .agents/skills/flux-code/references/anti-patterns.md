# Anti-patterns

Run new or changed code against this list before calling it done. Each entry: what it looks like, a concrete example, why it hurts, the fix, and how it relates to other entries here or to [principles.md](principles.md) / [design-patterns.md](design-patterns.md). Examples use TypeScript-flavored pseudocode; the shape transfers to any language.

## Magic numbers / magic strings

**What it looks like.** A literal value appears inline with no name explaining what it means or why that value, specifically, was chosen.

**Example.**
```ts
// Bad: what is 86400? what is "admin"? are the two "admin" strings below guaranteed to match?
if (user.role === "admin") { session.expiresIn = 86400; }
if (requester.role === "admin") { allowDelete = true; }

// Fixed:
const SECONDS_PER_DAY = 86400;
const ROLE_ADMIN = "admin";
if (user.role === ROLE_ADMIN) { session.expiresIn = SECONDS_PER_DAY; }
if (requester.role === ROLE_ADMIN) { allowDelete = true; }
```

**Why it hurts.** When the meaning needs to change, every occurrence has to be found and changed by hand — miss one and the two `"admin"` checks silently drift apart. A reader also can't tell, from the literal alone, whether two identical values mean the same thing by design or by coincidence.

**Fix.** Name the value as a constant (or an `enum` for a closed set of related values) at the point of first real use, and reference the name everywhere the value is needed.

**Related.** A specific case of duplicated *knowledge* — see DRY in [principles.md](principles.md). Enums are also the fix for [boolean/stringly-typed parameters](#boolean--stringly-typed-parameters) below.

## God object / god class

**What it looks like.** One class or module accumulates unrelated responsibilities — parsing, validation, persistence, business rules, formatting — until nothing can change without risking something unrelated breaking.

**Example.**
```ts
// Bad: one class does five unrelated jobs.
class UserManager {
  parseCsvImport(raw: string) { /* ... */ }
  validateEmail(email: string) { /* ... */ }
  saveToDatabase(user: User) { /* ... */ }
  sendWelcomeEmail(user: User) { /* ... */ }
  renderProfileHtml(user: User) { /* ... */ }
}

// Fixed: split by responsibility.
class UserCsvParser { parse(raw: string): UserInput[] { /* ... */ return []; } }
class UserValidator { validateEmail(email: string): boolean { /* ... */ return true; } }
class UserRepository { save(user: User) { /* ... */ } }
class WelcomeEmailSender { send(user: User) { /* ... */ } }
class ProfileRenderer { render(user: User): string { /* ... */ return ""; } }
```

**Why it hurts.** Every unrelated change request touches the same file, increasing merge conflicts and the odds that a change to one concern (say, email formatting) accidentally breaks another (validation) that happens to share the class. Tests for one concern have to set up unrelated state the class needs for the others.

**Fix.** Split by responsibility, not by line count — a class that's short but still does three unrelated things is a god object in miniature. This is single responsibility from [principles.md](principles.md) applied directly.

**Related.** Often the endpoint of [copy-paste programming](#copy-paste-programming) and [speculative generality](#speculative-generality) left unchecked over time — one class kept absorbing "just one more thing."

## Spaghetti code

**What it looks like.** Control flow with no discernible structure: deeply nested conditionals, jumps between unrelated sections, shared mutable state read and written from many scattered places.

**Example.**
```ts
// Bad: nesting obscures the actual decision being made.
function process(order: Order) {
  if (order) {
    if (order.items.length > 0) {
      if (order.customer) {
        if (order.customer.active) {
          // actual logic buried four levels deep
        }
      }
    }
  }
}

// Fixed: guard clauses flatten the structure and name each precondition.
function process(order: Order) {
  if (!order) return;
  if (order.items.length === 0) return;
  if (!order.customer) return;
  if (!order.customer.active) return;
  // actual logic, now at the top level
}
```

**Why it hurts.** A reader has to hold every open conditional in their head to understand what's actually happening at the innermost level, and it's easy to introduce a bug by attaching new logic to the wrong nesting level.

**Fix.** Extract named functions for each decision, replace deep nesting with early returns/guard clauses, and make data flow explicit — pass values through parameters and return values instead of relying on shared mutable state that's hard to trace.

**Related.** [KISS](principles.md) directly: if explaining the control flow out loud takes longer than the code itself, it needs simplifying, not documenting.

## Shotgun surgery

**What it looks like.** A single conceptual change (e.g. "orders now support a discount code") requires small edits scattered across many unrelated files or functions.

**Example.**
```ts
// Bad: the "how do we compute the final price" rule is duplicated in three places.
function renderCartTotal(cart: Cart) { return cart.subtotal - (cart.subtotal * 0.1); }
function invoiceTotal(invoice: Invoice) { return invoice.subtotal - (invoice.subtotal * 0.1); }
function reportRevenue(orders: Order[]) {
  return orders.reduce((sum, o) => sum + (o.subtotal - o.subtotal * 0.1), 0);
}
// A rate change from 0.1 to 0.15 means finding and editing all three correctly.

// Fixed: the rule lives in exactly one place.
function applyDiscount(subtotal: number): number { return subtotal - subtotal * DISCOUNT_RATE; }
function renderCartTotal(cart: Cart) { return applyDiscount(cart.subtotal); }
function invoiceTotal(invoice: Invoice) { return applyDiscount(invoice.subtotal); }
function reportRevenue(orders: Order[]) {
  return orders.reduce((sum, o) => sum + applyDiscount(o.subtotal), 0);
}
```

**Why it hurts.** Each scattered edit is a chance to miss one location — the classic way a rate change ships correctly everywhere except the one report nobody remembered to check.

**Fix.** Find the duplicated *knowledge* (not necessarily duplicated text — see DRY's distinction in [principles.md](principles.md)) and consolidate it into one place that every caller goes through.

**Related.** The symptom that DRY (in [principles.md](principles.md)) is meant to prevent; often the sign that a responsibility boundary is drawn in the wrong place (see [god object](#god-object--god-class) for the opposite failure mode — one place doing too much, versus one concept spread too thin).

## Premature optimization

**What it looks like.** Code is made faster or more "efficient" before profiling shows the change actually matters, usually at the cost of readability or added complexity.

**Example.**
```ts
// Bad: hand-rolled bit-twiddling "optimization" for a function called twice a day.
function isEven(n: number) { return (n & 1) === 0; } // saved nothing measurable, cost readability

// Fine: the clear version, until a profiler says otherwise.
function isEven(n: number) { return n % 2 === 0; }
```

**Why it hurts.** The added complexity is a real, permanent cost (harder to read, more surface for bugs) paid for a performance benefit that's usually not where the code actually spends its time — intuition about hot paths is frequently wrong without measurement.

**Fix.** Write the clear version first. Optimize only the specific part a profiler or a real measurement identifies as the actual bottleneck, and verify the optimization actually helped after making it.

**Related.** A specific case of [speculative generality](#speculative-generality)'s broader pattern — building for a need (here, performance) that hasn't been shown to be real yet. See YAGNI in [principles.md](principles.md).

## Primitive obsession

**What it looks like.** Domain concepts — money, an email address, a percentage — are passed around as raw primitives (`number`, `string`) instead of small, validated types, so invalid values can flow anywhere and the same validation/business rule gets re-implemented at every call site.

**Example.**
```ts
// Bad: nothing stops an invalid or negative amount from reaching this function,
// and every caller that needs to validate an email re-implements the check.
function chargeCard(amountInCents: number, currency: string) { /* ... */ }

// Fixed: invalid states are unrepresentable once constructed.
class Money {
  private constructor(public readonly cents: number, public readonly currency: string) {}
  static of(cents: number, currency: string): Money {
    if (cents < 0) throw new Error("Money cannot be negative");
    return new Money(cents, currency);
  }
}
function chargeCard(amount: Money) { /* amount is already guaranteed valid */ }
```

**Why it hurts.** Validation logic gets duplicated (or forgotten) at every call site instead of living in one place, and the type signature (`number`) gives a reader no signal about what values are actually valid.

**Fix.** Introduce a small value type/wrapper that validates on construction, when the language and the frequency of use justify the extra type — not for every primitive in the codebase, only the ones that carry real domain rules.

**Related.** The type-level version of the [magic numbers / magic strings](#magic-numbers--magic-strings) problem — both are about making an implicit meaning explicit, one via naming, one via typing.

## Copy-paste programming

**What it looks like.** A block of code is duplicated and lightly edited instead of extracted into a shared function, so each copy silently drifts and a bug fix applied to one copy doesn't reach the others.

**Example.**
```ts
// Bad: two near-identical blocks that will inevitably drift.
function validateSignupEmail(email: string) {
  if (!email.includes("@")) throw new Error("invalid email");
  return email.trim().toLowerCase();
}
function validateInviteEmail(email: string) {
  if (!email.includes("@")) throw new Error("bad email"); // already drifted: different message
  return email.trim().toLowerCase();
}

// Fixed: one implementation, two call sites.
function normalizeAndValidateEmail(email: string) {
  if (!email.includes("@")) throw new Error("invalid email");
  return email.trim().toLowerCase();
}
```

**Why it hurts.** Every copy is a separate place a fix has to be applied; in practice fixes reach one copy and not the others, and the copies drift further apart over time (as seen above, even the error message already differs).

**Fix.** Extract the shared logic — but only once a second real (not hypothetical) duplicate actually appears. See the rule of three below; abstracting on the very first occurrence tends to guess the wrong shared shape.

**Related.** The proximate cause of [shotgun surgery](#shotgun-surgery) once a third or fourth copy appears, and a common path toward a [god object](#god-object--god-class) if the "shared" extraction absorbs unrelated concerns along with the duplicated logic.

## Golden hammer

**What it looks like.** The same familiar tool, library, or pattern gets applied to every problem regardless of fit, because it's what the author already knows — a message queue for something needing a direct function call, a design pattern applied where a plain function would do, a favorite framework reached for out of habit.

**Example.** A team fluent in [Event-driven / pub-sub](design-patterns.md) reaches for a message broker and an async event handler for a same-process, synchronous "calculate order total" operation that has exactly one caller and needs the result immediately — adding latency, a new failure mode (undelivered/duplicate events), and a much harder-to-trace call path for no actual benefit.

**Why it hurts.** The tool's cost (complexity, indirection, a new failure mode, a dependency to maintain) is paid regardless of whether the problem actually needed it, and the mismatch is often invisible to the author precisely because the tool is so familiar it doesn't feel like a choice.

**Fix.** Name the actual problem first (step 1 of this skill's parent flow) before reaching for the default, most-familiar solution — then check the reference library's "when to avoid" column for the tool under consideration, not just its "when to use" column.

**Related.** The habit-driven mirror image of [speculative generality](#speculative-generality) (building for an imagined future) — golden hammer is applying a familiar *solution* without first confirming the *problem* actually matches it.

## Boolean / stringly-typed parameters

**What it looks like.** A function signature encodes meaning in bare booleans or strings the call site can't read without checking the function's definition.

**Example.**
```ts
// Bad: what do true and "sync" mean at the call site, without looking up the signature?
save(userData, true, false, "sync");

// Fixed: named options, or separate functions for genuinely distinct behaviors.
save(userData, { overwrite: true, validateFirst: false, mode: "sync" });
// or, if the modes are truly distinct operations:
saveSync(userData, { overwrite: true });
```

**Why it hurts.** A call site like `save(userData, true, false, "sync")` gives a reviewer or future reader no way to tell what's being requested without opening the function definition, and it's easy to transpose two boolean arguments of the same type without the compiler (or a reviewer) catching it.

**Fix.** Use named parameters / an options object, or split into separate, clearly-named functions when the "modes" represent genuinely different operations rather than independent toggles.

**Related.** A narrower case of [magic numbers / magic strings](#magic-numbers--magic-strings) — the values are readable at the definition but opaque at the call site.

## Speculative generality

**What it looks like.** Extension points, config flags, or abstraction layers built for a future requirement that hasn't materialized yet — a plugin system with one plugin, a strategy interface with one implementation, a config option with one real value ever passed.

**Example.**
```ts
// Bad: a strategy interface, a factory, and a config flag — for one implementation
// that has never had a second one and no concrete plan for a second one.
interface ExportStrategy { export(data: Data): void }
class CsvExportStrategy implements ExportStrategy { export(data: Data) { /* only one ever used */ } }
function createExportStrategy(config: Config): ExportStrategy { return new CsvExportStrategy(); }

// Fixed: a plain function, until a second real format is actually needed.
function exportAsCsv(data: Data): void { /* ... */ }
```

**Why it hurts.** Every unused extension point is a cost paid immediately (more code, more decisions, more surface to keep consistent) for a benefit that may never arrive, and the guessed-at abstraction is frequently the wrong shape once a real second case does show up — meaning it gets reworked anyway.

**Fix.** Delete the unused flexibility. Add it back — designed against the *real* second case, not a guessed one — when a second real caller actually needs it. This is YAGNI from [principles.md](principles.md), applied directly.

**Related.** [Premature optimization](#premature-optimization) is the performance-specific instance of this same pattern. Left unchecked over time, this is often how a [god object](#god-object--god-class) or an over-general framework accumulates.

## Error swallowing

**What it looks like.** A `catch`/`except` block that silently discards an error, or logs it and continues as though nothing happened, instead of handling it meaningfully or letting it propagate to something that can.

**Example.**
```ts
// Bad: the failure vanishes; the caller has no idea the write didn't happen.
try {
  await saveOrder(order);
} catch (e) {
  // swallowed
}

// Fixed: handle it meaningfully, or let it propagate to a caller that can.
try {
  await saveOrder(order);
} catch (e) {
  throw new OrderSaveError(`Failed to save order ${order.id}`, { cause: e });
}
```

**Why it hurts.** The real failure surfaces later, somewhere confusing and disconnected from its actual cause — a customer reports a missing order days later, with no error in the logs pointing at the actual write that failed.

**Fix.** Handle the error meaningfully at a point equipped to make that call, or let it propagate (with context) to one that is. Never catch-and-ignore purely to make a type checker or a linter warning go away.

**Related.** The language-specific forms of this are covered per language: discarded Go errors and empty Python `except` blocks in [languages.md](languages.md)'s entries both name this same failure.

## Domain boundary leakage

**What it looks like.** A concept, format, or rule owned by one domain/module (an internal status code, a currency's minor-unit count, a vendor's field name) is inlined directly into a different domain instead of crossing through a named translation point — so the second domain now silently depends on the first domain's internal representation.

**Example.**
```ts
// Bad: the billing module's internal status codes leak straight into the shipping module.
if (order.status === 3) { shipOrder(order); } // what is 3, and who guarantees billing won't renumber it?

// Fixed: billing exposes a named boundary; shipping depends on the meaning, not the encoding.
// billing.ts
export function isPaid(order: Order): boolean { return order.status === BillingStatus.PAID; }
// shipping.ts
if (isPaid(order)) { shipOrder(order); }
```

**Why it hurts.** The consuming domain breaks silently the moment the owning domain changes its internal representation, because nothing marks the dependency — there's no boundary to update, just a coincidental literal that happened to match. This is how magic numbers/strings (above) become cross-module outages instead of local ones.

**Fix.** Give every domain a named boundary (a function, constant, enum, or type) that the domain itself owns and exports; other domains depend on that boundary, never on the raw literal or internal shape behind it.

**Related.** The cross-module version of [magic numbers / magic strings](#magic-numbers--magic-strings) and [primitive obsession](#primitive-obsession) — same root cause (an implicit meaning left unnamed), but the blast radius crosses a domain instead of staying in one file. Also see dependency inversion in [principles.md](principles.md), which is the SOLID lens for the same boundary.

## The rule of three

A heuristic tying several of the above together, worth naming on its own: tolerate duplication once, watch it closely the second time, extract on the third. Abstracting after the very first occurrence usually guesses the wrong shared shape (see [speculative generality](#speculative-generality) and [copy-paste programming](#copy-paste-programming) above); waiting for three real occurrences gives enough signal about what's actually shared to extract it correctly.
