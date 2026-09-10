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
    progress: float
    chapters: int
    truncated: int


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
    state: str
    due: datetime | None


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

    `progress` is the share of those approved cards whose FSRS state is `review`
    (docs/data-model.md, `card_state`) — one more conditional count over the join this
    query already makes. A book with no approved cards reports 0.0, never a divide by
    zero.

    `chapters` is the Decks row's "48 cards · 9 chapters" (issue #172): distinct
    non-null `highlights.chapter` values among the book's *approved* cards, so it
    counts the same population `total` does. It cannot be derived on that screen —
    the Decks list never fetches a book's cards — which is why it is a field here
    rather than client arithmetic. An empty book reports 0.

    `truncated` is the Decks row's "2 TRUNCATED" badge (G6, issue #173): the book's
    clipped source highlights. It is the one count here that is *not* scoped to
    approved cards — truncation is a property of the O'Reilly export, so a clipped
    highlight is clipped whether the card it produced is approved, still in the
    queue, or not yet curated at all. It is informational only; nothing here or on
    the app reconstructs the lost text (hard rule 7).
    """
    as_of = now or utc_now()

    # Due means FSRS says so and the card is neither buried nor suspended (ADR-008) —
    # the one shared definition, owned by `services/reviews.py` so the deck counts and
    # `GET /reviews/due` cannot drift apart.
    is_due = due_cards_predicate(as_of)

    statement: Select[tuple[int, str, int, int, int, int, int]] = (
        select(
            Book.id,
            Book.title,
            func.count(func.distinct(Card.id)).label("total"),
            func.count(func.distinct(case((is_due, Card.id)))).label("due"),
            func.count(func.distinct(case((CardState.state == "review", Card.id)))).label(
                "in_review"
            ),
            # Only chapters that actually carry an approved card: the outer join
            # leaves `Card.id` NULL for an uncurated highlight, and NULL chapters
            # (a highlight with none) are outside the count either way. Counting
            # over the join's highlights rather than each card's assigned chapter
            # is safe because grouping never spans a chapter (docs/agents.md), so
            # a card's source highlights all share one.
            func.count(func.distinct(case((Card.id.isnot(None), Highlight.chapter)))).label(
                "chapters"
            ),
            # Counted off `Highlight` before any card filtering, unlike every
            # count above: the badge spans all card statuses. DISTINCT over the
            # highlight id, not the row, because the join down to `cards` fans a
            # highlight out once per card it produced.
            func.count(func.distinct(case((Highlight.truncated, Highlight.id)))).label("truncated"),
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
        DeckSummary(
            book_id=book_id,
            title=title,
            total=total,
            due=due,
            progress=in_review / total if total else 0.0,
            chapters=chapters,
            truncated=truncated,
        )
        for book_id, title, total, due, in_review, chapters, truncated in session.execute(
            statement
        ).all()
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

    Each card also carries its FSRS `state` and `due` (issue #172) so the browse row
    can show them. `due` is reported **only for a card FSRS has actually scheduled**:
    approval writes `card_state` at `learning` step 0 due immediately, so that stored
    timestamp is an availability marker rather than a schedule, and publishing it
    would put a date on screen that no review produced. A never-reviewed card
    (`last_review is None`) therefore reports `due: null`. The client renders this
    value and never derives one — the server stays the FSRS authority (hard rule 5).
    """
    statement = (
        select(Card, Highlight.chapter, CardState)
        .join(CuratedUnit, Card.unit_id == CuratedUnit.id)
        .join(CuratedUnitHighlight, CuratedUnitHighlight.unit_id == CuratedUnit.id)
        .join(Highlight, Highlight.id == CuratedUnitHighlight.highlight_id)
        # Outer: a card whose `card_state` row is somehow missing still belongs in
        # the browse list, where edit and unsuspend are reached (ADR-008).
        .outerjoin(CardState, CardState.card_id == Card.id)
        .where(Card.user_id == user_id, Card.status == "approved", Highlight.book_id == book_id)
        .order_by(Highlight.chapter, Highlight.export_position, Card.id)
    )
    if chapter is not None:
        statement = statement.where(Highlight.chapter == chapter)

    deck_cards: list[DeckCard] = []
    seen: set[int] = set()
    for card, card_chapter, card_state in session.execute(statement).all():
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
                state=card_state.state if card_state is not None else "learning",
                due=_scheduled_due(card_state),
            )
        )
    return deck_cards


def _scheduled_due(card_state: CardState | None) -> datetime | None:
    """The card's due date, or None when FSRS has never scheduled it.

    A card with no review history holds the approval-time `due` FSRS 6 gives a fresh
    `learning` step-0 card. That is "available now", not a scheduled date, so browse
    reports nothing rather than a date the human would read as a real interval.
    """
    if card_state is None or card_state.last_review is None:
        return None
    return card_state.due
