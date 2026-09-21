# Recally — Roadmap

## Build order (v1)

Every step has two gates. **Tests** is the merge requirement: the PR pastes the command and its output. **You verify** is a check a human runs against the real system after merge, and the step is not done until it passes. Where a check reads a table directly, `sqlite3 data/recally.db` is the tool in phase 1; an admin endpoint is added only if that becomes tedious.

### 0. Prerequisites
Done by hand, once.
- Python ≥ 3.10 (`py-fsrs` 6.x requires it; the Mac ships 3.9). Install via `uv` or Homebrew.
- Pin APScheduler 3.x.
- An LLM provider key in `backend/.env` (LiteLLM variable names, see `config.md`). Needed from step 2.
- A Firebase project with FCM enabled, its `google-services.json` for the app and a service-account JSON for `FIREBASE_CREDENTIALS_FILE`. Both gitignored. Needed from step 5; can be deferred until then.
- The real export `oreilly-annotations.csv` (695 rows, 324 of them for *30 Agents Every AI Engineer Must Build*) copied from `~/Downloads` into `data/`. It is the source for the fixtures and for every manual gate below. A second export — a re-export of the same book after new highlights are added — is what the add/remove half of the manual gates needs, and does not exist yet.

### 1. Backend skeleton + ingestion
- FastAPI app in the `backend/src/recally/` layout from [backend.md](backend.md), `X-API-Key` auth, `GET /health` + `GET /health/auth`, SQLAlchemy models, Alembic (batch mode), SQLite.
- Tooling per backend.md: `.python-version` + committed `uv.lock`; `[tool.ruff]` and `[tool.mypy]` (strict) in `pyproject.toml`; `.pre-commit-config.yaml` (ruff only); `.github/workflows/ci.yml` running ruff, mypy, pytest, bandit and pip-audit on every PR.
- O'Reilly CSV adapter + dedupe + watcher (rename event, debounced).
- Commit fixtures under `backend/tests/fixtures/`: two trimmed exports of the same book (~15 rows each) that between them cover UUID unchanged / added / removed, and the clipped Chapter 9 row (`efaf55cf-…`, 149 characters, an exact prefix of the fuller `bf9830d8-…`) that hard rule 7 forbids reconstructing. Real exports stay in gitignored `data/`. The Chapter 9 "Stage 1/2/3" heading run this step originally called for is not in the export we have — the only short Chapter 9 rows are `0c2b9d1d-…` and `1a6b6e83-…`, so grouping needs a different example or a further export before the step 2 gate below can use it.
- **Tests**: pytest ingests fixture A then fixture B and asserts the exact new/removed/updated counts; a third ingest of either adds nothing. A request without `X-API-Key` gets 401, `GET /health` succeeds without one, and `GET /health/auth` returns 401 without and 200 with.
- **You verify**: with the server running, copy the export into the watched folder: `GET /ingest/status` shows 695 new, 0 updated, 0 removed. Copy the same file again: 0 / 0 / 0 — that idempotence is what this gate checks. `GET /decks` lists the nine books in the export. A `curl` without the key returns 401; `GET /health` answers without a key while `GET /health/auth` returns 401 without it and 200 with it. The add/remove/update half of this gate is deferred until a genuine second export exists; the committed fixtures already cover those branches in pytest.

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
- **Tests**: rating Again puts the card back due within the first learning step with state `learning`. A `rate-batch` with `rated_at` out of order leaves the card in the same state as rating in order. Re-sending the same batch changes nothing and every item comes back `ok: true` with `duplicate: true` and `lapsed: false`. A batch holding one unknown `card_id` still applies the valid items, returns one result per submitted item in request order, and marks only that item `ok: false` with `status: 404`. A rating that moves a card from `review` to `relearning` returns `lapsed: true`; Again on a card in `learning` returns `lapsed: false`. An approved but never-reviewed card appears in `/reviews/due` with `state: "learning"`, `step: 0` and a non-null `due`. `PATCH` on an approved card changes `front` and sets `edited_at` while leaving every `card_state` column identical, and returns 409 on a `pending_review` card. A buried card is absent from `/reviews/due` and present in `/decks/{id}/cards`; unsuspend restores it with the same `due` it had before.
- **You verify**: approve a few Chapter 9 cards in the CLI, run `due`, rate one Again, run `due` again after a minute and it is back. Post a two-rating batch with the timestamps reversed and compare `next_due` with the same two ratings posted in order on another card: identical. Post the batch a second time: unchanged. Edit an approved card's front, then run `due`: the new text shows and its due date has not moved. Bury a card and confirm `due` no longer lists it; suspend another, find it in `/decks/{id}/cards`, unsuspend it, and confirm it returns.

