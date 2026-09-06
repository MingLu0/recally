"""Deck listing behind `GET /decks` (docs/api-spec.md, "Decks & browsing")."""

from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import Select, case, func, or_, select
from sqlalchemy.orm import Session

from recally.models import Book, Card, CardState, CuratedUnit, CuratedUnitHighlight, Highlight
from recally.models.base import utc_now


@dataclass(frozen=True)
class DeckSummary:
    """One book's row in the deck list."""

    book_id: int
    title: str
    total: int
    due: int


def list_decks(
    session: Session, *, user_id: int = 1, now: datetime | None = None
) -> list[DeckSummary]:
    """Books with their approved-card counts and how many of those are due.

    Every ingested book is listed, including one with no cards yet: after roadmap step
    1 the database holds highlights and no cards at all, and the step's manual gate
    reads the nine books of the export off this endpoint. So the joins down to `cards`
    are outer ones and an empty book comes back as `total: 0`.

    A card reaches its book through `curated_unit_highlights`, and a grouped unit can
    cover several highlights of the same book, so cards are counted over DISTINCT ids.
    Only `approved` cards count: nothing else has entered scheduling (hard rule 1).
    """
    as_of = now or utc_now()

    # Due means FSRS says so and the card is neither buried nor suspended (ADR-008) —
    # the predicate `GET /reviews/due` will share in roadmap step 3.
    is_due = (CardState.due <= as_of) & or_(
        Card.suspended_until.is_(None), Card.suspended_until <= as_of
    )

    statement: Select[tuple[int, str, int, int]] = (
        select(
            Book.id,
            Book.title,
            func.count(func.distinct(Card.id)).label("total"),
            func.count(func.distinct(case((is_due, Card.id)))).label("due"),
        )
        .select_from(Book)
        .outerjoin(Highlight, Highlight.book_id == Book.id)
        .outerjoin(CuratedUnitHighlight, CuratedUnitHighlight.highlight_id == Highlight.id)
        .outerjoin(CuratedUnit, CuratedUnit.id == CuratedUnitHighlight.unit_id)
        .outerjoin(Card, (Card.unit_id == CuratedUnit.id) & (Card.status == "approved"))
        .outerjoin(CardState, CardState.card_id == Card.id)
        .where(Book.user_id == user_id)
        .group_by(Book.id, Book.title)
        .order_by(Book.title)
    )

    return [
        DeckSummary(book_id=book_id, title=title, total=total, due=due)
        for book_id, title, total, due in session.execute(statement).all()
    ]
