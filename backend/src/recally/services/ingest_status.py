"""The latest ingest run behind `GET /ingest/status` (docs/api-spec.md, "Ingestion")."""

from sqlalchemy import select
from sqlalchemy.orm import Session

from recally.models import IngestRun


def latest_ingest_run(session: Session, *, user_id: int = 1) -> IngestRun | None:
    """The most recent `ingest_runs` row, or None before the first ingest.

    Ordered by id rather than `started_at`: two runs of the same file can share a
    timestamp at second resolution, and the id is the only strictly increasing key.
    """
    return session.scalar(
        select(IngestRun).where(IngestRun.user_id == user_id).order_by(IngestRun.id.desc()).limit(1)
    )
