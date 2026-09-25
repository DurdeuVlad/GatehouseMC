# Python

## Overview

Dynamically typed, interpreted, reference-counted with cycle-collecting GC. Optional static type hints (`typing`) are checked by external tools (mypy, pyright), not the runtime — they document and catch bugs before execution but never change what actually runs. Readability and explicitness are the language's stated design values ("explicit is better than implicit," `import this`); prefer the idiom that reads clearly over the one that's shortest.

## Type system and language idioms

- Add type hints to public function signatures and class attributes; they're the cheapest documentation available and let mypy/pyright catch a whole class of "wrong shape" bugs statically. Hints are optional at every call site — a function can be hinted while its caller passes an untyped value — so hints alone don't guarantee correctness, only communicate intent.
- Use `dataclasses` (or `typing.NamedTuple` for immutable, tuple-like data) for structured value objects instead of a plain class with a hand-written `__init__`. `@dataclass(frozen=True)` gives immutability and a generated `__eq__`/`__repr__` for free.
- Comprehensions (list/dict/set) over manual `for` + `.append()` loops, but only while the expression stays readable on one or two lines; a comprehension needing an explanatory comment should be a loop instead.
- Generators (`yield`) for lazily-produced sequences, especially large or unbounded ones — avoid building a full list in memory just to iterate it once.
- `is None` / `is not None` for None checks, never `== None`. Rely on explicit `is not None` rather than truthiness when `0`, `""`, or `[]` are meaningfully different from "absent."
- Never use a mutable default argument (`def f(x=[])`): the default object is created once at function-definition time and shared across every call that doesn't pass its own. Use `None` as the sentinel default and create the mutable value inside the function body instead.
- Unpacking (`a, b = pair`, `*rest, last = items`) and f-strings (`f"{value:.2f}"`) are the idiomatic choices over manual indexing and `%`/`.format()` string formatting in new code.
- Prefer composition and small, focused classes; Python supports multiple inheritance but deep or diamond hierarchies are as much a trap here as anywhere else (see [../principles.md](../principles.md)).

## Standard library first

Reach for these before adding a dependency: `pathlib` (path handling, not manual string joins), `dataclasses`, `functools` (`lru_cache`, `reduce`, `partial`), `itertools` (chained/lazy iteration), `collections` (`defaultdict`, `Counter`, `deque`, `namedtuple`), `contextlib` (`@contextmanager`, `suppress`), `enum` (for closed sets of named constants — replaces magic strings, see [../anti-patterns.md](../anti-patterns.md)), `json`/`csv` for common data formats, `argparse` for CLI argument parsing, `logging` (never `print` for anything beyond a throwaway script).

## Error handling

Exceptions are the idiomatic control flow for error cases ("easier to ask forgiveness than permission" — EAFP — is preferred over checking preconditions first, LBYL, when the check-then-act sequence has a race or is simply more code). Catch the narrowest exception type that's actually expected; a bare `except:` (or `except Exception:` that silently continues) hides real bugs — see error swallowing in [../anti-patterns.md](../anti-patterns.md). Define custom exception classes for domain-specific failure modes so callers can distinguish them; always chain (`raise NewError(...) from original`) when re-raising as a different type, to preserve the original traceback.

## Concurrency

The GIL (Global Interpreter Lock, CPython) means threads don't give true parallel CPU execution for pure-Python code — use `threading` for I/O-bound concurrency (network/disk waits release the GIL) and `multiprocessing` (or a process-based executor) for CPU-bound parallelism. `asyncio` with `async`/`await` is the idiomatic model for high-concurrency I/O-bound work (many open connections); don't mix blocking calls into an async function without offloading them (`run_in_executor`) or the event loop stalls for everyone.

## Tooling

- **Formatting/linting**: `ruff` (fast, increasingly the default for both linting and formatting) or `black` + `flake8`/`pylint`. Don't hand-debate style — pick one and let it own formatting.
- **Package/dependency management**: `pip` + `pyproject.toml` is the baseline; `uv` or `poetry` for reproducible lockfiles and faster installs on larger projects.
- **Static typing**: `mypy` or `pyright` run in CI once hints are in place.
- **Virtual environments**: always isolate dependencies per project (`venv`, or whatever the chosen package manager provides) — never install project dependencies into the system interpreter.

## Testing

`pytest` is the de facto standard over the stdlib `unittest`: plain `assert` statements, fixtures for setup/teardown and dependency injection into tests, parametrization (`@pytest.mark.parametrize`) instead of copy-pasted near-duplicate test functions (see the rule of three in [../anti-patterns.md](../anti-patterns.md)).

## Frameworks

