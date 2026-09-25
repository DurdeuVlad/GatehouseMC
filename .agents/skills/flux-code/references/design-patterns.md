# Design patterns index

This file is a lookup table only. Find the pattern (or the category) that matches the current trade-off, then open the linked category file for the actual detail — do not read every category file up front.

## Creational — control how objects get made

| Pattern | Use when | Avoid when | Detail |
|---|---|---|---|
| Factory method | A class can't know in advance which concrete subtype it needs to create. | There's only one concrete type; a constructor call is clearer. | [patterns/creational.md](patterns/creational.md#factory-method) |
| Abstract factory | Families of related objects must be created together and stay consistent (e.g. one UI toolkit's widgets). | Only one product family exists or will ever exist. | [patterns/creational.md](patterns/creational.md#abstract-factory) |
| Builder | Construction needs many optional parameters or a multi-step assembly, and the result must be immutable once built. | The object has two or three fields; a constructor or named-args call is enough. | [patterns/creational.md](patterns/creational.md#builder) |
| Singleton | Exactly one shared instance must exist and be globally reachable (a logger, a config registry). | Almost always: it hides a dependency, breaks test isolation, and can usually be replaced by passing the instance explicitly (dependency injection). | [patterns/creational.md](patterns/creational.md#singleton) |
| Prototype | Creating an object is expensive and a similar existing instance can be cloned and tweaked. | Objects are cheap to construct from scratch. | [patterns/creational.md](patterns/creational.md#prototype) |

## Structural — compose objects and interfaces

| Pattern | Use when | Avoid when | Detail |
|---|---|---|---|
| Adapter | An existing class's interface doesn't match what the caller needs, and you can't (or shouldn't) change either side. | You control both sides — just change the interface directly. | [patterns/structural.md](patterns/structural.md#adapter) |
| Decorator | Behavior needs to be added to individual objects at runtime without subclassing every combination. | Only one or two fixed variants exist; a subclass or a flag is simpler. | [patterns/structural.md](patterns/structural.md#decorator) |
| Facade | A subsystem has a complex, multi-step API and most callers only need a simple entry point. | The subsystem is already simple, or callers genuinely need the full underlying API. | [patterns/structural.md](patterns/structural.md#facade) |
| Composite | Individual objects and groups of objects must be treated uniformly (trees, UI hierarchies, nested filters). | The structure is never nested — a plain list or map is enough. | [patterns/structural.md](patterns/structural.md#composite) |
| Proxy | Access to an object needs to be controlled, deferred, or cached (lazy loading, permission checks, remote calls). | Direct access is already cheap and unrestricted. | [patterns/structural.md](patterns/structural.md#proxy) |

## Behavioral — coordinate responsibilities and control flow

| Pattern | Use when | Avoid when | Detail |
|---|---|---|---|
| Strategy | An algorithm has several interchangeable variants selected at runtime. | There's only one variant, or the variants never change independently of the caller. | [patterns/behavioral.md](patterns/behavioral.md#strategy) |
| Observer | Multiple parts of a system must react to a state change without the source knowing who's listening. | There's a single, known consumer — call it directly instead. | [patterns/behavioral.md](patterns/behavioral.md#observer) |
| Command | An action needs to be queued, logged, undone, or passed around as a first-class value. | The action can just be a direct function call with no need to defer, queue, or undo it. | [patterns/behavioral.md](patterns/behavioral.md#command) |
| State | An object's behavior changes substantially based on internal state, and that logic is turning into a large conditional. | There are only two states and a boolean flag reads fine. | [patterns/behavioral.md](patterns/behavioral.md#state) |
| Template method | Several algorithms share the same skeleton but differ in specific steps. | The "shared skeleton" is imagined, not real — forcing one skeleton onto unrelated algorithms creates coupling instead of removing duplication. | [patterns/behavioral.md](patterns/behavioral.md#template-method) |

## Modern / architectural — larger-grained application structure

| Pattern | Use when | Avoid when | Detail |
|---|---|---|---|
| Dependency injection | Units need their dependencies (especially I/O, external services) supplied from outside so they stay testable and swappable. | The dependency is a stable stdlib call with no reason to ever be swapped or mocked. | [patterns/modern.md](patterns/modern.md#dependency-injection) |
| Repository | Data access needs to be abstracted from business logic so storage details don't leak everywhere. | The app is small and has one storage backend that will not change; the abstraction adds a layer with no real second implementation. | [patterns/modern.md](patterns/modern.md#repository) |
| Middleware / pipeline | Cross-cutting concerns (auth, logging, validation) need to wrap a request/handler chain uniformly. | There's a single handler with no shared cross-cutting concern to factor out. | [patterns/modern.md](patterns/modern.md#middleware--pipeline) |
| CQRS | Read and write models have genuinely different shapes, scaling needs, or consistency requirements. | Reads and writes share the same simple model — CQRS here is pure overhead and duplicated types. | [patterns/modern.md](patterns/modern.md#cqrs) |
| Event-driven / pub-sub | Producers and consumers must stay decoupled and consumers may change or multiply over time. | There is one producer and one consumer with a synchronous need for the result — a direct call is simpler and easier to trace. | [patterns/modern.md](patterns/modern.md#event-driven--pub-sub) |
| Hexagonal / ports & adapters | The domain logic must stay independent of frameworks and infrastructure so either can be swapped or tested in isolation. | A small script or service with one obvious infrastructure choice that will not change; the layering adds indirection with no payoff. | [patterns/modern.md](patterns/modern.md#hexagonal--ports--adapters) |

Every "avoid when" above is a default, not a rule — apply it, or override it explicitly with a stated reason, per [principles.md](principles.md)'s trade-off guidance.
