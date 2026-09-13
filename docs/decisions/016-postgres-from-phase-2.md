# ADR-016: Postgres from phase 2

**Status**: accepted
**Date**: 2026-09-13

## Context

Phase 2 ("want access away from home") was planned as Docker on HF Spaces with SQLite on a persistent volume, or a small VPS — the option recorded in `docs/architecture.md`, "Infrastructure migration path", and in the roadmap's productionization table. ADR-004 deferred Postgres to "phase 2/3" without choosing which.

Two findings, checked against the Hugging Face documentation on 2026-09-13, remove the SQLite option on HF:

- **Spaces no longer offer block storage.** `docs/hub/spaces-storage` now reads: "Every Space comes with a small amount of disk storage. This disk space is ephemeral, meaning its content will be lost if your Space restarts or is stopped." The replacement is Storage Buckets — S3-like *object* storage, "non-versioned and mutable". `huggingface.co/storage` prices buckets per-TB ($12 public, $18 private) and lists no block storage, no persistent volume and no database product. SQLite needs POSIX file locking and partial-page writes; object storage provides neither, so **SQLite has nowhere durable to live on a Space.**
- **Free Docker Spaces now require a paid plan**: "CPU Basic has no hourly cost, but creating a new Space that runs on compute (Gradio or Docker) requires a paid plan." Only static Spaces are free for everyone. CPU Basic remains $0/hr on a plan and sleeps after 48h idle; CPU Upgrade is $0.03/hr and never sleeps.

Independently, the SQLite → Postgres migration is the one step in the project that can silently lose data, and its cost rises the longer it is deferred. Cards regenerate from the O'Reilly CSV for a few dollars of LLM spend; `review_logs` and the FSRS state built from them cannot be regenerated at all.

Whether the code is actually ready was audited rather than assumed:

- No dialect-specific SQL anywhere. Every `session.execute` in `services/`, `pipeline.py` and `scheduling/learner.py` executes ORM constructs; the only raw SQL in the codebase is `PRAGMA journal_mode=WAL` at `db.py:53`, which is connection setup and is skipped for every non-SQLite backend.
- Column types are the portable subset: 35 `Integer`, 18 `Text`, 18 `DateTime`, 7 generic `JSON`, 3 `Float`, 2 `Boolean`, 1 `Date`. No `Enum`, no dialect-specific types.
- Timestamps are naive UTC by policy (`models/base.py`), converted at the edges — the single most common SQLite → Postgres corruption vector, already avoided.
- `user_id` is on every table with `server_default=text("1")`, so phase 3's multi-user retrofit adds a `users` table and FKs rather than backfilling rows.
- The constraint naming convention predates the first migration, which is what makes `render_as_batch=True` reproducible.

## Decision

**Postgres becomes the hosted backend's database in phase 2**, ahead of any hosting work, as roadmap step 7.

The cutover is an **ORM-level copy, not a SQL dump**: `alembic upgrade head` builds the schema on the empty target, then rows are copied table by table in FK-dependency order through the same SQLAlchemy models, then sequences are reset to `max(id) + 1`. A `.dump | psql` is explicitly rejected.

**Both backends stay supported indefinitely.** No SQLite-removing cleanup ships with this ADR. `db.py` already dispatches on URL backend, so `RECALLY_DATABASE_URL` alone selects one.

Where the container is hosted is a separate, reversible decision recorded at roadmap step 10 rather than here: **HF Spaces on CPU Basic plus Neon Postgres**, chosen 2026-09-13 because the HF deployment flow is already familiar and Neon's free tier costs nothing. Fly.io was rejected on price — its Managed Postgres starts at $38/mo with no hobby tier — but Fly compute with external Postgres remains the fallback at roughly $5/mo. This ADR holds whichever host wins: none of them can store a database on the container.

## Rationale

- On HF there is no alternative: object storage cannot host SQLite, and the ephemeral disk loses the database on every restart.
- The migration's cost is fixed but its stakes are not. Today the database holds validation-checkpoint data and the CSV is a complete rebuild path; by phase 3 it holds months of review history that nothing can reconstruct. Doing it now is the same work at a fraction of the risk.
- Doing it in phase 3 would entangle the one lossy migration with real auth, a `users`-table retrofit, RDS and ECS — four unfamiliar systems at once, with no way to attribute a subtly wrong `card_state.due` to any of them.
- It makes phase 3's database step `pg_dump | pg_restore`: same engine, same types, no coercion.
- ADR-004's "no SQLite-specific SQL" is currently a property of code that has only ever run on SQLite. Running the suite against real Postgres converts it from a claim into a fact, and catches what a grep cannot — `JSON` comparison semantics, `NULL` ordering in `ORDER BY`, string collation.
- An ORM-level copy makes type coercion SQLAlchemy's problem rather than ours. The four silent-corruption risks are all coercion: booleans stored `0`/`1` (`highlights.truncated`, `highlights.processed`), timestamps stored as text across 18 columns, generic `JSON` stored as TEXT (`cards.tags`, `push_runs.card_ids`, `fsrs_params.parameters`, `llm_calls.request`/`response`), and sequences that do not transfer.
- Keeping both backends is what makes the step reversible, so the rollback needs no code change.

## Consequences

- **Rollback is one environment variable.** `RECALLY_DATABASE_URL` back to the SQLite path restores the previous state exactly. This is the whole backwards-compatibility story and it is why no SQLite removal ships here.
- **The SQLite file is never written during migration.** It is opened read-only and remains a complete working database; `test_migration_never_writes_to_source` asserts it by checksum. `data/recally.db.pre-migration` is taken before the run regardless.
- **Verification is value-level, not just row counts.** `card_state.due`, `stability` and `difficulty` are asserted byte-identical, because a shifted `due` corrupts scheduling with no error anywhere. Verification is a separate command from the migration so it can be re-run against a live database at any time.
- The test suite must run against both backends. CI keeps SQLite for speed; a Postgres job covers the semantics SQLite cannot.
- Local development is unchanged: the SQLite default stays in `.env.example` and in `Settings.database_url`.
- Free-tier hosted Postgres carries no backup guarantee worth relying on, so a periodic `pg_dump` is required from the day the hosted database holds real reviews. An HF Storage Bucket is a reasonable target — the bucket docs name rolling backups as an intended use case, and `hf sync --delete` is a one-liner.
- The `data/` drop zone and the watcher remain phase-1 local tooling; the hosted path is `POST /ingest` (`docs/api-spec.md`, "Ingestion").
- `docs/architecture.md`'s phase-2 row and its "Phase 2 caveat" paragraph were both written against HF persistent storage and are corrected by this ADR.
