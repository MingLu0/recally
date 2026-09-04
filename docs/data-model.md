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
| dedupe_key | text UNIQUE | O'Reilly: annotation UUID from the Annotation URL fragment (stable across exports). Kindle: sha256(book+location+text) |
| source | text | `oreilly` / `kindle` |
| highlighted_at | date | |
| export_position | int | row index within the export file. Export order is newest first, and within a day reverse creation order, so this is the only positional signal available |
| truncated | bool | Curator flag: text is clipped mid-word at start or end |
| processed | bool | Curator has run |
| removed_at | datetime, nullable | set when the UUID is absent from a later export; cards are kept |

### curated_units
One row per Curator output. Most units wrap a single highlight; a group (e.g. three sibling headings) wraps several. This is where `curated_text` lives, not on `highlights`, because a group has one curated text and several sources.

| col | type | notes |
|---|---|---|
| id | PK | |
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
| front | text | |
| back | text | |
| tags | json | inherited + card-specific |
| status | text | `pending_review` / `needs_human` / `approved` / `rejected` |
| status_reason | text, nullable | critic critique or human rejection reason (feeds the Learner) |
| generation_rounds | int | Writer⇄Critic rounds used |
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
| last_push_at | datetime, nullable | enforces the one-push-per-day policy |

### llm_calls
| col | type | notes |
|---|---|---|
| id | PK | |
| agent | text | curator / writer / critic / learner |
| model | text | |
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
| rows_removed | int | UUIDs present before, absent now |
| started_at / finished_at | datetime | |

Backs `GET /ingest/status`.

## Relationships

```
books 1───n highlights n───n curated_units 1───n cards 1───1 card_state
                                                   cards 1───n review_logs
                                   writer_guidance 1───n cards
```
