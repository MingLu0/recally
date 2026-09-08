"""`GET /ingest/status` payloads (docs/api-spec.md, "Ingestion")."""

from pydantic import BaseModel

from recally.models import IngestRun
from recally.schemas.types import UtcDatetime


class IngestStatusResponse(BaseModel):
    """The latest `ingest_runs` row, in the shape docs/api-spec.md publishes.

    `rows_unchanged` is derived rather than stored (docs/data-model.md). The
    Curator's `units_kept` / `units_dropped` / `highlights_dropped` counts are the
    pipeline's output; since step 2 they are always present.
    """

    filename: str
    rows_seen: int
    rows_new: int
    rows_updated: int
    rows_unchanged: int
    rows_removed: int
    units_kept: int
    units_dropped: int
    highlights_dropped: int
    cards_generated: int
    cost_microusd: int
    started_at: UtcDatetime
    finished_at: UtcDatetime | None
    error: str | None

    @classmethod
    def from_run(cls, run: IngestRun) -> "IngestStatusResponse":
        return cls(
            filename=run.filename,
            rows_seen=run.rows_seen,
            rows_new=run.rows_new,
            rows_updated=run.rows_updated,
            rows_unchanged=run.rows_seen - run.rows_new - run.rows_updated,
            rows_removed=run.rows_removed,
            units_kept=run.units_kept,
            units_dropped=run.units_dropped,
            highlights_dropped=run.highlights_dropped,
            cards_generated=run.cards_generated,
            cost_microusd=run.cost_microusd,
            started_at=run.started_at,
            finished_at=run.finished_at,
            error=run.error,
        )
