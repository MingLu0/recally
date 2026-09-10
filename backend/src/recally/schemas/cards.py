"""Approval-queue payloads (docs/api-spec.md, "Approval queue")."""

from pydantic import BaseModel, field_validator

from recally.models import Card
from recally.schemas.types import UtcDatetime
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


class PendingCounts(BaseModel):
    """Collection-wide queue totals for the home-screen tiles (docs/api-spec.md).

    They deliberately ignore the route's `status`/`book_id`/`chapter` filters:
    Today must render the right numbers before any filter exists (issue #132).
    """

    pending_review: int
    needs_human: int


class PendingCardsResponse(BaseModel):
    cards: list[PendingCardResponse]
    counts: PendingCounts


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


class PatchCardRequest(BaseModel):
    """A post-approval wording fix (ADR-008); omitted fields are left alone."""

    front: str | None = None
    back: str | None = None
    tags: list[str] | None = None

    @field_validator("front", "back")
    @classmethod
    def not_blank(cls, value: str | None) -> str | None:
        if value is not None and not value.strip():
            raise ValueError("must not be blank")
        return value


class SuspensionResponse(BaseModel):
    """The new `suspended_until` after a bury, suspend or unsuspend (null = in rotation)."""

    suspended_until: UtcDatetime | None


class CardResponse(BaseModel):
    """The card after an approve/reject/edit, so the client can update its cache."""

    id: int
    status: str
    type: str
    front: str
    back: str
    original_front: str
    original_back: str
    status_reason: str | None
    approved_at: UtcDatetime | None
    # Set by a post-approval edit (ADR-008); the Learner segments these cards.
    edited_at: UtcDatetime | None

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
            edited_at=card.edited_at,
        )


class ApproveBatchRequest(BaseModel):
    """The bulk-approve body (issue #168). `card_ids` is required and typed —
    unlike `rate-batch`, whose items are validated individually because they
    arrive from an offline queue that must not be rejected wholesale. Here a
    malformed body is a caller bug, not a stale client, so it is a 422."""

    card_ids: list[int]


class ApproveBatchResultResponse(BaseModel):
    """One card's outcome. `ok` false carries the problem+json `status` and
    `detail` the single-card route would have raised, so the client can tell a
    `needs_human` refusal (409) from an unknown id (404)."""

    card_id: int
    ok: bool
    status: str | None = None
    error_status: int | None = None
    detail: str | None = None


class ApproveBatchResponse(BaseModel):
    """Exactly one entry per request item, in request order — the client matches
    by position, as it does for `rate-batch`."""

    results: list[ApproveBatchResultResponse]
