"""Shared fixtures. Currently just the embedded Postgres the step 7 tests run against.

The suite's default backend is still SQLite (roadmap step 7 does not switch the
running backend). What lives here is the *other* half of hard rule 4's proof: a real
PostgreSQL server, so "no SQLite-specific SQL" is something the suite executes rather
than something a grep asserts.

`pgserver` vendors a PostgreSQL 16 binary and runs it on a unix socket under a temp
directory, which keeps the gate reproducible with no system Postgres, no Docker and no
CI service container. The server is session-scoped because initdb costs a second or
two; isolation between tests comes from each one getting its own *database* on that
server, not its own server.
"""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from typing import TYPE_CHECKING

import pytest
from sqlalchemy import create_engine, text

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
def postgres_url(postgres_server_url: str) -> Iterator[str]:
    """A URL for an empty, uniquely-named database on the session's server.

    A fresh database per test rather than a fresh server: `CREATE DATABASE` is
    milliseconds, and it gives every test the empty target the migration script
    insists on without any cross-test cleanup ordering.
    """
    database_name = f"recally_test_{uuid.uuid4().hex}"
    admin_engine = create_engine(postgres_server_url, isolation_level="AUTOCOMMIT")
    try:
        with admin_engine.connect() as connection:
            connection.execute(text(f'CREATE DATABASE "{database_name}"'))
    finally:
        admin_engine.dispose()

    yield _with_database(postgres_server_url, database_name)

    admin_engine = create_engine(postgres_server_url, isolation_level="AUTOCOMMIT")
    try:
        with admin_engine.connect() as connection:
            # A leaked session would block the DROP; WITH (FORCE) ends them (PG 13+).
            connection.execute(text(f'DROP DATABASE IF EXISTS "{database_name}" WITH (FORCE)'))
    finally:
        admin_engine.dispose()


@pytest.fixture
def postgres_engine(postgres_url: str) -> Iterator[Engine]:
    """An engine on an empty Postgres database, disposed at the end of the test."""
    engine = create_engine(postgres_url)
    try:
        yield engine
    finally:
        engine.dispose()


def _with_database(server_url: str, database_name: str) -> str:
    """Point a server URL at `database_name`, keeping the socket host query string.

    `pgserver` connects over a unix socket, so the URL carries `?host=/tmp/...` and an
    empty netloc. Rebuilding it through `make_url` keeps that query intact, which naive
    string surgery on the last path segment would be fragile about.
    """
    from sqlalchemy import make_url

    return make_url(server_url).set(database=database_name).render_as_string(hide_password=False)
