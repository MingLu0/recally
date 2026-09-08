"""The due queue and rating application behind `/reviews/*` (docs/api-spec.md,
"Reviews"; ADR-005, ADR-008).

Deterministic, no LLM (hard rule 2), and no FastAPI (docs/backend.md, "Layering",
rule 1): the CLI (roadmap step 3f) calls these functions directly. The server is
authoritative for FSRS state but never for the clock (hard rule 5) — every rating
is scored at its explicit client `rated_at`.
"""

from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import ColumnElement, or_, select
from sqlalchemy.orm import Session

from recally.models import Card, CardState, CuratedUnitHighlight, Highlight, ReviewLog
from recally.models.base import utc_now
from recally.scheduling.fsrs import FsrsScheduler, rating_from_int

VALID_RATINGS = (1, 2, 3, 4)


class ReviewError(Exception):
    """A rating that cannot be applied, carrying the problem+json pair the API emits.

    Raised before any database write, so a failed batch item leaves nothing to roll
    back — one bad item can never take its neighbours down with it.
    """

    def __init__(self, *, status: int, detail: str) -> None:
        super().__init__(detail)
        self.status = status
        self.detail = detail


def due_cards_predicate(as_of: datetime) -> ColumnElement[bool]:
    """Due means FSRS says so and the card is neither buried nor suspended (ADR-008).

    The single definition of "due", shared by `GET /reviews/due` here and the deck
    due counts in `services/decks.py` — two copies of this predicate are exactly the
    drift ADR-008 warns about.
    """
    return (CardState.due <= as_of) & or_(
        Card.suspended_until.is_(None), Card.suspended_until <= as_of
    )


@dataclass(frozen=True)
class DueCard:
    """One entry of the due list, in the shape docs/api-spec.md publishes."""

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


@dataclass(frozen=True)
class DueList:
    """`GET /reviews/due`: due cards plus today's new-card allotment."""

    due_count: int
    new_count: int
    learning_steps_minutes: list[int | float]
    cards: list[DueCard]


@dataclass(frozen=True)
class RatingCommand:
    """One rating to apply, at its client timestamp (hard rule 5)."""

    card_id: int
    rating: int
    response_ms: int
    rated_at: datetime
    device_id: int | None = None


@dataclass(frozen=True)
class RatingResult:
    """The card's position after a rating (docs/api-spec.md, POST rate)."""

    card_id: int
    rated_at: datetime
    next_due: datetime
    state: str
    step: int | None
    lapsed: bool
    duplicate: bool


@dataclass(frozen=True)
class RatingOutcome:
    """One batch item's verdict: exactly one of `result` / `error` is set."""

    result: RatingResult | None = None
    error: ReviewError | None = None


def list_due(
    session: Session,
    scheduler: FsrsScheduler,
    *,
    new_cards_per_day: int,
    now: datetime | None = None,
    user_id: int = 1,
) -> DueList:
    """Cards due now plus the new-card allotment, capped by `NEW_CARDS_PER_DAY`.

    A never-reviewed card is one whose `card_state` row (created at approval, step
    3a) has no `last_review`; those are the cards the cap applies to. Reviewed cards
    are due when the shared predicate says so. Only `approved` cards are ever
    scheduled (hard rule 1), so the queue can never leak the approval queue.
    """
    as_of = now or utc_now()
    rows = session.execute(
        select(Card, CardState)
        .join(CardState, CardState.card_id == Card.id)
        .where(Card.user_id == user_id, Card.status == "approved")
        .where(due_cards_predicate(as_of))
        .order_by(CardState.due, Card.id)
    ).all()

    due_reviewed = [(card, state) for card, state in rows if state.last_review is not None]
    new_cards = [(card, state) for card, state in rows if state.last_review is None]
    allotment = new_cards[:new_cards_per_day]

    cards = [_due_card(session, card, state) for card, state in due_reviewed + allotment]
    # Sorted by due so a cached queue needs no re-sort against the client clock.
    cards.sort(key=lambda entry: entry.due)
    return DueList(
        due_count=len(due_reviewed),
        new_count=len(allotment),
        learning_steps_minutes=scheduler.learning_steps_minutes,
        cards=cards,
    )


