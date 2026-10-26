# Behavioral patterns

Detail for the behavioral row of [../design-patterns.md](../design-patterns.md). Read one section, not the whole file. Examples use TypeScript-flavored pseudocode; the shape transfers to any language with interfaces and first-class functions.

## Strategy

**Intent.** Encapsulate a family of interchangeable algorithms behind a common interface, selected at runtime by the caller — e.g. a sort comparator, a pricing rule, a retry policy.

**Structure.** A `Context` holds a reference to a `Strategy` interface and delegates the algorithm-specific work to it, rather than implementing the algorithm itself or branching on a type flag.

**Example.**
```ts
interface PricingStrategy { price(order: Order): number }

class StandardPricing implements PricingStrategy {
  price(order: Order) { return order.subtotal; }
}
class BulkDiscountPricing implements PricingStrategy {
  price(order: Order) { return order.subtotal * 0.9; }
}

class Checkout {
  constructor(private pricing: PricingStrategy) {}
  total(order: Order) { return this.pricing.price(order); } // never branches on "which pricing"
}
```

**Consequences.** New variants are added without touching `Checkout` or any existing strategy (open/closed, see [../../principles.md](../principles.md)), and the active strategy can be swapped per call or per configuration. Cost: one interface plus one class per variant, which is overhead when there's genuinely only one algorithm today. In languages with first-class functions, this is usually just a function passed as a parameter (`checkout(order, bulkDiscountPricing)`) — no class hierarchy required.

