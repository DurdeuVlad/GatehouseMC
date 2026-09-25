# Modern / architectural patterns

Detail for the modern/architectural row of [../design-patterns.md](../design-patterns.md). These operate at application-structure granularity, not single-class granularity. Read one section, not the whole file. Examples use TypeScript-flavored pseudocode; the shape transfers to any language with interfaces.

## Dependency injection

**Intent.** Supply a unit's dependencies (especially I/O and external services) from outside — via constructor, function parameter, or a container — instead of the unit constructing or reaching out for them itself, so the unit stays testable and its dependencies stay swappable.

**Structure.** A class or function declares what it needs as parameters typed by interface/abstraction; a separate piece of code (the "composition root," often just the application's entry point) constructs the real implementations and wires them together.

**Example.**
```ts
interface EmailSender { send(to: string, body: string): void }

class UserService {
  constructor(private emailSender: EmailSender) {} // injected, not constructed here
  registerUser(email: string) {
    // ... save user ...
    this.emailSender.send(email, "Welcome!");
  }
}

// Composition root:
const service = new UserService(new SmtpEmailSender(config));

// Test:
const fakeSender: EmailSender = { send: jest.fn() };
const testService = new UserService(fakeSender); // no real network call needed
```

**Consequences.** Makes unit tests possible without real I/O, and lets an implementation be swapped (a fake for tests, a different provider in production) without touching the class that uses it. Cost: an extra layer of indirection and, at scale, a decision about how wiring happens (see below). Plain "pass the dependency as a parameter" is dependency injection in full; a DI *framework/container* (Spring, .NET's built-in `IServiceCollection`, InversifyJS) automates the wiring for large object graphs and is a separate, heavier decision — most codebases need the constructor-parameter form far more often than a container. Skip DI entirely for a stable stdlib call with no plausible reason to ever be swapped (e.g. `Math`, string formatting).

**Related.** Frequently the composition-root code selects a concrete implementation via an [Abstract factory](creational.md#abstract-factory) or [Factory method](creational.md#factory-method). Underlies how a [Repository](#repository)'s interface gets a real implementation supplied to the business logic that depends on it.

## Repository

**Intent.** Abstract data access behind an interface expressed in domain terms, so business logic depends on "what data operation" rather than "which database/ORM/query."

**Structure.** A `Repository` interface declares domain-meaningful methods (`findActiveUsers()`, `save(order)`); one or more concrete implementations back it with a real database, an in-memory store for tests, or another backend.

**Example.**
```ts
interface UserRepository {
  findById(id: string): User | null;
  save(user: User): void;
}

class PostgresUserRepository implements UserRepository {
  findById(id: string) { /* SQL query */ return null as any; }
  save(user: User) { /* SQL insert/update */ }
}

class InMemoryUserRepository implements UserRepository {
  private store = new Map<string, User>();
  findById(id: string) { return this.store.get(id) ?? null; }
  save(user: User) { this.store.set(user.id, user); }
}

// Business logic depends only on the interface:
function deactivateUser(repo: UserRepository, id: string) {
  const user = repo.findById(id);
  if (user) { user.active = false; repo.save(user); }
}
```

**Consequences.** Business logic (`deactivateUser`) can be unit-tested against `InMemoryUserRepository` with no real database, and the storage backend can change without touching business logic. Cost: an interface plus at least one implementation, which is a layer that pays for a flexibility nobody uses when the app has one storage backend that isn't changing and tests are fine hitting a real (or containerized) database. A thin wrapper directly around the ORM's query builder is often enough until a second real need (a second backend, or a genuine testability problem) appears.

**Related.** A specific application of [Dependency injection](#dependency-injection) at the data-access boundary. Often paired with [Hexagonal / ports & adapters](#hexagonal--ports--adapters), where the repository interface is one of the domain's "ports."

## Middleware / pipeline

**Intent.** Wrap a handler chain with cross-cutting concerns (auth, logging, validation, rate limiting) that apply uniformly across many endpoints/handlers, each middleware calling the next in sequence.

**Structure.** Each middleware receives the request (and a reference to "the rest of the chain") and decides whether to act before/after delegating, or to short-circuit the chain entirely (e.g. reject an unauthenticated request without ever calling the next handler).

**Example.**
```ts
type Handler = (req: Request) => Response;
type Middleware = (next: Handler) => Handler;

const withLogging: Middleware = (next) => (req) => {
  console.log(`-> ${req.path}`);
  const res = next(req);
  console.log(`<- ${res.status}`);
  return res;
};

const withAuth: Middleware = (next) => (req) => {
  if (!req.headers.authorization) return { status: 401, body: "" };
  return next(req); // only calls onward if authorized
};

function compose(...middlewares: Middleware[]): Middleware {
  return (next) => middlewares.reduceRight((wrapped, mw) => mw(wrapped), next);
}

const handler = compose(withLogging, withAuth)(actualHandler);
```

**Consequences.** A concern that would otherwise be copy-pasted into every handler is written once and composed in — one of the highest-leverage, lowest-cost patterns in this whole reference. Cost: a long middleware chain can make it harder to trace exactly which layer produced a given response without good logging/tracing. Keep each middleware single-purpose (mirrors single responsibility, see [../../principles.md](../principles.md)) — a middleware doing five unrelated things defeats the point of composability.

**Related.** Structurally, Middleware is [Decorator](../patterns/structural.md#decorator) applied specifically to a request-handling chain rather than to an arbitrary object.

## CQRS (command query responsibility segregation)

**Intent.** Split the read model from the write model so each can be shaped, optimized, and scaled independently — most valuable when reads and writes genuinely have different shapes or performance/consistency needs.

**Structure.** "Commands" (writes) go through one model, typically normalized and consistency-focused; "queries" (reads) go through a separate model, often denormalized and cache/index-optimized for the specific read patterns needed, sometimes populated asynchronously from the write side.

**Example (shape, not a full implementation).**
```ts
// Write side: normalized, transactional.
interface PlaceOrderCommand { customerId: string; items: OrderItem[] }
function placeOrder(cmd: PlaceOrderCommand): void { /* validate + write to normalized tables */ }

// Read side: denormalized, shaped exactly for the UI that queries it.
interface OrderSummaryView { orderId: string; customerName: string; itemCount: number; total: number }
function getOrderSummaries(customerId: string): OrderSummaryView[] { /* read from a pre-joined view/cache */ }
```

**Consequences.** Lets the read side be denormalized/cached/scaled for query performance without distorting the write side's transactional integrity, and vice versa. Cost: real complexity — two models to keep in sync (often via events, introducing eventual consistency), roughly double the types and code paths, and a new failure mode where the read model can lag behind the write model. Applied to a simple CRUD app with no such divergence, CQRS is close to pure overhead — this is one of the most commonly over-applied patterns in the whole library; confirm the divergence is real before reaching for it.

**Related.** Frequently implemented using [Event-driven / pub-sub](#event-driven--pub-sub) to propagate write-side changes into the read model.

## Event-driven / pub-sub

**Intent.** Let producers publish events without knowing who, if anyone, consumes them, and let consumers subscribe independently — decoupling producer and consumer so consumers can be added, removed, or changed without touching the producer.

**Structure.** Producers publish an event (a fact: "OrderPlaced", "UserDeactivated") to a broker/bus/queue; one or more independent consumers subscribe to event types they care about and react asynchronously.

**Example (shape).**
```ts
interface EventBus { publish(event: DomainEvent): void; subscribe(type: string, handler: (e: DomainEvent) => void): void }

// Producer has no idea who (if anyone) is listening:
function placeOrder(order: Order, bus: EventBus) {
  saveOrder(order);
  bus.publish({ type: "OrderPlaced", orderId: order.id });
}

// Consumers register independently, potentially added long after the producer was written:
bus.subscribe("OrderPlaced", (e) => sendConfirmationEmail(e.orderId));
bus.subscribe("OrderPlaced", (e) => updateInventory(e.orderId));
```

**Consequences.** New consumers can be added with zero changes to the producer, and consumers can process later, retry independently, or be temporarily down without blocking the producer. Real costs to weigh, not hand-wave past: it's harder to trace a single request end-to-end across producer and consumers, the system moves from immediate to eventual consistency, and undelivered or duplicate event delivery becomes a failure mode that must be explicitly handled (idempotent consumers, dead-letter queues). Skip it for one producer and one consumer with a synchronous need for the result — a direct function/method call is simpler, faster to write, and far easier to debug.

**Related.** Generalizes [Observer](../patterns/behavioral.md#observer) across process/service boundaries via a broker instead of an in-process listener list. Commonly the propagation mechanism for [CQRS](#cqrs)'s read-model updates.

## Hexagonal / ports & adapters

**Intent.** Keep domain logic independent of frameworks and infrastructure by expressing everything the domain needs as "ports" (interfaces), with "adapters" implementing those ports against real infrastructure — so either the domain or the infrastructure can change without forcing a change in the other.

**Structure.** The domain core depends only on port interfaces it defines for its own needs (`OrderRepository`, `PaymentGateway`); adapters live outside the core and implement those ports against a specific database, HTTP API, or message queue. The domain never imports an adapter or infrastructure library directly.

**Example (shape).**
```ts
// Port, defined by and for the domain:
interface PaymentGateway { charge(amount: number, token: string): Promise<boolean> }

// Domain core depends only on the port:
class CheckoutService {
  constructor(private payments: PaymentGateway) {}
  async completeOrder(order: Order, token: string) {
    const ok = await this.payments.charge(order.total, token);
    if (ok) markOrderPaid(order);
  }
}

// Adapter, outside the core, implementing the port against a real provider:
class StripePaymentGateway implements PaymentGateway {
  async charge(amount: number, token: string) { /* call Stripe SDK */ return true; }
}
```

**Consequences.** The domain (`CheckoutService`) can be tested with a fake `PaymentGateway` and has zero dependency on Stripe's SDK, framework, or wire format; swapping payment providers means writing a new adapter, not touching domain logic. Cost: real layering and indirection — more files, more interfaces, and a mental model the team has to actually maintain discipline around (it's easy to "cheat" and import infrastructure into the domain under deadline pressure, which quietly defeats the whole point). Skip it for a small script or service with one obvious infrastructure choice unlikely to change; the layering costs more than the flexibility is worth there.

**Related.** This is [Dependency inversion](../../principles.md) (from SOLID) applied at the scale of a whole application rather than a single class; if the class-level version isn't earning its cost yet in a given codebase, the application-wide version won't either. Ports are typically realized as [Repository](#repository) interfaces (for data) or similar narrow interfaces for other external systems, each wired to a real adapter via [Dependency injection](#dependency-injection).
