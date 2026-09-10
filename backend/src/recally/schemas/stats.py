"""`GET /stats` payload (docs/api-spec.md, "Stats").

Every key of the documented example response is here, and nothing else:
per-book completion is gap G2, scoped out of step 3e (docs/roadmap.md,
"Feature gaps").
"""

from datetime import date

from pydantic import BaseModel

from recally.schemas.types import UtcDatetime
from recally.services.stats import StatsSummary


class ForecastEntry(BaseModel):
    """One day's due count, day granularity from `card_state.due`."""

    date: date
    due: int


class StatsResponse(BaseModel):
    streak_days: int
    reviews_today: int
    # Recall on cards already learned, last 30 days. Null when no review fell
    # in the window — an absence, not 0% (issue #190); `retention_30d_reviews`
    # is the sample it was computed over, so a handful can be qualified.
    retention_30d: float | None
    retention_30d_reviews: int
    lapse_rate_by_type: dict[str, float]
    # Keyed on `cards.guidance_version` as strings; null guidance is omitted.
    lapse_rate_by_guidance_version: dict[str, float]
    curation_yield: float
    # Earliest future due across approved, unsuspended cards; null when
    # nothing is scheduled. `UtcDatetime` so it renders with the Z suffix
    # like every other timestamp in the API.
    next_due_at: UtcDatetime | None
    forecast: list[ForecastEntry]

    @classmethod
    def from_summary(cls, summary: StatsSummary) -> "StatsResponse":
        return cls(
            streak_days=summary.streak_days,
            reviews_today=summary.reviews_today,
            retention_30d=summary.retention_30d,
            retention_30d_reviews=summary.retention_30d_reviews,
            lapse_rate_by_type=summary.lapse_rate_by_type,
            lapse_rate_by_guidance_version=summary.lapse_rate_by_guidance_version,
            curation_yield=summary.curation_yield,
            next_due_at=summary.next_due_at,
            forecast=[ForecastEntry(date=entry.date, due=entry.due) for entry in summary.forecast],
        )
