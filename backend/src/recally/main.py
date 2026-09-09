"""The FastAPI app factory. Run with `uv run uvicorn recally.main:app --reload`.

The lifespan resolves settings so a missing `RECALLY_API_KEY` fails startup rather
than the first request; it also takes ownership of the watcher and the APScheduler
jobs when those land (roadmap steps 1f and 5), which is why startup work lives there
and not at module import time.
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from recally.api.errors import register_error_handlers
from recally.api.routers import cards, decks, devices, ingest, jobs, reviews, stats
from recally.config import get_settings


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    get_settings()
    yield


def create_app() -> FastAPI:
    """Build the application. Every entry point goes through here, tests included."""
    app = FastAPI(
        title="Recally",
        description="Turns O'Reilly reading highlights into scheduled flashcards.",
        version="0.1.0",
        lifespan=lifespan,
    )
    register_error_handlers(app)
    app.include_router(ingest.router)
    app.include_router(decks.router)
    app.include_router(cards.router)
    app.include_router(reviews.router)
    app.include_router(stats.router)
    app.include_router(devices.router)
    app.include_router(jobs.router)
    return app


app = create_app()
