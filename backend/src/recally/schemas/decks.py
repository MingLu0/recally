"""`GET /decks` and `GET /decks/{book_id}/cards` payloads (docs/api-spec.md)."""

from pydantic import BaseModel

from recally.schemas.types import UtcDatetime
from recally.services.decks import DeckCard as DeckCardRecord
from recally.services.decks import DeckSummary


class Deck(BaseModel):
    """One book with its card counts and progress."""

    book_id: int
    title: str
    total: int
    due: int
    progress: float
    # Distinct chapters among the book's approved cards, behind the Decks row's
    # "48 cards · 9 chapters" (issue #172). The Decks screen never fetches a
    # book's cards, so it cannot derive this the way Book detail can.
    chapters: int

    @classmethod
    def from_summary(cls, summary: DeckSummary) -> "Deck":
        return cls(
            book_id=summary.book_id,
            title=summary.title,
            total=summary.total,
            due=summary.due,
            progress=summary.progress,
            chapters=summary.chapters,
        )


class DeckListResponse(BaseModel):
    decks: list[Deck]


class DeckCard(BaseModel):
    """One card in the per-book browse view.

    Suspended cards stay in this list — it is where unsuspend is reached — so each
    card carries `suspended_until` (null when in rotation), unlike `/reviews/due`
    (ADR-008).

    `state` and `due` are the card's server-side FSRS position (issue #172). `due` is
    null for a card FSRS has never scheduled — a fresh `learning` step-0 card holds
    an approval-time marker, not a schedule, and browse must not present it as one.
    """

    id: int
    type: str
    front: str
    back: str
    chapter: str | None
    tags: list[str]
    suspended_until: UtcDatetime | None
    state: str
    due: UtcDatetime | None

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
            state=record.state,
            due=record.due,
        )


class DeckCardsResponse(BaseModel):
    cards: list[DeckCard]
