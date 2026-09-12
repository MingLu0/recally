# ADR-015: Concurrent unit resolution in the pipeline

**Status**: accepted
**Date**: 2026-09-12

## Context

The pipeline's LLM calls ran strictly sequentially, so an ingest cost its full summed provider latency in wall-clock. Measured on the 2026-09-09 ingest (309 `llm_calls` rows, `openai/k3`): 61.7 minutes total — `critic/default` 186 calls / 32.3 min, `writer/default` 114 calls / 20.9 min, `curator/default` 9 calls / 8.6 min. Almost all of it is idle waiting on the provider, not compute.

Two axes were modelled from the same rows. Parallelising **cards within a unit** gives ~42 min: units average 2.06 cards, 12 have only one, and the unit's Writer round-1 call is a serial prefix before any Critic can start. Parallelising **units** gives ~5 min: the 68 units are fully independent chains, and the slowest single unit (144 s) is the floor. Same refactor, one level up, ~12x instead of ~1.5x.

The constraints this rests on were verified rather than assumed: SQLAlchemy's `Session` is not thread-safe; `LlmCaller` **is** (its own session per row, committed independently, no shared mutable state); agents are registered as stateless singletons (`agents/registry.py`); `expire_on_commit=False` (`db.py`) lets a worker read `unit.curated_text` off an ORM object after a main-thread commit without firing a lazy load; agents hold no DB session (ADR-007); cost attribution is post-hoc and re-queries `llm_calls` grouped by `unit_id`, so it is concurrency-immune.

## Decision

Curated units resolve their Writer ⇄ Critic chains on a `ThreadPoolExecutor`, `LLM_CONCURRENCY` wide (default **1**, which is the historical sequential path). No prompt changes: the same calls, overlapped, so card quality is unchanged by construction.

The refactor splits resolution from persistence. `_resolve_card` and `_resolve_unit_cards` take **no `session`** and return `_CardOutcome` values; `_persist_unit` holds everything that touches the session — `_write_card`, the `highlight.processed` flips, the stats mutation, the commit — and runs on the main thread only, one unit at a time. Futures are drained with **`as_completed`, never `map`**. Cards are **not** parallelised within a unit. SQLite is put in WAL mode by a `connect` listener in `db.py` (connection configuration, not SQL, so ADR-004 holds).

## Rationale

- Unit-level concurrency is ~12x for the same complexity that card-level buys ~1.5x; the payoff is not there one level down.
- A session-free resolution half makes the thread-safety rule structural rather than a comment: a worker cannot touch the database because it is never handed a session.
- `as_completed` means a unit commits the moment it finishes, so a crash 40 units in keeps those 40 — today's recovery behaviour. `map` would batch every write to the end of the run and lose ~300 calls' worth of work on a crash.
- Re-raising the first exception after the pool drains reuses the existing handler in `run()`. Swallowing it would make the run row write `error=None`, claiming success while failed units sat unprocessed.
- Default 1 makes the shipped default provably the historical code path, so rollback needs no deploy.
- `LLM_CONCURRENCY` is a **ceiling on provider concurrency, not a thread quota**: the pool is built `min(LLM_CONCURRENCY, len(pending))` wide, because a thread with no unit to resolve can never do work. This only ever spawns *fewer* threads than configured, so it cannot raise provider pressure above the documented limit. An empty `pending` returns before the pool is built — `ThreadPoolExecutor` rejects `max_workers=0`, and a re-run where every keep unit already has cards reaches that path legitimately.
- WAL is **necessary but not sufficient**: it gives one writer and many readers, while this design has concurrent *writers* (worker `llm_calls` commits plus main-thread unit commits). Those still serialize, and whether a blocked writer waits or raises `database is locked` depends on the busy timeout. Python's `sqlite3` defaults to `timeout=5.0`, which is expected to cover it — `test_llm_calls_row_per_call_under_concurrency` exercises that rather than assuming it.

## Consequences

- **Partial failure changes meaning.** Previously an agent exception aborted the run mid-way. Now each unit fails independently: already-committed units stand, failed units keep `processed=false` so the next run retries them (the documented path, `docs/architecture.md`, "Failure handling"), and the **first** exception is recorded on `ingest_runs.error`. When several units fail, that column names one, not all.
- **Ordering is no longer unit order.** `as_completed` yields in completion order, so card ids no longer follow unit order. Nothing depends on id ordering (`_record_outcome` groups by `unit_id`; the API sorts explicitly), but it is observable, and `test_concurrent_run_produces_identical_rows` pins the rows as a set to say so.
- The stateless-singleton property of agents becomes load-bearing: one shared instance is called from N threads, so a future variant holding per-call state would break concurrency silently.
- Concurrency tests must use a **file-backed** SQLite fixture. The in-memory `StaticPool` used elsewhere shares one connection with `check_same_thread=False` and silently ignores WAL, so it would test neither the locking nor the mitigation.
- Scoped out, deliberately: card-level concurrency; retry/backoff in `llm.py` (a 429 at 8-wide propagates as a unit failure, which the retry path survives — a follow-up ticket); any prompt change; overlapping the Curator and Writer phases.
- **The ~5 min estimate is modelled from one ingest on one model.** Provider behaviour under 8 concurrent requests is not in that data. Rollout is `LLM_CONCURRENCY=1`, then 4 on a real ingest watching for 429s and `database is locked`, then 8. The first real run at 4 is the measurement that matters; the estimate is a hypothesis this change sets up to test, not a promise.
- Reversible by config alone: `LLM_CONCURRENCY=1` restores the sequential path without a deploy.
