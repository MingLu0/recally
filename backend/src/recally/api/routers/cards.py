"""The approval queue (docs/api-spec.md, "Approval queue").

This is the hard-rule-1 gate: only here (a human decision) and in `pipeline.py`
does any code assign `cards.status` — the design-invariant tests enforce it
(test_design_invariants.py, `CARD_STATUS_WRITE_FILES`), which is why the mutations
live in this module rather than in a service.

Approval is also the only entry into FSRS scheduling: it creates the `card_state`
row (docs/data-model.md). Rejection creates nothing — the rule works in one
direction only.

The ADR-008 approved-card controls (`PATCH`, bury, suspend, unsuspend) sit in the
same module because they share the card lookup. They never touch `cards.status`,
never recompute FSRS state and never make an LLM call: an edit is a correction to
the same retrieval task, and suspension is exclusion at the due query, not a state
change (ADR-005).
"""

from typing import Literal

from fastapi import APIRouter
from sqlalchemy.orm import Session

from recally.api.auth import ApiKeyGuard
from recally.api.deps import ContainerDep, SessionDep
from recally.api.errors import ProblemDetail
from recally.models import Card
from recally.models.base import utc_now
from recally.scheduling.fsrs import new_card_state
from recally.schemas.cards import (
    ApproveCardRequest,
    CardResponse,
    PatchCardRequest,
    PendingCardResponse,
    PendingCardsResponse,
    RejectCardRequest,
    SuspensionResponse,
)
from recally.services.cards import QUEUE_STATUSES, bury, list_pending_cards, suspend, unsuspend

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
    Writer's text for the Learner. Approval enters the card into FSRS: the
    `card_state` row is created `learning` at step 0, due at the approval time.
    """
    card = _queued_card(session, card_id)
    if body is not None:
        if body.front is not None:
            card.front = body.front
        if body.back is not None:
            card.back = body.back
    card.status = "approved"
    approved_at = utc_now()
    card.approved_at = approved_at
    session.add(new_card_state(card_id=card.id, due=approved_at, user_id=card.user_id))
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


@router.patch("/{card_id}", response_model=CardResponse)
def patch_card(card_id: int, body: PatchCardRequest, session: SessionDep) -> CardResponse:
    """Fix the wording of an approved card (docs/api-spec.md, "Approved-card controls").

    FSRS state is untouched — stability, difficulty, `due` and `step` all survive,
    because an edit is a correction to the same retrieval task, not a new card
    (ADR-008). `edited_at` is set so the Learner can segment hand-fixed cards;
    `original_front`/`original_back` keep the Writer's text.
    """
    card = _approved_card(session, card_id)
    if body.front is not None:
        card.front = body.front
    if body.back is not None:
        card.back = body.back
    if body.tags is not None:
        card.tags = body.tags
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


def _approved_card(session: Session, card_id: int) -> Card:
    """The card, or the problem+json error: 404 unknown, 409 not past the gate.

    The ADR-008 controls act only on `approved` cards: edits before approval belong
    to `POST /cards/{id}/approve` (hard rule 1 — two edit paths into the same field
    would blur the gate), and suspension is meaningless for a card that has not
    entered scheduling.
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
