# Java

## Overview

Statically typed, compiled to JVM bytecode, garbage-collected. Explicit and verbose by design relative to more terse languages — that verbosity buys IDE-supported refactoring and compile-time safety across large, multi-team codebases, which is the trade-off to weigh before treating verbosity itself as a flaw to work around. Everything is a class or interface member; there are no free functions, which shapes several of the idioms below.

## Type system and language idioms

- Favor composition over inheritance for code reuse. Deep inheritance hierarchies are a common Java-specific trap: they look organized early on and become rigid the moment a subclass needs to violate a base-class assumption (see Liskov substitution in [../principles.md](../principles.md)).
- Program against interface types, not concrete implementations, at any point where the concrete type might reasonably change (`List<String> list = new ArrayList<>();` not `ArrayList<String> list = ...`). This is what makes dependency injection and testing with fakes possible later without a rewrite.
- Prefer immutability where the value doesn't need to change after construction: `final` fields with no setters, or records (`record Point(int x, int y) {}`, Java 16+) for simple data carriers — a record gives a constructor, accessors, `equals`/`hashCode`/`toString` for free with far less boilerplate than a hand-written immutable class.
- Use `Optional<T>` for a return value that may legitimately be absent, instead of returning `null` — it forces the caller to handle the absent case explicitly rather than risking a `NullPointerException` three call sites later. Do not use `Optional` for fields or method parameters; it's a return-type-only idiom.
- Streams (`.stream().map().filter().collect()`) for declarative collection transforms — but don't force a stream pipeline where a plain enhanced-for loop reads more clearly, particularly once the pipeline needs side effects or multiple exit points.
- Generics with bounded wildcards (`List<? extends Number>`) at API boundaries that need to accept a range of related types read-only; avoid raw types (`List` without a type parameter) entirely in new code.

## Standard library and platform features

`java.time` (`LocalDate`, `Instant`, `Duration`) for all date/time handling — never the legacy `Date`/`Calendar` classes in new code. `java.util.concurrent` (`ConcurrentHashMap`, `ExecutorService`, `CompletableFuture`) rather than hand-rolled synchronization for concurrent data structures and async composition. `try-with-resources` for anything `AutoCloseable` (streams, connections, files) instead of manual try/finally cleanup.

## Error handling

Checked exceptions (declared in a method's `throws` clause, must be caught or declared by every caller) versus unchecked (`RuntimeException` subclasses, propagate silently until caught) is a Java-specific design decision to make deliberately: checked exceptions communicate "the caller must handle this" at the type level, but overusing them forces boilerplate try/catch or `throws` propagation through layers that have no meaningful way to handle the failure. Reserve checked exceptions for genuinely recoverable conditions the immediate caller can act on; use unchecked exceptions for programmer errors and conditions there's no local recovery from. Never catch `Exception` broadly just to satisfy the compiler, and never leave a catch block empty (see error swallowing in [../anti-patterns.md](../anti-patterns.md)).

## Concurrency

Threads are OS-level and relatively heavyweight; the `java.util.concurrent` package (executors, thread pools, `CompletableFuture` for async composition) is the idiomatic layer above raw `Thread`/`synchronized`. Virtual threads (Java 21+, Project Loom) make lightweight, high-concurrency blocking-style code practical without the old thread-pool tuning trade-offs — worth reaching for over reactive/async frameworks purely for scaling I/O-bound concurrency, when the JDK version allows it.

## Tooling

- **Build/dependency management**: Maven (declarative XML, predictable convention-over-configuration) or Gradle (Groovy/Kotlin DSL, more flexible and typically faster for large multi-module builds). Pick based on whether the team wants Maven's rigidity or Gradle's flexibility — both are production-standard.
- **Formatting/linting**: Checkstyle or Spotless for enforced formatting; SpotBugs/Error Prone for static analysis catching real bugs (not just style) at build time.
- **Build the JAR/module** with the ecosystem's standard packaging (`maven-shade-plugin`/`maven-assembly-plugin`, or Gradle's application plugin) rather than a hand-rolled classpath assembly step.

## Testing

**JUnit 5** is the standard test framework (`@Test`, `@ParameterizedTest` to avoid copy-pasted near-duplicate test methods — see the rule of three in [../anti-patterns.md](../anti-patterns.md)). **Mockito** for mocking dependencies at unit-test boundaries, most often the interfaces introduced via dependency injection (see [../design-patterns.md](../design-patterns.md)). **AssertJ** for fluent, more readable assertions than JUnit's built-in `assertEquals`/`assertTrue`.

## Frameworks

### Spring / Spring Boot

**What it is.** The dominant application framework: a dependency-injection container at its core (see [Dependency injection](../design-patterns.md)), with web MVC, data access, security, messaging, and scheduling as pluggable modules on top. Spring Boot's auto-configuration and starter dependencies remove most of the manual XML/Java config classic Spring needed.

**Example.**
```java
@RestController
@RequestMapping("/articles")
class ArticleController {
    private final ArticleRepository repository; // injected, not constructed here

    ArticleController(ArticleRepository repository) { this.repository = repository; }

    @PostMapping
    Article create(@RequestBody Article article) {
        return repository.save(article);
    }
}
```
`repository` is wired in by Spring's DI container based on the constructor signature — no manual instantiation anywhere in application code.

**When to use / avoid.** The standard choice for a new web service, and worth reaching for whenever the app needs several of DI, web MVC, data access, and security together. Drop to plain Spring (or no framework at all) for a small, self-contained tool or library where pulling in the DI container and its component-scanning adds ceremony with no real payoff — not every Java program is a Spring application.

**Related.** Its `@Autowired`/constructor injection is [Dependency injection](../design-patterns.md) as a first-class framework feature; Spring Data's repository interfaces are a direct framework-level implementation of [Repository](../design-patterns.md).

### Hibernate / JPA

**What it is.** The standard ORM layer, most often used through Spring Data JPA's repository abstraction rather than raw Hibernate APIs.

**Example.**
```java
interface ArticleRepository extends JpaRepository<Article, Long> {
    List<Article> findByPublishedTrue(); // method name alone generates the query
}
```

**When to use / avoid.** Reach for it whenever an ORM's object-relational mapping is wanted over hand-written SQL/JDBC. Watch specifically for the N+1 query problem: a lazy-loaded association (e.g. `article.getComments()`) fetched inside a loop issues one query per iteration instead of one join — a frequent, easy-to-introduce performance bug that only shows up under real data volume, not in a small local test. Fetch eagerly (`JOIN FETCH` or an entity graph) when the association is known to be needed.

### Quarkus / Micronaut

**What they are.** Alternatives to Spring Boot built for fast startup and low memory footprint — dependency injection resolved at compile time rather than Spring's runtime reflection-heavy container — aimed at containerized/serverless deployment where cold-start time matters.

**When to use / avoid.** Reach for one of these specifically when Spring Boot's JVM startup cost is a *measured* problem (serverless cold starts, high pod-churn container deployments), not by default — Spring's larger ecosystem and community familiarity are a real cost to give up without a concrete reason.

### Build ecosystem plugins

**What they are.** Maven/Gradle plugins rounding out a typical service's toolchain: containerization (Jib, which builds container images without a Docker daemon), code generation (Lombok), and API documentation (Springdoc/OpenAPI generating docs from annotated controllers).

**When to use / avoid.** Use Lombok sparingly — it hides generated code (`equals`, `hashCode`, getters/setters) from readers relying on IDE navigation, and Java records now cover much of what it was originally used for; prefer a record over a Lombok-annotated class for simple immutable data carriers.
