"""Alembic environment.

Two things here are load-bearing:

- `render_as_batch=True` (ADR-004). SQLite cannot `ALTER TABLE` a column, so without
  batch mode the first migration that changes one is unrunnable locally. Setting it
  from the very first migration means no migration is ever written against the wrong
  dialect; it is a no-op on Postgres.
- The URL comes from `RECALLY_DATABASE_URL` via `recally.config` unless the caller
  supplied one, so `alembic upgrade head` and the app can never disagree about which
  database they are talking to. Tests override it with `sqlalchemy.url`.
"""

from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from recally.config import get_settings
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
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

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
