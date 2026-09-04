# Recally — API Spec

Base: FastAPI. Auth: `X-API-Key` header (single user, env-configured). All responses JSON.

## Reviews

### GET /reviews/due
Cards due now (FSRS), plus today's new-card allotment. Returns the FSRS `learning_steps` in effect so the client can re-queue Again/Hard cards inside the session (see ADR-005).
**Response**
```json
{
  "due_count": 12,
  "new_count": 5,
  "learning_steps_minutes": [1, 10],
  "cards": [
    {
      "id": 101, "type": "qa",
      "front": "Why evaluate traces rather than individual steps?",
      "back": "An LLM pipeline's behavior only makes sense end-to-end.",
      "book": "Evals for AI Engineers", "chapter": "3. Error Analysis",
      "tags": ["evals"], "state": "review"
    }
  ]
}
```

### POST /reviews/{card_id}/rate
```json
{ "rating": 3, "response_ms": 8200, "rated_at": "2026-09-04T08:12:30Z" }
```
`rating`: 1=Again, 2=Hard, 3=Good, 4=Easy. `rated_at` is the client timestamp; required so offline ratings replay in order. Runs FSRS update with `review_datetime=rated_at`, writes review_log. If a rating with an earlier `rated_at` arrives after a later one has been applied, the server recomputes the card from its full log. Duplicate (`card_id`, `rated_at`) is a no-op returning the current state.
**Response**: `{ "next_due": "2026-09-09T08:00:00Z", "state": "review", "step": null }`

### POST /reviews/rate-batch
Same body as above as a list; used by the Android sync queue to flush offline ratings in one call. Applied in `rated_at` order per card.

## Approval queue

### GET /cards/pending
`?status=pending_review|needs_human&book_id=&chapter=`. Grouped by chapter by default. Includes critic critique and all source highlights (a grouped unit has several) for context.
```json
{
  "cards": [
    {
      "id": 55, "status": "needs_human", "type": "cloze",
      "front": "The {{c1::Gulf of Specification}} is the gap between intent and instructions.",
      "back": "—",
      "status_reason": "Critic: potentially ambiguous term; Writer 3 rounds unresolved.",
      "source_highlights": ["The Gulf of Specification is this gap between our intent and our instructions..."],
      "truncated": false,
      "book": "Evals for AI Engineers", "chapter": "1. Introduction"
    }
  ]
}
```

### POST /cards/{id}/approve
Optional edits: `{ "front": "...", "back": "..." }` → status `approved`, enters FSRS.

### POST /cards/{id}/reject
`{ "reason": "..." }` → status `rejected`. Reasons feed the Learner.

## Decks & browsing

### GET /decks
Books with card counts and due counts.
```json
{ "decks": [ { "book_id": 1, "title": "Evals for AI Engineers", "total": 48, "due": 6 } ] }
```

### GET /decks/{book_id}/cards
`?chapter=` optional filter. Browse cards per book.

## Stats

### GET /stats
```json
{
  "streak_days": 9,
  "reviews_today": 23,
  "retention_30d": 0.87,
  "lapse_rate_by_type": { "qa": 0.11, "cloze": 0.18 },
  "forecast": [ { "date": "2026-09-05", "due": 14 } ]
}
```

## Ingestion

### POST /ingest
Multipart CSV upload (same pipeline as the watcher; enables HF Spaces phase).

### GET /ingest/status
Last run: rows seen, new, unchanged, removed, cards generated, cost.

## Jobs

### POST /jobs/run
`{ "job": "notify" | "learner" | "optimizer" }`. Runs a scheduled job on demand. Exists so an external cron can drive scheduling on hosts where the in-process scheduler cannot stay alive (see architecture.md, phase 2).

## Devices

### POST /devices
`{ "fcm_token": "...", "platform": "android" }` — register for push.

## Errors

Standard problem+json: `{ "status": 422, "detail": "..." }`.
