"""`GET /stats` payload (docs/api-spec.md, "Stats").

Every key of the documented example response is here, and nothing else: the
hours-away next-due figure is gap G3 and per-book completion is gap G2, both scoped
out of step 3e (docs/roadmap.md, "Feature gaps").
"""

from datetime import date

from pydantic import BaseModel

from recally.services.stats import StatsSummary


class ForecastEntry(BaseModel):
    """One day's due count, day granularity from `card_state.due`."""

    date: date
    due: int


class StatsResponse(BaseModel):
    streak_days: int
    reviews_today: int
    retention_30d: float
    lapse_rate_by_type: dict[str, float]
    # Keyed on `cards.guidance_version` as strings; null guidance is omitted.
    lapse_rate_by_guidance_version: dict[str, float]
    curation_yield: float
    forecast: list[ForecastEntry]

    @classmethod
    def from_summary(cls, summary: StatsSummary) -> "StatsResponse":
        return cls(
            streak_days=summary.streak_days,
            reviews_today=summary.reviews_today,
            retention_30d=summary.retention_30d,
            lapse_rate_by_type=summary.lapse_rate_by_type,
            lapse_rate_by_guidance_version=summary.lapse_rate_by_guidance_version,
            curation_yield=summary.curation_yield,
            forecast=[ForecastEntry(date=entry.date, due=entry.due) for entry in summary.forecast],
        )