def rate_card(session: Session, scheduler: FsrsScheduler, command: RatingCommand) -> RatingResult:
    """Score one rating at its client timestamp, write the `review_logs` row, commit.

    Each rating is its own unit of work, committed here rather than by the caller:
    the batch endpoint's "one bad item does not roll back the others" (docs/api-spec.md)
    and the CLI's direct use both follow from that.

    `duplicate` is always False here: the no-op replay of an already-logged
    `(card_id, rated_at)` and the out-of-order recompute arrive in roadmap step 3c,
    which extends this path (the UNIQUE constraint already exists on the model).
    """
    card = session.get(Card, command.card_id)
    if card is None:
        raise ReviewError(status=404, detail=f"Card {command.card_id} not found.")
    if card.status != "approved":
        raise ReviewError(
            status=409,
            detail=f"Card {command.card_id} is not approved (status: {card.status}).",
        )
    if command.rating not in VALID_RATINGS:
        raise ReviewError(
            status=422, detail="rating must be 1-4 (1=Again, 2=Hard, 3=Good, 4=Easy)."
        )
    state = session.get(CardState, card.id)
    if state is None:
        # 3a creates the row at approval; its absence on an approved card is a data
        # bug, never a rating to accept silently.
        raise ReviewError(status=409, detail=f"Card {command.card_id} has no scheduling state.")

    rated_at = _as_naive_utc(command.rated_at)
    state_before = state.state
    scheduler.review_card(state, rating_from_int(command.rating), review_datetime=rated_at)
    session.add(
        ReviewLog(
            card_id=card.id,
            rated_at=rated_at,
            rating=command.rating,
            response_ms=command.response_ms,
            scheduled_days=max(0, (state.due - rated_at).days),
            state_before=state_before,
            device_id=command.device_id,
            user_id=card.user_id,
        )
    )
    session.commit()
    return RatingResult(
        card_id=card.id,
        rated_at=rated_at,
        next_due=state.due,
        state=state.state,
        step=state.step,
        # A lapse is this rating moving the card out of `review` into `relearning`;
        # Again on a card already in `learning` moved nothing (docs/api-spec.md).
        lapsed=state_before == "review" and state.state == "relearning",
        duplicate=False,
    )


def apply_batch(
    session: Session, scheduler: FsrsScheduler, commands: list[RatingCommand]
) -> list[RatingOutcome]:
    """Apply each rating independently, in `rated_at` order per card.

    Applying in global `rated_at` order gives every card its own chronological
    ordering even when the request interleaves cards (docs/api-spec.md). The
    returned list aligns with `commands` by position — request order is the
    caller's bookkeeping.
    """
    outcomes: list[RatingOutcome | None] = [None] * len(commands)
    application_order = sorted(
        range(len(commands)), key=lambda index: (commands[index].rated_at, index)
    )
    for index in application_order:
        try:
            outcomes[index] = RatingOutcome(result=rate_card(session, scheduler, commands[index]))
        except ReviewError as error:
            outcomes[index] = RatingOutcome(error=error)
    return [outcome for outcome in outcomes if outcome is not None]


def _due_card(session: Session, card: Card, state: CardState) -> DueCard:
    """One due-list entry, with the book context of the card's first source highlight.

    A grouped unit shares one book and chapter across its sources, so the first by
    export position stands in for the card — the same rule as the approval queue
    (`services/cards.py`).
    """
    first_source = session.scalars(
        select(Highlight)
        .join(CuratedUnitHighlight, CuratedUnitHighlight.highlight_id == Highlight.id)
        .where(CuratedUnitHighlight.unit_id == card.unit_id)
        .order_by(Highlight.export_position)
    ).first()
    if first_source is None:
        # Provenance is mandatory: a sourceless card cannot have passed the pipeline.
        raise ReviewError(status=500, detail=f"Card {card.id} has no source highlight.")
    return DueCard(
        id=card.id,
        unit_id=card.unit_id,
        type=card.type,
        front=card.front,
        back=card.back,
        book_id=first_source.book_id,
        book=first_source.book.title,
        chapter=first_source.chapter,
        tags=card.tags,
        state=state.state,
        step=state.step,
        due=state.due,
    )


def _as_naive_utc(value: datetime) -> datetime:
    """The client timestamp as naive UTC, matching what the `DateTime` columns store."""
    if value.tzinfo is None:
        return value
    return value.astimezone(timezone.utc).replace(tzinfo=None)
