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
- Anki export could be added later if wanted.