### Django

**What it is.** Batteries-included web framework: ORM, schema migrations, an auto-generated admin panel, auth, forms, and templating all built in and designed to work together as one opinionated stack.

**Example.**
```python
# models.py
class Article(models.Model):
    title = models.CharField(max_length=200)
    published = models.BooleanField(default=False)

# views.py
def article_list(request):
    articles = Article.objects.filter(published=True)  # ORM query, no raw SQL
    return render(request, "articles/list.html", {"articles": articles})
```
The admin panel, forms, and migrations for `Article` come largely for free once the model is declared — the framework's core trade: a lot of behavior in exchange for doing things its way.

**When to use / avoid.** Reach for it when the app genuinely needs most of that stack (admin, auth, forms, ORM) and benefits from a shared "the Django way" across a team. Avoid it for an app that needs a very different data layer than the ORM models well, or a thin, custom API surface — the ORM and admin become friction, not help, and a small service that's mostly a few endpoints doesn't need this much framework.

**Related.** Its ORM is a full [Repository](../design-patterns.md)-and-then-some; **Django REST Framework** layers serializers/viewsets on top when the app is an API rather than server-rendered pages.

### Flask / FastAPI

**What it is.** Minimal web frameworks that route requests to handlers and leave everything else (ORM, auth, validation) to be chosen explicitly, rather than bundled. FastAPI adds request/response validation and automatic OpenAPI docs generated straight from Python type hints, plus native `async` support; Flask is simpler and synchronous-by-default (async is possible, not the default model).

**Example (FastAPI).**
```python
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()

class Article(BaseModel):
    title: str
    published: bool = False

@app.post("/articles")
async def create_article(article: Article):  # validated + documented from the type hint alone
    return {"id": 1, **article.model_dump()}
```

**When to use / avoid.** Reach for either when the team wants control over the stack rather than Django's opinions, or when the service is small enough that a full framework is unwarranted ceremony. Prefer FastAPI specifically when request validation and generated API docs are worth the type-hint discipline they require; prefer Flask for its simplicity and larger legacy ecosystem when async and auto-validation aren't needed.

**Related.** Neither ships an ORM — pair with [SQLAlchemy](#sqlalchemy) below for anything beyond a trivial data layer.

### SQLAlchemy

**What it is.** The general-purpose ORM/SQL toolkit used outside Django (which ships its own ORM). Its Core layer is a SQL expression builder usable on its own, without the full ORM's object-mapping layer.

**Example.**
```python
# ORM layer:
class Article(Base):
    __tablename__ = "articles"
    id: Mapped[int] = mapped_column(primary_key=True)
    title: Mapped[str]

session.query(Article).filter(Article.published == True).all()

# Core layer, no ORM mapping needed — just query-building:
stmt = select(articles_table).where(articles_table.c.published == True)
```

**When to use / avoid.** Reach for the full ORM when the app benefits from mapping rows to Python objects with relationships, identity, and change-tracking. Reach for Core alone when only query-building is needed and the object-mapping layer would be unused weight.

**Related.** Fills the same role as [Repository](../design-patterns.md) implementations sit behind — wrap it behind a repository interface when business logic needs to stay testable without a real database.

### pytest ecosystem

**What it is.** Extensions to the core `pytest` framework covering common test-infrastructure needs rather than being reinvented per project.

**Example.**
```python
def test_creates_article(mocker):
    fake_repo = mocker.Mock()  # pytest-mock's mocker fixture, wraps unittest.mock
    service = ArticleService(fake_repo)
    service.create("Title")
    fake_repo.save.assert_called_once()
```

**When to use / avoid.** `pytest-cov` for coverage reporting in CI, `pytest-mock` for a `mocker` fixture wrapping `unittest.mock` with automatic cleanup between tests, `pytest-asyncio` for testing `async def` test functions against async code. Add each only once the project actually needs what it provides — coverage reporting with no CI gate consuming it, for instance, is dead tooling.

### pandas / NumPy

**What it is.** Data manipulation (pandas: labeled, tabular `DataFrame`s) and numerical computing (NumPy: fast, vectorized array operations) for genuinely data-heavy work.

**Example.**
```python
import pandas as pd
df = pd.read_csv("orders.csv")
totals = df.groupby("customer_id")["amount"].sum()  # vectorized, not a manual loop
```

**When to use / avoid.** Reach for them for real tabular or array-heavy work where vectorized operations meaningfully outperform and outread manual loops. Don't pull pandas in for a handful of dict/list operations the stdlib's `itertools`/`collections` already cover — it's a heavy dependency (and a real learning curve for its API) to reach for by habit rather than need.
