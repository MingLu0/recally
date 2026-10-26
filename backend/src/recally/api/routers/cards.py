"""The approval queue (docs/api-spec.md, "Approval queue").

This is one of the two human-decision entry points of the hard-rule-1 gate (the
`recally` CLI is the other): only a human decision moves `cards.status` to
`approved`/`rejected`, and the writes themselves live in `services/cards.py` as
the single implementation both entry points call — the design-invariant tests
enforce that restriction (test_design_invariants.py, `CARD_STATUS_WRITE_FILES`).

Approval is also the only entry into FSRS scheduling: it creates the `card_state`
row (docs/data-model.md). Rejection creates nothing — the rule works in one
direction only.

The ADR-008 card controls (`PATCH`, bury, suspend, unsuspend) sit in the same
module because they share the card lookup. They never touch `cards.status`,
never recompute FSRS state and never make an LLM call: an edit is a correction
to the same retrieval task, and suspension is exclusion at the due query, not a
state change (ADR-005). `PATCH` edits both approved cards (ADR-008) and queued
cards — a pre-approval save changes wording only, never the decision state
(ADR-018, issue #287); bury, suspend and unsuspend remain approved-only.
"""

from typing import Literal

from fastapi import APIRouter
from sqlalchemy.orm import Session

from recally.api.auth import ApiKeyGuard
from recally.api.deps import ContainerDep, SessionDep
from recally.api.errors import ProblemDetail
from recally.models import Card
from recally.models.base import utc_now
from recally.schemas.cards import (
    ApproveBatchRequest,
    ApproveBatchResponse,
    ApproveBatchResultResponse,
    ApproveCardRequest,
    CardResponse,
    PatchCardRequest,
    PendingCardResponse,
    PendingCardsResponse,
    PendingCounts,
    RejectCardRequest,
    SuspensionResponse,
)
from recally.services.cards import (
    QUEUE_STATUSES,
    bury,
    list_pending_cards,
    pending_counts,
    record_approval,
    record_rejection,
    suspend,
    unsuspend,
)

router = APIRouter(prefix="/cards", tags=["cards"], dependencies=[ApiKeyGuard])


@router.get("/pending", response_model=PendingCardsResponse)
def get_pending_cards(
    session: SessionDep,
    status: Literal["pending_review", "needs_human"] | None = None,
    book_id: int | None = None,
    chapter: str | None = None,
) -> PendingCardsResponse:
    """The flat review queue, ordered by book, chapter, then `export_position`.

    `counts` is collection-wide on purpose (issue #132): it ignores the
    filters above, because the home-screen tiles must be right before any
    filter exists.
    """
    statuses = (status,) if status is not None else QUEUE_STATUSES
    pending = list_pending_cards(session, statuses=statuses, book_id=book_id, chapter=chapter)
    return PendingCardsResponse(
        cards=[PendingCardResponse.from_pending(card) for card in pending],
        counts=PendingCounts(**pending_counts(session)),
    )


@router.post("/{card_id}/approve", response_model=CardResponse)
def approve_card(
    card_id: int, session: SessionDep, body: ApproveCardRequest | None = None
) -> CardResponse:
    """Human approval, with optional edits (docs/api-spec.md).

    Edits overwrite `front`/`back`; `original_front`/`original_back` keep the
    Writer's text for the Learner. Approval enters the card into FSRS: the
    `card_state` row is created `learning` at step 0, due at the approval time.
    """
    card = _queued_card(session, card_id)
    if body is not None:
        if body.front is not None:
            card.front = body.front
        if body.back is not None:
            card.back = body.back
    record_approval(session, card)
    session.commit()
    return CardResponse.from_card(card)


@router.post("/approve-batch", response_model=ApproveBatchResponse)
def approve_batch(body: ApproveBatchRequest, session: SessionDep) -> ApproveBatchResponse:
    """Bulk human approval (issue #168).

    One entry per request id, in request order, so the client matches by
    position. A bad id fails only its own entry: an unknown card, an
    already-decided card and a `needs_human` card are per-item failures, never
    a whole-body error — a queue-clearing action must not be defeated by one
    stale id.

    **`needs_human` is refused here, not by the caller** (hard rule 1;
    design-system.md, "Constraints this design must not break"). Those cards
    are opened individually, and putting the exclusion server-side means no
    client can skip it.
    """
    results: list[ApproveBatchResultResponse] = []
    for card_id in body.card_ids:
        card = session.get(Card, card_id)
        if card is None:
            results.append(
                ApproveBatchResultResponse(
                    card_id=card_id,
                    ok=False,
                    error_status=404,
                    detail=f"Card {card_id} not found.",
                )
            )
            continue
        if card.status == "needs_human":
            results.append(
                ApproveBatchResultResponse(
                    card_id=card_id,
                    ok=False,
                    error_status=409,
                    detail=(
                        f"Card {card_id} needs a human decision and must be opened individually."
                    ),
                )
            )
            continue
        if card.status not in QUEUE_STATUSES:
            results.append(
                ApproveBatchResultResponse(
                    card_id=card_id,
                    ok=False,
                    error_status=409,
                    detail=f"Card {card_id} is not awaiting review (status: {card.status}).",
                )
            )
            continue
        record_approval(session, card)
        results.append(ApproveBatchResultResponse(card_id=card_id, ok=True, status=card.status))
    session.commit()
    return ApproveBatchResponse(results=results)


