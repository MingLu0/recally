# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Cursor, Copilot, etc.) working in this repo. `CLAUDE.md` imports this file, so keep everything here and do not duplicate it there.

## What this project is

Recally turns O'Reilly reading highlights into flashcards. A watched folder picks up `*oreilly-annotations*.csv` exports, an LLM pipeline (Curator → Writer ⇄ Critic) writes atomic Q&A and cloze cards, a human approval queue gates them, FSRS schedules reviews, and a native Android app delivers them with FCM push reminders. Single user (Ming) for v1, but every table carries `user_id`.

## Current state

**Step 1 in progress.** `backend/` has the tooling baseline (uv, ruff, mypy, pytest, CI), the SQLAlchemy models plus the initial Alembic migration for every table in `docs/data-model.md`, the FastAPI app (`container.py`, `X-API-Key` auth, problem+json errors, `GET /ingest/status` + `GET /decks`), and a debounced watchdog watcher for completed O'Reilly exports. The O'Reilly CSV adapter, the annotation-UUID dedupe and the committed fixtures are in. `android/` has the step 4a foundation: the buildable Gradle project (Compose BOM + Material 3, Hilt, Navigation Compose), Hilt wired (`RecallyApplication`, `di/DispatchersModule`), and the design system in code under `ui/theme/` (light/dark colour schemes, Inter Tight type scale, spacing/radius constants, `@CombinedPreviews`, fixed book-cover colours), with `MainActivity` + `AppScaffold` + `RecallyNavHost` wired to sealed routes. Step 4d added the data layer: `domain/model` + `domain/repository` (interfaces and the sealed `Result`), Room entities/DAOs/`RecallyDatabase` caching due cards, the minimal `RecallyApi` the repositories consume, and the Hilt network/database/repository modules (base URL is an unconfigured sentinel until 4c/4e wire settings). The screens land from step 4b onwards. Build order is in `docs/roadmap.md`. When code lands, update the *Commands* section below.

## Repo layout

```
backend/    FastAPI backend + agent pipeline (Python ≥ 3.10)
android/    Android app (Kotlin, Jetpack Compose, Material 3, min SDK 26)
data/       local SQLite db + ingest drop zone (gitignored, never commit)
docs/       product & engineering docs (source of truth for design)
docs/decisions/   ADRs, numbered 00N-slug.md
```

## Read these before designing anything

The docs are the spec. Do not invent behaviour that contradicts them; if a change is needed, change the doc in the same commit.

| Question | Read |
|---|---|
| Why does this exist, what is in/out of scope | `docs/PRD.md` |
| How the pieces fit, hosting phases | `docs/architecture.md` |
| What each agent does, LLM vs deterministic | `docs/agents.md` |
| Backend package layout, agent protocol/registry | `docs/backend.md` |
| Tables, columns, FSRS state | `docs/data-model.md` |
| REST endpoints and payloads | `docs/api-spec.md` |
| Android screens, offline sync, push | `docs/android.md` |
| Env vars and their defaults | `docs/config.md` |
| Build order and gates | `docs/roadmap.md` |
| How work is tracked, run, and reviewed | `docs/workflow.md` |
| Why we chose X over Y | `docs/decisions/` |

## Hard rules

These come from the PRD and ADRs. Do not work around them.

