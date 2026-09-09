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

from recally import pipeline
from recally.agents.base import Critic, Curator, Learner, Writer
from recally.agents.registry import AgentRegistry, default_registry
from recally.config import Settings, get_settings
from recally.db import create_database_engine, create_session_factory
from recally.ingest import ingest_file
from recally.ingest.adapters import OReillyCsvAdapter
from recally.llm import LlmCaller
from recally.models import IngestRun
from recally.scheduling.fsrs import FsrsScheduler


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
    def agent_registry(self) -> AgentRegistry:
        """The registry the container resolves agents through (ADR-007).

        Exposed so pipeline entry points (`run_pipeline`, the Learner job's leech
        rewrites) bind the same registry — test-local stubs included — rather than
        falling back to the process-wide default.
        """
        return self._agent_registry

    @property
    def engine(self) -> Engine:
        return self._engine

    @property
    def session_factory(self) -> sessionmaker[Session]:
        return self._session_factory

    def fsrs_scheduler(self, session: Session) -> FsrsScheduler:
        """The py-fsrs wrapper for one unit of work.

        Built per unit of work rather than once: the latest `fsrs_params` row is
        active (docs/data-model.md), and a fit written by the nightly Learner must
        take effect without a restart.
        """
        return FsrsScheduler.build(self.settings, session)

    @contextmanager
    def session(self) -> Iterator[Session]:
        """A session scoped to one unit of work, closed however the block exits."""
        session = self._session_factory()
        try:
            yield session
        finally:
            session.close()

    def ingest_oreilly_export(self, file: Path) -> IngestRun:
        """Run the deterministic O'Reilly adapter and dedupe, then the pipeline.

        This is the watcher's and the upload route's single path (docs/backend.md,
        "Wiring and entry points"): ingest writes the `highlights` rows and the
        `ingest_runs` row, then the pipeline (Curator → Writer ⇄ Critic) processes
        every `processed=false` highlight against that run. A pipeline failure is
        recorded on the run row, never raised (docs/architecture.md, "Failure
        handling"), so a bad export cannot kill the watcher.
        """
        with self.session() as session:
            run = ingest_file(session, file, OReillyCsvAdapter())
            session.commit()
            ingest_run_id = run.id
        self.run_pipeline(ingest_run_id)
        with self.session() as session:
            refreshed = session.get(IngestRun, ingest_run_id)
            if refreshed is None:  # pragma: no cover - the row was committed above
                raise RuntimeError(f"ingest_runs row {ingest_run_id} vanished")
            return refreshed

    def run_pipeline(self, ingest_run_id: int) -> None:
        """Curator → Writer ⇄ Critic over every unprocessed highlight (docs/agents.md).

        Kept at the composition root so the watcher, `POST /ingest`, the CLI and the
        tests all drive the same runner (docs/backend.md, "Wiring and entry points").
        """
        pipeline.run(
            settings=self.settings,
            session_factory=self._session_factory,
            llm_caller=self.llm_caller,
            ingest_run_id=ingest_run_id,
            agent_registry=self._agent_registry,
        )


@lru_cache(maxsize=1)
def get_container() -> Container:
    """The process-wide container, built on first use."""
    return Container(get_settings())
