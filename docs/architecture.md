# Recally — System Architecture

## Overview

```
~/Downloads/oreilly-annotations*.csv
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
              │   merge/tag   1-3 cards                        │
              └──────────────────────┬─────────────────────────┘
                                     ▼
                     cards.status = pending_review
                                     ▼
                    Human approval (Android app)
                                     ▼
                     approved ──► FSRS engine (py-fsrs)
                                     ▼
              Scheduler (APScheduler hourly) ──► FCM push
                                     ▼
                          Android review session
                                     ▼
                         review_logs (ratings, timing)
                                     ▼
              Learner agent (nightly) ──► feedback into Writer prompt
```

## Components

### Ingestion (deterministic)
- **Watcher**: `watchdog` on a configurable folder (default `~/Downloads`). Detects new `oreilly-annotations*.csv`, routes by filename/format sniffing to the right adapter. Also exposed as `POST /ingest` so the same path supports manual upload and, later, cloud storage triggers.
- **Adapters**: `BaseAdapter.parse(file) -> list[NormalizedHighlight]`. O'Reilly CSV now; Kindle adapter later (format TBD — clippings.txt vs notebook HTML vs CSV).
- **Dedupe**: O'Reilly annotation URL contains a unique UUID per highlight — idempotent re-ingestion of the same export.

### Agent pipeline (LLM, provider-agnostic via LiteLLM)
See [agents.md](agents.md). Provider selected by env var (Claude / OpenAI / Ollama).

### Scheduling (deterministic)
- **FSRS engine**: `py-fsrs`. Rating → next due date, stability, difficulty.
- **Notifier**: APScheduler hourly job finds due cards → FCM push → deep link into review session.

### API
FastAPI, single API-key auth (header). See [api-spec.md](api-spec.md).

### Storage
SQLite now (`data/recally.db`), SQLAlchemy models, Postgres-ready (no SQLite-specific SQL). See [data-model.md](data-model.md).

### Android
Native Kotlin + Jetpack Compose, offline-first with Room cache and rating sync queue. See [android.md](android.md).

## Infrastructure migration path

| Phase | Hosting | Ingestion | DB | Notes |
|---|---|---|---|---|
| 1. Local (now) | Mac | watch folder | SQLite | LAN access for the app |
| 2. HF Spaces | Docker container | manual upload endpoint | SQLite volume / external PG | single user |
| 3. AWS | ECS/Lambda + RDS | S3 drop → trigger | Postgres (RDS) | real auth, multi-user ready |

The ingestion and agent code is identical across phases; only triggers and connection strings change.
