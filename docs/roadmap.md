# Recally — Roadmap

## Build order (v1)

Every step has two gates. **Tests** is the merge requirement: the PR pastes the command and its output. **You verify** is a check a human runs against the real system after merge, and the step is not done until it passes. Where a check reads a table directly, `sqlite3 data/recally.db` is the tool in phase 1; an admin endpoint is added only if that becomes tedious. Separately, every branch clears the no-mistakes pipeline before its PR opens (docs/workflow.md, "The no-mistakes gate"); that is a per-branch check, not a per-step one.

### 0. Prerequisites
Done by hand, once.
- Python ≥ 3.10 (`py-fsrs` 6.x requires it; the Mac ships 3.9). Install via `uv` or Homebrew.
- Pin APScheduler 3.x.
- An LLM provider key in `backend/.env` (LiteLLM variable names, see `config.md`). Needed from step 2.
- A Firebase project with FCM enabled, its `google-services.json` for the app and a service-account JSON for `FIREBASE_CREDENTIALS_FILE`. Both gitignored. Needed from step 5; can be deferred until then.
- The real export `oreilly-annotations.csv` (695 rows, 324 of them for *30 Agents Every AI Engineer Must Build*) copied from `~/Downloads` into `data/`. It is the source for the fixtures and for every manual gate below. A second export — a re-export of the same book after new highlights are added — is what the add/remove half of the manual gates needs, and does not exist yet.

### 1. Backend skeleton + ingestion
- FastAPI app in the `backend/src/recally/` layout from [backend.md](backend.md), `X-API-Key` auth, SQLAlchemy models, Alembic (batch mode), SQLite.
- Tooling per backend.md: `.python-version` + committed `uv.lock`; `[tool.ruff]` and `[tool.mypy]` (strict) in `pyproject.toml`; `.pre-commit-config.yaml` (ruff only); `.github/workflows/ci.yml` running ruff, mypy, pytest, bandit and pip-audit on every PR.
- O'Reilly CSV adapter + dedupe + watcher (rename event, debounced).
- Commit fixtures under `backend/tests/fixtures/`: two trimmed exports of the same book (~15 rows each) that between them cover UUID unchanged / added / removed, and the clipped Chapter 9 row (`efaf55cf-…`, 149 characters, an exact prefix of the fuller `bf9830d8-…`) that hard rule 7 forbids reconstructing. Real exports stay in gitignored `data/`. The Chapter 9 "Stage 1/2/3" heading run this step originally called for is not in the export we have — the only short Chapter 9 rows are `0c2b9d1d-…` and `1a6b6e83-…`, so grouping needs a different example or a further export before the step 2 gate below can use it.
- **Tests**: pytest ingests fixture A then fixture B and asserts the exact new/removed/updated counts; a third ingest of either adds nothing. A request without `X-API-Key` gets 401.
- **You verify**: with the server running, copy the export into the watched folder: `GET /ingest/status` shows 695 new, 0 updated, 0 removed. Copy the same file again: 0 / 0 / 0 — that idempotence is what this gate checks. `GET /decks` lists the nine books in the export. A `curl` without the key returns 401. The add/remove/update half of this gate is deferred until a genuine second export exists; the committed fixtures already cover those branches in pytest.

### 2. Agent pipeline
- Role protocols + `(role, variant)` registry + `default` implementations for Curator, Writer, Critic (ADR-007, [backend.md](backend.md)); the pipeline resolves agents through the registry only.
- Curator → Writer ⇄ Critic with LiteLLM, `llm_calls` trace (cost, prompt, response, card linkage), human approval queue.
- Agent tests mock `llm.py` with recorded responses; no test touches a provider.
- **Tests**: a recorded Curator response naming three highlights yields one `curated_units` row with three `curated_unit_highlights` rows and `truncated=true` on the flagged highlight. Three `revise` rounds end in `needs_human`; a Critic `reject` ends in `needs_human`; nothing is ever `rejected` or `approved` by the pipeline with `AUTO_APPROVE_ROUND1_ACCEPT` off. Every call writes an `llm_calls` row with `agent`, `unit_id`, `round` and non-null `request`/`response`.
- **You verify**: run the pipeline on Chapter 9 against a real provider. In `GET /cards/pending?chapter=Chapter 9` there is one card whose `source_highlights` are the three Stage headings, and a card with `truncated: true` for the clipped `efaf55cf-…` row whose text stops where the source stops. `GET /ingest/status` shows `cards_generated` and a non-zero `cost_microusd`. Pick any card and read the prompt that produced it in `llm_calls.request`. Judgement of card quality is yours; this gate only asks that the two known cases came out right.

