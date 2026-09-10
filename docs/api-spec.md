# Recally — API Spec

Base: FastAPI. Auth: `X-API-Key` header (single user, value from `RECALLY_API_KEY`, see [config.md](config.md)). All responses JSON. Phase 1 is plain HTTP on the LAN; TLS arrives with hosting (phase 2).

**List endpoints are unpaginated in v1.** `/reviews/due`, `/cards/pending` and `/decks/{book_id}/cards` return the full set. One user, a few thousand cards, and a client that caches whole lists in Room; pagination would be two designs to keep in sync for no benefit. Revisit if a single response exceeds a few hundred cards.

## Health

### GET /health
Unauthenticated liveness check: `{ "status": "ok" }`. Does not touch the database.

### GET /health/auth
Same, but behind the `X-API-Key` dependency. This is what the Android Settings screen's *connection test* calls: 200 means the base URL and the key are both right, 401 means the key is wrong, and a connection error means the server was not reachable at all. Kept separate from `GET /health` so a load balancer probe never needs the key.

## Reviews

### GET /reviews/due
Cards due now (FSRS), plus today's new-card allotment (capped by `NEW_CARDS_PER_DAY`). Returns the FSRS `learning_steps` in effect so the client can re-queue Again/Hard cards inside the session (see ADR-005). Cards whose `suspended_until` is in the future are excluded (ADR-008).
**Response**
```json
{
  "due_count": 12,
  "new_count": 5,
  "learning_steps_minutes": [1, 10],
  "cards": [
    {
      "id": 101, "unit_id": 40, "type": "qa",
      "front": "Why evaluate traces rather than individual steps?",
      "back": "An LLM pipeline's behavior only makes sense end-to-end.",
      "book_id": 1, "book": "Evals for AI Engineers", "chapter": "3. Error Analysis",
      "tags": ["evals"],
      "state": "learning", "step": 0, "due": "2026-09-05T07:55:00Z"
    }
  ]
}
```
`state` and `step` are the card's server-side FSRS position at fetch time (`step` is null in `review`). The client needs `step`, not just `state`, to re-queue correctly: a card already at step 1 rated Again must wait the step-1 interval, not restart at step 0. `due` is included so a cached queue can be re-sorted offline without a refetch.

`cards` mixes due cards and the new-card allotment, and the client does not need to tell them apart: a never-reviewed card has no `card_state` row yet (it is created at approval), so the server returns it as `state: "learning"`, `step: 0`, `due` set to the fetch time. Every entry therefore carries the same fields and the client needs no special case.

### POST /reviews/{card_id}/rate
```json
{ "rating": 3, "response_ms": 8200, "rated_at": "2026-09-04T08:12:30Z", "device_id": 3 }
```
`rating`: 1=Again, 2=Hard, 3=Good, 4=Easy. `rated_at` is the client timestamp; required so offline ratings replay in order. `device_id` is optional (the id returned by `POST /devices`) and lands in `review_logs.device_id`; omit it from the CLI. Runs FSRS update with `review_datetime=rated_at`, writes review_log. If a rating with an earlier `rated_at` arrives after a later one has been applied, the server recomputes the card from its full log. Duplicate (`card_id`, `rated_at`) is a **no-op**: the existing log row is kept untouched and the current state is returned with `200`, so a retried flush is safe and never surfaces as an error on the client.
**Response**
```json
{ "card_id": 101, "rated_at": "2026-09-04T08:12:30Z",
  "next_due": "2026-09-09T08:00:00Z", "state": "review", "step": null,
  "lapsed": false, "duplicate": false }
```
`lapsed` is true when this rating moved the card out of `review` into `relearning` — the client cannot derive it (a rating of Again on a card already in `learning` is not a lapse), and the session summary counts it. On a replay it is always `false`, because a no-op moved nothing; the client counts lapses only from responses with `duplicate: false`, so a retried flush cannot double-count. `duplicate` is true when the request was a no-op replay; the client dequeues on both values.

