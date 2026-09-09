# Recally — Backend App Structure

Target layout for the FastAPI backend (built from roadmap step 1). The behaviour each module implements is specified in the other docs — [architecture.md](architecture.md), [agents.md](agents.md), [data-model.md](data-model.md), [api-spec.md](api-spec.md); this doc specifies where the code lives and the rules that keep the multi-agent system modular, so an agent implementation can be swapped without touching the pipeline. The mechanism is recorded in ADR-007.

## Package layout

```
backend/
  pyproject.toml                  # uv; ruff + mypy + pytest config
  .env.example                    # mirrors config.md
  src/recally/
    main.py                       # app factory; lifespan owns watcher + APScheduler
    config.py                     # env parsing, single source of defaults
    container.py                  # source of truth for agent/service resolution
    db.py                         # engine/session factory; no SQLite-specific SQL
    api/                          # the only layer that imports FastAPI
      deps.py                     # request-scoped dependencies (pull from container)
      auth.py                     # X-API-Key dependency
      routers/                    # one module per api-spec.md section:
        health.py  reviews.py  cards.py  decks.py  stats.py  ingest.py  jobs.py  devices.py
    models/                       # SQLAlchemy models (data-model.md)
    schemas/                      # Pydantic request/response models (api-spec.md)
    ingest/
      watcher.py                  # watchdog, on_moved + debounce
      dedupe.py                   # annotation-UUID upsert / removed_at logic
      adapters/
        base.py                   # BaseAdapter.parse(file) -> list[NormalizedHighlight]
        oreilly_csv.py
    agents/
      base.py                     # Protocol per role, dataclass request/result types, AgentContext
      registry.py                 # (role, variant) -> implementation; resolves AGENT_* env vars
      curator/                    # one package per role; the "default" variant plus
        default.py                #   its prompt file live here
        prompts/curator.md
      writer/
        default.py
        prompts/writer.md
      critic/
        default.py
        prompts/critic.md
      learner/
        default.py
        prompts/learner.md
    pipeline.py                   # the runner; resolves agents via registry, owns all DB transitions
    llm.py                        # sole LiteLLM wrapper; logs every call to llm_calls
    scheduling/
      fsrs.py                     # py-fsrs wrapper; server-authoritative review_card
      optimizer.py                # Learner stage A: nightly fsrs.Optimizer fit -> fsrs_params
      notifier.py                 # one-push-per-day policy
      jobs.py                     # notify / learner / optimizer entry points for POST /jobs/run
    cli.py                        # recally CLI (roadmap step 3)
  alembic/                        # render_as_batch=True
  tests/
    fixtures/                     # committed trimmed exports (roadmap step 1)
```

## Layering

Five rules, checked by import direction:

1. **Nothing below `api/` imports FastAPI.** The pipeline also runs from the watcher, APScheduler jobs, the CLI and `POST /jobs/run` — none of those are HTTP requests, so request-framework types must not leak into the layers they call.
2. **`api/` depends on services; services never depend on `api/`.** Routers translate HTTP ↔ service calls and nothing else.
3. **Agents never import each other, the pipeline, or `db.py`.** They see only `agents/base.py` types and the `llm.py` callable they are given.
4. **`pipeline.py` is the only module that orchestrates agents**, and it resolves them through `agents/registry.py`, never by direct import.
5. **`llm.py` is the only module that imports LiteLLM** (ADR-003).

## The agent contract

Each LLM agent role (curator, writer, critic, learner) is defined in `agents/base.py` as a `typing.Protocol` with dataclass request/result types matching the inputs and outputs in [agents.md](agents.md):

```python
class Writer(Protocol):
    def __call__(self, request: WriterRequest, ctx: AgentContext) -> WriterResult: ...
```

`AgentContext` carries the run correlation id and config — and **deliberately no DB session**. This makes three hard rules structural rather than conventional:

- Hard rule 1 (approval gate): the Critic returns a `verdict`, not a card status. No agent implementation can set `approved`, because no agent can write to the database at all.
- Hard rule 9 (bounded loop): round counting and the 3-round cap live in `pipeline.py`; the Writer and Critic see one request and return one result.
- Hard rule 7 (no reconstruction) and the `truncated` flag: the Critic/Writer work from the text they are given; the runner persists `truncated_highlight_ids` back to `highlights` rows after the Curator returns.

All persistence — `curated_units`, card statuses, `processed` flips, orphan cleanup — is the runner's job, exactly as specified in agents.md, "Pipeline runner and handoffs".

Each agent parses and validates the LLM response into its result dataclass before returning, including cross-field checks against the request: the Curator's `highlight_ids` must be a subset of the batch it was given and `decision` must be `keep|drop`; the Critic's `verdict` must be `accept|revise|reject`. A response that fails validation raises at the agent boundary and is handled by the existing failure path ([architecture.md](architecture.md), "Failure handling"). The protocol boundary itself does not cover this — mypy checks that an implementation conforms, not that a model's output does — and a well-formed response with invalid content raises no exception on its own, so without this check it would bypass that path.