**Related.** Frequently *selected* by a [Factory method](creational.md#factory-method) based on some discriminator, and often *produced* through [Dependency injection](modern.md#dependency-injection) at the composition root.

## Observer

**Intent.** Let one or more listeners react to a state change in a subject, without the subject knowing the concrete listeners — the basis for event emitters, pub/sub libraries, and most reactive-UI state.

**Structure.** A `Subject` maintains a list of `Observer`s and notifies all of them (calling an agreed method, e.g. `update()`) when its state changes; observers register/unregister themselves without the subject needing to know their concrete type.

**Example.**
```ts
type Listener = (temp: number) => void;

class Thermostat {
  private listeners: Listener[] = [];
  onChange(listener: Listener) { this.listeners.push(listener); }
  private setTemperature(temp: number) {
    this.listeners.forEach(listener => listener(temp)); // subject doesn't know who's listening
  }
}

const thermostat = new Thermostat();
thermostat.onChange(temp => console.log(`Display: ${temp}°`));
thermostat.onChange(temp => logToHistory(temp));
```

**Consequences.** New reactors can be added without modifying the subject, and the set of listeners can change at runtime. Cost/risk: ordering between multiple listeners is often unspecified and shouldn't be relied on; listeners that are never unregistered on teardown cause memory leaks and phantom callbacks firing on objects that should be dead — a very common bug class in UI code specifically.

**Related.** [Event-driven / pub-sub](modern.md#event-driven--pub-sub) is Observer generalized across process/service boundaries via a message broker, rather than an in-process listener list.

## Command

**Intent.** Turn a request or action into a first-class object (or closure) so it can be queued, logged, retried, or undone, instead of being an immediate, un-inspectable function call.

**Structure.** A `Command` interface declares an `execute()` (and often `undo()`) method; concrete commands capture whatever state/parameters they need to perform the action later, independent of who created them.

**Example.**
```ts
interface Command { execute(): void; undo(): void }

class InsertTextCommand implements Command {
  constructor(private doc: Document, private text: string, private at: number) {}
  execute() { this.doc.insert(this.at, this.text); }
  undo() { this.doc.delete(this.at, this.text.length); }
}

class CommandHistory {
  private stack: Command[] = [];
  run(cmd: Command) { cmd.execute(); this.stack.push(cmd); }
  undoLast() { this.stack.pop()?.undo(); }
}
```

**Consequences.** Makes the action itself a value that can be stored, passed around, logged, retried, or reversed — necessary whenever "run this later" or "undo this" is a real requirement. Cost: an object per action type, which is unnecessary ceremony for a direct, immediate call with no need to defer or undo it. In modern code, a command is frequently just a closure stored in a list; a formal `Command` interface is worth it mainly when commands need shared structure beyond just "call this" (serialization for a persistent queue, a matching `undo`).

**Related.** Underlies task queues and job systems; often combined with [Memento](#memento-brief-mention) when `undo()` needs to restore prior state rather than compute its inverse.

### Memento (brief mention)

Not detailed as its own index row because it's usually reached for only alongside Command's `undo()`: it captures and externally stores an object's internal state so it can be restored later, without exposing that state's structure to the code doing the storing (e.g. a snapshot object opaque to everything but the originator that created it).

## State

**Intent.** Let an object change its behavior when its internal state changes, by delegating to a state-specific object instead of branching on a state field in every method that cares about it.

**Structure.** A `Context` holds a reference to a `State` interface; each concrete state implements the context's behavior for that state, and a state transition is just swapping which `State` object the context holds.

**Example.**
```ts
interface ConnectionState { send(conn: Connection, data: string): void }

class ConnectedState implements ConnectionState {
  send(conn: Connection, data: string) { conn.socket.write(data); }
}
class DisconnectedState implements ConnectionState {
  send(conn: Connection, data: string) { conn.queue.push(data); conn.reconnect(); }
}

class Connection {
  state: ConnectionState = new DisconnectedState();
  send(data: string) { this.state.send(this, data); } // no "if connected" check here
}
```

**Consequences.** Keeps state-specific behavior for each state together in one place instead of scattered `if (state === "connected")` checks across every method, and adding a new state means adding a new class, not editing every existing branch. Cost: for two states, a boolean flag and a plain `if` communicate intent just as well and don't need the indirection.

**Related.** Structurally identical to [Strategy](#strategy) — the difference is intent: Strategy's algorithm is chosen once by the caller, State's "strategy" changes itself over the object's lifetime as a reaction to events.

## Template method

**Intent.** Define the invariant skeleton of an algorithm in a base method, deferring specific steps to overridable subclass methods, so the shared sequence lives in exactly one place.

**Structure.** A base class's method calls a fixed sequence of steps, some implemented in the base class and some declared abstract (or given a default that subclasses may override) for subclasses to fill in.

**Example.**
```ts
abstract class DataImporter {
  import(source: string): void {
    const raw = this.fetch(source);
    const parsed = this.parse(raw);      // step that varies
    this.validate(parsed);               // shared, in base class
    this.save(parsed);                   // step that varies
  }
  protected abstract fetch(source: string): string;
  protected abstract parse(raw: string): Record<string, unknown>[];
  private validate(rows: Record<string, unknown>[]) { /* shared rule */ }
  protected abstract save(rows: Record<string, unknown>[]): void;
}

class CsvImporter extends DataImporter {
  protected fetch(source: string) { return readFile(source); }
  protected parse(raw: string) { return parseCsv(raw); }
  protected save(rows: Record<string, unknown>[]) { writeToDb(rows); }
}
```

**Consequences.** Keeps the invariant sequence (and any genuinely shared steps, like `validate` above) in exactly one place while letting each concrete algorithm vary the steps that actually differ. Cost/risk: it locks subclasses into inheriting from a specific base class, and if the "shared skeleton" turns out to be only superficially shared (two algorithms that happen to have similarly-named steps today but diverge for unrelated reasons), it creates fragile coupling instead of removing duplication — see the DRY trade-off note in [../../principles.md](../principles.md). Composing small functions (closer to [Strategy](#strategy)) is usually preferable to inheritance when the shared structure is thin.

**Related.** Inversion of [Strategy](#strategy): Template method fixes the algorithm's shape and varies its steps via subclassing; Strategy fixes nothing about shape and varies the whole algorithm via composition.
