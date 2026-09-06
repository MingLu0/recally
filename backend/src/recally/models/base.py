"""Declarative base and the column conventions every table shares.

Type choices are deliberately portable (ADR-004, "no SQLite-specific SQL"):

- JSON columns use SQLAlchemy's generic `JSON`, which compiles on both backends.
  Postgres `JSONB` would be faster to query but is dialect-specific, so it waits for
  the cutover — and the v1 JSON columns are read whole in Python anyway (ADR-004).
- Timestamps are `DateTime` without a timezone. SQLite has no native timestamp type
  and stores no offset, so the application keeps every stored datetime in UTC and
  converts at the edges; a `TIMESTAMPTZ` column would behave differently on the two
  backends for the same rows.
- Statuses and other small vocabularies are `String`, not `Enum`. The values are
  listed in docs/data-model.md; a database enum is a migration to change on Postgres
  and a rewritten CHECK constraint on SQLite, for no gain over validating in Python.
"""

from datetime import datetime, timezone

from sqlalchemy import Integer, MetaData, text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# Every constraint and index gets a deterministic name. This matters more than style
# here: `render_as_batch=True` rebuilds a SQLite table to alter it, and it can only
# reliably reproduce constraints it can name. Without a convention, SQLite invents
# anonymous names that a later migration cannot refer to, so this has to be in place
# before the first migration, not retrofitted after one exists.
NAMING_CONVENTION = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_N_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    """Base for every Recally table."""

    metadata = MetaData(naming_convention=NAMING_CONVENTION)


def utc_now() -> datetime:
    """Timezone-naive UTC, matching what the `DateTime` columns store."""
    return datetime.now(timezone.utc).replace(tzinfo=None)


class UserScopedMixin:
    """`user_id` on every table (ADR-004).

    v1 is single-user, so this is a plain integer defaulting to 1 with no foreign key;
    the `users` table and the FKs arrive with multi-user auth in roadmap phase 3. The
    default is a *server* default so it is baked into the schema and applies to rows
    written outside the ORM (a migration backfill, or `sqlite3` by hand at a gate).
    """

    user_id: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("1"))
