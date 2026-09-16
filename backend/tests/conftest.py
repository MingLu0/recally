"""Shared fixtures: the embedded Postgres server, and the suite-wide test database.

The suite's default backend is still SQLite (roadmap step 7 does not switch the
running backend). What lives here is the *other* half of hard rule 4's proof: a real
PostgreSQL server, so "no SQLite-specific SQL" is something the suite executes rather
than something a grep asserts.

`pgserver` vendors a PostgreSQL 16 binary and runs it on a unix socket under a temp
directory, which keeps the gate reproducible with no system Postgres, no Docker and no
CI service container. The server is session-scoped because initdb costs a second or
two; isolation between tests comes from each one getting its own *database* on that
server, not its own server.

## The dual-backend switch

`test_engine` / `test_engine_factory` are the one engine fixture the whole suite
shares (issue #226 — 25 test files used to repeat the same `sqlite://` + `StaticPool`
incantation verbatim). Which backend they build on is read once from the
`RECALLY_TEST_BACKEND` environment variable:

- unset (the default): today's behaviour — a fresh in-memory SQLite database per
  engine, `StaticPool` on a single connection so the schema survives between the
  fixture and the code under test.
- `postgres`: a fresh, uniquely-named database per engine on the session's embedded
  server. A whole fresh database per engine (not a shared one plus TRUNCATE) keeps
  the semantics identical to the SQLite path, where every engine is its own empty
  database — so no test can pass or fail on leftover rows from a sibling.

Run the full suite against Postgres with:

    RECALLY_TEST_BACKEND=postgres uv run pytest

CI runs both invocations. `tests/test_pipeline_concurrency.py` is the one exception:
it is pinned to file-backed SQLite on purpose (see its module docstring, ADR-015) and
skips itself when the switch is set.
"""

from __future__ import annotations

import os
import uuid
from collections.abc import Callable, Iterator
from typing import TYPE_CHECKING

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from recally.models import Base

if TYPE_CHECKING:
    from sqlalchemy import Engine

# The dialect every Postgres URL in the suite uses. `psycopg` is psycopg 3; the bare
# `postgresql://` form would pick psycopg2, which is not installed.
POSTGRES_DIALECT = "postgresql+psycopg"


@pytest.fixture(scope="session")
def postgres_server_url() -> Iterator[str]:
    """A running embedded PostgreSQL, as a SQLAlchemy URL for its `postgres` database.

    Skips rather than fails when the vendored server cannot start: the binary is
    platform-specific, and a machine that cannot run it should report one clear skip
    instead of a cascade of connection errors.
    """
    pgserver = pytest.importorskip(
        "pgserver", reason="the embedded Postgres server is not installed"
    )

    import tempfile

    with tempfile.TemporaryDirectory() as server_directory:
        try:
            server = pgserver.get_server(server_directory)
        except Exception as exc:  # pragma: no cover - platform-dependent
            pytest.skip(f"could not start the embedded Postgres server: {exc}")
        try:
            yield server.get_uri().replace("postgresql://", f"{POSTGRES_DIALECT}://", 1)
        finally:
            server.cleanup()


@pytest.fixture
def postgres_url_factory(postgres_server_url: str) -> Iterator[Callable[[], str]]:
    """A factory for URLs of empty, uniquely-named databases on the session's server.

    A fresh database per call rather than a fresh server: `CREATE DATABASE` is
    milliseconds, and it gives every caller the empty target the migration script
    insists on without any cross-test cleanup ordering. Everything created is dropped
    at test teardown.
    """
    created_database_names: list[str] = []

    def factory() -> str:
        database_name = f"recally_test_{uuid.uuid4().hex}"
        admin_engine = create_engine(postgres_server_url, isolation_level="AUTOCOMMIT")
        try:
            with admin_engine.connect() as connection:
                connection.execute(text(f'CREATE DATABASE "{database_name}"'))
        finally:
            admin_engine.dispose()
        created_database_names.append(database_name)
        return _with_database(postgres_server_url, database_name)

    try:
        yield factory
    finally:
        if created_database_names:
            admin_engine = create_engine(postgres_server_url, isolation_level="AUTOCOMMIT")
            try:
                with admin_engine.connect() as connection:
                    for database_name in created_database_names:
                        # Leaked sessions would block the DROP; WITH (FORCE) ends
                        # them (PG 13+).
                        connection.execute(
                            text(f'DROP DATABASE IF EXISTS "{database_name}" WITH (FORCE)')
                        )
            finally:
                admin_engine.dispose()


@pytest.fixture
def postgres_url(postgres_url_factory: Callable[[], str]) -> str:
    """A URL for one empty database on the session's server."""
    return postgres_url_factory()


@pytest.fixture
def postgres_engine(postgres_url: str) -> Iterator[Engine]:
    """An engine on an empty Postgres database, disposed at the end of the test."""
    engine = create_engine(postgres_url)
    try:
        yield engine
    finally:
        engine.dispose()


def _postgres_backend_requested() -> bool:
    """The suite-wide switch: run the shared engine fixtures on Postgres."""
    return os.environ.get("RECALLY_TEST_BACKEND") == "postgres"


@pytest.fixture
def test_engine_factory(request: pytest.FixtureRequest) -> Iterator[Callable[[], Engine]]:
    """The one engine builder the suite shares (issue #226).

    Each call returns a fresh engine with `Base.metadata.create_all` already applied,
    on the backend `RECALLY_TEST_BACKEND` selects (see the module docstring). Engines
    are disposed at test teardown. `postgres_url_factory` is resolved lazily so the
    default SQLite run never starts the embedded server.
    """
    postgres_url_factory: Callable[[], str] | None = (
        request.getfixturevalue("postgres_url_factory") if _postgres_backend_requested() else None
    )
    built_engines: list[Engine] = []

    def factory() -> Engine:
        if postgres_url_factory is not None:
            engine = create_engine(postgres_url_factory())
        else:
            engine = create_engine(
                "sqlite://",
                connect_args={"check_same_thread": False},
                poolclass=StaticPool,
            )
        Base.metadata.create_all(engine)
        built_engines.append(engine)
        return engine

    try:
        yield factory
    finally:
        for engine in built_engines:
            engine.dispose()


@pytest.fixture
def test_engine(test_engine_factory: Callable[[], Engine]) -> Engine:
    """One fresh schema-ready engine for tests that need exactly one database."""
    return test_engine_factory()


def _with_database(server_url: str, database_name: str) -> str:
    """Point a server URL at `database_name`, keeping the socket host query string.

    `pgserver` connects over a unix socket, so the URL carries `?host=/tmp/...` and an
    empty netloc. Rebuilding it through `make_url` keeps that query intact, which naive
    string surgery on the last path segment would be fragile about.
    """
    from sqlalchemy import make_url

    return make_url(server_url).set(database=database_name).render_as_string(hide_password=False)
