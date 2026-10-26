# C# / .NET

## Overview

Statically typed, compiled to CLR intermediate language, garbage-collected. A close cousin to Java in overall shape (class-based OOP on a managed runtime), but with more language-level sugar accumulated over successive versions — LINQ, `async`/`await` (which originated in C# before other languages adopted the pattern), records, pattern matching, nullable reference types. Modern idiomatic C# leans on this sugar; code written in an older, more Java-like style still compiles but reads as dated.

## Type system and language idioms

- Use properties (`{ get; set; }`) instead of public fields for any externally-visible state — properties allow validation/computation to be added later without an ABI/API break, and are the conventional expectation in C# codebases.
- Prefer immutable data: `init`-only setters (`{ get; init; }`) for objects that should be set once at construction, or `record`/`record struct` types for value-like data — a record gives value-based `Equals`/`GetHashCode`, a concise constructor, and non-destructive mutation (`with` expressions) for free.
- Enable nullable reference types (`<Nullable>enable</Nullable>`) project-wide and use `string?` vs `string` to make "can this be null" explicit at the type level rather than by convention or a doc comment — the compiler then flags likely null-dereference sites.
- Pattern matching (`switch` expressions, `is` patterns, property patterns) over long `if`/`else if` chains for branching on a value's shape or type — reads closer to the data it's branching on.
- LINQ (`.Where().Select().OrderBy()`, or query syntax) for declarative collection queries is idiomatic C# — unlike Java, where forcing a stream pipeline can hurt readability, LINQ is the expected default here. Still stop chaining once the pipeline needs a comment to explain what it produces.

## Error handling

Exceptions are the standard control flow for error conditions, similar to Java's unchecked exceptions — C# has no checked-exception concept at all, so nothing forces a caller to acknowledge a specific exception type; document what a public method can throw. Catch the narrowest exception type actually expected; an empty or purely logging `catch` block that otherwise continues is the same anti-pattern as anywhere else (see [../anti-patterns.md](../anti-patterns.md)). Custom exception types for domain-specific failures the caller needs to distinguish from generic runtime errors.

## Concurrency

`async`/`await` is the idiomatic model for I/O-bound work; suffix async methods with `Async` by convention (`GetUserAsync`) so callers can tell at a glance. Avoid blocking on a `Task` from synchronous code (`.Result`, `.Wait()`) — in contexts with a synchronization context (classic ASP.NET, WPF/WinForms UI threads) this risks deadlock; use `async` all the way up the call chain instead ("async all the way"). `Task.WhenAll`/`Task.WhenAny` for running independent async operations concurrently rather than awaiting them sequentially in a loop. For CPU-bound parallelism, `System.Threading.Tasks.Parallel` or PLINQ (`.AsParallel()`), which are a different concern from `async`/`await`'s I/O-bound concurrency model — don't reach for `async` to get CPU parallelism, it doesn't provide that on its own.

## Dependency injection and architecture

DI is built into ASP.NET Core's hosting model (`IServiceCollection`, constructor injection) rather than needing a separate container library, and is the expected default wiring pattern for any non-trivial C# service — inject against interfaces rather than `new`-ing dependencies inside a class (see dependency injection in [../design-patterns.md](../design-patterns.md)).

## Tooling

- **Formatting/linting**: `dotnet format` (built into the SDK) plus an `.editorconfig` for enforced style; Roslyn analyzers (many ship with the SDK, more available as NuGet packages) for real static-analysis warnings, not just formatting.
- **Package/dependency management**: NuGet, referenced via the `.csproj` file (modern SDK-style projects) — no separate lockfile-based tool needed by default, though `packages.lock.json` can be enabled for reproducible restores.
- **Build**: the `dotnet` CLI (`dotnet build`, `dotnet publish`) across all supported platforms; `dotnet publish` with self-contained/AOT options for single-file, dependency-free deployables when that's a real requirement.

## Testing

**xUnit** is the most common modern test framework for new projects; NUnit and MSTest are also in wide use, especially in older codebases — match whatever the codebase already uses rather than introducing a second framework. **Moq** or **NSubstitute** for mocking dependencies at the interfaces introduced via DI. **FluentAssertions** for more readable assertion chains than the built-in `Assert.*` methods.

## Frameworks

### ASP.NET Core

**What it is.** The standard web framework, covering both minimal APIs (a lightweight, closure-based routing style introduced in .NET 6+) and the older MVC/Razor Pages controller-based structure, both built on the same DI-driven hosting model.

**Example (minimal API).**
```csharp
var builder = WebApplication.CreateBuilder(args);
builder.Services.AddScoped<IArticleRepository, ArticleRepository>(); // DI registration
var app = builder.Build();

app.MapPost("/articles", (Article article, IArticleRepository repo) => {
    repo.Save(article); // repo resolved by the DI container per-request
    return Results.Created($"/articles/{article.Id}", article);
});
```

**When to use / avoid.** Reach for minimal APIs by default for a small-to-medium service — less boilerplate, the routing and handler live together. Reach for MVC's controller/action structure when the app's size and team genuinely benefit from that more rigid, file-per-controller organization (larger teams, more endpoints per resource); don't default to the heavier structure out of habit.

**Related.** Its built-in `IServiceCollection` container is [Dependency injection](../design-patterns.md) as a core framework feature, not an add-on.

### Entity Framework Core

**What it is.** The standard ORM, with LINQ as its query syntax, mapping C# classes to database tables and relationships.

**Example.**
```csharp
var articles = await db.Articles
    .Where(a => a.Published)
    .Include(a => a.Comments) // eager load — avoids the N+1 trap below
    .ToListAsync();
```

**When to use / avoid.** Reach for it as the default ORM in a .NET data layer. Watch for the same N+1 lazy-loading trap as Java's Hibernate: a navigation property (`article.Comments`) accessed inside a loop without `.Include()` issues one query per iteration instead of one join — a frequent, easy-to-introduce performance bug that only shows up under real data volume, not in local testing with a handful of rows.

### Blazor

**What it is.** C#-based web UI, either server-rendered (component state lives on the server, a thin SignalR connection streams UI updates to the browser) or client-side via WebAssembly (the whole app runs in-browser, compiled to WASM).

**Example.**
```razor
@page "/articles"
<ul>@foreach (var a in Articles) { <li>@a.Title</li> }</ul>
@code {
    List<Article> Articles = new();
    protected override async Task OnInitializedAsync() => Articles = await Repo.GetAllAsync();
}
```

**When to use / avoid.** Worth reaching for when the team wants to avoid maintaining a separate JS/TS frontend stack and stay entirely in C# end to end. Not a default choice over React/Vue for a team that's already JS-fluent or needs the larger JS component/library ecosystem — that ecosystem gap is the real cost being traded away.

### gRPC / SignalR

**What they are.** `Grpc.AspNetCore` provides typed, high-performance service-to-service RPC (schema-first via Protobuf). **SignalR** provides real-time bidirectional communication (WebSockets with automatic fallback to other transports).

**Example (gRPC service method, from a `.proto`-generated contract).**
```csharp
public override Task<ArticleReply> GetArticle(ArticleRequest request, ServerCallContext context) {
    var article = repo.FindById(request.Id);
    return Task.FromResult(new ArticleReply { Title = article.Title });
}
```

**When to use / avoid.** Reach for gRPC when a plain REST/JSON API isn't the right shape for the traffic pattern — internal service-to-service calls where the schema contract and performance matter more than human-readable payloads. Reach for SignalR specifically when the app needs server-push (live updates, chat, notifications), not for ordinary request/response APIs where a normal HTTP call is simpler.
