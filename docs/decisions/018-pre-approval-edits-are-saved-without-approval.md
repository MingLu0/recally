# ADR-018: Pre-approval edits are saved without approving the card

**Status**: accepted
**Date**: 2026-10-26

## Context

The approval queue's inline editor had one action, **Save & approve**: saving
wording also approved the card, removed it from the queue and created its FSRS
state. Saving and deciding are separate intents — the user must be able to save
an edit, keep reviewing the card in the queue, and approve it later as a
distinct action.

ADR-008 deliberately left `PATCH /cards/{id}` off queued cards: "edits before
approval belong to `POST /cards/{id}/approve`… two edit paths into the same
field would blur the gate." That reasoning conflated the edit path with the
decision path. Hard rule 1 is about what moves a card to `approved` — the gate
is `record_approval`, not which endpoint touched `front`. With no save-only
path, the UI was forced to fuse the two intents.

## Decision

**Save only saves.** `PATCH /cards/{id}` now accepts cards in `pending_review`
and `needs_human` as well as `approved`. For a queued card it updates only the
supplied editable fields (`front`/`back`/`tags`) and preserves `status`,
`approved_at`, `status_reason`, `original_front`/`original_back` and `edited_at`.
It creates no `card_state` row and makes no LLM call. On an `approved` card the
ADR-008 behaviour is unchanged, including setting `edited_at` and leaving every
FSRS column untouched.

**Approve only approves.** Only `POST /cards/{id}/approve` moves a queued card
to `approved`, sets `approved_at` and creates the `card_state` row (hard rule
1). The endpoint keeps its optional `front`/`back` payload for existing
callers, but the Android approval queue stops using it: Save goes through
`PATCH`, then the per-card Approve approves the saved text with a bodyless
call. `edited_at` remains the distinct post-approval edit signal — a
pre-approval save does not set it.

Bury, suspend and unsuspend remain approved-only. Decided cards other than
`approved` (for example `rejected`) remain 409 for `PATCH`.

## Rationale

- **Saving and deciding are different questions.** "This wording is better" says
  nothing about "this card is worth reviewing", and fusing them meant every
  wording fix was also an irreversible scheduling decision. The separate Save
  action matches how the user actually reviews: fix now, decide when sure.
- **The gate was never the edit path.** Hard rule 1 holds because only
  `record_approval` transitions `cards.status` to `approved` and creates
  `card_state`; both human entry points (HTTP and CLI) call it. Widening
  `PATCH` does not add a second path into FSRS — it removes a second path into
  the approval decision.
- **`edited_at` stays post-approval.** The Learner segments hand-fixed cards so
  they do not flatter the `guidance_version` that wrote the flawed original.
  Pre-approval wording fixes are a normal part of reviewing, not a signal that
  an approved card lapsed badly enough to be hand-fixed later; folding them
  into `edited_at` would blur exactly that signal. The original-versus-current
  text diff (`original_front`/`original_back` vs `front`/`back`) already records
  pre-approval edits for the Learner.
- **Keeping the approve-with-edits payload** costs one optional body field and
  preserves the API contract other callers may rely on; removing it is scoped
  out, not endorsed.

## Consequences

- ADR-008 is amended: its "PATCH is deliberately not offered for queued cards"
  consequence no longer holds; its FSRS-preserving edit semantics for approved
  cards are untouched.
- `docs/api-spec.md`, `docs/data-model.md`, `docs/android.md` and
  `docs/agents.md` describe Save as distinct from Approve.
- The Android inline editor's button reads **Save**; the approval repository
  gains a `saveCard` path over `PATCH /cards/{id}` and `approveCard` loses its
  edit parameters.
- The backend tests asserting 409 on `PATCH` of queued cards are replaced by
  tests asserting the save-without-approve contract (`test_card_controls.py`).
