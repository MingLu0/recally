"""Dropped-unit payloads (docs/api-spec.md, "Dropped units"; #365)."""

from typing import Literal

from pydantic import BaseModel, model_validator

from recally.schemas.cards import PendingCardResponse
from recally.schemas.types import UtcDatetime
from recally.services.units import DroppedUnit, validate_hand_written_card


class DroppedUnitResponse(BaseModel):
    """One Curator drop with every source highlight; `drop_resolution` is null
    while it is listed, and set on the dismiss response."""

    id: int
    reason: str | None
    curated_text: str
    source_highlights: list[str]
    truncated: bool
    book_id: int
    book: str
    chapter: str | None
    created_at: UtcDatetime
    drop_resolution: str | None
    drop_resolved_at: UtcDatetime | None

    @classmethod
    def from_dropped(cls, unit: DroppedUnit) -> "DroppedUnitResponse":
        return cls(
            id=unit.id,
            reason=unit.reason,
            curated_text=unit.curated_text,
            source_highlights=unit.source_highlights,
            truncated=unit.truncated,
            book_id=unit.book_id,
            book=unit.book,
            chapter=unit.chapter,
            created_at=unit.created_at,
            drop_resolution=unit.drop_resolution,
            drop_resolved_at=unit.drop_resolved_at,
        )


class DroppedUnitsResponse(BaseModel):
    """`count` is the collection-wide unresolved total and ignores the filters,
    like `GET /cards/pending`'s `counts` — the header must be right regardless."""

    count: int
    units: list[DroppedUnitResponse]


class GeneratedCardsResponse(BaseModel):
    """The cards a rescue wrote, in the `GET /cards/pending` shape, so the client
    inserts them into its queue groups without a refetch."""

    cards: list[PendingCardResponse]


class HandWrittenCardRequest(BaseModel):
    """A card the human typed on a dropped unit. A cloze's `back` may be empty."""

    type: Literal["qa", "cloze"]
    front: str
    back: str = ""

    @model_validator(mode="after")
    def follows_the_card_rules(self) -> "HandWrittenCardRequest":
        # One rule set, shared with the service, so the 422 here and the write
        # there can never disagree.
        validate_hand_written_card(self.type, self.front, self.back)
        return self
