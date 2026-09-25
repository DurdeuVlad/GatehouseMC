# Rust

## Overview

Statically typed, compiled, no garbage collector. Memory and thread safety are enforced at compile time through ownership, borrowing, and lifetimes rather than a runtime GC or manual free — a design goal, not an incidental feature, and it's the single idea most of Rust's idioms flow from. The compiler is deliberately strict; a program that compiles has already ruled out data races and use-after-free by construction, which is the trade a steeper initial learning curve buys.

## Ownership and the type system

- Let the borrow checker drive the design rather than fighting it with `.clone()` everywhere to make the compiler stop complaining. A design that needs pervasive cloning to compile is usually signaling that ownership should be structured differently (e.g. an `Rc`/`Arc` for genuinely shared ownership, or restructuring which part of the code owns the data), not a checker to work around with copies.
- `Result<T, E>` for recoverable errors, `Option<T>` for a value that may legitimately be absent — both are ordinary enums the type system forces callers to handle, replacing null pointers and unchecked error codes with compile-time-checked alternatives. `panic!` is reserved for unrecoverable programmer-error states (a broken invariant), never for an expected failure path like "file not found" or "invalid user input."
- The `?` operator propagates a `Result`/`Option`'s error case up to the caller without manual matching at every call site — the idiomatic replacement for exception-style propagation.
- Prefer borrowing over taking ownership at function boundaries: `&str` over `String`, `&[T]` over `Vec<T>`, for parameters that only need to read the value. Take ownership only when the function genuinely needs to keep or consume the value.
- Traits over inheritance for shared behavior. Choose between a trait object (`dyn Trait`, runtime dispatch via vtable, needed when a collection must hold different concrete types behind one interface) and a generic with a trait bound (`fn f<T: Trait>(x: T)`, compile-time monomorphization, faster but generates code per concrete type) based on whether runtime polymorphism is actually needed.
- Pattern matching (`match`, `if let`, `while let`) is exhaustive by default for enums — the compiler forces every variant to be handled (or an explicit wildcard), which is a large part of how Rust code avoids an entire class of "forgot a case" bugs common in other languages' switch statements.

## Error handling

`Result<T, E>` and the `?` operator, as above. For libraries, define a specific error enum (often with the `thiserror` crate to reduce boilerplate) so callers can match on failure modes; for applications, a boxed dynamic error type (`anyhow::Error` or `Box<dyn std::error::Error>`) is usually enough since the top-level caller typically just reports the failure rather than branching on it. Never `.unwrap()`/`.expect()` a `Result`/`Option` on a path that can realistically fail at runtime with real input — reserve those for cases already proven impossible (e.g. right after a check that guarantees success) or genuinely-fatal startup conditions.

## Concurrency

The ownership/borrowing rules extend to concurrency: the compiler rejects a large class of data races at compile time via the `Send`/`Sync` marker traits, rather than catching them at runtime or not at all. `std::thread` plus channels (`std::sync::mpsc`) for straightforward message-passing concurrency; `Arc<Mutex<T>>` for genuinely shared mutable state across threads. For async I/O-bound workloads, an async runtime (see Tokio below) plus `async`/`await` syntax — async and OS threads solve different problems (I/O concurrency vs. CPU parallelism/blocking work) and reaching for async when the workload is actually CPU-bound doesn't help.

## Tooling

- **Formatting**: `rustfmt` (via `cargo fmt`) — the standard, not meaningfully configurable, removing style debate the same way `gofmt` does in Go.
- **Linting**: `clippy` (via `cargo clippy`) catches idiom violations and likely bugs well beyond what the compiler itself flags; treat clippy warnings as close to compiler errors in CI.
- **Package/build management**: `cargo` handles dependencies (`Cargo.toml`), builds, tests, and publishing in one tool — there is no separate build-system decision to make, unlike Java/C#/Go's varied ecosystems.

## Testing

Tests live alongside the code they test by convention, in a `#[cfg(test)] mod tests` block using `#[test]` functions and the built-in `assert!`/`assert_eq!` macros — no external test framework is typically needed for unit tests. Integration tests live in a top-level `tests/` directory and exercise the crate's public API only. `cargo test` runs both. Property-based testing (`proptest` or `quickcheck`) is worth reaching for on logic with a lot of edge cases (parsers, serialization) where enumerating example-based tests by hand would miss cases.

## Frameworks

### Tokio

**What it is.** The dominant async runtime; most async web/network Rust code depends on it directly or indirectly, and many libraries (web frameworks, database drivers) are written against its APIs specifically rather than being runtime-agnostic.

**Example.**
```rust
#[tokio::main]
async fn main() {
    let result = fetch_user(42).await; // scheduled on Tokio's runtime
    println!("{:?}", result);
}
```

**When to use / avoid.** Only pull in async and Tokio when the workload is genuinely I/O-bound and concurrent — many simultaneous connections/requests where blocking one thread per connection wouldn't scale. Synchronous, thread-based code is simpler to write and reason about when concurrency at that scale isn't the actual need; don't reach for async by default on a CLI tool or a batch job with no concurrent I/O to overlap.

### Axum / Actix-web

**What they are.** Web frameworks. Axum builds directly on Tokio and the Tower middleware/service abstraction, so its middleware composes with anything else built on Tower. Actix-web predates Axum, has its own actor-based history, and remains a solid, mature choice.

**Example (Axum).**
```rust
async fn create_article(Json(payload): Json<CreateArticle>) -> Json<Article> {
    Json(Article { id: 1, title: payload.title }) // extractors (Json<T>) validate/deserialize the body
}

let app = Router::new().route("/articles", post(create_article));
```

**When to use / avoid.** Pick Axum as the more common modern default for new services, particularly when Tower's composable middleware ecosystem matters. Pick Actix-web when its actor-based model or existing team familiarity with it outweighs Axum's tighter Tower integration — both are production-grade choices, this is largely an ecosystem/preference decision rather than a capability gap.

### Serde

**What it is.** The de facto standard for serialization/deserialization (JSON, YAML, TOML, and more) via `#[derive(Serialize, Deserialize)]` macros that generate the (de)serialization code at compile time.

**Example.**
```rust
#[derive(Serialize, Deserialize)]
struct Article { title: String, published: bool }

let json = serde_json::to_string(&article)?; // derive handles the mapping, no manual (de)serialization
```

**When to use / avoid.** Treat it as close to a standard-library dependency for this purpose — nearly every Rust project touching structured data uses it, and there's rarely a reason to hand-write serialization instead.

### Diesel / SQLx

**What they are.** Diesel is a compile-time-checked ORM and query builder — invalid queries or schema mismatches are caught before the program runs, via its query-building DSL. SQLx checks raw SQL query strings against the actual database schema at compile time (via a macro that connects to the DB or a cached schema) without a full ORM abstraction layer on top.

**Example (SQLx).**
```rust
let articles = sqlx::query_as!(Article, "SELECT id, title FROM articles WHERE published = $1", true)
    .fetch_all(&pool)
    .await?; // the query string itself is checked against the schema at compile time
```

**When to use / avoid.** Pick SQLx when writing raw SQL directly is preferred, with that compile-time safety net replacing a full ORM's abstraction. Pick Diesel when a fuller, more abstracted query-builder API (composable queries built from Rust expressions rather than SQL strings) is wanted instead.