### POST /reviews/rate-batch
Used by the Android sync queue to flush offline ratings in one call.
**Request**: `{ "ratings": [ <same object as above, plus "card_id">, ... ] }`
Applied in `rated_at` order per card, **each rating independently**. One bad item does not roll back the others: `results` has exactly one entry per request item, in request order, so the client matches results to queued ratings by position.
**Response**
```json
{
  "results": [
    { "card_id": 101, "rated_at": "2026-09-04T08:12:30Z", "ok": true,
      "next_due": "2026-09-09T08:00:00Z", "state": "review", "step": null,
      "lapsed": false, "duplicate": false },
    { "card_id": 999, "rated_at": "2026-09-04T08:14:02Z", "ok": false,
      "status": 404, "detail": "card not found" }
  ]
}
```
Items are validated individually, not by the request schema, so one malformed item cannot fail the whole body. The call returns `200` whenever the body itself parsed as `{"ratings": [...]}`, even if every item failed; `ok` is the per-item verdict, and a failed item carries the same `status`/`detail` pair as a top-level error. The client retries on `status` 5xx and drops the item on 4xx (unknown card, unparseable rating) rather than retrying it forever — it logs the drop and moves on. A body that does not parse at all is a top-level `422`; the client discards that batch rather than re-flushing it.

## Approval queue

### GET /cards/pending
`?status=pending_review|needs_human&book_id=&chapter=`. Returns a flat list ordered by book, chapter, then `export_position`; the client renders the chapter grouping. Includes critic critique and all source highlights (a grouped unit has several) for context. `truncated` is true if any source highlight is clipped. Every card carries `book_id` and `chapter` so the client can build filter chips that round-trip back into the query params; `book` is the display title.

`counts` reports **collection-wide totals and ignores the route's filters** — Today's "N to approve" / "N need you" tiles and the Approve header must be right before any filter exists, and the approval queue requires connectivity, so a home screen cannot derive them from the list (docs/android.md, *Offline-first sync*).
```json
{
  "counts": { "pending_review": 5, "needs_human": 3 },
  "cards": [
    {
      "id": 55, "status": "needs_human", "type": "cloze",
      "front": "The {{c1::Gulf of Specification}} is the gap between intent and instructions.",
      "back": "—",
      "status_reason": "Critic: potentially ambiguous term; Writer 3 rounds unresolved.",
      "source_highlights": ["The Gulf of Specification is this gap between our intent and our instructions..."],
      "truncated": false,
      "book_id": 1, "book": "Evals for AI Engineers", "chapter": "1. Introduction"
    }
  ]
}
```

### POST /cards/{id}/approve
Optional edits: `{ "front": "...", "back": "..." }` → status `approved`, `approved_at` set, `card_state` row created (enters FSRS). Edits overwrite `front`/`back`; `original_front`/`original_back` keep the Writer's text for the Learner.
**Response**: the updated card (same shape as `PATCH /cards/{id}` below), so the client can update its cache.

### POST /cards/approve-batch
Bulk human approval, behind Approve's "Approve N ready" (issue #168). Body `{ "card_ids": [12, 15, 19] }`.

**Response**: `{ "results": [...] }` — exactly one entry per request id, **in request order**, so the client matches by position (the same contract as `POST /reviews/rate-batch`).

```json
{ "results": [
    { "card_id": 12, "ok": true,  "status": "approved" },
    { "card_id": 15, "ok": false, "error_status": 409,
      "detail": "Card 15 needs a human decision and must be opened individually." },
    { "card_id": 19, "ok": true,  "status": "approved" }
]}
```

A bad id fails only its own entry — an unknown card (404), an already-decided card (409) and a `needs_human` card (409) never fail the whole body, because a queue-clearing action must not be defeated by one stale id. Only a body that is not `{"card_ids": [...]}` is a 422; unlike `rate-batch`, whose items arrive from an offline queue and are validated individually, a malformed body here is a caller bug rather than a stale client.

**`needs_human` cards are refused by this endpoint**, not filtered by the client (hard rule 1). Each is entered individually through `POST /cards/{id}/approve` after a human reads it; server-side enforcement means no client can skip the gate.

There is deliberately no bulk reject: a rejection carries a reason that feeds the Learner, and one reason applied to N cards is not that.

