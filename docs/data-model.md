# Recally — Data Model

SQLite now, Postgres-ready. SQLAlchemy ORM, Alembic migrations from day one (`render_as_batch=True` for SQLite). Every table below carries `user_id int NOT NULL DEFAULT 1` for future multi-user; it is omitted from the column lists to keep them readable.

Money is stored as `cost_microusd int` (1 USD = 1,000,000). Cheap-model calls cost fractions of a cent and a `cents` column loses them.

## Tables

### books
| col | type | notes |
|---|---|---|
| id | PK | |
| title | text | |
| author | text, nullable | Kindle exports include author; O'Reilly CSV doesn't |
| source | text | `oreilly` / `kindle` |
| external_id | text | O'Reilly: ISBN from the Book URL. Kindle: ASIN or title hash |
| url | text, nullable | O'Reilly book URL |

UNIQUE (`source`, `external_id`).

### highlights
| col | type | notes |
|---|---|---|
| id | PK | |
| book_id | FK books | |
| chapter | text, nullable | |
| location | text, nullable | Kindle location/page |
| raw_text | text | as exported |
| personal_note | text, nullable | O'Reilly "Personal Note" column |
| color | text, nullable | O'Reilly "Color" column; currently always YELLOW, kept for future filtering |
| dedupe_key | text UNIQUE | O'Reilly: annotation UUID from the Annotation URL fragment (stable across exports). Kindle: sha256(book+location+text). Same key + same text on re-import → skip; same key + different `raw_text`/`personal_note` → update this row and set `processed=false` |
| source | text | `oreilly` / `kindle` |
| highlighted_at | date | |
| export_position | int | row index within the export file. Export order is newest first, and within a day reverse creation order, so this is the only positional signal available |
| truncated | bool | Curator flag: text is clipped mid-word at start or end. Lives only here; units and cards derive "any source truncated" |
| processed | bool | set once the unit covering this highlight reached a terminal outcome (`drop`, or all its cards have a status); not when the Curator merely ran. See `agents.md`, "Pipeline runner and handoffs" |
| removed_at | datetime, nullable | set when the UUID is absent from a later export; cards are kept |

### curated_units
One row per Curator output. Most units wrap a single highlight; a group (e.g. three sibling headings) wraps several. This is where `curated_text` lives, not on `highlights`, because a group has one curated text and several sources.

| col | type | notes |
|---|---|---|
| id | PK | |
| ingest_run_id | FK ingest_runs | the run whose Curator call produced this unit; lets orphaned units from a crashed run be found and removed |
| curated_text | text | after Curator cleanup / grouping |
| tags | json | topic tags from Curator |
| decision | text | `keep` / `drop` |
| reason | text, nullable | Curator rationale |
| created_at | datetime | |

### curated_unit_highlights
| col | type | notes |
|---|---|---|
| unit_id | FK curated_units | |
| highlight_id | FK highlights | |

PK (`unit_id`, `highlight_id`). Gives every card full provenance back to each source highlight, including grouped ones.

### cards
| col | type | notes |
|---|---|---|
| id | PK | |
| unit_id | FK curated_units | provenance; source highlights via `curated_unit_highlights` |
| type | text | `qa` / `cloze` |
| front | text | current text (may be human-edited at approval) |
| back | text | |
| original_front | text | Writer output as accepted by the Critic; never changed |
| original_back | text | |
| tags | json | inherited + card-specific |
| status | text | `pending_review` / `needs_human` / `approved` / `rejected` |
| status_reason | text, nullable | critic critique, human rejection reason, or `superseded by <id>` (feeds the Learner) |
| approved_at | datetime, nullable | when the human approved; `card_state` is created at the same time |
| generation_rounds | int | Writer⇄Critic rounds used for this card (per card, not per unit) |
| model | text | generating model (for quality analysis) |
| guidance_version | int, nullable FK writer_guidance | which Learner guidance was in the Writer prompt |
| cost_microusd | int | generation cost |
| created_at | datetime | |

### card_state (FSRS)
Mirrors the `py-fsrs` 6.x `Card` object exactly so it round-trips through `Card.from_dict`. Note there is no `new` state in FSRS 6: a fresh card is `learning` at step 0. `reps` and `lapses` are not on the library object; derive them from `review_logs`.

| col | type | notes |
|---|---|---|
| card_id | PK, FK cards | |
| state | text | `learning` / `review` / `relearning` |
| step | int, nullable | learning/relearning step index; NULL in `review` |
| stability | float, nullable | FSRS; NULL before first review |
| difficulty | float, nullable | FSRS; NULL before first review |
| due | datetime | next review (UTC) |
| last_review | datetime, nullable | |

