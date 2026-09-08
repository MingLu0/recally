"""Review payloads (docs/api-spec.md, "Reviews")."""

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from recally.services.reviews import DueCard, RatingResult


class DueCardResponse(BaseModel):
    """One card in the due list. `step` is null in `review`; `due` lets a cached
    queue re-sort offline without a refetch (docs/api-spec.md)."""

    id: int
    unit_id: int
    type: str
    front: str
    back: str
    book_id: int
    book: str
    chapter: str | None
    tags: list[str]
    state: str
    step: int | None
    due: datetime

    @classmethod
    def from_due_card(cls, card: DueCard) -> "DueCardResponse":
        return cls(
            id=card.id,
            unit_id=card.unit_id,
            type=card.type,
            front=card.front,
            back=card.back,
            book_id=card.book_id,
            book=card.book,
            chapter=card.chapter,
            tags=card.tags,
            state=card.state,
            step=card.step,
            due=card.due,
        )


class DueListResponse(BaseModel):
    """Due cards plus today's new-card allotment, and the learning steps in effect
    so the client can re-queue Again/Hard cards inside the session (ADR-005)."""

    due_count: int
    new_count: int
    learning_steps_minutes: list[int | float]
    cards: list[DueCardResponse]


class RateCardRequest(BaseModel):
    """One rating. `rated_at` is the client timestamp — required so offline ratings
    replay in order (hard rule 5); the server never substitutes its own clock."""

    rating: int = Field(ge=1, le=4)
    response_ms: int = Field(ge=0)
    rated_at: datetime
    # The id from `POST /devices`; omitted by the CLI (docs/api-spec.md).
    device_id: int | None = None


class RateResultResponse(BaseModel):
    """The card's position after a rating. `lapsed` is true only when this rating
    moved the card out of `review` into `relearning` — the client cannot derive it."""

    card_id: int
    rated_at: datetime
    next_due: datetime
    state: str
    step: int | None
    lapsed: bool
    duplicate: bool

    @classmethod
    def from_result(cls, result: RatingResult) -> "RateResultResponse":
        return cls(
            card_id=result.card_id,
            rated_at=result.rated_at,
            next_due=result.next_due,
            state=result.state,
            step=result.step,
            lapsed=result.lapsed,
            duplicate=result.duplicate,
        )


class RateBatchItem(RateCardRequest):
    """A batch rating: the single-rate body plus its `card_id`."""

    card_id: int


class RateBatchRequest(BaseModel):
    """The sync-queue flush. `ratings` is intentionally untyped here: items are
    validated individually (docs/api-spec.md), so one malformed item cannot fail
    the whole body — only a body that is not `{"ratings": [...]}` is a 422."""

    ratings: list[Any]


class RateBatchResultItem(BaseModel):
    """One batch item's verdict. Success carries the rate response fields; failure
    carries the same `status`/`detail` pair a top-level error would."""

    card_id: int | None = None
    rated_at: datetime | None = None
    ok: bool
    next_due: datetime | None = None
    state: str | None = None
    step: int | None = None
    lapsed: bool | None = None
    duplicate: bool | None = None
    status: int | None = None
    detail: str | None = None


class RateBatchResponse(BaseModel):
    """Exactly one entry per request item, in request order — the client matches
    results to its queue by position."""

    results: list[RateBatchResultItem]
