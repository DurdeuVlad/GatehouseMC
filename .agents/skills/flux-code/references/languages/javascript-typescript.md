# JavaScript / TypeScript

Grouped together: almost all modern frontend/backend JS work is written as, or transpiled through, TypeScript, and the runtime idioms are shared. TypeScript's types are erased at compile time — they exist purely to catch mistakes before the code runs, not to change runtime behavior.

## Overview

Single-threaded, event-loop concurrency model (see Concurrency below) with prototype-based object inheritance under the hood, even though `class` syntax makes it look classical. TypeScript adds a structural (not nominal) type system: two types are compatible if their shapes match, regardless of declared name — this affects how interfaces and generics should be designed, since "duck typing, but checked" is the actual model.

## Type system and language idioms

- Prefer TypeScript for anything beyond a small script. Avoid `any` — it opts back out of every guarantee TypeScript provides and tends to spread silently through a codebase from one bad annotation. Prefer `unknown` plus an explicit narrowing check (`typeof`, `instanceof`, a type-guard function) when a value's type genuinely isn't known yet.
- `const` by default; `let` only when reassignment is real and intentional; never `var` (function-scoped rather than block-scoped, and hoisted — a common source of confusing bugs).
- Prefer array/object methods (`map`, `filter`, `reduce`, spread/rest) over manual mutation loops for transforming data, but stop chaining once the pipeline needs a comment to explain what it produces — a named intermediate variable is often clearer than one long chain.
- Strict equality (`===`/`!==`) always; `==` coercion rules (`"" == 0` is `true`) are a well-known source of bugs.
- Optional chaining (`?.`) and nullish coalescing (`??`) over manual `&&` guards or `||` defaults — `||` incorrectly treats `0` and `""` as "absent," `??` only treats `null`/`undefined` that way.
- Destructuring for extracting fields from objects/arrays at the point of use, rather than repeated `obj.field` access, when it improves readability.
- Prefer discriminated unions (a shared literal "tag" field) over a loose object with many optional fields for representing "one of several shapes" — TypeScript can then narrow and exhaustively check every branch.

## Asynchrony and concurrency

JavaScript runs on a single thread with an event loop; "concurrency" here means interleaved I/O, not parallel CPU execution. `async`/`await` over raw `.then()` chains for readability and stack-trace clarity. Always handle rejection — an unhandled promise rejection is a silent failure that can crash a Node process or vanish unnoticed in a browser. Avoid deeply nested callbacks ("callback hell"); `async`/`await` or named intermediate functions replace them. `Promise.all`/`Promise.allSettled` for running independent async operations concurrently instead of awaiting them one at a time in a loop, when their order doesn't matter.

## Error handling

`try`/`catch` around `await`ed calls that can reject; a caught error that's silently swallowed (empty `catch` block) is the same anti-pattern as anywhere else — see [../anti-patterns.md](../anti-patterns.md). In TypeScript, a caught error's type is `unknown`, not `Error` — narrow it (`instanceof Error`) before accessing `.message`. Custom error classes (`class NotFoundError extends Error`) for domain-specific failures the caller needs to distinguish.

## Tooling

- **Formatting/linting**: Prettier for formatting (non-negotiable, stops style debate), ESLint (with `@typescript-eslint` for TS-aware rules) for correctness/style linting.
- **Package management**: npm is the baseline; pnpm (disk-efficient, strict dependency resolution) or Yarn are common alternatives on larger projects. Commit the lockfile always.
- **Build/bundling**: Vite is the standard modern dev server/bundler for new projects; esbuild/Rollup/Webpack sit underneath various tools. Don't hand-roll a Webpack config unless an existing one has a real, specific reason to stay.
- **Type checking**: `tsc --noEmit` in CI even when a bundler (esbuild, SWC) handles the actual build without type-checking — those transpile-only tools don't catch type errors themselves.

## Testing

**Vitest** (Vite-native, fast, Jest-compatible API) or **Jest** (still the most widely deployed) for unit/integration tests. **React Testing Library** for component tests — it deliberately queries the DOM the way a user would (by role/text) rather than by implementation detail, so tests survive refactors. **Playwright** or **Cypress** for end-to-end browser tests.

## Frameworks

