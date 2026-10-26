# Structural patterns

Detail for the structural row of [../design-patterns.md](../design-patterns.md). Read one section, not the whole file. Examples use TypeScript-flavored pseudocode; the shape transfers to any language with interfaces.

## Adapter

**Intent.** Convert the interface a class already exposes into the interface a caller needs, without modifying either side — used at integration boundaries where you don't own, or shouldn't change, one of the two interfaces.

**Structure.** An `Adapter` implements the *target* interface the caller expects, and internally holds/wraps the *adaptee* (the existing class), translating calls between the two.

**Example.**
```ts
// Third-party library's shape — not ours to change.
class LegacyXmlLogger { logAsXml(xml: string): void { /* ... */ } }

// The interface our code actually depends on.
interface Logger { log(message: string): void }

class XmlLoggerAdapter implements Logger {
  constructor(private legacy: LegacyXmlLogger) {}
  log(message: string): void {
    this.legacy.logAsXml(`<log>${message}</log>`);
  }
}
```

**Consequences.** Lets incompatible interfaces work together without touching either the caller or the wrapped class — valuable specifically because the wrapped class is external, legacy, or otherwise off-limits. Cost: one more layer to trace through when debugging. Keep the adapter thin — pure translation of calls/shapes; business logic that creeps into an adapter is a sign it belongs in the caller or a dedicated service instead.

**Related.** Structurally similar to [Proxy](#proxy) and [Decorator](#decorator) — all three wrap another object — but the *intent* differs: Adapter changes the interface, Proxy controls access to the same interface, Decorator adds behavior to the same interface.

## Decorator

**Intent.** Attach additional behavior to an individual object at runtime, without altering its class and without producing a new subclass for every combination of features (logging + caching + retry, in any combination, would otherwise need one subclass per combination).

**Structure.** A `Decorator` implements the same interface as the object it wraps, delegates to the wrapped object, and adds its own behavior before/after the delegated call. Decorators can be stacked, each wrapping the previous one.

**Example.**
```ts
interface DataSource { write(data: string): void }

class FileDataSource implements DataSource {
  write(data: string) { /* write to disk */ }
}

class CompressingDecorator implements DataSource {
  constructor(private wrapped: DataSource) {}
  write(data: string) { this.wrapped.write(compress(data)); }
}

class EncryptingDecorator implements DataSource {
  constructor(private wrapped: DataSource) {}
  write(data: string) { this.wrapped.write(encrypt(data)); }
}

// Compose only the behaviors actually needed, in any order:
const source: DataSource = new EncryptingDecorator(new CompressingDecorator(new FileDataSource()));
```

**Consequences.** Composes optional behaviors without a combinatorial subclass explosion, and behaviors can be added/removed at runtime by choosing what to wrap with. Cost: a stack of many small decorator objects can be harder to step through in a debugger than one class with the logic inline. In languages with first-class functions, a decorator is often just a higher-order function wrapping another function (`withLogging(withRetry(fetchUser))`) — no class needed at all.

**Related.** Structurally close to [Proxy](#proxy) (see Adapter's related note above) and often layered together with [Middleware / pipeline](modern.md#middleware--pipeline), which is essentially Decorator applied specifically to a request-handling chain.

## Facade

**Intent.** Provide a single, simplified entry point in front of a subsystem that has many moving parts, for the common case where most callers only need one straightforward operation from it.

**Structure.** A `Facade` class holds references to the subsystem's internal components and exposes a small number of high-level methods that internally coordinate multiple subsystem calls.

**Example.**
```ts
class VideoConverterFacade {
  constructor(
    private demuxer: Demuxer,
    private codec: VideoCodec,
    private muxer: Muxer,
  ) {}
  convert(inputFile: string, outputFormat: string): string {
    const stream = this.demuxer.extract(inputFile);
    const decoded = this.codec.decode(stream);
    return this.muxer.write(decoded, outputFormat); // caller never touches demuxer/codec/muxer directly
  }
}
```

**Consequences.** Hides subsystem complexity from the common case and gives the subsystem one stable public surface that can be reorganized internally without breaking callers. Cost/risk: if callers keep needing to reach past the facade for the "real" subsystem API, the facade is covering the wrong slice of functionality — don't force every caller through it if some genuinely need finer control.

**Related.** Unlike [Adapter](#adapter), a facade isn't matching a required interface — it's simplifying an existing one. Unlike [Proxy](#proxy), a facade typically talks to multiple objects, not one.

## Composite

**Intent.** Let individual objects ("leaves") and groups of objects ("composites") be treated through the same interface, so client code doesn't need to special-case "is this one item or a collection of them" — most useful for genuinely recursive, tree-shaped data.

**Structure.** A common `Component` interface (e.g. `render()`, `totalSize()`) is implemented both by leaf nodes and by composite nodes; a composite node implements the interface by delegating to (and combining the results of) its children, which may themselves be leaves or composites.

**Example.**
```ts
interface FileSystemNode { size(): number }

class File implements FileSystemNode {
  constructor(private bytes: number) {}
  size() { return this.bytes; }
}

class Directory implements FileSystemNode {
  private children: FileSystemNode[] = [];
  add(node: FileSystemNode) { this.children.push(node); }
  size(): number {
    return this.children.reduce((total, child) => total + child.size(), 0); // recurses uniformly
  }
}
```

**Consequences.** Client code that calls `.size()` never needs to know or check whether it's holding a `File` or a `Directory` — recursion is handled inside the composite itself. Cost: it can make it harder to restrict which operations apply only to leaves vs. only to composites, since both share one interface by design.

**Related.** Frequently combined with [Visitor](#visitor-brief-mention) (a behavioral pattern for operating on a composite's varied node types without polluting each node class with every operation) in larger tree-processing code, though a simple recursive method as above is often enough.

### Visitor (brief mention)

Not detailed as its own row in the index because it's rarely reached for outside compilers/AST-processing code: it lets a new operation be added over a composite/heterogeneous object structure without modifying the node classes themselves, at the cost of needing every node type to accept a visitor. Consider it specifically when new *operations* over a fixed set of node types are added far more often than new *node types* are — the opposite situation favors a plain method on each node instead.

## Proxy

**Intent.** Stand in for another object to control access to it — deferring expensive creation until first use, checking permissions before delegating, caching results, or forwarding calls across a process/network boundary — without the caller needing to know a proxy is involved.

**Structure.** A `Proxy` implements the same interface as the real subject, holds a reference to it (created eagerly or lazily), and adds an access policy around delegating to it.

**Example.**
```ts
interface ImageLoader { load(path: string): Image }

class RealImageLoader implements ImageLoader {
  load(path: string): Image { return decodeExpensiveImage(path); } // slow
}

class LazyImageLoaderProxy implements ImageLoader {
  private cache = new Map<string, Image>();
  constructor(private real: RealImageLoader) {}
  load(path: string): Image {
    if (!this.cache.has(path)) this.cache.set(path, this.real.load(path)); // deferred + cached
    return this.cache.get(path)!;
  }
}
```

**Consequences.** Lets access-control concerns (lazy loading, caching, permission checks, remote forwarding) live outside the real object, which stays focused on its own behavior. Cost: another layer between caller and callee, and a caching/lazy proxy specifically should be justified by measured cost, not added speculatively (see premature optimization in [../anti-patterns.md](../anti-patterns.md)).

**Related.** Structurally close to [Decorator](#decorator): both wrap the same interface. The distinction is intent — Proxy controls access to the *same* conceptual operation the real object performs; Decorator adds genuinely *new* behavior on top of it.
