"""`GET /decks` and `GET /decks/{book_id}/cards` payloads (docs/api-spec.md)."""

from datetime import datetime

from pydantic import BaseModel

from recally.services.decks import DeckCard as DeckCardRecord
from recally.services.decks import DeckSummary


class Deck(BaseModel):
    """One book with its card counts."""

    book_id: int
    title: str
    total: int
    due: int

    @classmethod
    def from_summary(cls, summary: DeckSummary) -> "Deck":
        return cls(
            book_id=summary.book_id, title=summary.title, total=summary.total, due=summary.due
        )


class DeckListResponse(BaseModel):
    decks: list[Deck]


class DeckCard(BaseModel):
    """One card in the per-book browse view.

    Suspended cards stay in this list — it is where unsuspend is reached — so each
    card carries `suspended_until` (null when in rotation), unlike `/reviews/due`
    (ADR-008).
    """

    id: int
    type: str
    front: str
    back: str
    chapter: str | None
    tags: list[str]
    suspended_until: datetime | None

    @classmethod
    def from_record(cls, record: DeckCardRecord) -> "DeckCard":
        return cls(
            id=record.id,
            type=record.type,
            front=record.front,
            back=record.back,
            chapter=record.chapter,
            tags=record.tags,
            suspended_until=record.suspended_until,
        )


class DeckCardsResponse(BaseModel):
    cards: list[DeckCard]
