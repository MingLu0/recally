"""`POST /jobs/run` (docs/api-spec.md, "Jobs").

A thin adapter over `recally.scheduling.jobs` — the same functions APScheduler
and an external cron call (docs/backend.md, "Wiring and entry points"), so the
router translates HTTP ↔ `run_job` and nothing else.
"""

from fastapi import APIRouter

from recally.api.auth import ApiKeyGuard
from recally.api.deps import ContainerDep
from recally.api.errors import ProblemDetail
from recally.scheduling.jobs import JobNotImplementedError, run_job
from recally.schemas.jobs import JobRunRequest, JobRunResponse

router = APIRouter(prefix="/jobs", tags=["jobs"], dependencies=[ApiKeyGuard])


@router.post("/run", response_model=JobRunResponse)
def run(request: JobRunRequest, container: ContainerDep) -> JobRunResponse:
    """Run a scheduled job on demand.

    Known-but-unbuilt jobs (`learner`, `optimizer`, steps 6a/6b) are a 501 —
    a silent success for an unbuilt job would look identical to a real run in
    cron logs. Unknown names never reach here: request validation answers 422.
    """
    try:
        run_job(request.job, container)
    except JobNotImplementedError as error:
        raise ProblemDetail(status=501, detail=str(error)) from error
    return JobRunResponse(job=request.job)
