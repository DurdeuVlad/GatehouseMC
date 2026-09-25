# Creational patterns

Detail for the creational row of [../design-patterns.md](../design-patterns.md). Read one section, not the whole file. Examples use TypeScript-flavored pseudocode; the shape transfers to any language with first-class functions and interfaces.

## Factory method

**Intent.** A creator defines a method for producing an object, but lets subclasses (or a passed-in function) decide the concrete type produced — callers depend on an interface, never on `new ConcreteType()` directly.

**Structure.** A `Creator` declares `createProduct(): Product`; concrete creators override it to return a specific `Product` subtype. In languages with first-class functions this collapses to a single function or a lookup map from a discriminator to a constructor, with no class hierarchy at all.

**Example.**
```ts
interface Notifier { send(msg: string): void }
class EmailNotifier implements Notifier { send(msg) { /* ... */ } }
class SmsNotifier implements Notifier { send(msg) { /* ... */ } }

function createNotifier(kind: "email" | "sms"): Notifier {
  return kind === "email" ? new EmailNotifier() : new SmsNotifier();
}
```
The caller only ever holds a `Notifier`; adding a `PushNotifier` later touches the factory, not every call site.

**Consequences.** Decouples callers from concrete types, so a new variant can be added without editing existing call sites (open/closed, see [../../principles.md](../principles.md)). Cost: one more layer of indirection than a direct constructor call, which is wasted ceremony when only one concrete type will ever exist.

**Related.** Often the mechanism an [Abstract factory](#abstract-factory) exposes per product. A [Strategy](behavioral.md#strategy) is frequently *produced* by a factory method rather than constructed inline.

## Abstract factory

**Intent.** Produce families of related objects through one interface, guaranteeing the family stays internally consistent (e.g. a UI theme's button, checkbox, and dialog must all match).

**Structure.** An `AbstractFactory` interface declares one creation method per product in the family (`createButton()`, `createCheckbox()`); each concrete factory (`LightThemeFactory`, `DarkThemeFactory`) implements the whole set consistently.

**Example.**
```ts
interface WidgetFactory {
  createButton(): Button;
  createCheckbox(): Checkbox;
}
class DarkThemeFactory implements WidgetFactory {
  createButton() { return new DarkButton(); }
  createCheckbox() { return new DarkCheckbox(); }
}
// Client code depends only on WidgetFactory, never on DarkThemeFactory directly.
function renderForm(factory: WidgetFactory) {
  const button = factory.createButton();
  const checkbox = factory.createCheckbox();
}
```

**Consequences.** Guarantees the product family stays consistent, and swapping the whole family is a one-line change (pass a different factory). Cost: adding a *new product* to the family (e.g. a `Slider`) requires editing every concrete factory — the pattern trades easy family-swapping for harder per-product extension. Confirm multiple real families exist (not just one, imagined-future one) before adopting it; a single family is just [Factory method](#factory-method) with extra ceremony.

**Related.** Each creation method inside an abstract factory is typically implemented as its own [Factory method](#factory-method). Frequently paired with [Dependency injection](modern.md#dependency-injection) at the composition root, where the chosen concrete factory is selected once and passed down.

## Builder

**Intent.** Separate the step-by-step construction of a complex object from its final representation, so the same construction process can produce different configurations and the result can be made immutable once built.

**Structure.** A `Builder` exposes chained methods for each optional piece of configuration and a final `build()` that returns the finished, often immutable, object.

**Example.**
```ts
class HttpRequestBuilder {
  private headers: Record<string, string> = {};
  private body?: string;
  setHeader(key: string, value: string) { this.headers[key] = value; return this; }
  setBody(body: string) { this.body = body; return this; }
  build(): HttpRequest { return new HttpRequest(this.headers, this.body); }
}

const req = new HttpRequestBuilder()
  .setHeader("Content-Type", "application/json")
  .setBody(JSON.stringify(payload))
  .build();
```

**Consequences.** Avoids the "telescoping constructor" problem (a constructor with many optional parameters, most calls passing `null`/defaults for the ones they don't need) and can enforce invariants only once `build()` runs. Cost: an extra class and more code than a direct constructor call. In most modern languages, named/keyword arguments or a plain data class with defaults (Python dataclasses, Kotlin/C# named parameters, TypeScript object-literal parameters) solve the same telescoping-constructor problem with far less ceremony — reach for a real Builder class only when construction has genuine multi-step logic or validation beyond just "set some fields."

**Related.** Often paired with [Factory method](#factory-method) when the builder itself needs to be selected polymorphically. Distinct from [Prototype](#prototype): a builder assembles a new object piece by piece; a prototype clones a whole existing instance.

## Singleton

**Intent.** Guarantee a single instance of a class and provide one global access point to it.

**Structure.** A private constructor plus a static accessor that lazily creates the instance on first call and returns the same instance thereafter.

**Example (what to avoid, and the fix).**
```ts
// Avoid: hides the dependency, hard to test.
class ConfigSingleton {
  private static instance: ConfigSingleton;
  static get(): ConfigSingleton {
    if (!this.instance) this.instance = new ConfigSingleton();
    return this.instance;
  }
}
function loadUser(id: string) {
  const cfg = ConfigSingleton.get(); // hidden dependency — every caller of loadUser
  // implicitly depends on this, invisibly, and no test can substitute a fake config
  // without reaching into global state.
}

// Prefer: construct once at the composition root, pass it explicitly.
function loadUser(id: string, cfg: Config) { /* ... */ }
const cfg = new Config(/* ... */);
loadUser("123", cfg);
```

**Consequences.** Almost always avoid it. A singleton is a global variable with a class wrapper: it hides a real dependency inside whatever function reaches for it, makes unit tests fragile (shared global state can leak between test cases unless carefully reset), and blocks substituting a fake/mock in tests without reaching into the class internals. The narrow legitimate case is a genuinely singular, stateless resource with no plausible reason to ever be swapped or mocked (e.g. a process-wide logging configuration) where threading it through every call would add pure noise.

**Related.** The real fix for most "I need a singleton" impulses is [Dependency injection](modern.md#dependency-injection): construct the one instance once, at the composition root, and pass it in explicitly instead of reaching for it globally. In languages where a module is already a singleton by construction (Python, JavaScript/ESM), prefer a plain module over a hand-rolled singleton class.

## Prototype

**Intent.** Create new objects by cloning an existing, pre-configured instance and adjusting the copy, instead of constructing from scratch — useful when construction is expensive or when a suitable template already exists.

**Structure.** Objects implement a `clone()` method that returns a new, independent copy of themselves (or the language provides this natively, e.g. Python's `copy.deepcopy`, Rust's `Clone` trait).

**Example.**
```ts
class DocumentTemplate {
  constructor(public sections: string[], public styles: Record<string, string>) {}
  clone(): DocumentTemplate {
    return new DocumentTemplate([...this.sections], { ...this.styles }); // deep-ish copy
  }
}
const invoiceTemplate = new DocumentTemplate(["header", "line-items", "total"], { font: "Inter" });
const customInvoice = invoiceTemplate.clone();
customInvoice.sections.push("notes");
```

**Consequences.** Avoids repeating expensive initialization (deep setup, an expensive parse, a network fetch) when a similar instance already exists to copy. Main pitfall: shallow-copy bugs — cloning an object that holds references to mutable nested data (arrays, other objects) without deep-copying them leaves the two "independent" clones silently sharing state, so a mutation on one leaks into the other.

**Related.** An alternative to [Factory method](#factory-method)/[Builder](#builder) specifically when construction cost, not construction complexity, is the problem being solved.