### ⚠️ Validation checkpoint
Use the backend through the CLI for ~14 days. Before starting, write down the numbers that count as "habit sustained" (candidates from the PRD metrics: review days out of 14, approval queue drained within a day of each ingest, share of pending cards approved). Read them off `GET /stats` and `GET /cards/pending` at the end. If the numbers are not met, fix the pipeline before investing in the app.

### 4. Android MVP
- Settings (base URL + API key + connection test), Today, Review, Approval Queue screens; Retrofit client; Room cache; LAN cleartext network security config.
- Card controls in the UI: bury and edit from the review session, edit/suspend/unsuspend from Decks (ADR-008).
- Tooling per android.md: Gradle Kotlin DSL + `gradle/libs.versions.toml` version catalog; JDK 17; ktlint via `ktlint-gradle`; the ktlint hook in the repo-root `.pre-commit-config.yaml`; the `android` job in `.github/workflows/ci.yml` running ktlint, Android Lint, `:app:testDebugUnitTest` and `:app:assembleDebug` on every PR. The `continue-on-error` on the two lint steps comes off once the module is clean.
- Build to [design-system.md](design/design-system.md) — colour tokens (light and dark), type scale, component specs and the required states are settled there. The *Feature gaps* below are all closed, so nothing there needs deciding before building.
- **Tests**: unit tests for the sync queue (ratings stored with the client `rated_at` and `device_id`, flushed via `rate-batch`, a retried flush sends the same payload, results matched by position, items returning `ok: true` or a 4xx `status` dequeued while 5xx items are kept) and for same-session re-queueing from `learning_steps_minutes` (a card at `step` 1 waits the step-1 interval, not step 0; offline, the local step counter advances without a rate response and stops re-queueing past the last step).
- **You verify**: enter the Mac's LAN URL and key in Settings; the connection test passes. Put the phone in aeroplane mode, review five cards, reconnect. `review_logs` has five rows with the phone's `device_id` and the offline `rated_at` values, and the app's next due matches `GET /reviews/due`.

### 5. Notifications
- FCM HTTP v1 push via `firebase-admin`; `push_runs` table; one-per-day and unreviewed-batch policy; deep links.
- **Tests**: notifier sends once inside `PUSH_WINDOW` and not again the same day; skips while any card in the latest `push_runs` row has no `review_logs` entry after `sent_at`; sends nothing outside the window.
- **You verify**: leave due cards unreviewed overnight. Exactly one notification arrives inside the window and tapping it opens **Today** (not a review session directly — [android.md](android.md), *Push notifications*: the count the notification names stays correct there when some cards were already reviewed before the tap). Leave those cards unreviewed another day: no push. `push_runs` has one row whose `card_ids` match the cards shown.

### 6a. FSRS optimizer + stats
- Nightly optimizer (stage A) via `POST /jobs/run {"job":"optimizer"}`; Stats screen.
- **Tests**: below `OPTIMIZER_MIN_REVIEWS` no `fsrs_params` row is written; above it one row with 21 parameters and the `review_count` used.
- **You verify**: trigger the job before and after the threshold and check `fsrs_params`. Stats screen shows streak, retention and forecast that agree with `GET /stats`.

