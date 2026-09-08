"""`GET /ingest/status` (docs/api-spec.md, "Ingestion").

`POST /ingest` (multipart upload) arrives with the watcher and the hosted phase; this
module carries only the status read the roadmap step 1 gate gets run against.
"""

from fastapi import APIRouter

from recally.api.auth import ApiKeyGuard
from recally.api.deps import SessionDep
from recally.api.errors import ProblemDetail
from recally.schemas.ingest import IngestStatusResponse
from recally.services.ingest_status import latest_ingest_run

router = APIRouter(prefix="/ingest", tags=["ingest"], dependencies=[ApiKeyGuard])


@router.get("/status", response_model=IngestStatusResponse)
def get_ingest_status(session: SessionDep) -> IngestStatusResponse:
    """The latest ingest run.

    404 before the first ingest: there is no run to report, and an empty object would
    make "never ingested" indistinguishable from "ingested nothing".
    """
    run = latest_ingest_run(session)
    if run is None:
        raise ProblemDetail(status=404, detail="No ingest run has been recorded yet.")
    return IngestStatusResponse.from_run(run)
