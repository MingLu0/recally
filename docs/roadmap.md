# Recally — Roadmap

## Build order (v1)

### 0. Prerequisites
- Python ≥ 3.10 (`py-fsrs` 6.x requires it; the Mac ships 3.9). Install via `uv` or Homebrew.
- Pin APScheduler 3.x.

### 1. Backend skeleton + ingestion
- FastAPI app, SQLAlchemy models, Alembic (batch mode), SQLite.
- O'Reilly CSV adapter + dedupe + watcher (rename event, debounced).
- **Gate**: ingest the 25/08 export (326 rows), then the 04/09 export (380 rows) of the same book. Expect 56 new, 2 removed, 0 changed, and a third run of either file adds nothing.

### 2. Agent pipeline
- Curator → Writer ⇄ Critic with LiteLLM, cost logging, human approval queue.
- **Gate**: generate cards for Chapter 9; the run of "Stage 1/2/3" headings becomes one grouped card; a truncated row ("…written to the san") produces a card that does not invent the missing text.

### 3. FSRS + reviews API
- py-fsrs engine, `/reviews/due`, `/reviews/{id}/rate`, `/reviews/rate-batch`, scheduler.
- **Gate**: review loop works end-to-end via API/CLI, including a card rated Again coming back within the session and an out-of-order offline rating replaying correctly.

### ⚠️ Validation checkpoint
Use the backend with a minimal client for ~2 weeks. If card quality doesn't sustain a review habit, fix the pipeline before investing in the app.

### 4. Android MVP
- Today, Review, Approval Queue screens; Retrofit client; Room cache.
- **Gate**: daily reviews happen on the phone.

### 5. Notifications
- FCM HTTP v1 push via `firebase-admin`; one-per-day policy; deep links.

### 6. Learner + stats
- Nightly FSRS optimizer (stage A) as soon as there are a few hundred reviews.
- LLM Learner (stage B) writing versioned `writer_guidance`; leech rewrites; Stats screen with lapse rate by guidance version.

## Productionization phases

| Phase | Trigger | Changes |
|---|---|---|
| 1. Local | now | Mac, SQLite, watcher, LAN |
| 2. Hosted | want access away from home | Dockerize; upload endpoint replaces watcher. HF Spaces only with paid always-on hardware and persistent storage (free tier sleeps after 48 h and wipes disk, which kills the scheduler and the DB). Otherwise a small VPS, or external cron → `POST /jobs/run` plus hosted Postgres |
| 3. AWS | multi-user or reliability needs | ECS/Lambda + RDS Postgres, S3 drop zone, real auth |

## Later ideas

- Kindle adapter (My Clippings.txt from the device, or notebook HTML export from read.amazon.com / the mobile app; there is no CSV export).
- Cross-book decks via topic tags.
- LangGraph migration if orchestration outgrows plain Python (ADR-001).
- Web/PWA client.
