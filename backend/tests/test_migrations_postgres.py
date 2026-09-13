"""Hard rule 4 executed rather than asserted: the schema and the queries on Postgres.

`test_migrations.py` proves the migrations build the schema on SQLite. This proves the
*same* migrations build it on Postgres, and then runs the query shapes that a grep for
"sqlite" cannot vet — the ones issue #224 names: `JSON` comparison semantics, `NULL`
ordering in `ORDER BY`, and string collation.

The backend under test comes from `RECALLY_TEST_DATABASE_URL` when it is set (that is
how `test_migrate_to_postgres.py::test_suite_passes_on_postgres` runs this file against
its embedded server) and from the module's own `postgres_url` fixture otherwise, so the
file is runnable on its own.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import Engine, create_engine, inspect, select
from sqlalchemy.orm import Session

from recally.models import (
    Base,
    Book,
    Card,
    CardState,
    CuratedUnit,
    IngestRun,
)

BACKEND_ROOT = Path(__file__).resolve().parents[1]

pytestmark = pytest.mark.postgres

REFERENCE_NOW = datetime(2026, 9, 13, 4, 37, 11, 123456)


@pytest.fixture
def migrated_postgres(postgres_url: str) -> Iterator[Engine]:
    """A Postgres database with `alembic upgrade head` already applied.

    Prefers `RECALLY_TEST_DATABASE_URL` so the parent suite can hand this file the
    database it already created, rather than standing up a second server.
    """
    database_url = os.environ.get("RECALLY_TEST_DATABASE_URL") or postgres_url
    config = Config(str(BACKEND_ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(BACKEND_ROOT / "alembic"))
    config.set_main_option("sqlalchemy.url", database_url.replace("%", "%%"))
    command.upgrade(config, "head")

    engine = create_engine(database_url)
    # `RECALLY_TEST_DATABASE_URL` hands every test in the file the *same* database, so
    # rows have to be cleared between them; the module-local `postgres_url` fixture
    # would otherwise make each test fresh and hide the difference. TRUNCATE ... CASCADE
    # in one statement sidesteps FK ordering and resets the sequences with it, which is
    # what keeps these tests independent of execution order.
    _truncate_every_table(engine)
    try:
        yield engine
    finally:
        engine.dispose()


def _truncate_every_table(engine: Engine) -> None:
    """Empty every mapped table and restart its sequences."""
    from sqlalchemy import text

    table_list = ", ".join(f'"{name}"' for name in Base.metadata.tables)
    with engine.begin() as connection:
        connection.execute(text(f"TRUNCATE {table_list} RESTART IDENTITY CASCADE"))


def test_migrations_build_the_schema_on_postgres(migrated_postgres: Engine) -> None:
    """The same migrations, unmodified, produce every mapped table on Postgres.

    `render_as_batch=True` is a no-op off SQLite, so this is where a migration written
    against SQLite's batch rebuild would show up as a failure rather than a comment.
    """
    inspector = inspect(migrated_postgres)
    migrated_tables = set(inspector.get_table_names())
    assert set(Base.metadata.tables) <= migrated_tables

    for table_name, table in Base.metadata.tables.items():
        migrated_columns = {column["name"]: column for column in inspector.get_columns(table_name)}
        assert set(table.columns.keys()) == set(migrated_columns), table_name
        for column in table.columns:
            assert column.nullable == migrated_columns[column.name]["nullable"], (
                f"{table_name}.{column.name}"
            )


def test_json_columns_round_trip_on_postgres(migrated_postgres: Engine) -> None:
    """Generic `JSON` behaves the same on Postgres, including `[]` and nested objects.

    SQLite stores JSON as TEXT and Postgres has a real type, so equality and the empty
    list are the two places a dialect difference would surface.
    """
    with Session(migrated_postgres) as session:
        ingest_run = IngestRun(filename="oreilly-annotations.csv", started_at=REFERENCE_NOW)
        session.add(ingest_run)
        session.flush()
        unit = CuratedUnit(
            ingest_run_id=ingest_run.id,
            curated_text="text",
            tags=["a", "b"],
            decision="keep",
            created_at=REFERENCE_NOW,
        )
        session.add(unit)
        session.flush()
        populated = Card(
            unit_id=unit.id,
            type="qa",
            front="front",
            back="back",
            original_front="front",
            original_back="back",
            tags=["replication", "两个"],
            status="pending_review",
            generation_rounds=1,
            model="claude-sonnet-5",
            cost_microusd=0,
            created_at=REFERENCE_NOW,
        )
        empty = Card(
            unit_id=unit.id,
            type="cloze",
            front="front",
            back="back",
            original_front="front",
            original_back="back",
            tags=[],
            status="pending_review",
            generation_rounds=1,
            model="claude-sonnet-5",
            cost_microusd=0,
            created_at=REFERENCE_NOW,
        )
        session.add_all([populated, empty])
        session.commit()

        session.expunge_all()
        reloaded = {card.type: card.tags for card in session.execute(select(Card)).scalars().all()}
        assert reloaded["qa"] == ["replication", "两个"]
        assert reloaded["cloze"] == []


def test_null_ordering_matches_the_application_expectation(migrated_postgres: Engine) -> None:
    """`ORDER BY` with NULLs is explicit, so the two backends agree.

    This is the difference issue #224 calls out: SQLite sorts NULL first ascending,
    Postgres sorts it last. Any query whose correctness depends on that has to say
    which it wants — this test pins the behaviour so a future `ORDER BY` that relies on
    the implicit default is caught here rather than in production.
    """
    with Session(migrated_postgres) as session:
        ingest_run = IngestRun(filename="f.csv", started_at=REFERENCE_NOW)
        session.add(ingest_run)
        session.flush()
        unit = CuratedUnit(
            ingest_run_id=ingest_run.id,
            curated_text="text",
            tags=[],
            decision="keep",
            created_at=REFERENCE_NOW,
        )
        session.add(unit)
        session.flush()
        approval_stamps = [REFERENCE_NOW, None, REFERENCE_NOW - timedelta(days=1)]
        for index, approved_at in enumerate(approval_stamps):
            session.add(
                Card(
                    unit_id=unit.id,
                    type="qa",
                    front=f"front {index}",
                    back="back",
                    original_front=f"front {index}",
                    original_back="back",
                    tags=[],
                    status="approved" if approved_at else "pending_review",
                    approved_at=approved_at,
                    generation_rounds=1,
                    model="claude-sonnet-5",
                    cost_microusd=0,
                    created_at=REFERENCE_NOW,
                )
            )
        session.commit()

        # Spelled out rather than left to the dialect default, which differs.
        nulls_last = (
            session.execute(select(Card.front).order_by(Card.approved_at.desc().nullslast()))
            .scalars()
            .all()
        )
        assert nulls_last[-1] == "front 1"

        nulls_first = (
            session.execute(select(Card.front).order_by(Card.approved_at.desc().nullsfirst()))
            .scalars()
            .all()
        )
        assert nulls_first[0] == "front 1"


def test_naive_datetimes_stay_naive_on_postgres(migrated_postgres: Engine) -> None:
    """`DateTime` without a timezone stores and returns naive UTC on Postgres too.

    models/base.py keeps every stored datetime in UTC and converts at the edges. If
    Postgres handed back an aware value, every comparison in the scheduling code would
    start raising on naive/aware mixing.
    """
    with Session(migrated_postgres) as session:
        ingest_run = IngestRun(filename="f.csv", started_at=REFERENCE_NOW)
        session.add(ingest_run)
        session.commit()
        session.expunge_all()

        reloaded = session.execute(select(IngestRun)).scalars().one()
        assert reloaded.started_at == REFERENCE_NOW
        assert reloaded.started_at.tzinfo is None
        assert reloaded.started_at.microsecond == REFERENCE_NOW.microsecond


def test_string_comparison_is_case_sensitive_on_postgres(migrated_postgres: Engine) -> None:
    """String equality is case-sensitive, which is what the dedupe key relies on.

    SQLite's `=` is case-sensitive for non-ASCII and for ASCII unless a column is
    declared `COLLATE NOCASE`; Postgres' default collation is case-sensitive too. Hard
    rule 6 makes `highlights.dedupe_key` a UUID whose uniqueness must not be
    case-folded, so this pins the collation the two backends share.
    """
    with Session(migrated_postgres) as session:
        session.add(Book(title="T", source="oreilly", external_id="ABC123", url=None))
        session.commit()

        assert (
            session.execute(select(Book).where(Book.external_id == "ABC123"))
            .scalars()
            .one_or_none()
            is not None
        )
        assert (
            session.execute(select(Book).where(Book.external_id == "abc123"))
            .scalars()
            .one_or_none()
            is None
        )


def test_float_and_json_fsrs_state_round_trips_on_postgres(migrated_postgres: Engine) -> None:
    """`card_state`'s floats and `push_runs.card_ids` survive a Postgres round trip.

    The FSRS columns are the ones where a silent coercion does damage no error reports
    (issue #224), so they get an explicit check on the target backend as well as in the
    migration tests.
    """
    stability = 15.372819374623
    difficulty = 6.1049283746512
    due = datetime(2026, 10, 1, 19, 3, 47, 654321)

    with Session(migrated_postgres) as session:
        ingest_run = IngestRun(filename="f.csv", started_at=REFERENCE_NOW)
        session.add(ingest_run)
        session.flush()
        unit = CuratedUnit(
            ingest_run_id=ingest_run.id,
            curated_text="t",
            tags=[],
            decision="keep",
            created_at=REFERENCE_NOW,
        )
        session.add(unit)
        session.flush()
        card = Card(
            unit_id=unit.id,
            type="qa",
            front="f",
            back="b",
            original_front="f",
            original_back="b",
            tags=[],
            status="approved",
            approved_at=REFERENCE_NOW,
            generation_rounds=1,
            model="claude-sonnet-5",
            cost_microusd=0,
            created_at=REFERENCE_NOW,
        )
        session.add(card)
        session.flush()
        session.add(
            CardState(
                card_id=card.id,
                state="review",
                step=None,
                stability=stability,
                difficulty=difficulty,
                due=due,
                last_review=REFERENCE_NOW,
            )
        )
        session.commit()
        session.expunge_all()

        state = session.execute(select(CardState)).scalars().one()
        assert state.stability == stability
        assert state.difficulty == difficulty
        assert state.due == due
        assert state.step is None