### 6b. LLM Learner
- Stage B writing versioned `writer_guidance`; leech rewrites; lapse rate by guidance version on the Stats screen. Expected months after 6a, once there is history.
- **Tests**: below `LEARNER_MIN_REVIEWS` no `writer_guidance` row is written and no LLM call is made; above it, with a mocked `llm.py`, the job writes `writer_guidance` v2 and never edits v1; the next Writer call's `request` contains the v2 text; a leech rewrite approved by the human sets the old card `rejected` with `status_reason="superseded by <id>"`.
- **You verify**: run `POST /jobs/run {"job":"learner"}`, then ingest a new chapter. `writer_guidance` has a v2 row, new cards in the queue show `guidance_version: 2`, and the Stats screen shows lapse rate split by version.

## Feature gaps — API fields the Android design needs

The Android design ([design-system.md](design/design-system.md)) displayed several things no documented endpoint returned. They were found by auditing the mockups against [api-spec.md](api-spec.md) on 2026-09-07 and were **kept in the design deliberately** — the screens were built as intended and the endpoints caught up.

**All resolved; nothing here blocks the step 4 gate.** Pending counts as `counts` on `GET /cards/pending` in #132, per-book progress as `progress` on `GET /decks` in #133, the next-due timestamp as `next_due_at` on `GET /stats` in #134, bulk approve as `POST /cards/approve-batch` in #168, the browse response shape plus `chapters` on `GET /decks` in #172, and the truncated count per book as `truncated` on `GET /decks` in #173.

The last one was an either/or — build the count or drop the badge — and was **built**: `truncated` counts the book's clipped source highlights across every card status, since clipping is a property of the O'Reilly export rather than of the approved population. It is informational only; nothing offers to reconstruct the text (hard rule 7). The reasoning is recorded beside the Decks entry in [design-system.md](design/design-system.md) so a future reading of the artboard does not reopen it.


## Productionization phases

| Phase | Trigger | Changes |
|---|---|---|
| 1. Local | now | Mac, SQLite, watcher, LAN |
| 2. Hosted | want access away from home | Steps 7–11 below: Postgres cutover, Dockerize, upload endpoint, deploy, Android over HTTPS |
| 3. AWS | multi-user or reliability needs | ECS/Lambda + RDS Postgres, S3 drop zone, real auth |

Phase 2 is Postgres from the start (ADR-016). Hugging Face Spaces no longer offers block storage — its disk is ephemeral and the replacement, Storage Buckets, is S3-like object storage that SQLite cannot run on — so SQLite has nowhere durable to live on a Space. Postgres is therefore forced by the hosting choice, and independently wanted: it moves the one lossy migration to the point where the database is still disposable.

Steps 7–11 carry the same two gates as steps 1–6. Where they name a test, it is a named test function (ADR-012); where a negative is asserted, the red output goes in the PR under `## TDD evidence` (ADR-013).

### 7. Postgres cutover
The riskiest step, done first and entirely locally — no hosting involved, so a failure costs nothing. Cards regenerate from the CSV; `review_logs` and the FSRS state built from them cannot. Today that is validation-checkpoint data, by phase 3 it is months of history.