### 3. FSRS + reviews API + CLI
- py-fsrs `Scheduler` wrapper, `/reviews/due`, `/reviews/{id}/rate`, `/reviews/rate-batch`, `/cards/{id}/approve|reject`, `/stats`.
- Approved-card controls (ADR-008): `PATCH /cards/{id}`, `/cards/{id}/bury|suspend|unsuspend`, and the `suspended_until` filter on `/reviews/due`.
- A minimal `recally` CLI (`pending`, `approve <id>`, `reject <id>`, `due`, `rate <id> <1-4>`, `edit <id>`, `bury <id>`, `suspend <id>`, `unsuspend <id>`) so the checkpoint can run without the app.
- **Tests**: rating Again puts the card back due within the first learning step with state `learning`. A `rate-batch` with `rated_at` out of order leaves the card in the same state as rating in order. Re-sending the same batch changes nothing. `PATCH` on an approved card changes `front` and sets `edited_at` while leaving every `card_state` column identical, and returns 409 on a `pending_review` card. A buried card is absent from `/reviews/due` and present in `/decks/{id}/cards`; unsuspend restores it with the same `due` it had before.
- **You verify**: approve a few Chapter 9 cards in the CLI, run `due`, rate one Again, run `due` again after a minute and it is back. Post a two-rating batch with the timestamps reversed and compare `next_due` with the same two ratings posted in order on another card: identical. Post the batch a second time: unchanged. Edit an approved card's front, then run `due`: the new text shows and its due date has not moved. Bury a card and confirm `due` no longer lists it; suspend another, find it in `/decks/{id}/cards`, unsuspend it, and confirm it returns.

### ⚠️ Validation checkpoint
Use the backend through the CLI for ~14 days. Before starting, write down the numbers that count as "habit sustained" (candidates from the PRD metrics: review days out of 14, approval queue drained within a day of each ingest, share of pending cards approved). Read them off `GET /stats` and `GET /cards/pending` at the end. If the numbers are not met, fix the pipeline before investing in the app.

### 4. Android MVP
- Settings (base URL + API key + connection test), Today, Review, Approval Queue screens; Retrofit client; Room cache; LAN cleartext network security config.
- Card controls in the UI: bury and edit from the review session, edit/suspend/unsuspend from Decks (ADR-008).
- **Tests**: unit tests for the sync queue (ratings stored with the client `rated_at`, flushed via `rate-batch`, a retried flush sends the same payload) and for same-session re-queueing from `learning_steps_minutes`.
- **You verify**: enter the Mac's LAN URL and key in Settings; the connection test passes. Put the phone in aeroplane mode, review five cards, reconnect. `review_logs` has five rows with the phone's `device_id` and the offline `rated_at` values, and the app's next due matches `GET /reviews/due`.

### 5. Notifications
- FCM HTTP v1 push via `firebase-admin`; `push_runs` table; one-per-day and unreviewed-batch policy; deep links.
- **Tests**: notifier sends once inside `PUSH_WINDOW` and not again the same day; skips while any card in the latest `push_runs` row has no `review_logs` entry after `sent_at`; sends nothing outside the window.
- **You verify**: leave due cards unreviewed overnight. Exactly one notification arrives inside the window and tapping it opens the review session. Leave those cards unreviewed another day: no push. `push_runs` has one row whose `card_ids` match the cards shown.

### 6a. FSRS optimizer + stats
- Nightly optimizer (stage A) via `POST /jobs/run {"job":"optimizer"}`; Stats screen.
- **Tests**: below `OPTIMIZER_MIN_REVIEWS` no `fsrs_params` row is written; above it one row with 21 parameters and the `review_count` used.
- **You verify**: trigger the job before and after the threshold and check `fsrs_params`. Stats screen shows streak, retention and forecast that agree with `GET /stats`.

### 6b. LLM Learner
- Stage B writing versioned `writer_guidance`; leech rewrites; lapse rate by guidance version on the Stats screen. Expected months after 6a, once there is history.
- **Tests**: below `LEARNER_MIN_REVIEWS` no `writer_guidance` row is written and no LLM call is made; above it, with a mocked `llm.py`, the job writes `writer_guidance` v2 and never edits v1; the next Writer call's `request` contains the v2 text; a leech rewrite approved by the human sets the old card `rejected` with `status_reason="superseded by <id>"`.
- **You verify**: run `POST /jobs/run {"job":"learner"}`, then ingest a new chapter. `writer_guidance` has a v2 row, new cards in the queue show `guidance_version: 2`, and the Stats screen shows lapse rate split by version.

## Productionization phases

| Phase | Trigger | Changes |
|---|---|---|
| 1. Local | now | Mac, SQLite, watcher, LAN |
| 2. Hosted | want access away from home | Dockerize; upload endpoint replaces watcher. HF Spaces only with paid always-on hardware and persistent storage (free tier sleeps after 48 h and wipes disk, which kills the scheduler and the DB). Otherwise a small VPS, or external cron → `POST /jobs/run` plus hosted Postgres |
| 3. AWS | multi-user or reliability needs | ECS/Lambda + RDS Postgres, S3 drop zone, real auth |

## Later ideas

- Kindle adapter (My Clippings.txt from the device, or notebook HTML export from read.amazon.com / the mobile app; there is no CSV export).
- Cross-book decks via topic tags.
- Orchestration framework if plain Python outgrows itself (revisit triggers in ADR-001).
- Web/PWA client.
