# Recally — Roadmap

## Build order (v1)

### 1. Backend skeleton + ingestion
- FastAPI app, SQLAlchemy models, Alembic, SQLite.
- O'Reilly CSV adapter + dedupe + watcher.
- **Gate**: ingests the real 826-row export idempotently (re-run = 0 new rows).

### 2. Agent pipeline
- Curator → Writer ⇄ Critic with LiteLLM, cost logging, human approval queue.
- **Gate**: generate cards for one chapter; manually review quality in a CLI/web queue.

### 3. FSRS + reviews API
- py-fsrs engine, `/reviews/due`, `/reviews/{id}/rate`, scheduler.
- **Gate**: review loop works end-to-end via API/CLI.

### ⚠️ Validation checkpoint
Use the backend with a minimal client for ~2 weeks. If card quality doesn't sustain a review habit, fix the pipeline before investing in the app.

### 4. Android MVP
- Today, Review, Approval Queue screens; Retrofit client; Room cache.
- **Gate**: daily reviews happen on the phone.

### 5. Notifications
- FCM push from the hourly scheduler; deep links.

### 6. Learner agent + stats
- Nightly Learner job, Writer prompt feedback, leech rewrites; Stats screen.

## Productionization phases

| Phase | Trigger | Changes |
|---|---|---|
| 1. Local | now | Mac, SQLite, watcher, LAN |
| 2. HF Spaces | want access away from home | Dockerize, upload endpoint replaces watcher, persistent volume |
| 3. AWS | multi-user or reliability needs | ECS/Lambda + RDS Postgres, S3 drop zone, real auth |

## Later ideas

- Kindle adapter (export format TBD: clippings.txt / notebook HTML / CSV).
- Cross-book decks via topic tags.
- LangGraph migration if orchestration outgrows plain Python (ADR-001).
- Web/PWA client.