- `backend/scripts/migrate_to_postgres.py`: refuse a non-empty target, `alembic upgrade head` to build the schema, copy table by table in FK-dependency order **through the SQLAlchemy models** (so type coercion is SQLAlchemy's problem, not ours), reset every sequence to `max(id) + 1`, then verify and exit non-zero on any mismatch. A `.dump | psql` does not work and is not the method (ADR-016).
- The four silent-corruption risks, all coercion: booleans stored `0`/`1` (`highlights.truncated`, `highlights.processed`), timestamps stored as text across 18 `DateTime` columns, generic `JSON` stored as TEXT (`cards.tags`, `push_runs.card_ids`, `fsrs_params.parameters`, `llm_calls.request`/`response`), and sequences that do not transfer.
- **Data safety**: the SQLite file is opened read-only and never written; take `data/recally.db.pre-migration` first anyway. **Rollback is one env var** — `RECALLY_DATABASE_URL` back to the SQLite path. Both backends stay supported indefinitely; that dual support *is* the rollback, so no SQLite-removing cleanup lands here.
- **Tests**: `test_migration_refuses_nonempty_target` raises rather than double-writing; `test_migration_never_writes_to_source` leaves the source checksum unchanged; `test_migration_copies_every_table_row_count`; `test_migration_preserves_card_state_values` keeps `due`, `stability` and `difficulty` byte-identical; `test_migration_preserves_json_payloads` round-trips all four JSON columns; `test_migration_preserves_naive_utc_timestamps` shifts no offset; `test_migration_resets_sequences` inserts without a PK collision; `test_suite_passes_on_postgres` runs the existing suite against Postgres, which is what converts hard rule 4 from a claim into a fact (`JSON` comparison semantics, `NULL` ordering, string collation).
- **You verify**: migrate a copy of the real `data/recally.db` into a local Postgres. `GET /stats`, `GET /cards/pending` and `GET /reviews/due` return identical payloads on both backends. Rate a card on Postgres and confirm the next due date matches what SQLite gave for the same rating. Point `RECALLY_DATABASE_URL` back at SQLite and confirm the app still runs unchanged.

### 8. Dockerize
- `backend/Dockerfile`: `uv sync --frozen`, non-root user, `EXPOSE 7860` (the HF convention), `alembic upgrade head` on boot before uvicorn. `.dockerignore` excludes `data/`, `.env*`, `.venv`. `LOG_LEVEL` logs to stdout, which is what the HF log viewer reads.
- `GET /health` reports the running version. With auto-deploy there is no tag naming what is live, so this is the only way to tell what is actually running.
- The `optimizer` extra (~800MB of torch) stays out. Not a decision for this step: the fit is a no-op below `OPTIMIZER_MIN_REVIEWS`, and `scheduling/optimizer.py` degrades gracefully without the extra — "never a raise, never a row". Step 6a is where it becomes real; adding `uv sync --extra optimizer` then is a one-line change.
- **Tests**: `test_dockerfile_has_no_secrets` finds no key material baked into the image; `test_container_boots_and_serves_health`; `test_container_runs_migrations_on_boot` leaves an empty database at head; `test_health_reports_version` matches `pyproject.toml`.
- **You verify**: `docker run` locally against the step 7 Postgres. `GET /health/auth` answers 200 with the key and 401 without.

### 9. Upload endpoint for the hosted container
`POST /ingest` is already specified (`api-spec.md`, "Ingestion") and already noted as absent in `api/routers/ingest.py`. The watcher stays for local use; it has no role in the container, where there is no folder to watch.

**The endpoint and its Android client may already be built** by the Improvements sub-issue that adds the Decks import tile — it is unblocked from steps 7 and 8 because the upload works against local SQLite on the LAN. If so, this step is the deploy-time verification of work that already exists, not a second implementation. Check before building.

- **Tests**: `test_ingest_upload_matches_watcher_counts` gives the same new/updated/removed counts as the watcher for the same fixture; `test_ingest_upload_requires_api_key` returns 401 without one; `test_ingest_upload_rejects_non_csv` returns 422; `test_ingest_upload_is_idempotent` reports 0/0/0 on the second upload of the same file.
- **You verify**: upload the real 695-row export to the deployed Space; `GET /ingest/status` shows the counts the step 1 gate produced.

### 10. Deploy
- Space on the Docker SDK, GitHub-connected, **auto-deploying on every push to `main`**. No tag gate: single user, and shipping is a human call on what lands on `main`. A `backend-v*` tag gate mirroring the Android `v*` path in `ci.yml` is the easy upgrade if a bad deploy ever warrants it — deferred deliberately, not overlooked.
- **HF Spaces + Neon Postgres**, decided 2026-09-13. Start on **CPU Basic** and add a keep-alive if sleep actually bites. Basic sleeps after 48 h idle, but daily use keeps it awake on its own, so the gap that matters is a lapsed habit — and that case is circular, because the notifier is what recovers a lapsed habit and it is what stops when the Space sleeps. The fix is an external cron pinging the Space every few hours while APScheduler keeps doing the real scheduling in-process; that sidesteps the UTC-vs-`RECALLY_TIMEZONE` and 60-day-auto-disable problems of running the jobs themselves from cron. CPU Upgrade (~$22/mo) never sleeps and needs none of it — the upgrade is a settings change, so start cheap.
- Rejected: **Fly.io**, whose Managed Postgres starts at $38/mo with no hobby tier (checked 2026-09-13) — more than HF Upgrade plus Neon's free tier, for one user. Fly compute with external Postgres is the cheapest always-on option at roughly $5/mo and stays the fallback if HF disappoints; the container is portable either way, so this is reversible.
- Neon's free tier scales compute to zero when idle, so the first query after a quiet period pays a wake-up. It stacks with a sleeping Space; see the step 11 timeout note.
- Env vars in Space settings: `RECALLY_API_KEY`, `RECALLY_DATABASE_URL`, the provider key, `RECALLY_TIMEZONE`, `FIREBASE_CREDENTIALS_FILE`. **Rotate `RECALLY_API_KEY`** — it stops being a LAN-only secret and starts guarding a public endpoint.
- Nightly `pg_dump` to an HF Storage Bucket via `hf sync`. Free-tier Postgres carries no backup guarantee worth relying on, and the bucket docs name rolling backups as an intended use case.
- `docs/deployment.md` records the procedure.
- **Tests**: `test_settings_rejects_sqlite_url_when_hosted` refuses to boot the hosted container on the SQLite default, which would otherwise lose every write on restart.
- **You verify**: `GET /health` over HTTPS from off the home network. Upload the CSV, watch the pipeline run, approve a card. **Restart the Space and confirm the data is still there** — this is the check that proves the ephemeral-disk problem is solved. Confirm the backup lands in the bucket.

### 11. Android over HTTPS
The phone talks to the API exactly as before with a different base URL; Postgres is invisible to it, and FCM is unaffected because push goes server → Google → phone and never touches the base URL. Depends on step 10 for a live URL to test against, except the signing work, which can run in parallel.

- **A real release variant.** [android.md](android.md), "Connecting to the backend" says the release build "cannot talk to a phase-1 LAN backend — which is correct: phase 2 hosting brings TLS, and that is when a release build gets a backend it can reach." This is that moment. But `ci.yml`'s `distribute` job ships `assembleDebug` with a debug keystore, and the debug variant permits cleartext globally, so a mistyped `http://` URL would silently downgrade against a public endpoint. Needs a release signing config, a keystore secret, `assembleRelease` and an R8/minify decision.
- Base URL default and placeholder become HTTPS (`SettingsScreen.kt` currently hints `http://192.168.1.42:8000`), and a cleartext URL gets a real warning rather than failing as "no answer from the server".
- OkHttp timeouts are tuned for a LAN Mac; a hosted backend (and a free-tier Postgres waking from idle) needs headroom. The Room cache absorbs much of this.
- **Tests**: `test_release_manifest_permits_no_cleartext` keeps the existing release audit honest; `test_release_variant_is_signed_with_release_key` fails on the debug keystore; `test_settings_warns_on_cleartext_url`; `test_base_url_accepts_https_host_without_port`.
- **You verify**: install the release APK, put the phone on mobile data off the home network, and the Settings connection test passes against the Space URL. Review five cards offline, reconnect, and `review_logs` matches. Leave cards unreviewed overnight: exactly one push arrives inside the window, so hard rule 8 still holds against the hosted scheduler.

## Later ideas

- Kindle adapter (My Clippings.txt from the device, or notebook HTML export from read.amazon.com / the mobile app; there is no CSV export).
- Cross-book decks via topic tags.
- Orchestration framework if plain Python outgrows itself (revisit triggers in ADR-001).
- Web/PWA client.
