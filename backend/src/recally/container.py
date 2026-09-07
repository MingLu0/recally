"""Composition root: the source of truth for how services are resolved (ADR-007).

Every entry point converges here — `api/deps.py`, the watcher, APScheduler, the CLI —
because most of them are not HTTP requests and so cannot be served by FastAPI's
`Depends`. Nothing in this module imports FastAPI, and nothing in it is request-scoped;
it holds the process-wide object graph and hands out sessions on demand.

Agent resolution lives here: the container builds the single `LlmCaller`, validates
the configured `AGENT_*` variants against the registry at build time (so an unknown
variant fails at startup, ADR-007), and hands out resolved agents via `agent()`.
"""

from collections.abc import Iterator
from contextlib import contextmanager
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal, overload

from sqlalchemy import Engine
from sqlalchemy.orm import Session, sessionmaker

from recally.agents.base import Critic, Curator, Learner, Writer
from recally.agents.registry import AgentRegistry, default_registry
from recally.config import Settings, get_settings
from recally.db import create_database_engine, create_session_factory
from recally.ingest import ingest_file
from recally.ingest.adapters import OReillyCsvAdapter
from recally.llm import LlmCaller
from recally.models import IngestRun


class Container:
    """The process-wide object graph.

    The engine and session factory are built once, here, and shared: a connection pool
    is expensive to create. `get_container` caches the single process-wide instance;
    tests build their own container against an in-memory database rather than reaching
    for the global one.
    """

    def __init__(
        self,
        settings: Settings,
        engine: Engine | None = None,
        *,
        registry: AgentRegistry | None = None,
    ) -> None:
        self.settings = settings
        self._engine = engine or create_database_engine(settings.database_url)
        self._session_factory: sessionmaker[Session] = create_session_factory(self._engine)
        # One caller for the process: it opens its own session per call to write the
        # `llm_calls` row and commits independently, so a pipeline rollback cannot
        # lose the trace (ADR-006). This is the intended exception to "agents hold
        # no DB session" — agents receive the callable, never the factory.
        self.llm_caller = LlmCaller(self._session_factory, log_payloads=settings.llm_log_payloads)
        self._agent_registry = registry or default_registry
        # Fail at startup, not mid-run: a configured `AGENT_*` variant that is not
        # registered for its role raises here (ADR-007).
        self._agent_registry.validate(settings)

    @overload
    def agent(self, role: Literal["curator"]) -> Curator: ...

    @overload
    def agent(self, role: Literal["writer"]) -> Writer: ...

    @overload
    def agent(self, role: Literal["critic"]) -> Critic: ...

    @overload
    def agent(self, role: Literal["learner"]) -> Learner: ...

    def agent(self, role: str) -> Any:
        """The implementation for the role's configured variant, via the registry."""
        return self._agent_registry.resolve(role, self.settings)

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
