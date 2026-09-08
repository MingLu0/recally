# ADR-008: Post-approval card edits keep FSRS state; suspend and bury are separate

**Status**: accepted
**Date**: 2026-09-06

## Context

Once a card is `approved` it has no remaining controls. `POST /cards/{id}/approve` accepts edits, but only at the moment of approval; after that `api-spec.md` offers nothing that changes a card's text or takes it out of rotation. Two cases have no answer:

1. **A wording problem noticed weeks in.** The only lever is rating the card Again until the Learner flags it as a leech and proposes a rewrite through the full Writer ⇄ Critic → approval path (`agents.md`, Learner stage B). That is the right machinery for a systematic quality pattern and far too heavy for a typo.
2. **"Not this one today."** No way to defer a card without rating it, so an unwanted card either blocks the session queue or gets a dishonest rating that corrupts `review_logs` — the training data for both FSRS optimisation and Learner stage B.

Neither is listed in the PRD non-goals, so both read as omissions rather than decisions.

For edits, the question is what happens to `card_state`:

1. Reset FSRS state on edit — the edited card re-enters learning at step 0.
2. Keep FSRS state and record that an edit happened.
3. Let the caller choose per edit.

For deferral, the question is whether one control or two:

1. Suspend only — indefinite, manual undo.
2. Bury only — transient, auto-clears at the day boundary.
3. Both.

## Decision

**Edits keep FSRS state.** `PATCH /cards/{id}` updates `front`/`back`/`tags` on an `approved` card and leaves `card_state` untouched — stability, difficulty, due date and step all survive. The card records `edited_at`; `original_front`/`original_back` continue to hold the Writer's text and are never touched by a post-approval edit.

**Suspend and bury are separate controls on one column.** `cards.suspended_until datetime, nullable` backs both: bury sets it to the next day boundary in `RECALLY_TIMEZONE`, suspend sets it to a sentinel far-future value, and unsuspend/unbury clears it to NULL. `GET /reviews/due` excludes any card whose `suspended_until` is in the future. FSRS state is untouched in every case.

Both operations are deterministic and human-triggered. Neither involves an LLM call, and neither changes `cards.status` — an edited or suspended card is still `approved`.

## Rationale

- **Edits preserve state because an edit is usually a correction, not a new card.** Fixing "recieve" on a card with four months of history is the same retrieval task; resetting it would discard real scheduling information and make the user hesitate before fixing anything. The case where an edit genuinely changes the question is already served by the leech-rewrite path, which creates a new card with fresh state and supersedes the old one.
- **Per-edit choice was rejected as a decision at the wrong moment.** The flag would be set while fixing a word, which is exactly when the user has least appetite for a scheduling question. If reset-on-edit turns out to be needed, adding the flag later is additive.
- **`edited_at` keeps the Learner honest.** Lapse rate is attributed to `guidance_version` (PRD success metrics). A silent post-approval edit would let a hand-fixed card flatter the guidance version that wrote the flawed original; the timestamp lets the Learner exclude or segment those cards.
- **Bury and suspend are different needs, not two sizes of the same one.** Bury is transient and self-clearing — the right answer to "not right now", and it requires no memory of having done it. Suspend is indefinite — the right answer to "not until I have read that chapter again". Offering only one forces the other case into a dishonest rating.
- **One nullable column serves both** because the difference between them is only the value, and the read path is one predicate. Two boolean columns would need a consistency rule between them.
- **Excluding at the query, not by mutating state,** keeps the server the single FSRS authority (ADR-005). A suspended card's due date keeps advancing conceptually; nothing is recomputed on unsuspend.

## Consequences

- `cards` gains `edited_at datetime, nullable` and `suspended_until datetime, nullable`; both are Alembic migrations under `render_as_batch=True` (ADR-004).
- `GET /reviews/due` gains a `suspended_until IS NULL OR suspended_until <= now` predicate. Every other card read (`/decks/{id}/cards`, `/cards/pending`) is unaffected — suspended cards are still browsable, and the browse view is where unsuspend is reached.
- Bury depends on `RECALLY_TIMEZONE` for the day boundary, matching the existing one-push-per-day and streak rules (`config.md`).
- The notifier must not announce suspended or buried cards; it reads the same due query, so this follows without a separate rule.
- `PATCH` is deliberately not offered for `pending_review` / `needs_human` cards — edits there belong to `POST /cards/{id}/approve`, which is the approval gate (hard rule 1). Two edit paths into the same field would blur it.
- Reversible: dropping both columns and the four endpoints restores current behaviour. No agent, prompt or pipeline code is involved.
