"""Composition root for non-HTTP and HTTP entry points (ADR-007).

The watcher deliberately gets its database access here rather than constructing an
engine or session itself.  Future pipeline and API services join this same object
graph, so all ways of receiving an export converge on one ingestion operation.
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
    """The process-wide object graph shared by every entry point."""

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
        """Return a session scoped to one operation."""
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
    """Return the lazily built process-wide container."""
    return Container(get_settings())
