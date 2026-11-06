# ADR-019: Rescue dropped units from the Approve screen

**Status**: accepted
**Date**: 2026-11-06

## Context

A highlight the Curator drops produces no card, so it was visible only as a count on
`GET /ingest/status` (`units_dropped`, `highlights_dropped`). Every drop is already a
`curated_units` row with `decision='drop'`, a `reason`, and its sources through
`curated_unit_highlights`. The production DB held 19 dropped units and eval-r8 37, and
some of them should have been cards. There was no way to act on that, and no record
that the Curator got it wrong (issue #365).

Fixing this adds two things the system did not have:

- **A second way for a card to come into existence.** Until now every card was written
  by the pipeline from a `keep` unit, in the same run that curated it (or as a leech
  rewrite of an existing card).
- **A human-authored card in a table measured as Writer output.** `cards.model`,
  `generation_rounds`, `guidance_version` and `original_*` all describe what the Writer
  produced, and the Learner attributes lapse rates to `guidance_version`.

## Decision

1. **The Curator's decision is never rewritten.** `curated_units.decision` stays `drop`
   on a unit the human rescues — it is the Curator's output and eval truth. The human's
   answer is two new nullable columns: `drop_resolution` (`dismissed` / `rescued`) and
   `drop_resolved_at`. Historical drops read NULL, i.e. unresolved.
2. **Three actions on an unresolved drop**, under `/units` (docs/api-spec.md, "Dropped
   units"): dismiss (the Curator was right), generate (the normal Writer ⇄ Critic loop
   over the unit), or a hand-written card. Both rescues set `drop_resolution='rescued'`,
   the Curator false-negative signal. Nothing reads that signal automatically yet; it
   is recorded for evals and later Curator tuning.
3. **Generation reuses `_resolve_unit_cards` unchanged** — the 3-round cap (hard rule
   9), the quote and blank checks, `MAX_CARDS_PER_UNIT`, the split-off path. It differs
   only around the loop: `AUTO_APPROVE_ROUND1_ACCEPT` is forced off for the call (hard
   rule 1 — the human asked for these cards and still decides them); the Curator's drop
   reason is not sent to the Writer (it would argue against the card); and `llm_calls`
   rows carry the unit's id with `ingest_run_id=NULL`, like a leech rewrite. The cards
   and the resolution commit in one transaction, so a failed loop leaves the unit
   unresolved and listed.
4. **A hand-written card is marked as no Writer's work**: `model='human'`,
   `generation_rounds=0`, `guidance_version=NULL`, `cost_microusd=0`, `original_*` =
   the typed text, `status='pending_review'`. It still passes through the approval
   queue (hard rule 1). The Learner excludes `model='human'` cards from both
   guidance-version lapse buckets; they count in every other bucket.

## Rationale

- Keeping `decision` intact is what makes the false-negative signal measurable at all:
  a rewritten `decision` would erase the very disagreement the rescue records, and
  every eval comparing Curator output against the DB would silently improve.
- Reusing the loop rather than writing a rescue-specific generation path is the same
  choice docs/agents.md §7 makes for leech rewrites — one generation path, so the
  round cap and the post-accept checks cannot drift between two copies.
- A hand-written card in `cards` (rather than a separate table) lets the queue, the
  approval gate, FSRS and Decks treat it like any other card. The cost is that
  Writer-quality analysis must now filter on `model`; `model='human'` is the one
  marker, and the Learner is the one current consumer that needs it.
- `generate` answers synchronously, like `POST /ingest`, so the client reports a real
  result; a background job would need polling and a second state machine for one
  user's occasional taps.

## Consequences

- `curated_units` gains `drop_resolution` and `drop_resolved_at` (Alembic migration,
  `render_as_batch=True`, no backfill).
- Any analysis of Writer output over `cards` — eval scoring, yield, approval-time edit
  rates — must exclude `model='human'` rows or report them separately. The Learner's
  guidance attribution does so from this change; its approval-edit and rejection inputs
  include them, which is harmless (a typed card's `original_*` equals what was typed).
- `pipeline.py` is no longer the only writer of `curated_units`: dismiss and the
  hand-written card set `drop_resolution` through `services/units.py`.
- No undo for a dismiss, no re-drop of a rescued unit, no bulk action and no Curator
  change ship with this; each would be a new decision.
