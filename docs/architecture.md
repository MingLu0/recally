# Recally — System Architecture

## Overview

```
~/Downloads/*oreilly-annotations*.csv
        │
        ▼
┌─────────────────┐     ┌──────────────────────────────────────┐
│  Watcher        │────►│  Ingestion adapters                  │
│  (watchdog)     │     │  oreilly_csv.py → NormalizedHighlight│
└─────────────────┘     └──────────────┬───────────────────────┘
                                       ▼
                              dedupe (annotation UUID)
                                       ▼
                              highlights table (raw)
                                       ▼
              ┌────────────────────────────────────────────────┐
              │  AGENT PIPELINE (LLM via LiteLLM)              │
              │                                                │
              │  Curator ──► Writer ⇄ Critic (≤3 rounds)       │
              │   filter/     generate   reject ──► needs_human│
              │   group/tag   1-3 cards                        │
              └──────────────────────┬─────────────────────────┘
                                     ▼
                     cards.status = pending_review
                                     ▼
                    Human approval (Android app)
                                     ▼
                     approved ──► FSRS engine (py-fsrs)
                                     ▼
              Scheduler (APScheduler, ≤1 push/day) ──► FCM push
                                     ▼
                          Android review session
                          (client re-queues Again/Hard in-session)
                                     ▼
                         review_logs (ratings, client timestamps)
                                     ▼
              Learner (nightly): FSRS optimizer ──► card_state params
                                 LLM analysis  ──► writer_guidance (versioned)
```

## Components

### Ingestion (deterministic)
- **Watcher**: `watchdog` on a configurable folder (default `~/Downloads`). Detects new `*oreilly-annotations*.csv`, routes by filename/format sniffing to the right adapter. Browsers write a partial `.crdownload`/`.download` file and then rename it, so the watcher acts on the rename (`on_moved`) with a short debounce, not on `on_created`. Also exposed as `POST /ingest` so the same path supports manual upload and, later, cloud storage triggers.
- **Adapters**: `BaseAdapter.parse(file) -> list[NormalizedHighlight]`. O'Reilly CSV now (9 columns: Book Title, Chapter Title, Date of Highlight, Book URL, Chapter URL, Annotation URL, Highlight, Color, Personal Note); Kindle adapter later (My Clippings.txt and notebook HTML export are the formats that exist today).
- **Dedupe**: the O'Reilly annotation URL fragment is a per-highlight UUID that is stable across exports (verified on two real exports). Book URL carries the ISBN, which is the `books` dedupe key. UUIDs absent from a later export mark the highlight `removed`; cards already generated are kept.
- **Truncation**: some highlights are clipped mid-word at the start or end inside a single row (e.g. a 160-character row ending "written to the san"). The missing text is not in any other row, so nothing downstream should try to reconstruct it; the Curator flags it and the Writer works from what is there.

### Agent pipeline (LLM, provider-agnostic via LiteLLM)
See [agents.md](agents.md). Provider selected by env var (Claude / OpenAI / Ollama).

### Scheduling (deterministic)
- **FSRS engine**: `py-fsrs` 6.x (requires Python ≥ 3.10). Rating → next due date, stability, difficulty, learning step. Default learning steps are 1 and 10 minutes, so a card rated Again is due again inside the same session; see ADR-005 for how the client and server share that.
- **Offline replay**: `Scheduler.review_card` accepts an explicit review datetime, so ratings recorded offline are replayed in client-timestamp order and the server stays authoritative.
- **Optimizer**: `fsrs.Optimizer` (optional `torch`/`pandas` extra) fits personal FSRS parameters from `review_logs`. Runs nightly once there are a few hundred reviews.
- **Notifier**: APScheduler 3.x (4.x is still pre-release) checks due cards hourly but sends at most one FCM push per day, inside a configured window, and none while the previous push's cards are still unreviewed. Push → deep link into review session.

### API
FastAPI, single API-key auth (header). See [api-spec.md](api-spec.md).

### Storage
SQLite now (`data/recally.db`), SQLAlchemy models, Postgres-ready (no SQLite-specific SQL). Alembic runs with `render_as_batch=True` because SQLite cannot ALTER columns in place. See [data-model.md](data-model.md).

### Android
Native Kotlin + Jetpack Compose, offline-first with Room cache and rating sync queue. See [android.md](android.md).

## Infrastructure migration path

| Phase | Hosting | Ingestion | DB | Notes |
|---|---|---|---|---|
| 1. Local (now) | Mac (Python ≥ 3.10) | watch folder | SQLite | LAN access for the app |
| 2. Hosted | Docker on HF Spaces (paid always-on hardware + persistent storage) **or** a small VPS/Fly.io machine | manual upload endpoint | SQLite on persistent volume, or external Postgres (Neon/Supabase) | single user |
| 3. AWS | ECS/Lambda + RDS | S3 drop → trigger | Postgres (RDS) | real auth, multi-user ready |

Phase 2 caveat: the free HF Spaces tier sleeps after 48 h of inactivity and its disk is ephemeral, which halts the in-process scheduler and loses the database. Either pay for always-on hardware plus persistent storage, or move the scheduler out of process (an external cron such as GitHub Actions calling `POST /jobs/run`) and the database to a hosted Postgres.

The ingestion and agent code is identical across phases; only triggers and connection strings change.
