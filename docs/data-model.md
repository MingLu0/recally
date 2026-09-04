# Recally — Data Model

SQLite now, Postgres-ready. SQLAlchemy ORM, Alembic migrations from day one. All tables carry `user_id` (default 1) for future multi-user.

## Tables

### books
| col | type | notes |
|---|---|---|
| id | PK | |
| title | text | |
| author | text, nullable | Kindle exports include author; O'Reilly CSV doesn't |
| source | text | `oreilly` / `kindle` |
| url | text, nullable | O'Reilly book URL |

### highlights
| col | type | notes |
|---|---|---|
| id | PK | |
| book_id | FK books | |
| chapter | text, nullable | |
| location | text, nullable | Kindle location/page |
| raw_text | text | as exported |
| curated_text | text, nullable | after Curator merge/cleanup |
| personal_note | text, nullable | O'Reilly "Personal Note" column |
| tags | json | list of topic tags from Curator |
| dedupe_key | text UNIQUE | O'Reilly: annotation UUID. Kindle: sha256(book+location+text) |
| source | text | `oreilly` / `kindle` |
| highlighted_at | date | |
| curator_decision | text, nullable | `keep` / `merged` / `dropped` + reason |
| processed | bool | Curator has run |

### cards
| col | type | notes |
|---|---|---|
| id | PK | |
| highlight_id | FK highlights | provenance — always traceable back to the source highlight |
| type | text | `qa` / `cloze` |
| front | text | |
| back | text | |
| tags | json | inherited + card-specific |
| status | text | `pending_review` / `needs_human` / `approved` / `rejected` |
| status_reason | text, nullable | critic critique or human note |
| generation_rounds | int | Writer⇄Critic rounds used |
| model | text | generating model (for quality analysis) |
| cost_cents | numeric | generation cost |
| created_at | datetime | |

### card_state (FSRS)
| col | type | notes |
|---|---|---|
| card_id | PK, FK cards | |
| stability | float | FSRS |
| difficulty | float | FSRS |
| due | datetime | next review |
| reps | int | |
| lapses | int | |
| state | text | `new` / `learning` / `review` / `relearning` |
| last_review | datetime, nullable | |

### review_logs
Full-fidelity from day one — this is the Learner agent's training data.
| col | type | notes |
|---|---|---|
| id | PK | |
| card_id | FK cards | |
| rated_at | datetime | |
| rating | int | 1=Again 2=Hard 3=Good 4=Easy |
| response_ms | int | time to answer |
| scheduled_days | int | interval before this review |
| device | text, nullable | |

### devices
| col | type | notes |
|---|---|---|
| id | PK | |
| fcm_token | text UNIQUE | |
| platform | text | `android` |
| created_at | datetime | |

### llm_calls
| col | type | notes |
|---|---|---|
| id | PK | |
| agent | text | curator / writer / critic / learner |
| model | text | |
| input_tokens | int | |
| output_tokens | int | |
| cost_cents | numeric | |
| latency_ms | int | |
| created_at | datetime | |

## Relationships

```
books 1───n highlights 1───n cards 1───1 card_state
                                  cards 1───n review_logs
```
