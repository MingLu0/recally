"""Engine and session factory.

Nothing here is SQLite-specific in the SQL sense (ADR-004): the phase 2/3 Postgres
cutover is a change to `RECALLY_DATABASE_URL` and nothing else. The one file-backend
concession is creating the parent directory of a SQLite path, which is confined to the
branch below and is a no-op for every other dialect.
"""

from pathlib import Path

from sqlalchemy import Engine, create_engine, make_url
from sqlalchemy.orm import Session, sessionmaker

from recally.config import get_settings


def create_database_engine(database_url: str | None = None) -> Engine:
    """Build an engine for `database_url`, defaulting to the configured one."""
    url = make_url(database_url or get_settings().database_url)
    _ensure_sqlite_directory_exists(url.database if url.get_backend_name() == "sqlite" else None)
    return create_engine(url)


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
