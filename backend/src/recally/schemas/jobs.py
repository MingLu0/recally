"""`POST /jobs/run` payloads (docs/api-spec.md, "Jobs")."""

from pydantic import BaseModel

from recally.scheduling.jobs import JobName


class JobRunRequest(BaseModel):
    """The job to run on demand. An unknown name fails validation here (422);
    a known-but-unbuilt one (`learner`, `optimizer`) is a 501 from the router."""

    job: JobName


class JobRunResponse(BaseModel):
    """Confirmation of the job that ran. The spec defines no payload beyond
    success, so this carries only the echoed name."""

    job: str
