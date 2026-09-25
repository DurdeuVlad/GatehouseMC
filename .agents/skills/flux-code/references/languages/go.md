# Go

## Overview

Statically typed, compiled, garbage-collected, deliberately small language (no generics until 1.18, no exceptions, no inheritance, no operator overloading — all by design, not oversight). The language spec fits in a single sitting; most of "idiomatic Go" is convention layered on top of a small core, enforced by tooling (`gofmt`) rather than debate. Optimizes for a large team reading unfamiliar code quickly over expressive power for the author.

## Type system and language idioms

- Interfaces are satisfied structurally (implicitly) — a type never declares which interfaces it implements. Define interfaces at the point of *use* (the consumer package), not next to the implementation; this is the opposite convention from Java/C#, where interfaces are typically declared alongside their implementation.
- Keep interfaces small — often a single method (`io.Reader`, `io.Writer`, `fmt.Stringer`). A large interface is harder for every implementer to satisfy and harder for a consumer to mock; prefer several small interfaces composed where needed over one broad one (mirrors interface segregation, see [../principles.md](../principles.md)).
- Prefer composition (struct embedding) over inheritance-style hierarchies — Go has no classical inheritance, and embedding a struct/interface promotes its methods without the fragile-base-class problems inheritance introduces.
- Zero values should be useful: design types so their zero value (`""`, `0`, `nil` slice, an empty struct) is already a valid, usable state where possible, rather than requiring an explicit constructor before use.
- Named return values are useful for documentation and for `defer`-based cleanup that sets an error, but avoid them purely to save a few keystrokes — an unclear named return can obscure control flow more than it clarifies it.

## Error handling

Errors are ordinary values (the `error` interface), returned as a function's last return value, never exceptions. Handle every error explicitly at the call site (`if err != nil { ... }`) — this is Go's deliberate alternative to try/catch, trading verbosity for making every failure path visible in the code that can see it. Never discard an error with `_` unless the reason it's genuinely safe to ignore is obvious and worth a short comment; a discarded error is the Go-specific form of error swallowing (see [../anti-patterns.md](../anti-patterns.md)). Wrap errors with context as they propagate up (`fmt.Errorf("doing X: %w", err)`), using `%w` so `errors.Is`/`errors.As` can still inspect the original cause further up the call stack. Reserve `panic` for truly unrecoverable programmer errors (e.g. a broken invariant at startup), not for expected failure conditions.

## Concurrency

Goroutines (lightweight, runtime-scheduled, cheap to spawn in the thousands) and channels are the core concurrency primitives. The idiom is "share memory by communicating" — send a value over a channel to hand off ownership — rather than "communicate by sharing memory" (multiple goroutines touching the same data behind a mutex), when either approach is a reasonable fit for the problem; `sync.Mutex`/`sync.RWMutex` still exist and are correct for protecting genuinely shared state (e.g. a cache). Always give a goroutine a clear owner and a way to be stopped (a `context.Context` for cancellation, a done channel); a goroutine nothing ever signals to stop is a leak. `select` for waiting on multiple channel operations at once, commonly paired with a `context.Done()` case for cancellation/timeout.

## Tooling

- **Formatting**: `gofmt` (or `goimports`, which also manages import grouping) — not configurable per project by design; this removes an entire category of style debate from the language.
- **Linting**: `go vet` (built in, catches real bugs like format-string mismatches) plus `golangci-lint` aggregating a broader set of linters for CI.
- **Modules/dependencies**: `go mod` is the standard, built-in dependency management — no external tool needed. Commit `go.sum` for reproducible builds.
- **Build**: `go build`/`go install` — the toolchain produces a single static binary by default, which is a large part of why Go is a common choice for CLIs and deployable services with no runtime dependency to install alongside them.

## Testing

The stdlib `testing` package is the standard — no external framework is typically needed. Table-driven tests (a slice of input/expected-output structs iterated in a single test function via subtests, `t.Run`) are the idiomatic way to cover multiple cases without duplicating test bodies (see the rule of three in [../anti-patterns.md](../anti-patterns.md)). `testify` adds richer assertions and mocking helpers on top of the stdlib package when plain `t.Errorf` comparisons aren't expressive enough. Benchmarks (`func BenchmarkX(b *testing.B)`) are a first-class, built-in part of the same tooling — reach for them before hand-rolling a timing script when performance actually needs to be measured (see premature optimization in [../anti-patterns.md](../anti-patterns.md)).

## Frameworks

### net/http (standard library)

**What it is.** A fully capable HTTP server and client on its own, including pattern-based routing (`http.ServeMux`, method- and path-parameter-aware since Go 1.22) and middleware composable as plain functions wrapping `http.Handler`.

**Example.**
```go
mux := http.NewServeMux()
mux.HandleFunc("POST /articles", createArticle)

func withLogging(next http.Handler) http.Handler { // middleware is just a wrapping function
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        log.Printf("-> %s %s", r.Method, r.URL.Path)
        next.ServeHTTP(w, r)
    })
}
http.ListenAndServe(":8080", withLogging(mux))
```

**When to use / avoid.** Many production services use it directly with no framework at all. Check whether the stdlib already covers the actual need (routing, middleware, JSON encoding via `encoding/json`) before adding a dependency — the ladder in this skill's parent [SKILL.md](../../SKILL.md) applies directly here.

### chi / gorilla/mux

**What they are.** Lightweight routers that stay close to `net/http`'s own interfaces (`http.Handler`) while adding path parameters and cleaner middleware chaining than hand-rolled `ServeMux` patterns.

**Example (chi).**
```go
r := chi.NewRouter()
r.Get("/articles/{id}", func(w http.ResponseWriter, req *http.Request) {
    id := chi.URLParam(req, "id")
    // ...
})
```

**When to use / avoid.** Reach for one when routing needs (nested route groups, richer path parameters) outgrow what `ServeMux` alone comfortably expresses, without wanting a full framework's opinions about JSON binding, validation, or response helpers.

### Gin / Echo

**What they are.** Fuller-featured web frameworks: built-in JSON binding/validation, a larger middleware ecosystem, and more batteries than `net/http` + a router provide.

**Example (Gin).**
```go
r := gin.Default()
r.POST("/articles", func(c *gin.Context) {
    var article Article
    if err := c.ShouldBindJSON(&article); err != nil { // binding + validation in one call
        c.JSON(400, gin.H{"error": err.Error()})
        return
    }
    c.JSON(201, article)
})
```

**When to use / avoid.** Reach for one when that convenience (binding, validation, a broader plugin ecosystem) is worth its added dependency weight and its own idioms diverging from stdlib `net/http` — teams that want to stay closest to the standard library tend to prefer `net/http` + chi instead.

### database/sql + sqlx, or GORM

**What they are.** The idiomatic default is the stdlib `database/sql` interface plus a driver for the specific database, often paired with `sqlx` for convenient struct-scanning of query results. Full ORMs (GORM) generate queries from Go structs and relationships, closer to Hibernate/Entity Framework's model.

**Example (sqlx).**
```go
var articles []Article
err := db.Select(&articles, "SELECT id, title FROM articles WHERE published = $1", true)
```

**When to use / avoid.** `database/sql` + `sqlx` is more idiomatic in Go than a full ORM — the ecosystem generally favors explicit, visible SQL over a query-generating abstraction, matching the language's broader preference for explicitness over hidden magic (see the Overview above). Reach for GORM only when that trade-off — less explicit SQL, more generated queries and relationship handling — is deliberately wanted, typically for rapid CRUD-heavy development where the generated queries are simple enough not to need close scrutiny.
