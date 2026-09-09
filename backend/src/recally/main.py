"""The FastAPI app factory. Run with `uv run uvicorn recally.main:app --reload`.

The lifespan resolves settings so a missing `RECALLY_API_KEY` fails startup rather
than the first request; it also owns the in-process APScheduler — the nightly
optimizer (stage A) and learner guidance job (stage B) on `LEARNER_CRON` below,
the notifier tick with step 5b — which is why startup work lives there and not at
module import time.
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from fastapi import FastAPI

from recally.api.errors import register_error_handlers
from recally.api.routers import cards, decks, devices, health, ingest, jobs, reviews, stats
from recally.config import get_settings
from recally.container import get_container
from recally.scheduling.jobs import run_job


def _run_nightly_optimizer() -> None:
    """The APScheduler entry point for `{"job": "optimizer"}`.

    The container is resolved at fire time, not at registration: building it at
    startup would open the configured database for every process that creates
    the app — tests included — long before the 3am run needs it.
    """
    run_job("optimizer", get_container())


def _run_nightly_learner() -> None:
    """The APScheduler entry point for `{"job": "learner"}` (stage B). Same
    late-container rule as the optimizer above; both ride `LEARNER_CRON`."""
    run_job("learner", get_container())


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    timezone = ZoneInfo(settings.timezone)
    scheduler = BackgroundScheduler(timezone=timezone)
    trigger = CronTrigger.from_crontab(settings.learner_cron, timezone=timezone)
    scheduler.add_job(
        _run_nightly_optimizer,
        trigger,
        id="optimizer",
        name="nightly FSRS optimizer (Learner stage A)",
    )
    scheduler.add_job(
        _run_nightly_learner,
        trigger,
        id="learner",
        name="nightly Learner stage B (writer_guidance)",
    )
    scheduler.start()
    try:
        yield
    finally:
        scheduler.shutdown(wait=False)


def create_app() -> FastAPI:
    """Build the application. Every entry point goes through here, tests included."""
    app = FastAPI(
        title="Recally",
        description="Turns O'Reilly reading highlights into scheduled flashcards.",
        version="0.1.0",
        lifespan=lifespan,
    )
    register_error_handlers(app)
    app.include_router(health.router)
    app.include_router(health.authed_router)
    app.include_router(ingest.router)
    app.include_router(decks.router)
    app.include_router(cards.router)
    app.include_router(reviews.router)
    app.include_router(stats.router)
    app.include_router(devices.router)
    app.include_router(jobs.router)
    return app


app = create_app()