@router.post("/{card_id}/reject", response_model=CardResponse)
def reject_card(card_id: int, session: SessionDep, body: RejectCardRequest) -> CardResponse:
    """Human rejection; the reason lands in `status_reason` and feeds the Learner."""
    card = _queued_card(session, card_id)
    record_rejection(session, card, reason=body.reason)
    session.commit()
    return CardResponse.from_card(card)


@router.patch("/{card_id}", response_model=CardResponse)
def patch_card(card_id: int, body: PatchCardRequest, session: SessionDep) -> CardResponse:
    """Save a wording edit, with or without approval (docs/api-spec.md, "Card edits").

    On an `approved` card this is the ADR-008 control: FSRS state is untouched —
    stability, difficulty, `due` and `step` all survive, because an edit is a
    correction to the same retrieval task, not a new card — and `edited_at` is
    set so the Learner can segment hand-fixed cards.

    On a queued card (`pending_review`/`needs_human`, ADR-018) the same payload
    saves the edit without deciding anything: `status`, `approved_at`,
    `status_reason`, `original_front`/`original_back` and `edited_at` all
    survive, and no `card_state` row is created — only Approve enters FSRS
    (hard rule 1).
    """
    card = _editable_card(session, card_id)
    if body.front is not None:
        card.front = body.front
    if body.back is not None:
        card.back = body.back
    if body.tags is not None:
        card.tags = body.tags
    if card.status == "approved":
        card.edited_at = utc_now()
    session.commit()
    return CardResponse.from_card(card)


@router.post("/{card_id}/bury", response_model=SuspensionResponse)
def bury_card(card_id: int, session: SessionDep, container: ContainerDep) -> SuspensionResponse:
    """Hide the card for the rest of the local day; it clears itself at the boundary."""
    card = _approved_card(session, card_id)
    boundary = bury(card, now=utc_now(), timezone_name=container.settings.timezone)
    session.commit()
    return SuspensionResponse(suspended_until=boundary)


@router.post("/{card_id}/suspend", response_model=SuspensionResponse)
def suspend_card(card_id: int, session: SessionDep) -> SuspensionResponse:
    """Take the card out of rotation indefinitely; only `unsuspend` clears it."""
    card = _approved_card(session, card_id)
    sentinel = suspend(card)
    session.commit()
    return SuspensionResponse(suspended_until=sentinel)


@router.post("/{card_id}/unsuspend", response_model=SuspensionResponse)
def unsuspend_card(card_id: int, session: SessionDep) -> SuspensionResponse:
    """Clear either a bury or a suspend. Nothing is recomputed: the card is due at
    whatever date it already held (ADR-005, ADR-008)."""
    card = _approved_card(session, card_id)
    unsuspend(card)
    session.commit()
    return SuspensionResponse(suspended_until=None)


def _queued_card(session: Session, card_id: int) -> Card:
    """The card, or the problem+json error: 404 unknown, 409 no longer in the queue.

    The 409 keeps the gate honest: only a card waiting for a decision can receive
    one, so an approve/reject can never silently rewrite a decided card.
    """
    card = session.get(Card, card_id)
    if card is None:
        raise ProblemDetail(status=404, detail=f"Card {card_id} not found.")
    if card.status not in QUEUE_STATUSES:
        raise ProblemDetail(
            status=409,
            detail=f"Card {card_id} is not awaiting review (status: {card.status}).",
        )
    return card


def _editable_card(session: Session, card_id: int) -> Card:
    """The card, or the problem+json error: 404 unknown, 409 already decided.

    `PATCH` saves wording for a queued card without approving it (ADR-018) and
    keeps the ADR-008 edit on an `approved` card; a decided card such as
    `rejected` is past both, so only the queue and `approved` are editable.
    """
    card = session.get(Card, card_id)
    if card is None:
        raise ProblemDetail(status=404, detail=f"Card {card_id} not found.")
    if card.status not in QUEUE_STATUSES and card.status != "approved":
        raise ProblemDetail(
            status=409,
            detail=f"Card {card_id} is not editable (status: {card.status}).",
        )
    return card


def _approved_card(session: Session, card_id: int) -> Card:
    """The card, or the problem+json error: 404 unknown, 409 not past the gate.

    The ADR-008 suspension controls act only on `approved` cards: a card that
    has not entered scheduling cannot be excluded from it, and a decided card
    is past the point of deferral.
    """
    card = session.get(Card, card_id)
    if card is None:
        raise ProblemDetail(status=404, detail=f"Card {card_id} not found.")
    if card.status != "approved":
        raise ProblemDetail(
            status=409,
            detail=f"Card {card_id} is not approved (status: {card.status}).",
        )
    return card