### POST /cards/{id}/reject
`{ "reason": "..." }` → status `rejected`. Reasons feed the Learner.
**Response**: the updated card (same shape as `PATCH /cards/{id}` below).

## Approved-card controls

These act on cards already past the approval gate. They are deterministic, involve no LLM call, and never change `cards.status` — an edited, buried or suspended card is still `approved`. See ADR-008.

### PATCH /cards/{id}
Fix the wording of an approved card. Body carries any of `front`, `back`, `tags`; omitted fields are left alone.
```json
{ "front": "Why evaluate traces rather than individual steps?" }
```
FSRS state is **untouched** — stability, difficulty, `due` and `step` all survive, because an edit is a correction to the same retrieval task, not a new card. Sets `edited_at`. `original_front`/`original_back` still hold the Writer's text and are not affected. 409 if the card is not `approved`: edits before approval belong to `POST /cards/{id}/approve`, which is the hard-rule-1 gate.
**Response**: the updated card.
```json
{
  "id": 55, "status": "approved", "type": "cloze",
  "front": "The {{c1::Gulf of Specification}} is the gap between intent and instructions.",
  "back": "—",
  "original_front": "The {{c1::Gulf of Specification}} is the gap between intent and instructions.",
  "original_back": "—",
  "status_reason": null,
  "approved_at": "2026-09-05T19:22:00Z"
}
```

### POST /cards/{id}/bury
Hide the card for the rest of the day. Sets `suspended_until` to the next day boundary in `RECALLY_TIMEZONE`, so it clears itself with no action from the user. Use for "not right now" instead of a dishonest rating, which would corrupt `review_logs`.
**Response**: `{ "suspended_until": "2026-09-06T12:00:00Z" }` — the next day boundary in `RECALLY_TIMEZONE`, rendered as UTC like every other timestamp here.

### POST /cards/{id}/suspend
Take the card out of rotation indefinitely. Sets `suspended_until` to a far-future sentinel; only `unsuspend` clears it.
**Response**: `{ "suspended_until": "9999-12-31T00:00:00Z" }`

### POST /cards/{id}/unsuspend
Clears `suspended_until` (whether set by bury or suspend). FSRS state is unchanged and nothing is recomputed — the card simply becomes visible to `GET /reviews/due` again, due at whatever date it already held.
**Response**: `{ "suspended_until": null }`

## Decks & browsing

### GET /decks
Books with card counts, due counts, and per-book progress.
```json
{ "decks": [ { "book_id": 1, "title": "Evals for AI Engineers", "total": 48, "due": 6, "progress": 0.625, "chapters": 9, "truncated": 2 } ] }
```
`progress` is the share of the book's approved cards whose FSRS state is `review` (definition in docs/data-model.md, `card_state`); a book with no approved cards reports `0`.

`chapters` is the count of distinct chapters among those approved cards — the "48 cards · 9 chapters" on the Decks row. It is a field rather than client arithmetic because the Decks screen never fetches a book's cards; Book detail, which does, derives its own per-chapter counts from the list below. A book with no approved cards reports `0`.

`truncated` is the count of the book's clipped source highlights — the "2 TRUNCATED" badge on the Decks row. It is the one count here **not** scoped to approved cards: `truncated` is a `highlights` column and clipping is a property of the O'Reilly export, so a clipped highlight counts whether the card it produced is approved, still in the approval queue, or not yet curated at all. Counted over distinct highlights, so one highlight backing several cards counts once. A book with nothing clipped reports `0`, never `null`. The count is informational; nothing in the API or the app offers to reconstruct the lost text (AGENTS.md hard rule 7).

### GET /decks/{book_id}/cards
`?chapter=` optional filter. Browse cards per book, ordered by chapter then the source highlight's `export_position`. Each card carries `suspended_until` (null when in rotation) so the browse view can show suspended cards and offer unsuspend; unlike `/reviews/due`, this list does not filter them out.
**Response**
```json
{
  "cards": [
    {
      "id": 55, "type": "cloze",
      "front": "The {{c1::Gulf of Specification}} is the gap between intent and instructions.",
      "back": "—",
      "chapter": "1. Introduction", "tags": [],
      "suspended_until": null,
      "state": "review", "due": "2026-09-09T08:00:00Z"
    },
    {
      "id": 56, "type": "qa",
      "front": "What makes an error taxonomy useful rather than merely tidy?",
      "back": "It has to change what you fix next.",
      "chapter": "3. Error Analysis", "tags": [],
      "suspended_until": null,
      "state": "learning", "due": null
    }
  ]
}
```
`state` is the card's server-side FSRS state (`learning`, `review`, `relearning`), and `due` its `card_state.due` — both read straight from the server so the browse row can show "Due in 4h" or a date. The client **renders** `due`; it never computes one (hard rule 5, ADR-005).

