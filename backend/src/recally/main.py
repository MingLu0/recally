"""The FastAPI app factory. Run with `uv run uvicorn recally.main:app --reload`.

The lifespan takes ownership of the watcher and the APScheduler jobs when those land
(roadmap steps 1f and 5); today it only exists so those have somewhere to go that is
not module import time.
"""

from fastapi import FastAPI

from recally.api.errors import register_error_handlers
from recally.api.routers import decks, ingest


def create_app() -> FastAPI:
    """Build the application. Every entry point goes through here, tests included."""
    app = FastAPI(
        title="Recally",
        description="Turns O'Reilly reading highlights into scheduled flashcards.",
        version="0.1.0",
    )
    register_error_handlers(app)
    app.include_router(ingest.router)
    app.include_router(decks.router)
    return app


app = create_app()
