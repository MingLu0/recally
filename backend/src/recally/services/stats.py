"""The aggregates behind `GET /stats` (docs/api-spec.md, "Stats").

Deterministic, no LLM (hard rule 2). Read-only; the CLI and the future Stats screen
share this module, so nothing here imports FastAPI (docs/backend.md, "Module rules").

Conventions:

- Stored datetimes are timezone-naive (models/base.py); `tz` (`RECALLY_TIMEZONE`,
  docs/config.md) owns every day boundary — streaks, "today", the 30-day window and
  the forecast's day granularity. A boundary computed in the storage zone would be
  wrong by up to a day for the configured user.
- `rated_at` (the client timestamp) is what a review "happened on": offline ratings
  replay in client-timestamp order (hard rule 5), so a synced batch lands on the day
  the user actually reviewed.
- A lapse is an Again (rating 1) on a card in FSRS `review` state when rated; an
  Again during (re)learning is not one — that is the specific meaning
  docs/design/design-system.md pins, and `review_logs.state_before` is what records it.
  That definition is why retention reads 100% on a young collection: a card has to
  graduate before it can lapse, so the figure has not had the chance to be anything
  else (issue #190). It is correct, not stuck.
- Retention is `None` with no review in the window, never `0.0`: an absence and a
  genuine 0% are different statements, and `0.0` renders as the worse of the two.
  `retention_30d_reviews` is the sample it was computed over, so the client can
  qualify a handful of reviews rather than present them at full confidence.
- The streak is forgiving about today: a day that has not ended cannot break it, so
  with no review yet today the count starts from yesterday.
"""

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from recally.models import Card, CardState, Highlight, ReviewLog
from recally.models.base import utc_now

AGAIN = 1  # review_logs.rating; the only rating that can lapse
LAPSE_STATE = "review"  # an Again counts as a lapse only from this state_before
RETENTION_WINDOW_DAYS = 30


@dataclass(frozen=True)
class ForecastDay:
    """One day's due count; day granularity by design (the hours-away figure
    is `next_due_at`)."""

    date: date
    due: int


@dataclass(frozen=True)
class StatsSummary:
    """The full documented `GET /stats` shape, computed from real data."""

    streak_days: int
    reviews_today: int
    retention_30d: float | None
    retention_30d_reviews: int
    lapse_rate_by_type: dict[str, float]
    lapse_rate_by_guidance_version: dict[str, float]
    curation_yield: float
    next_due_at: datetime | None
    forecast: list[ForecastDay]


def _local_day(instant_utc: datetime, tz: ZoneInfo) -> date:
    """The local calendar day a stored naive instant falls on."""
    return instant_utc.replace(tzinfo=timezone.utc).astimezone(tz).date()


def _is_lapse(log: ReviewLog) -> bool:
    return log.rating == AGAIN and log.state_before == LAPSE_STATE


def _streak_days(review_days: set[date], today: date) -> int:
    cursor = today
    if cursor not in review_days:
        cursor -= timedelta(days=1)  # today has not ended; it cannot break the streak
    streak = 0
    while cursor in review_days:
        streak += 1
        cursor -= timedelta(days=1)
    return streak


def _lapse_rates(logs: list[ReviewLog], key_of: dict[int, str]) -> dict[str, float]:
    """Lapses ÷ reviews per bucket; a bucket with no reviews has no rate to give."""
    totals: dict[str, int] = {}
    lapses: dict[str, int] = {}
    for log in logs:
        key = key_of[log.card_id]
        totals[key] = totals.get(key, 0) + 1
        if _is_lapse(log):
            lapses[key] = lapses.get(key, 0) + 1
    return {key: lapses.get(key, 0) / total for key, total in sorted(totals.items())}


def get_stats(
    session: Session, *, tz: ZoneInfo, now: datetime | None = None, user_id: int = 1
) -> StatsSummary:
    """Every `GET /stats` figure in one pass over the user's rows."""
    as_of = now or utc_now()
    today = _local_day(as_of, tz)

    cards_by_id = {
        card.id: card for card in session.scalars(select(Card).where(Card.user_id == user_id))
    }
    logs = list(session.scalars(select(ReviewLog).where(ReviewLog.user_id == user_id)))
    review_days = [_local_day(log.rated_at, tz) for log in logs]

    window_start = today - timedelta(days=RETENTION_WINDOW_DAYS - 1)
    windowed = [log for log, day in zip(logs, review_days, strict=True) if day >= window_start]
    window_lapses = sum(1 for log in windowed if _is_lapse(log))
    retention_30d = (len(windowed) - window_lapses) / len(windowed) if windowed else None

    lapse_rate_by_type = _lapse_rates(
        logs, {card_id: card.type for card_id, card in cards_by_id.items()}
    )
    # Null guidance is omitted entirely (api-spec.md): those reviews are filtered out
    # rather than bucketed under a "null" key.
    lapse_rate_by_guidance_version = _lapse_rates(
        [log for log in logs if cards_by_id[log.card_id].guidance_version is not None],
        {
            card_id: str(card.guidance_version)
            for card_id, card in cards_by_id.items()
            if card.guidance_version is not None
        },
    )

    approved_count = sum(1 for card in cards_by_id.values() if card.status == "approved")
    highlight_count = len(
        session.scalars(select(Highlight.id).where(Highlight.user_id == user_id)).all()
    )
    curation_yield = approved_count / highlight_count if highlight_count else 0.0

    # ADR-008's exclusion lives at the query: a buried or suspended card
    # (`suspended_until` in the future) in the forecast would over-promise the day.
    due_rows = session.scalars(
        select(CardState)
        .join(Card, Card.id == CardState.card_id)
        .where(
            CardState.user_id == user_id,
            or_(Card.suspended_until.is_(None), Card.suspended_until <= as_of),
        )
    ).all()
    due_by_day: dict[date, int] = {}
    for row in due_rows:
        day = max(_local_day(row.due, tz), today)  # overdue is due now, so it lands today
        due_by_day[day] = due_by_day.get(day, 0) + 1
    forecast = [ForecastDay(date=day, due=due_by_day[day]) for day in sorted(due_by_day)]

    # The hours-away figure the forecast's day granularity cannot give: the
    # earliest *future* due across approved cards, with ADR-008's suspension
    # exclusion. Unapproved cards are not scheduled (hard rule 1) and an
    # overdue card is due now, not next; nothing scheduled means null.
    next_due_at = session.scalars(
        select(CardState.due)
        .join(Card, Card.id == CardState.card_id)
        .where(
            CardState.user_id == user_id,
            Card.status == "approved",
            CardState.due > as_of,
            or_(Card.suspended_until.is_(None), Card.suspended_until <= as_of),
        )
        .order_by(CardState.due)
        .limit(1)
    ).first()

    return StatsSummary(
        streak_days=_streak_days(set(review_days), today),
        reviews_today=sum(1 for day in review_days if day == today),
        retention_30d=retention_30d,
        retention_30d_reviews=len(windowed),
        lapse_rate_by_type=lapse_rate_by_type,
        lapse_rate_by_guidance_version=lapse_rate_by_guidance_version,
        curation_yield=curation_yield,
        next_due_at=next_due_at,
        forecast=forecast,
    )
