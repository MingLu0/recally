"""Engine and session factory.

Nothing here is SQLite-specific (ADR-004): the phase 2/3 Postgres cutover is a change
to `RECALLY_DATABASE_URL` and nothing else.
"""

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from recally.config import get_settings


def create_database_engine(database_url: str | None = None) -> Engine:
    """Build an engine for `database_url`, defaulting to the configured one."""
    return create_engine(database_url or get_settings().database_url)


def create_session_factory(engine: Engine) -> sessionmaker[Session]:
    """Session factory for `engine`; `container.py` owns the process-wide instance."""
    return sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