1. **Nothing enters FSRS scheduling without human approval.** `cards.status` must pass through `pending_review` or `needs_human` → `approved`. The only exception is the `AUTO_APPROVE_ROUND1_ACCEPT` config flag, default **off**.
2. **Ingestion, dedupe, FSRS and the notifier are deterministic. No LLM calls there.** Only Curator, Writer, Critic and Learner stage B use an LLM (`docs/agents.md`, "What is LLM vs deterministic").
3. **All LLM calls go through the single `llm.py` LiteLLM wrapper** and are logged to `llm_calls` (agent, model, tokens, cost, latency, and by default the rendered prompt and parsed response, linked to the unit/card served; see `docs/data-model.md` and ADR-006). Never import a provider SDK directly. Never use provider-specific prompt features (ADR-003).
4. **No SQLite-specific SQL.** SQLAlchemy ORM + Alembic from the first schema change, `render_as_batch=True`, Postgres-ready (ADR-004). Every table has `user_id` defaulting to 1.
5. **Server is authoritative for FSRS state.** Offline ratings replay in client-timestamp order via `Scheduler.review_card(review_datetime=...)`. Never let the client compute the next due date as truth.
6. **Dedupe key for O'Reilly is the annotation UUID** from the Annotation URL. Same UUID + same text → skip. Same UUID + different text or note → update the existing row in place and reset `processed=false` (never insert a second row; `dedupe_key` is UNIQUE). UUID missing from a later export → set `removed_at`, keep existing cards. Book dedupe key is the ISBN from the Book URL.
7. **Do not reconstruct truncated highlights.** Some rows are clipped mid-word and the lost text exists nowhere else. Curator flags `truncated=true`; Writer works from the partial text.
8. **Push policy: at most one FCM push per day**, inside the configured window, and none while any card listed in the previous `push_runs` row is still unreviewed.
9. **Writer ⇄ Critic loop is bounded at 3 rounds**, then `status=needs_human`. A Critic `reject` verdict also goes to `needs_human`; the pipeline never sets `rejected` itself.
10. **`writer_guidance` rows are versioned, never edited in place.** Cards record the `guidance_version` that generated them.
11. **Plain Python, no orchestration framework** (ADR-001). Do not add LangGraph, CrewAI, etc. without a new ADR.
12. **Watcher acts on `on_moved` (browser rename after download), not `on_created`**, with a short debounce.

## Design invariants

Structural rules from `docs/backend.md` and ADR-007. Verify these in any backend change; violations are bugs, not style.

- Nothing below `api/` imports FastAPI. The pipeline also runs from the watcher, APScheduler, the CLI and `POST /jobs/run`.
- Agents implement the role protocols in `agents/base.py` and hold **no DB session**; they return typed results. All persistence (statuses, `processed`, `truncated` write-backs, orphan cleanup) is done by `pipeline.py`.
- `pipeline.py` resolves agents through `agents/registry.py` (`(role, variant)` + `AGENT_*` env vars), never by direct import.
- `container.py` is the composition root for every entry point; `api/deps.py` only pulls from it.
- `llm_calls.agent` records `role/variant` — the trace must name the implementation.
- A new ingestion source is a new file in `ingest/adapters/` plus a documented dedupe-key contract, never a branch in the pipeline.

## Conventions

