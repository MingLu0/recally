"""`GET /stats` (docs/api-spec.md, "Stats")."""

from zoneinfo import ZoneInfo

from fastapi import APIRouter

from recally.api.auth import ApiKeyGuard
from recally.api.deps import ContainerDep, SessionDep
from recally.schemas.stats import StatsResponse
from recally.services.stats import get_stats

router = APIRouter(prefix="/stats", tags=["stats"], dependencies=[ApiKeyGuard])


@router.get("", response_model=StatsResponse)
def read_stats(session: SessionDep, container: ContainerDep) -> StatsResponse:
    """Streak, retention, lapse rates, curation yield and the due forecast.

    `RECALLY_TIMEZONE` owns every day boundary in the aggregates (docs/config.md).
    """
    summary = get_stats(session, tz=ZoneInfo(container.settings.timezone))
    return StatsResponse.from_summary(summary)
