"""Approval-queue payloads (docs/api-spec.md, "Approval queue")."""

from datetime import datetime

from pydantic import BaseModel, field_validator

from recally.models import Card
from recally.services.cards import PendingCard


class PendingCardResponse(BaseModel):
    """One card in the queue, in the shape docs/api-spec.md publishes."""

    id: int
    status: str
    type: str
    front: str
    back: str
    status_reason: str | None
    source_highlights: list[str]
    truncated: bool
    book_id: int
    book: str
    chapter: str | None

    @classmethod
    def from_pending(cls, card: PendingCard) -> "PendingCardResponse":
        return cls(
            id=card.id,
            status=card.status,
            type=card.type,
            front=card.front,
            back=card.back,
            status_reason=card.status_reason,
            source_highlights=card.source_highlights,
            truncated=card.truncated,
            book_id=card.book_id,
            book=card.book,
            chapter=card.chapter,
        )


class PendingCardsResponse(BaseModel):
    cards: list[PendingCardResponse]


class ApproveCardRequest(BaseModel):
    """Optional edits applied at approval time; omitted fields keep the Writer text."""

    front: str | None = None
    back: str | None = None

    @field_validator("front", "back")
    @classmethod
    def not_blank(cls, value: str | None) -> str | None:
        if value is not None and not value.strip():
            raise ValueError("must not be blank")
        return value


class RejectCardRequest(BaseModel):
    """The human rejection reason; it feeds the Learner, so it cannot be empty."""

    reason: str

    @field_validator("reason")
    @classmethod
    def not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be blank")
        return value


class CardResponse(BaseModel):
    """The card after an approve/reject, so the client can update its cache."""

    id: int
    status: str
    type: str
    front: str
    back: str
    original_front: str
    original_back: str
    status_reason: str | None
    approved_at: datetime | None

    @classmethod
    def from_card(cls, card: Card) -> "CardResponse":
        return cls(
            id=card.id,
            status=card.status,
            type=card.type,
            front=card.front,
            back=card.back,
            original_front=card.original_front,
            original_back=card.original_back,
            status_reason=card.status_reason,
            approved_at=card.approved_at,
        )
