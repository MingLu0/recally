"""Composition root: the source of truth for how services are resolved (ADR-007).

Every entry point converges here — `api/deps.py`, the watcher, APScheduler, the CLI —
because most of them are not HTTP requests and so cannot be served by FastAPI's
`Depends`. Nothing in this module imports FastAPI, and nothing in it is request-scoped;
it holds the process-wide object graph and hands out sessions on demand.

Agent resolution joins this container in roadmap step 2, when the registry exists.
"""

from collections.abc import Iterator
from contextlib import contextmanager
from functools import lru_cache
from pathlib import Path

from sqlalchemy import Engine
from sqlalchemy.orm import Session, sessionmaker

from recally.config import Settings, get_settings
from recally.db import create_database_engine, create_session_factory
from recally.ingest import ingest_file
from recally.ingest.adapters import OReillyCsvAdapter
from recally.models import IngestRun


class Container:
    """The process-wide object graph.

    The engine and session factory are built once, here, and shared: a connection pool
    is expensive to create. `get_container` caches the single process-wide instance;
    tests build their own container against an in-memory database rather than reaching
    for the global one.
    """

    def __init__(self, settings: Settings, engine: Engine | None = None) -> None:
        self.settings = settings
        self._engine = engine or create_database_engine(settings.database_url)
        self._session_factory: sessionmaker[Session] = create_session_factory(self._engine)

    @property
    def engine(self) -> Engine:
        return self._engine

    @property
    def session_factory(self) -> sessionmaker[Session]:
        return self._session_factory

    @contextmanager
    def session(self) -> Iterator[Session]:
        """A session scoped to one unit of work, closed however the block exits."""
        session = self._session_factory()
        try:
            yield session
        finally:
            session.close()

    def ingest_oreilly_export(self, file: Path) -> IngestRun:
        """Run the deterministic O'Reilly adapter and dedupe transaction.

        Pipeline processing is added after the agent pipeline exists; keeping this
        operation at the composition root means the watcher and future upload route
        will use the same path.
        """
        with self.session() as session:
            run = ingest_file(session, file, OReillyCsvAdapter())
            session.commit()
            return run


@lru_cache(maxsize=1)
def get_container() -> Container:
    """The process-wide container, built on first use."""
    return Container(get_settings())
