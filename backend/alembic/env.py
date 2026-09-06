"""Alembic environment.

Two things here are load-bearing:

- `render_as_batch=True` (ADR-004). SQLite cannot `ALTER TABLE` a column, so without
  batch mode the first migration that changes one is unrunnable locally. Setting it
  from the very first migration means no migration is ever written against the wrong
  dialect; it is a no-op on Postgres.
- The URL comes from `RECALLY_DATABASE_URL` via `recally.config` unless the caller
  supplied one, so `alembic upgrade head` and the app can never disagree about which
  database they are talking to. Tests override it with `sqlalchemy.url`.

The online engine is built by `recally.db` rather than Alembic's `engine_from_config`
so that migrations and the app share one construction path — in particular the SQLite
parent-directory creation, without which `alembic upgrade head` on a fresh clone fails
before it can create the file the default URL points at.
"""

from logging.config import fileConfig

from alembic import context

from recally.config import get_settings
from recally.db import create_database_engine
from recally.models import Base

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

if not config.get_main_option("sqlalchemy.url", default=None):
    # `%` is the config parser's interpolation character; a URL containing one (a
    # percent-encoded password, say) has to be escaped before it goes in.
    config.set_main_option("sqlalchemy.url", get_settings().database_url.replace("%", "%%"))

target_metadata = Base.metadata


def run_migrations_offline() -> None:
    """Emit SQL to stdout without a DBAPI connection (`alembic upgrade --sql`)."""
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        render_as_batch=True,
        compare_type=True,
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    """Run migrations against a live connection."""
    connectable = create_database_engine(config.get_main_option("sqlalchemy.url"))

    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=True,
            compare_type=True,
        )

        with context.begin_transaction():
            context.run_migrations()

    connectable.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
