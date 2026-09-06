"""`GET /decks` payloads (docs/api-spec.md, "Decks & browsing")."""

from pydantic import BaseModel

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
