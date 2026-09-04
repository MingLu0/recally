# Recally — Configuration

All runtime settings are environment variables, read once at startup. Locally they live in `backend/.env` (gitignored); `backend/.env.example` mirrors this table and is committed. Add a new variable here and to `.env.example` in the same change.

## Backend

| Variable | Default | Used by | Notes |
|---|---|---|---|
| `RECALLY_API_KEY` | *(required)* | API | Value the client must send in `X-API-Key` |
| `RECALLY_DATABASE_URL` | `sqlite:///data/recally.db` | storage | Any SQLAlchemy URL; Postgres in phase 2/3 |
| `RECALLY_WATCH_DIR` | `~/Downloads` | watcher | Folder scanned for `*oreilly-annotations*.csv` |
| `RECALLY_WATCH_DEBOUNCE_MS` | `2000` | watcher | Wait after `on_moved` before reading the file |
| `RECALLY_TIMEZONE` | `Pacific/Auckland` | scheduler, stats | Day boundaries for "one push per day", streaks, new-card allotment |

## LLM (LiteLLM)

Provider credentials use LiteLLM's own variable names (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OLLAMA_API_BASE`, …); Recally does not rename them.

| Variable | Default | Used by | Notes |
|---|---|---|---|
| `LLM_MODEL_CURATOR` | `claude-haiku-4-5-20251001` | Curator | Cheap tier |
| `LLM_MODEL_WRITER` | `claude-sonnet-5` | Writer | Strong tier |
| `LLM_MODEL_CRITIC` | `claude-haiku-4-5-20251001` | Critic | Cheap tier |
| `LLM_MODEL_LEARNER` | `claude-sonnet-5` | Learner stage B | |
| `LLM_MAX_ROUNDS` | `3` | Writer ⇄ Critic | Hard rule 9; do not raise without a doc change |
| `AUTO_APPROVE_ROUND1_ACCEPT` | `false` | pipeline | Hard rule 1 exception; skips the queue for round-1 `accept` |

## Scheduling and push

| Variable | Default | Used by | Notes |
|---|---|---|---|
| `NEW_CARDS_PER_DAY` | `10` | `/reviews/due`, notifier | Cap on never-reviewed cards added per day |
| `FSRS_DESIRED_RETENTION` | `0.9` | FSRS | Used until the optimizer writes an `fsrs_params` row |
| `FSRS_LEARNING_STEPS_MINUTES` | `1,10` | FSRS, `/reviews/due` | Returned to the client for same-session relearning (ADR-005) |
| `PUSH_WINDOW` | `08:00-21:00` | notifier | Local time, `RECALLY_TIMEZONE`; no push outside it |
| `PUSH_CHECK_INTERVAL_MIN` | `60` | notifier | APScheduler tick |
| `FIREBASE_CREDENTIALS_FILE` | *(required for push)* | notifier | Service-account JSON for FCM HTTP v1 |
| `LEARNER_CRON` | `0 3 * * *` | Learner | Nightly, `RECALLY_TIMEZONE` |
| `OPTIMIZER_MIN_REVIEWS` | `400` | Learner stage A | Skip the fit below this many `review_logs` |

## Android

The app has no build-time config. Base URL and API key are entered in the Settings screen and stored encrypted on device (see [android.md](android.md)). FCM project config comes from `google-services.json`, which is gitignored.