`due` is `null` for a card FSRS has never scheduled. Approval writes `card_state` at `learning` step 0 due immediately (docs/data-model.md), so that stored timestamp says "available now" rather than naming an interval a review produced; the row shows the state alone rather than a date the human would misread. Once a card has been reviewed, `due` is always present.

The response carries no chapter counts: the list is complete and unpaginated, so the client groups by `chapter` and counts client-side. Grouping must preserve the server's order — chapter names are not alphabetical.

## Stats

### GET /stats
```json
{
  "streak_days": 9,
  "reviews_today": 23,
  "retention_30d": 0.87,
  "lapse_rate_by_type": { "qa": 0.11, "cloze": 0.18 },
  "lapse_rate_by_guidance_version": { "1": 0.19, "2": 0.12 },
  "curation_yield": 0.83,
  "next_due_at": "2026-09-05T13:00:00Z",
  "forecast": [ { "date": "2026-09-05", "due": 14 } ]
}
```

`lapse_rate_by_guidance_version` keys are `cards.guidance_version` as strings (`null` guidance is omitted); it is how the Learner's effect is judged, so it is the one number that has to exist before Stage B writes a v2 (PRD success metrics, roadmap step 6b). `curation_yield` is approved cards ÷ highlights ingested. `next_due_at` is the earliest *future* `card_state.due` across approved, unsuspended cards — `null` when nothing is scheduled. It is the hours-away figure `forecast`'s day granularity cannot give, behind the session summary's "Next card due in 4 hours" and Today's nothing-due line.

## Ingestion

### POST /ingest
Multipart CSV upload (same pipeline as the watcher; enables HF Spaces phase).

### GET /ingest/status
Latest `ingest_runs` row; 404 before the first ingest, so "never ingested" stays distinguishable from "ingested nothing". `units_dropped`/`highlights_dropped` are the Curator's filter output: a dropped highlight produces no card, so without them a wrongly-dropped highlight is invisible everywhere (a wrongly-*grouped* one is not — `GET /cards/pending` shows every source highlight of a unit). They are also what makes the PRD's curation-yield metric computable.
```json
{
  "filename": "30-agents-every-oreilly-annotations.csv",
  "rows_seen": 380, "rows_new": 56, "rows_updated": 0, "rows_unchanged": 322, "rows_removed": 2,
  "units_kept": 49, "units_dropped": 4, "highlights_dropped": 5,
  "cards_generated": 131, "cost_microusd": 184200,
  "started_at": "2026-09-04T09:00:00Z", "finished_at": "2026-09-04T09:06:12Z", "error": null
}
```
`rows_unchanged` is derived (`rows_seen - rows_new - rows_updated`), not a stored column. `units_kept`, `units_dropped`, `highlights_dropped`, `cards_generated` and `cost_microusd` are the pipeline's counters, filled by every run since step 2.

## Jobs

### POST /jobs/run
`{ "job": "notify" | "learner" | "optimizer" }`. Runs a scheduled job on demand. Exists so an external cron can drive scheduling on hosts where the in-process scheduler cannot stay alive (see architecture.md, phase 2).

## Devices

### POST /devices
`{ "fcm_token": "...", "platform": "android" }` — register for push.
**Response**: `{ "device_id": 3 }`. Idempotent on `fcm_token` (UNIQUE in `devices`): re-registering the same token returns the same `device_id` rather than creating a row, so the app can call this on every start and on every token refresh. The client stores `device_id` and sends it on ratings so `review_logs.device_id` is populated.

## Errors

Standard problem+json: `{ "status": 422, "detail": "..." }`.