Prompts are files under `agents/<role>/prompts/`, loaded from disk, never inline strings (ADR-003). LLM access is the `llm.py` callable injected via the context; agents never import a provider SDK.

## Swapping an agent

Variants are registered under a `(role, variant)` key in `agents/registry.py`; the active variant per role comes from `AGENT_CURATOR` / `AGENT_WRITER` / `AGENT_CRITIC` / `AGENT_LEARNER` (default `default`, see [config.md](config.md)). To swap the Writer:

1. Add `agents/writer/strict.py` implementing the `Writer` protocol (new prompt file optional).
2. Register an instance at module import: `registry.register("writer", "strict", StrictWriter())`. Variants are stateless; the registry hands the pipeline the ready-to-call object.
3. Set `AGENT_WRITER=strict`.

No pipeline change, no config-schema change. Because every `llm_calls` row records `agent` as `role/variant` (e.g. `writer/strict`), the trace says which implementation wrote any card, so variants can be compared on `status`/`original_front` edit rates. Ingestion sources follow the same pattern: a Kindle source is a new file in `ingest/adapters/`, never a branch in the pipeline.

## Wiring and entry points

`container.py` builds the object graph (session factory, `llm.py` callable, resolved agent variants, scheduler) and is the source of truth for resolution. FastAPI's `Depends` in `api/deps.py` only pulls from the container — the watcher, APScheduler, CLI and tests use the container directly.

Every entry point converges on the same functions:

| Entry point | Calls |
|---|---|
| watcher (`on_moved`, debounced) | `ingest` adapter + dedupe → `pipeline.run` |
| `POST /ingest` (upload) | same |
| `POST /jobs/run {"job": ...}` / external cron | `scheduling/jobs.py` |
| APScheduler (in-process, phase 1) | same `jobs.py` functions |
| `recally` CLI | container services directly |

## Testing shape per layer

- **Adapters / dedupe**: pure functions over the committed fixtures in `tests/fixtures/` (roadmap step 1 gates).
- **Agents**: unit-tested against recorded `llm.py` responses; no test touches a provider (hard rule: tests never call a real LLM).
- **Pipeline**: runs the `default` variants with a mocked `llm.py` against fixture-ingested highlights; asserts statuses, rounds and `llm_calls` rows (roadmap step 2 gates).
- **API**: router tests with the container overridden (in-memory DB, stub services); a request without `X-API-Key` returns 401.
- **Scheduling**: FSRS wrapper tested with explicit `review_datetime` values; notifier tested against the `push_runs` policy.

## Tooling

- **Environment: `uv`.** A `.python-version` file at the repo root pins the interpreter (`3.12` — satisfies `py-fsrs` ≥ 3.10 without bleeding edge) so uv uses it everywhere, including the future Docker image. `uv.lock` is committed (this is an app, not a library) so dev, CI and Docker install bit-identical dependencies; upgrades are deliberate (`uv lock --upgrade`), never accidental.
- **Dependency names.** `py-fsrs` is the project's name (and its GitHub repo); it publishes to PyPI as `fsrs`, so that is what `pyproject.toml` declares.
- **The optimizer is an optional extra.** `fsrs.Optimizer` (Learner stage A, the nightly fit) needs torch + pandas via `fsrs[optimizer]` — declared as `[project.optional-dependencies]` → `optimizer`, never a main dependency: ~800MB of torch in every PR's `uv sync` for a job that runs nightly on one machine is a bad trade. Install it with `uv sync --extra optimizer`. Without it the optimizer job logs the install command and no-ops, and the fit tests (`@pytest.mark.optimizer`) skip, so CI stays green and fast.
- **Ruff** (lint + format; replaces Flake8/isort/Autoflake/Black): config in `pyproject.toml` under `[tool.ruff]`, rule sets `E, F, I, UP, B`. Run: `uv run ruff check . && uv run ruff format --check .`.
- **Mypy, strict on `src/recally/`**: config under `[tool.mypy]` in `pyproject.toml`. Strictness is load-bearing, not taste: ADR-007's protocol boundary only protects the pipeline if mypy rejects a variant that doesn't satisfy its role `Protocol`.
- **pre-commit, ruff only**: `.pre-commit-config.yaml` runs `ruff check --fix` and `ruff format` on commit. Milliseconds fast; catches "forgot to lint" before CI. Nothing else runs in hooks.
- **Security scanners, CI only**: Bandit (scans our code for hard-coded secrets and insecure patterns) and pip-audit (dependency CVEs; chosen over Safety — maintained, no account needed) run in GitHub Actions on every PR. Kept out of the local loop: Bandit's false positives and scan latency aren't worth it for a single-user LAN app.