### React

**What it is.** Component-based UI built around a virtual DOM diff — components re-render declaratively from state, and React reconciles the minimal real-DOM changes needed.

**Example.**
```tsx
function ArticleList({ userId }: { userId: string }) {
  const [articles, setArticles] = useState<Article[]>([]);
  useEffect(() => {
    fetchArticles(userId).then(setArticles); // syncing with an external system: correct useEffect use
  }, [userId]);
  return <ul>{articles.map(a => <li key={a.id}>{a.title}</li>)}</ul>;
}
```

**Conventions and pitfalls.** Hooks (`useState`, `useEffect`, `useMemo`, `useCallback`) over class components for all new code. Keep components small and colocate state with where it's actually used; lift state up only as far as the components that genuinely need to share it — lifting further "just in case" recreates prop-drilling for no benefit (see speculative generality in [../anti-patterns.md](../anti-patterns.md)). `useEffect` is for synchronizing with an external system (subscriptions, DOM APIs, network, as above) — not a general-purpose "run this after render" hook; a common source of bugs is using it to derive state that could just be computed directly during render (`const total = items.reduce(...)`, no effect needed).

**When to use / avoid.** The default choice for interactive UI in the JS ecosystem given its size and hiring pool; a small, mostly-static page may not need a component framework at all.

### Next.js

**What it is.** A React framework adding file-based routing, server-side rendering (SSR) and static generation (SSG), and API routes, so a React app can ship server-rendered pages without hand-wiring a separate server.

**Example.**
```tsx
// app/articles/[id]/page.tsx — file path *is* the route.
export default async function ArticlePage({ params }: { params: { id: string } }) {
  const article = await getArticle(params.id); // runs on the server, streamed to the client
  return <article>{article.title}</article>;
}
```

**When to use / avoid.** Reach for it when SSR/SSG, file-based routing, or its React Server Components model genuinely matters for the app — SEO, initial load performance, or wanting backend routes integrated with the frontend. A client-only SPA with no such need doesn't benefit from its added build complexity and is simpler as a plain React + Vite app.

### Vue

**What it is.** A component framework with a gentler learning curve than React and template-based (rather than JSX-based, though JSX is supported) components by default.

**Example.**
```vue
<script setup lang="ts">
const articles = ref<Article[]>([]);
onMounted(async () => { articles.value = await fetchArticles(); }); // Composition API
</script>
<template>
  <ul><li v-for="a in articles" :key="a.id">{{ a.title }}</li></ul>
</template>
```

**When to use / avoid.** The Composition API (`ref`, `reactive`, `computed`) is the modern idiom and mirrors React hooks conceptually; the older Options API is still common in existing codebases and should be matched, not mixed, within one project. Choose Vue over React largely on team familiarity and template-vs-JSX preference — the two solve the same problem with different syntax philosophies.

### Node.js backend frameworks

**What they are.** Node.js itself is the JS runtime for backend/CLI work outside the browser — not a framework on its own. **Express** is the minimal, unopinionated HTTP framework on top of it: routing and middleware, nothing more, giving full control over structure. **NestJS** adds a full, Angular-inspired, dependency-injected architecture (modules, controllers, providers) on top of Express or Fastify.

**Example (Express).**
```ts
const app = express();
app.use(express.json());
app.post("/articles", (req, res) => {
  const article = createArticle(req.body);
  res.status(201).json(article);
});
```

**When to use / avoid.** Reach for Express when routing plus middleware is genuinely all that's needed and the team wants to choose its own structure. Reach for NestJS when that imposed structure (modules, DI, decorators) is actually wanted for a larger team/service that benefits from convention; it's real overhead — more files, more ceremony — for a small API.

### Fastify

**What it is.** A performance-focused alternative to Express with schema-based request/response validation built in via JSON Schema.

**Example.**
```ts
fastify.post("/articles", {
  schema: { body: { type: "object", required: ["title"], properties: { title: { type: "string" } } } },
}, async (request, reply) => {
  return createArticle(request.body); // request already validated against the schema
});
```

**When to use / avoid.** Worth reaching for when request throughput or built-in JSON-schema validation genuinely matter more than Express's larger ecosystem, plugin availability, and broader team familiarity.
