"""The py-fsrs wrapper — the only module in the codebase that imports `fsrs`.

A single import site is what keeps one scheduler in the system: a second one is how
two schedulers start to disagree (docs/backend.md, "Package layout"; ADR-002,
ADR-005). Nothing here imports FastAPI — the wrapper is also called from the CLI and
APScheduler (docs/backend.md, "Layering", rule 1).

The server is authoritative for FSRS state (hard rule 5): `review_card` requires an
explicit `review_datetime` — the client's `rated_at` — and never substitutes the
current time, so offline ratings replay at their true timestamps.
"""

from datetime import datetime, timezone

from fsrs import Card as LibraryCard
from fsrs import Rating, ReviewLog, Scheduler, State
from sqlalchemy import select
from sqlalchemy.orm import Session

from recally.config import Settings
from recally.models import CardState, FsrsParams

# `card_state.state` stores the library state name in lowercase; the library's
# to_dict/from_dict speaks the integer enum. This is the whole mapping — the table
# mirrors the 6.x `Card` object so conversion is never a translation
# (docs/data-model.md, `card_state`).
_STATE_BY_TABLE_NAME = {member.name.lower(): member for member in State}


def new_card_state(*, card_id: int, due: datetime, user_id: int = 1) -> CardState:
    """The FSRS entry for a freshly approved card.

    FSRS 6 has no `new` state: a card with no history is `learning` at step 0, due
    immediately, with stability/difficulty NULL until its first review
    (docs/data-model.md, `card_state`).
    """
    return CardState(card_id=card_id, state="learning", step=0, due=due, user_id=user_id)


def rating_from_int(value: int) -> Rating:
    """The wire value (1=Again … 4=Easy, docs/api-spec.md) as the library enum.

    Kept here so callers outside this module never import `fsrs` — this module is
    the single import site (docs/backend.md, "Package layout").
    """
    return Rating(value)


def _as_utc_iso(value: datetime) -> str:
    """A stored naive-UTC datetime as the ISO string the library's dict shape holds."""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.isoformat()


def _from_utc_iso(value: str) -> datetime:
    """The reverse of `_as_utc_iso`: the table stores naive UTC datetimes."""
    return datetime.fromisoformat(value).astimezone(timezone.utc).replace(tzinfo=None)


def card_to_library(state: CardState) -> LibraryCard:
    """The row as the library object, via `Card.from_dict` on the verbatim dict shape."""
    return LibraryCard.from_dict(
        {
            "card_id": state.card_id,
            "state": _STATE_BY_TABLE_NAME[state.state].value,
            "step": state.step,
            "stability": state.stability,
            "difficulty": state.difficulty,
            "due": _as_utc_iso(state.due),
            "last_review": (
                _as_utc_iso(state.last_review) if state.last_review is not None else None
            ),
        }
    )


def apply_library_card(state: CardState, card: LibraryCard) -> CardState:
    """Write the library object back onto the row, via `to_dict` — the round-trip
    the `card_state` table is documented to survive unchanged."""
    data = card.to_dict()
    state.state = State(data["state"]).name.lower()
    state.step = data["step"]
    state.stability = data["stability"]
    state.difficulty = data["difficulty"]
    state.due = _from_utc_iso(data["due"])
    state.last_review = (
        _from_utc_iso(data["last_review"]) if data["last_review"] is not None else None
    )
    return state


class FsrsScheduler:
    """The one scheduler, built from config and the latest `fsrs_params` row.

    The latest row is active when one exists (docs/data-model.md: "Latest row is
    active. Defaults are used until the first fit."); step 6a writes those rows,
    this step only reads them.
    """

    def __init__(self, scheduler: Scheduler) -> None:
        self._scheduler = scheduler

    @classmethod
    def build(cls, settings: Settings, session: Session) -> "FsrsScheduler":
        learning_steps = list(settings.fsrs_learning_steps)
        latest = session.scalar(
            select(FsrsParams).order_by(FsrsParams.created_at.desc(), FsrsParams.id.desc()).limit(1)
        )
        if latest is None:
            return cls(
                Scheduler(
                    desired_retention=settings.fsrs_desired_retention,
                    learning_steps=learning_steps,
                )
            )
        return cls(
            Scheduler(
                parameters=[float(weight) for weight in latest.parameters],
                desired_retention=latest.desired_retention,
                learning_steps=learning_steps,
            )
        )

    @property
    def desired_retention(self) -> float:
        return self._scheduler.desired_retention

    @property
    def parameters(self) -> tuple[float, ...]:
        return tuple(self._scheduler.parameters)

    @property
    def learning_steps_minutes(self) -> list[int | float]:
        """The configured learning steps in minutes, as `GET /reviews/due` reports them
        for same-session re-queueing (ADR-005). Whole minutes come back as ints."""
        minutes: list[int | float] = []
        for step in self._scheduler.learning_steps:
            value = step.total_seconds() / 60
            minutes.append(int(value) if value == int(value) else value)
        return minutes

    def review_card(
        self, state: CardState, rating: Rating, *, review_datetime: datetime
    ) -> ReviewLog:
        """Score one rating at its client timestamp — never at "now" (hard rule 5).

        Mutates `state` in place with the new FSRS values and returns the library
        `ReviewLog`; persisting it as a `review_logs` row is the caller's job
        (roadmap step 3b).
        """
        if review_datetime.tzinfo is None:
            review_datetime = review_datetime.replace(tzinfo=timezone.utc)
        card, review_log = self._scheduler.review_card(
            card_to_library(state), rating, review_datetime=review_datetime
        )
        apply_library_card(state, card)
        return review_log
