# ADR-002: FSRS over SM-2

**Status**: accepted
**Date**: 2026-09-04

## Context
Spaced repetition scheduling needs an algorithm. Options: SM-2 (classic Anki), FSRS (modern, used in Anki 23.10+), or delegating to Anki itself.

## Decision
Use FSRS via the `py-fsrs` library, with card state stored in our own DB (`card_state` table).

## Rationale
- FSRS models memory (stability/difficulty) and outperforms SM-2 on retention-per-review.
- Library exists — no hand-rolled scheduler.
- Keeping state in our DB enables the Learner agent to analyze full review history; syncing to Anki would forfeit that data loop.

## Consequences
- We own scheduling correctness (mitigated by using the reference library).
- `card_state` mirrors the library's `Card` fields exactly (state/step/stability/difficulty/due/last_review); FSRS 6 has no `new` state and no `reps`/`lapses` on the card, so those are derived from `review_logs`.
- The library's optional `Optimizer` fits personal parameters from the same `review_logs`; see agents.md Learner stage A.
- Learning steps make cards due within minutes; how the client handles that is ADR-005.
- Anki export could be added later if wanted.