### review_logs
Full-fidelity from day one. This is the Learner's and the FSRS optimizer's training data, and the replay source for offline ratings.
| col | type | notes |
|---|---|---|
| id | PK | |
| card_id | FK cards | |
| rated_at | datetime | **client** timestamp of the rating, not server receipt time; replay order depends on it |
| received_at | datetime | server receipt time |
| rating | int | 1=Again 2=Hard 3=Good 4=Easy |
| response_ms | int | time to answer |
| scheduled_days | int | interval before this review |
| state_before | text | FSRS state when rated (from the library `ReviewLog`) |
| device_id | FK devices, nullable | |

UNIQUE (`card_id`, `rated_at`) makes offline re-sync idempotent.

### fsrs_params
| col | type | notes |
|---|---|---|
| id | PK | |
| parameters | json | 21 FSRS weights from `fsrs.Optimizer` |
| desired_retention | float | |
| review_count | int | reviews the fit was based on |
| created_at | datetime | |

Latest row is active. Defaults are used until the first fit.

### writer_guidance
| col | type | notes |
|---|---|---|
| version | PK int | monotonic |
| guidance | text | injected into the Writer prompt |
| basis | json | aggregates the Learner used to write it |
| created_at | datetime | |

Rows are never edited. Cards store the version that generated them.

### devices
| col | type | notes |
|---|---|---|
| id | PK | |
| fcm_token | text UNIQUE | |
| platform | text | `android` |
| created_at | datetime | |
| last_push_at | datetime, nullable | denormalised from `push_runs` for the one-push-per-day check |

### push_runs
One row per FCM push actually sent. Backs the "no push while the previous push's cards are unreviewed" rule: the notifier skips if any `card_ids` from the latest row has no `review_logs` entry after `sent_at`. That check loads the latest row and loops over `card_ids` in Python; it is one small batch per day, so a `push_run_cards` join table is deferred to the Postgres cutover (ADR-004).

| col | type | notes |
|---|---|---|
| id | PK | |
| device_id | FK devices | |
| sent_at | datetime | |
| card_ids | json | ids included in the batch this push announced |
| due_count | int | number shown in the notification |

### llm_calls
One row per call through `llm.py`. Doubles as the trace and the replay corpus: the full prompt and completion are stored so a bad Critic verdict can be inspected, and so prompts can be re-run against human-labelled cards later (ADR-006).
| col | type | notes |
|---|---|---|
| id | PK | |
| agent | text | `role/variant`, e.g. `writer/default`; the variant comes from the `AGENT_*` env vars (ADR-007), so the trace names the implementation that made the call |
| ingest_run_id | FK ingest_runs, nullable | null for Learner calls |
| unit_id | FK curated_units, nullable | set for Writer and Critic calls; null for Curator (batch) and Learner |
| card_id | FK cards, nullable | set for Critic calls and for Writer revisions of an existing card |
| round | int, nullable | Writer ⇄ Critic round (1-based); null outside the loop |
| model | text | |
| request | json, nullable | messages sent, after prompt rendering; null when `LLM_LOG_PAYLOADS=false` |
| response | json, nullable | parsed structured output; null when `LLM_LOG_PAYLOADS=false` |
| input_tokens | int | |
| output_tokens | int | |
| cost_microusd | int | from `litellm.completion_cost`; 0 for Ollama |
| latency_ms | int | |
| created_at | datetime | |

### ingest_runs
| col | type | notes |
|---|---|---|
| id | PK | |
| filename | text | |
| rows_seen | int | |
| rows_new | int | |
| rows_updated | int | same UUID, changed text or note (expected 0) |
| rows_removed | int | UUIDs present before, absent now |
| cards_generated | int | cards the pipeline produced during this run; may include leftover highlights from an earlier run, since the runner processes all `processed=false` rows |
| cost_microusd | int | sum of `llm_calls` for this run |
| started_at / finished_at | datetime | |
| error | text, nullable | set if the run aborted |

`rows_unchanged = rows_seen - rows_new - rows_updated`.

Backs `GET /ingest/status`.

## Relationships

```
books 1───n highlights n───n curated_units 1───n cards 1───1 card_state
                                                   cards 1───n review_logs
                                   writer_guidance 1───n cards
                                   devices 1───n push_runs
                                   ingest_runs 1───n llm_calls
                                   ingest_runs 1───n curated_units
                                   curated_units 1───n llm_calls
                                   cards 1───n llm_calls
```

There is no `users` table in v1. `user_id` is a plain integer column (default 1) with no FK; the table and FKs arrive with multi-user auth (roadmap phase 3).
