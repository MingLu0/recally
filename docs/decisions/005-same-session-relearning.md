# ADR-005: Same-session relearning is client-scheduled, server-scored

**Status**: accepted
**Date**: 2026-09-04

## Context
`py-fsrs` 6.x uses learning steps (default 1 and 10 minutes). A card rated Again is due again one minute later, inside the same review session. The API design (`GET /reviews/due` once, rate each card once) and the offline-first Android client had no way to show a card twice in one session. Options:

1. Run FSRS on the client too (Kotlin port), keep two schedulers in sync.
2. Client asks the server for the next card after every rating (no offline).
3. Client re-queues Again/Hard cards locally using the step intervals; server remains the only FSRS instance and replays ratings by client timestamp.

## Decision
Option 3. `GET /reviews/due` returns `learning_steps_minutes`. The client re-shows a card rated Again/Hard after that many minutes, or at the end of the queue if the session ends sooner. Every rating is posted with its client `rated_at`; the server calls `Scheduler.review_card(card, rating, review_datetime=rated_at)` and, if a rating arrives out of order, recomputes the card from its full `review_logs`.

## Rationale
- One FSRS implementation to keep correct (ADR-002 already accepted owning scheduling correctness; two copies would double that).
- Offline review keeps working; the client only needs a timer, not an algorithm.
- Learning-step timing is approximate by nature; a client-side re-queue that is a minute off has no effect on long-term scheduling because the server scores from the true timestamps.

## Consequences
- `review_logs.rated_at` must be the client timestamp and (`card_id`, `rated_at`) must be unique so retries are idempotent.
- The client's in-session view can disagree with server state for a few minutes; it is corrected on the next `GET /reviews/due`.
- If learning steps are changed server-side, clients pick it up on the next due fetch.
