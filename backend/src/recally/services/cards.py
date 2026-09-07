"""The approval-queue read behind `GET /cards/pending` (docs/api-spec.md).

Deterministic, no LLM (hard rule 2). Only reads live here: the status writes of the
approval gate stay in `api/routers/cards.py`, one of the two modules the
design-invariant tests allow to assign `cards.status` (test_design_invariants.py).
"""

from dataclasses import dataclass, field

from sqlalchemy import select
from sqlalchemy.orm import Session

from recally.models import Card, CuratedUnitHighlight, Highlight

QUEUE_STATUSES = ("pending_review", "needs_human")


@dataclass(frozen=True)
class PendingCard:
    """One card in the approval queue, with every source highlight of its unit.

    `first_export_position` backs the documented ordering (book, chapter, then
    export position); it is not part of the API payload.
    """

    id: int
    status: str
    type: str
    front: str
    back: str
    status_reason: str | None
    source_highlights: list[str] = field(default_factory=list)
    truncated: bool = False
    book_id: int = 0
    book: str = ""
    chapter: str | None = None
    first_export_position: int = 0


def list_pending_cards(
    session: Session,
    *,
    statuses: tuple[str, ...] = QUEUE_STATUSES,
    book_id: int | None = None,
    chapter: str | None = None,
    user_id: int = 1,
) -> list[PendingCard]:
    """Cards awaiting the human, ordered by book, chapter, then `export_position`.

    A grouped unit has several source highlights; all of them are returned (the
    client needs them for context), and `truncated` is true when any of them is
    clipped — the flag lives only on `highlights` rows (docs/data-model.md).
    Grouping is within one chapter, so a unit's sources share book and chapter and
    the first one stands in for the card's placement in the queue.
    """
    cards = session.scalars(
        select(Card).where(Card.user_id == user_id, Card.status.in_(statuses))
    ).all()

    pending: list[PendingCard] = []
    for card in cards:
        sources = session.scalars(
            select(Highlight)
            .join(CuratedUnitHighlight, CuratedUnitHighlight.highlight_id == Highlight.id)
            .where(CuratedUnitHighlight.unit_id == card.unit_id)
            .order_by(Highlight.export_position)
        ).all()
        if not sources:
            continue  # provenance is mandatory; a sourceless card cannot be reviewed
        first = sources[0]
        if book_id is not None and first.book_id != book_id:
            continue
        if chapter is not None and first.chapter != chapter:
            continue
        pending.append(
            PendingCard(
                id=card.id,
                status=card.status,
                type=card.type,
                front=card.front,
                back=card.back,
                status_reason=card.status_reason,
                source_highlights=[source.raw_text for source in sources],
                truncated=any(source.truncated for source in sources),
                book_id=first.book_id,
                book=first.book.title,
                chapter=first.chapter,
                first_export_position=first.export_position,
            )
        )

    pending.sort(key=lambda card: (card.book, card.chapter or "", card.first_export_position))
    return pending
