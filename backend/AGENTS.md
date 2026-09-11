# backend/AGENTS.md

Python rules for `backend/`. The cross-cutting hard rules, the doc index and the working style are in the repo-root `AGENTS.md` — read that first.

## Design invariants

Structural rules from `docs/backend.md` and ADR-007. Verify these in any backend change; violations are bugs, not style.

- Nothing below `api/` imports FastAPI. The pipeline also runs from the watcher, APScheduler, the CLI and `POST /jobs/run`.
- Agents implement the role protocols in `agents/base.py` and hold **no DB session**; they return typed results. All persistence (statuses, `processed`, `truncated` write-backs, orphan cleanup) is done by `pipeline.py`.
- `pipeline.py` resolves agents through `agents/registry.py` (`(role, variant)` + `AGENT_*` env vars), never by direct import.
- `container.py` is the composition root for every entry point; `api/deps.py` only pulls from it.
- `llm_calls.agent` records `role/variant` — the trace must name the implementation.
- A new ingestion source is a new file in `ingest/adapters/` plus a documented dedupe-key contract, never a branch in the pipeline.

`tests/test_design_invariants.py` enforces the import and status rules; keep it passing rather than arguing with it.

## Conventions

- Python ≥ 3.10 (required by `py-fsrs` 6.x). FastAPI, SQLAlchemy, Alembic, `py-fsrs`, LiteLLM, APScheduler 3.x (not 4.x pre-release), `watchdog`. "py-fsrs" is the GitHub repo name; it publishes to PyPI as **`fsrs`**, so `pyproject.toml` declares `fsrs>=6,<7` and the import is `from fsrs import ...`. Declaring `py-fsrs` fails resolution. Keep saying "py-fsrs" in prose to match the docs.
- Each agent is a module with one LLM call, a prompt file, and typed structured input/output. Keep prompts in files, not inline strings.
- Ingestion adapters implement `BaseAdapter.parse(file) -> list[NormalizedHighlight]`. Add new sources (Kindle) as new adapters, never by special-casing the pipeline.
- `truncated` is a property of a `highlights` row. Units and cards do not store it; derive "any source highlight truncated" when needed.
- Auth is a single `X-API-Key` header from env. Secrets live in `.env` (gitignored). Never hard-code keys or commit `.env*`.
- Errors return problem+json: `{ "status": 422, "detail": "..." }`.
- Costs are stored as `cost_microusd int` (1 USD = 1,000,000) everywhere (`docs/data-model.md`). Do not introduce cents or float dollars.
- Config comes from env vars, named in `docs/config.md`. Do not invent new names; add them to that doc in the same change.
- Tooling: `uv` (interpreter pinned by `.python-version`, `uv.lock` committed), `ruff` (lint + format), `mypy` strict on `src/recally/`, `pytest`. Policy in `docs/backend.md`, "Tooling". A pre-commit hook runs ruff only; Bandit and pip-audit run in CI, not locally.
- Tests never call a real LLM provider. Agents are tested by mocking `llm.py` (or LiteLLM's mock response) with recorded outputs. Ingestion tests run against the committed fixtures in `tests/fixtures/`.

## Commands

Run from `backend/`.

```
uv sync
uv run pytest
uv run ruff check . && uv run ruff format --check .
uv run mypy src/
uv run alembic upgrade head
uv run uvicorn recally.main:app --reload

# The watcher is a separate process: the FastAPI lifespan owns APScheduler
# (optimizer, learner, notify jobs) but not the watcher.
uv run python -m recally.ingest.watcher

# The CLI talks to the container directly, no server.
uv run recally --help
```