### Backend (Python)
- Python ≥ 3.10 (required by `py-fsrs` 6.x). FastAPI, SQLAlchemy, Alembic, `py-fsrs`, LiteLLM, APScheduler 3.x (not 4.x pre-release), `watchdog`.
- Each agent is a module with one LLM call, a prompt file, and typed structured input/output. Keep prompts in files, not inline strings.
- Ingestion adapters implement `BaseAdapter.parse(file) -> list[NormalizedHighlight]`. Add new sources (Kindle) as new adapters, never by special-casing the pipeline.
- `truncated` is a property of a `highlights` row. Units and cards do not store it; derive "any source highlight truncated" when needed.
- Auth is a single `X-API-Key` header from env. Secrets live in `.env` (gitignored). Never hard-code keys or commit `.env*`.
- Errors return problem+json: `{ "status": 422, "detail": "..." }`.
- Costs are stored as `cost_microusd int` (1 USD = 1,000,000) everywhere (`docs/data-model.md`). Do not introduce cents or float dollars.
- Config comes from env vars, named in `docs/config.md`. Do not invent new names; add them to that doc in the same change.
- Tooling: `uv` (interpreter pinned by `.python-version`, `uv.lock` committed), `ruff` (lint + format), `mypy` strict on `src/recally/`, `pytest`. Policy in `docs/backend.md`, "Tooling". A pre-commit hook runs ruff only; Bandit and pip-audit run in CI, not locally.
- Tests never call a real LLM provider. Agents are tested by mocking `llm.py` (or LiteLLM's mock response) with recorded outputs. Ingestion tests run against the committed fixtures in `backend/tests/fixtures/` (see `docs/roadmap.md`, step 1). Every roadmap step has a *Tests* gate (merge requirement, output pasted in the PR) and a *You verify* gate the human runs after merge.

### Android (Kotlin)
- Jetpack Compose + Material 3, Retrofit + OkHttp, Room, Hilt, FCM. Package root `dev.recally`, structure in `docs/android.md`.
- Clean architecture with the scaffold pattern (`docs/android.md`, "Architecture"). Screen composables are pure: `UiState` in, callbacks out — no `hiltViewModel()`, no `NavController`, no flow collection inside a screen. ViewModels are instantiated at their `NavHost` route entry, which also owns the navigation decision. One immutable `UiState` per screen. ViewModels talk to repository interfaces, never to a DAO or Retrofit service. Every screen composable and shared component ships a private `@CombinedPreviews` preview function with its `UiState` built inline.
- Offline-first: Room caches due cards; ratings are queued locally with timestamps and synced later. Approval queue requires connectivity.
- Capture `response_ms` (flip-to-rate duration) on every rating.
- Use injected dispatchers, not hard-coded `Dispatchers.IO`. Include exception handlers on coroutines.

### Docs
- A design or technology change needs an ADR in `docs/decisions/` (next number, `Status`, `Date`, Context / Decision / Rationale / Consequences). Update the doc that the ADR affects and the index in `docs/decisions/README.md` in the same change. Small local decisions get a one-sentence "why" inline next to the item instead; the test is whether reversing it would touch more than one doc.
- Dates in docs use ISO `YYYY-MM-DD`. Times are GMT+12 unless stated.
- Keep the README doc index in sync when adding a doc.

### Git
- Branch naming: `docs/…`, `feat/…`, `fix/…`. Main branch is `main`.
- Commit messages: `type: short summary` (e.g. `docs: …`, `feat(ingest): …`). Do not commit `data/`, `*.db`, `.env*`, or Gradle build output.

## Commands

```
# backend
cd backend && uv sync
uv run pytest
uv run ruff check . && uv run ruff format --check .
uv run mypy src/
uv run alembic upgrade head
uv run uvicorn recally.main:app --reload
# Run the watcher by itself until the FastAPI lifespan owns it.
uv run python -m recally.ingest.watcher

# android
cd android && ./gradlew ktlintCheck
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew ktlintFormat   # writes fixes; what the pre-commit hook runs

# orchestrator (hand-started parallel dispatcher, ADR-013)
scala-cli scripts/orchestrate.sc -- --dry-run
scala-cli scripts/orchestrate.sc -- --step=step-4

```

Backend commands run from `backend/`; policy and config locations are in `docs/backend.md`, "Tooling".

## Working style for agents

- Read the relevant doc before proposing a design. Cite `file:line` for claims about existing behaviour.
- Investigate first, then report; do not start editing or writing plans when asked to investigate.
- Do exactly what was asked. Do not widen scope to adjacent files or "while we're here" refactors without asking.
- When a doc and this file disagree, the doc wins. Fix this file.
- Verify before claiming done: run the tests or command and paste the output. If something was skipped, say so.
- **The ticket's list of named tests is the whole gate** (ADR-012). Write those tests first; for every one asserting a raise, a refusal or a negative, confirm it **fails** before the implementation exists and paste that red output under a `## TDD evidence` heading in the PR, next to the green run (ADR-013 — the heading is what makes the evidence mechanically checkable). Then commit on a feature branch, run the full suite, and paste the output in the PR. Name in the PR any listed test you did not write, and why — never drop one silently. Full workflow in `docs/workflow.md`, "The two gates".
- **A hard rule above is the human's call, never yours.** If a ticket seems to ask you to work around one, stop and ask rather than quietly overruling it.
- **Never start a parent step issue, and never auto-merge one.** Parents carry a `You verify` gate only a human can run. An agent running unattended may merge its own PR only when all five conditions hold: every named test passes with output pasted, CI green, the `## TDD evidence` section present with red before green, the PR is not docs-only, and the issue is a sub-issue (`docs/workflow.md`, "Unattended dispatch"; ADR-010, ADR-012, ADR-013).
