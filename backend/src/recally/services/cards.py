"""The approval-queue read behind `GET /cards/pending` (docs/api-spec.md).

Deterministic, no LLM (hard rule 2). Only reads live here: the status writes of the
approval gate stay in `api/routers/cards.py`, one of the two modules the
design-invariant tests allow to assign `cards.status` (test_design_invariants.py).

The ADR-008 suspension helpers (`bury`, `suspend`, `unsuspend`) live here too: they
move `cards.suspended_until` and nothing else — never `cards.status`, never a
`card_state` column, so exclusion happens at the due query and the server stays the
single FSRS authority (ADR-005).
"""

from dataclasses import dataclass, field
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from recally.models import Card, CuratedUnitHighlight, Highlight

QUEUE_STATUSES = ("pending_review", "needs_human")

# docs/api-spec.md: suspend sets a far-future sentinel that only `unsuspend` clears.
# Naive UTC, matching what every `DateTime` column stores (models/base.py).
SUSPEND_SENTINEL = datetime(9999, 12, 31, 0, 0, 0)


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


def next_day_boundary(now: datetime, timezone_name: str) -> datetime:
    """The next local midnight in `timezone_name`, as an aware datetime.

    `now` follows the stored convention — naive UTC (models/base.py) — so a naive
    value is read as UTC. The aware return is what the bury response publishes
    (docs/api-spec.md shows the local offset); the row stores its naive-UTC form.
    """
    zone = ZoneInfo(timezone_name)
    aware_now = now.replace(tzinfo=timezone.utc) if now.tzinfo is None else now
    local_tomorrow = aware_now.astimezone(zone).date() + timedelta(days=1)
    return datetime.combine(local_tomorrow, time.min, tzinfo=zone)


def bury(card: Card, *, now: datetime, timezone_name: str) -> datetime:
    """Hide the card for the rest of the local day; it clears itself at the boundary.

    "Not right now" must not become a dishonest rating — one would corrupt
    `review_logs`, the training data for FSRS optimisation and Learner stage B
    (ADR-008). Returns the aware local boundary for the response.
    """
    boundary = next_day_boundary(now, timezone_name)
    card.suspended_until = boundary.astimezone(timezone.utc).replace(tzinfo=None)
    return boundary


def suspend(card: Card) -> datetime:
    """Take the card out of rotation indefinitely. Returns the aware sentinel."""
    card.suspended_until = SUSPEND_SENTINEL
    return SUSPEND_SENTINEL.replace(tzinfo=timezone.utc)


def unsuspend(card: Card) -> None:
    """Back in rotation at whatever `due` the card already held.

    Clears either a bury or a suspend (one column backs both), and recomputes
    nothing: FSRS state is the server's alone (ADR-005).
    """
    card.suspended_until = None
