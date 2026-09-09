"""The `notify` / `learner` / `optimizer` entry points (docs/backend.md, "Package
layout" and "Wiring and entry points").

Every scheduler driver converges here — `POST /jobs/run`, an external cron, and
the in-process APScheduler (phase 1) all call `run_job`. None of the last two is
an HTTP request, so this module must not import FastAPI (docs/backend.md,
"Layering" rule 1; enforced by tests/scheduling/test_jobs_layering.py).

`notify` is wired to the notifier seam step 5b fills. `optimizer` runs the step
6a-a fit (`scheduling/optimizer.py`). `learner` runs the step 6b-a guidance job
(`scheduling/learner.py`). `JobNotImplementedError` remains for the next known
job name that lands before its implementation does: a caller gets a loud 501,
never a silent success for a job that does not exist yet.
"""

from typing import Literal

from recally.container import Container
from recally.scheduling import learner, notifier, optimizer

JobName = Literal["notify", "learner", "optimizer"]


class JobNotImplementedError(Exception):
    """A known job name whose implementation has not landed yet."""

    def __init__(self, job: str) -> None:
        super().__init__(f"Job {job!r} is not implemented yet.")
        self.job = job


def run_job(job: JobName, container: Container) -> None:
    """Run one scheduled job on demand. Unknown names are rejected upstream by
    request validation (422)."""
    if job == "notify":
        notifier.send_due_push(container)
        return
    if job == "optimizer":
        optimizer.fit_parameters(container)
        return
    if job == "learner":
        learner.generate_guidance(container)
        return
    raise JobNotImplementedError(job)
