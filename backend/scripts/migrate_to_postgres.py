"""Copy a SQLite Recally database into Postgres through the ORM (roadmap step 7).

`.dump | psql` does not work here. SQLite is dynamically typed and Postgres is not, so
a textual transfer has four coercion risks, every one of them silent if unchecked
(issue #224):

| Risk | Where |
|---|---|
| Booleans stored `0`/`1` | `highlights.truncated`, `highlights.processed` |
| Timestamps stored as text | every `DateTime` column |
| Generic `JSON` stored as TEXT | `cards.tags`, `push_runs.card_ids`,
  `fsrs_params.parameters`, `llm_calls.request`/`response` |
| Sequences do not transfer | every `Integer` primary key |

Reading *and* writing through the same SQLAlchemy models makes all four SQLAlchemy's
problem rather than ours: the same `Boolean`, `DateTime` and `JSON` types that decoded
the SQLite value encode the Postgres one. That is the whole trick, and it is why this
is a Python script and not a shell pipeline.

Three safety properties this file is responsible for:

- **The source is opened read-only and never written.** Not merely "we only SELECT" —
  the URL carries SQLite's `mode=ro`, so a stray write raises instead of succeeding.
  That keeps the SQLite file a complete working database, which is what makes rollback
  just pointing `RECALLY_DATABASE_URL` back at it.
- **A non-empty target is refused**, so a re-run after a partial failure cannot append
  a second copy of every row.
- **Verification is a separate command** (`--verify-only`), so it can be re-run against
  a live database at any time rather than only in the seconds after a copy.

This does not switch the running backend. Both backends stay supported indefinitely;
that dual support *is* the rollback (issue #224).

Usage, from `backend/`:

    uv run python scripts/migrate_to_postgres.py \\
        --source sqlite:///../data/recally.db \\
        --target postgresql+psycopg://user:pass@localhost/recally

    uv run python scripts/migrate_to_postgres.py --source ... --target ... --verify-only

Verification is a separate command so it can be re-run against a live database at any
time, rather than only in the seconds after a copy.
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import quote

# Import from the source tree without installing the script as a package
# (same bootstrap as scripts/seed_demo.py).
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from sqlalchemy import (  # noqa: E402
    Engine,
    Table,
    create_engine,
    func,
    inspect,
    make_url,
    select,
    text,
)
from sqlalchemy.orm import Session  # noqa: E402

from recally.models import Base  # noqa: E402

# Rows are moved in batches rather than one statement per row: the real database is
# tens of thousands of `llm_calls` rows, and a round trip each would dominate. Small
# enough that a batch's parameters stay well inside Postgres' 65535-parameter limit
# even for the widest table.
COPY_BATCH_SIZE = 500


class MigrationRefused(Exception):
    """The migration will not run, because doing so could lose or duplicate data.

    Raised before anything is written. The two cases are a target that already has
    rows, and a source that is missing or unreadable.
    """


class VerificationFailed(Exception):
    """The copy finished but the target does not match the source."""


@dataclass(frozen=True)
class TableComparison:
    """One row of the summary table: what each side holds for one table."""

    table_name: str
    source_rows: int
    target_rows: int

    @property
    def matches(self) -> bool:
        return self.source_rows == self.target_rows


@dataclass(frozen=True)
class VerificationReport:
    """The result of comparing a target against its source."""

    comparisons: tuple[TableComparison, ...]

    @property
    def ok(self) -> bool:
        return all(comparison.matches for comparison in self.comparisons)

    @property
    def mismatches(self) -> tuple[TableComparison, ...]:
        return tuple(comparison for comparison in self.comparisons if not comparison.matches)

    def render(self) -> str:
        """A fixed-width summary table, printed by the CLI on success and failure."""
        name_width = max(len("table"), *(len(row.table_name) for row in self.comparisons))
        lines = [
            f"{'table'.ljust(name_width)}  {'sqlite':>10}  {'postgres':>10}  status",
            f"{'-' * name_width}  {'-' * 10}  {'-' * 10}  ------",
        ]
        for comparison in self.comparisons:
            status = "ok" if comparison.matches else "MISMATCH"
            lines.append(
                f"{comparison.table_name.ljust(name_width)}  "
                f"{comparison.source_rows:>10}  {comparison.target_rows:>10}  {status}"
            )
        total_source = sum(row.source_rows for row in self.comparisons)
        total_target = sum(row.target_rows for row in self.comparisons)
        lines.append(
            f"{'TOTAL'.ljust(name_width)}  {total_source:>10}  {total_target:>10}  "
            f"{'ok' if self.ok else 'MISMATCH'}"
        )
        return "\n".join(lines)


def migrate(*, source_url: str, target_url: str) -> VerificationReport:
    """Copy every table from `source_url` into `target_url` and verify the result.

    Refuses a non-empty target. Returns the verification report; raises
    `VerificationFailed` if the copy did not reproduce the source.
    """
    source_engine = _create_read_only_source_engine(source_url)
    target_engine = create_engine(target_url)
    try:
        _refuse_unless_target_is_empty(target_engine)
        _upgrade_target_schema(target_url)
        # `create_all` is deliberately not used: running the same Alembic migrations
        # makes the schema identical to production by construction rather than by
        # translation, and stamps `alembic_version` so later migrations still apply.
        _refuse_unless_target_is_empty(target_engine)

        for table in Base.metadata.sorted_tables:
            _copy_table(table, source_engine=source_engine, target_engine=target_engine)

        _reset_sequences(target_engine)

        report = verify(source_engine=source_engine, target_engine=target_engine)
        if not report.ok:
            raise VerificationFailed("row counts differ after the copy:\n" + report.render())
        return report
    finally:
        source_engine.dispose()
        target_engine.dispose()


def verify(*, source_engine: Engine, target_engine: Engine) -> VerificationReport:
    """Compare per-table row counts between two live databases.

    Separate from `migrate` on purpose: this is re-runnable against a database that has
    been serving traffic for a week, which a check welded into the copy would not be.
    """
    return VerificationReport(
        comparisons=tuple(
            TableComparison(
                table_name=table_name,
                source_rows=_count_rows(source_engine, table),
                target_rows=_count_rows(target_engine, table),
            )
            for table_name, table in Base.metadata.tables.items()
        )
    )


def _create_read_only_source_engine(source_url: str) -> Engine:
    """An engine that physically cannot write to the source database.

    For SQLite this is a `file:...?mode=ro` URI, which makes a write raise
    `OperationalError` rather than succeed quietly. `recally.db.create_database_engine`
    is deliberately *not* used: it puts the database in WAL mode, and that alone
    rewrites the file — the source would no longer be byte-identical even though no row
    changed. A non-SQLite source is returned as an ordinary engine; the script is only
    ever pointed at SQLite, but the guard should not silently drop for other URLs.
    """
    url = make_url(source_url)
    if url.get_backend_name() != "sqlite":
        return create_engine(source_url)

    database_path = url.database
    if not database_path or database_path == ":memory:":
        raise MigrationRefused(f"the source must be a SQLite file, got {source_url!r}")
    resolved_path = Path(database_path).expanduser()
    if not resolved_path.exists():
        raise MigrationRefused(f"source database does not exist: {resolved_path}")

    # SQLite's read-only mode is requested through a `file:...?mode=ro` URI, which
    # `sqlite3.connect(uri=True)` understands. It is passed via a `creator` rather than
    # embedded in the SQLAlchemy URL because SQLAlchemy parses the part after
    # `sqlite:///` as a filesystem path and mangles a nested URI, whatever the slash
    # count — the connection then silently lands on a relative path that does not
    # exist. Going through `creator` hands sqlite3 the exact string it needs.
    read_only_uri = f"file:{quote(str(resolved_path.resolve()))}?mode=ro"

    def connect_read_only() -> Any:
        return sqlite3.connect(read_only_uri, uri=True, check_same_thread=False)

    return create_engine("sqlite://", creator=connect_read_only)


def _refuse_unless_target_is_empty(target_engine: Engine) -> None:
    """Raise unless every mapped table on the target is absent or empty.

    The realistic accident is a re-run — after a partial failure, or against the wrong
    URL. Appending would duplicate every row and leave `card_state` with two rows per
    card, which is worse than an outright failure because the app would still start.
    """
    existing_tables = set(inspect(target_engine).get_table_names())
    populated: list[str] = []
    for table_name, table in Base.metadata.tables.items():
        if table_name not in existing_tables:
            continue
        if _count_rows(target_engine, table) > 0:
            populated.append(table_name)
    if populated:
        raise MigrationRefused(
            "target database is not empty; refusing to write into it. "
            f"Tables with rows: {', '.join(sorted(populated))}"
        )


def _upgrade_target_schema(target_url: str) -> None:
    """Run `alembic upgrade head` against the target.

    Same migrations as production, so the schema is identical by construction rather
    than by translation — and `alembic_version` ends up stamped, so the next migration
    applies normally instead of trying to recreate everything.
    """
    from alembic import command
    from alembic.config import Config

    backend_root = Path(__file__).resolve().parents[1]
    config = Config(str(backend_root / "alembic.ini"))
    config.set_main_option("script_location", str(backend_root / "alembic"))
    # `%` is the config parser's interpolation character, and a Postgres password may
    # legitimately contain a percent-encoded byte.
    config.set_main_option("sqlalchemy.url", target_url.replace("%", "%%"))
    command.upgrade(config, "head")


def _copy_table(table: Table, *, source_engine: Engine, target_engine: Engine) -> None:
    """Copy one table, reading and writing through the same SQLAlchemy types.

    The values handed to the INSERT are the Python objects SQLAlchemy decoded on the
    SQLite side — `True`, `datetime(...)`, `["a", "b"]` — never the raw stored `1`,
    text timestamp or JSON string. Re-encoding them for Postgres is then the same type
    object's job, which is what removes all four coercion risks at once.

    Ids are copied explicitly rather than regenerated: every foreign key in the
    database refers to them, and `_reset_sequences` fixes up the sequences afterwards.
    """
    with (
        Session(source_engine) as source_session,
        Session(target_engine) as target_session,
    ):
        for batch in _read_in_batches(source_session, table):
            target_session.execute(table.insert(), batch)
        target_session.commit()


def _read_in_batches(session: Session, table: Table) -> Iterator[list[dict[str, Any]]]:
    """Yield the table's rows as batches of plain dicts, ordered deterministically.

    Ordering by the primary key keeps a run reproducible and keeps self-referencing
    rows (`cards.supersedes_card_id`) in ascending id order, which matters if the FK is
    ever made immediate rather than deferred.
    """
    statement = select(table)
    primary_key_columns = list(table.primary_key.columns)
    if primary_key_columns:
        statement = statement.order_by(*primary_key_columns)

    result = session.execute(statement)
    while batch := result.mappings().fetchmany(COPY_BATCH_SIZE):
        yield [dict(row) for row in batch]


def _reset_sequences(target_engine: Engine) -> None:
    """Set every identity sequence to `max(id) + 1`.

    Copying explicit ids leaves the sequences at 1, so the first insert after a cutover
    fails on a duplicate key — a failure that would greet the first real request rather
    than the migration. `pg_get_serial_sequence` is asked for the sequence name so this
    keeps working if a column's sequence is ever renamed.

    `setval(..., false)` on an empty table leaves the sequence pointing *at* the given
    value rather than past it, which is why the empty case passes 1 with `false` and
    the populated case passes `max(id)` with `true`.
    """
    if target_engine.dialect.name != "postgresql":
        return

    with target_engine.begin() as connection:
        for table in Base.metadata.sorted_tables:
            for column in table.primary_key.columns:
                # Only integer PKs have a sequence; a composite text key has none.
                # `python_type` raises for a type that has no Python equivalent, which
                # is why this asks rather than assumes.
                try:
                    is_integer_key = column.type.python_type is int
                except NotImplementedError:
                    is_integer_key = False
                if not is_integer_key:
                    continue
                sequence_name = connection.execute(
                    text("SELECT pg_get_serial_sequence(:table_name, :column_name)"),
                    {"table_name": table.name, "column_name": column.name},
                ).scalar()
                if sequence_name is None:
                    # A plain integer PK with no sequence behind it — `card_state.card_id`
                    # and the `curated_unit_highlights` pair are supplied by the caller.
                    continue
                highest_id = connection.execute(
                    select(func.max(column)).select_from(table)
                ).scalar()
                if highest_id is None:
                    connection.execute(
                        text("SELECT setval(:sequence_name, 1, false)"),
                        {"sequence_name": sequence_name},
                    )
                else:
                    connection.execute(
                        text("SELECT setval(:sequence_name, :highest_id, true)"),
                        {"sequence_name": sequence_name, "highest_id": highest_id},
                    )


def _count_rows(engine: Engine, table: Table) -> int:
    """`SELECT count(*)` for one table, or 0 if the table is not there yet."""
    if not inspect(engine).has_table(table.name):
        return 0
    with engine.connect() as connection:
        return connection.execute(select(func.count()).select_from(table)).scalar_one()


def _parse_arguments(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="migrate_to_postgres",
        description=(
            "Copy a SQLite Recally database into Postgres through the ORM, "
            "then verify it row for row."
        ),
    )
    parser.add_argument(
        "--source",
        required=True,
        help="SQLite URL to read, e.g. sqlite:///../data/recally.db (opened read-only)",
    )
    parser.add_argument(
        "--target",
        required=True,
        help="Postgres URL to write, e.g. postgresql+psycopg://user:pass@host/recally",
    )
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Compare an existing target against the source without copying anything.",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    """CLI entry point. Returns the process exit code; non-zero on any mismatch."""
    arguments = _parse_arguments(argv)

    if arguments.verify_only:
        source_engine = _create_read_only_source_engine(arguments.source)
        target_engine = create_engine(arguments.target)
        try:
            report = verify(source_engine=source_engine, target_engine=target_engine)
        finally:
            source_engine.dispose()
            target_engine.dispose()
        print(report.render())
        if not report.ok:
            print("\nVERIFICATION FAILED: the target does not match the source.")
            return 1
        print("\nVerification passed.")
        return 0

    try:
        report = migrate(source_url=arguments.source, target_url=arguments.target)
    except MigrationRefused as refusal:
        print(f"Refusing to migrate: {refusal}", file=sys.stderr)
        return 2
    except VerificationFailed as failure:
        print(str(failure), file=sys.stderr)
        return 1

    print(report.render())
    print("\nMigration complete and verified.")
    print(
        "The SQLite source was opened read-only and is unchanged; "
        "rollback is RECALLY_DATABASE_URL back to it."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
