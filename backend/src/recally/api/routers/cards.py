"""The approval queue (docs/api-spec.md, "Approval queue").

This is the hard-rule-1 gate: only here (a human decision) and in `pipeline.py`
does any code assign `cards.status` — the design-invariant tests enforce it
(test_design_invariants.py, `CARD_STATUS_WRITE_FILES`), which is why the mutations
live in this module rather than in a service.

`card_state` creation on approve is deliberately absent: it belongs to roadmap
step 3 (FSRS), per issue #35. This ticket sets `status` and `approved_at` only.
"""

from typing import Literal

from fastapi import APIRouter
from sqlalchemy.orm import Session

from recally.api.auth import ApiKeyGuard
from recally.api.deps import SessionDep
from recally.api.errors import ProblemDetail
from recally.models import Card
from recally.models.base import utc_now
from recally.schemas.cards import (
    ApproveCardRequest,
    CardResponse,
    PendingCardResponse,
    PendingCardsResponse,
    RejectCardRequest,
)
from recally.services.cards import QUEUE_STATUSES, list_pending_cards

router = APIRouter(prefix="/cards", tags=["cards"], dependencies=[ApiKeyGuard])


@router.get("/pending", response_model=PendingCardsResponse)
def get_pending_cards(
    session: SessionDep,
    status: Literal["pending_review", "needs_human"] | None = None,
    book_id: int | None = None,
    chapter: str | None = None,
) -> PendingCardsResponse:
    """The flat review queue, ordered by book, chapter, then `export_position`."""
    statuses = (status,) if status is not None else QUEUE_STATUSES
    pending = list_pending_cards(session, statuses=statuses, book_id=book_id, chapter=chapter)
    return PendingCardsResponse(cards=[PendingCardResponse.from_pending(card) for card in pending])


@router.post("/{card_id}/approve", response_model=CardResponse)
def approve_card(
    card_id: int, session: SessionDep, body: ApproveCardRequest | None = None
) -> CardResponse:
    """Human approval, with optional edits (docs/api-spec.md).

    Edits overwrite `front`/`back`; `original_front`/`original_back` keep the
    Writer's text for the Learner. `card_state` (FSRS entry) arrives in step 3.
    """
    card = _queued_card(session, card_id)
    if body is not None:
        if body.front is not None:
            card.front = body.front
        if body.back is not None:
            card.back = body.back
    card.status = "approved"
    card.approved_at = utc_now()
    session.commit()
    return CardResponse.from_card(card)


@router.post("/{card_id}/reject", response_model=CardResponse)
def reject_card(card_id: int, session: SessionDep, body: RejectCardRequest) -> CardResponse:
    """Human rejection; the reason lands in `status_reason` and feeds the Learner."""
    card = _queued_card(session, card_id)
    card.status = "rejected"
    card.status_reason = body.reason
    session.commit()
    return CardResponse.from_card(card)


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
