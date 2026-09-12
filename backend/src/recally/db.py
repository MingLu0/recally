"""Engine and session factory.

Nothing here is SQLite-specific in the SQL sense (ADR-004): the phase 2/3 Postgres
cutover is a change to `RECALLY_DATABASE_URL` and nothing else. Two file-backend
concessions live in the one `sqlite` branch below and are absent for every other
dialect — creating the parent directory of a SQLite path, and putting the database in
WAL mode. Both are connection setup, not query text, so no SQL in the codebase is
dialect-bound.
"""

from pathlib import Path
from typing import Any

from sqlalchemy import Engine, create_engine, event, make_url
from sqlalchemy.orm import Session, sessionmaker

from recally.config import get_settings


def create_database_engine(database_url: str | None = None) -> Engine:
    """Build an engine for `database_url`, defaulting to the configured one."""
    url = make_url(database_url or get_settings().database_url)
    engine = create_engine(url)
    if url.get_backend_name() == "sqlite":
        _ensure_sqlite_directory_exists(url.database)
        _enable_write_ahead_logging(engine)
    return engine


def _enable_write_ahead_logging(engine: Engine) -> None:
    """Put a SQLite database in WAL mode on every new connection.

    Connection configuration, not SQL, so ADR-004 holds: no query in the codebase
    changes, and the pragma is simply absent on Postgres. WAL lets the pipeline's
    worker threads write their `llm_calls` rows while the main thread reads and
    commits units (ADR-015) — the default rollback journal blocks readers behind a
    writer, which under `LLM_CONCURRENCY > 1` shows up as `database is locked`.

    WAL is necessary but not sufficient: it gives one writer and many readers, while
    this design has concurrent *writers*. Those still serialize, and whether a
    blocked writer waits or raises depends on the busy timeout — Python's `sqlite3`
    defaults to `timeout=5.0`, which the concurrency tests exercise rather than
    assume.

    Note this fires only for engines built here. A test hand-rolling its own engine
    bypasses WAL entirely.
    """

    @event.listens_for(engine, "connect")
    def _set_journal_mode(dbapi_connection: Any, _connection_record: Any) -> None:
        cursor = dbapi_connection.cursor()
        try:
            cursor.execute("PRAGMA journal_mode=WAL")
        finally:
            cursor.close()


def create_session_factory(engine: Engine) -> sessionmaker[Session]:
    """Session factory for `engine`; `container.py` owns the process-wide instance."""
    return sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def _ensure_sqlite_directory_exists(database_path: str | None) -> None:
    """Create the folder a SQLite file lives in, if it is not there yet.

    The default `sqlite:///data/recally.db` points at a gitignored directory, so a
    fresh clone has no `data/` and SQLite reports the miss as "unable to open database
    file" — an opaque 500 on the first request rather than anything actionable.
    `:memory:` and a bare `sqlite://` have no path and are skipped.
    """
    if not database_path or database_path == ":memory:":
        return
    Path(database_path).parent.mkdir(parents=True, exist_ok=True)
