"""Deck listing behind `GET /decks` (docs/api-spec.md, "Decks & browsing")."""

from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import Select, case, func, select
from sqlalchemy.orm import Session

from recally.models import Book, Card, CardState, CuratedUnit, CuratedUnitHighlight, Highlight
from recally.models.base import utc_now
from recally.services.reviews import due_cards_predicate


@dataclass(frozen=True)
class DeckSummary:
    """One book's row in the deck list."""

    book_id: int
    title: str
    total: int
    due: int


@dataclass(frozen=True)
class DeckCard:
    """One approved card in the per-book browse view behind `GET /decks/{id}/cards`."""

    id: int
    type: str
    front: str
    back: str
    chapter: str | None
    tags: list[str]
    suspended_until: datetime | None


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
    # the one shared definition, owned by `services/reviews.py` so the deck counts and
    # `GET /reviews/due` cannot drift apart.
    is_due = due_cards_predicate(as_of)

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


def list_deck_cards(
    session: Session, book_id: int, *, chapter: str | None = None, user_id: int = 1
) -> list[DeckCard]:
    """A book's approved cards for the browse view, suspended ones included.

    Unlike `/reviews/due` this list must not filter suspended cards out — the browse
    view is where unsuspend is reached (ADR-008) — so each card carries
    `suspended_until` (null when in rotation). Only `approved` cards are deck
    members at all (hard rule 1). Grouping is within one chapter (docs/agents.md),
    so a unit's first source highlight stands in for the card's chapter and order.
    """
    statement = (
        select(Card, Highlight.chapter)
        .join(CuratedUnit, Card.unit_id == CuratedUnit.id)
        .join(CuratedUnitHighlight, CuratedUnitHighlight.unit_id == CuratedUnit.id)
        .join(Highlight, Highlight.id == CuratedUnitHighlight.highlight_id)
        .where(Card.user_id == user_id, Card.status == "approved", Highlight.book_id == book_id)
        .order_by(Highlight.chapter, Highlight.export_position, Card.id)
    )
    if chapter is not None:
        statement = statement.where(Highlight.chapter == chapter)

    deck_cards: list[DeckCard] = []
    seen: set[int] = set()
    for card, card_chapter in session.execute(statement).all():
        if card.id in seen:
            continue  # a grouped unit joins once per source highlight
        seen.add(card.id)
        deck_cards.append(
            DeckCard(
                id=card.id,
                type=card.type,
                front=card.front,
                back=card.back,
                chapter=card_chapter,
                tags=list(card.tags),
                suspended_until=card.suspended_until,
            )
        )
    return deck_cards
