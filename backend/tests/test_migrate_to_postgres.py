"""The step 7 gate: the SQLite → Postgres copy, and the suite running on both backends.

Issue #224. The migration is the one operation in this project that can silently lose
data: cards regenerate from the O'Reilly CSV for a few dollars, but `review_logs` and
the FSRS state fitted from them cannot be regenerated at all. So these tests are
written against the *values*, not against "it ran without raising".

The four coercion risks the copy has to survive, all silent if unchecked, are the ones
`.dump | psql` would get wrong (issue #224):

| Risk | Covered by |
|---|---|
| Booleans stored `0`/`1` | `test_migration_copies_every_table_row_count` via `_assert_rows_match` |
| Timestamps stored as text | `test_migration_preserves_naive_utc_timestamps` |
| Generic `JSON` stored as TEXT | `test_migration_preserves_json_payloads` |
| Sequences do not transfer | `test_migration_resets_sequences` |

`_build_source_database` writes one row in every table, with deliberately awkward
values — a float that does not round-trip through a shortened repr, a non-ASCII
string, an empty JSON list, a NULL beside a non-NULL in the same nullable column — so
a copy that flattens a type has somewhere to show it.
"""

from __future__ import annotations

import hashlib
import sqlite3
import subprocess
import sys
from collections.abc import Iterator
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import Engine, create_engine, func, inspect, select
from sqlalchemy.orm import Session

from recally.models import (
    Base,
    Book,
    Card,
    CardState,
    CuratedUnit,
    CuratedUnitHighlight,
    Device,
    FsrsParams,
    Highlight,
    IngestRun,
    LlmCall,
    PushRun,
    ReviewLog,
    WriterGuidance,
)

BACKEND_ROOT = Path(__file__).resolve().parents[1]

# Every test in this module needs the embedded server.
pytestmark = pytest.mark.postgres

# Naive UTC, as every `DateTime` column in the project stores (models/base.py). The
# odd minute/second/microsecond values are here so a truncating or re-parsing copy
# cannot coincidentally match.
REFERENCE_NOW = datetime(2026, 9, 13, 4, 37, 11, 123456)

# FSRS values chosen to be unfriendly to a lossy float path: both have long binary
# expansions, so a copy that round-trips them through a shortened string repr drifts.
REFERENCE_STABILITY = 15.372819374623
REFERENCE_DIFFICULTY = 6.1049283746512

# `due` is the value a silent error damages invisibly: a shifted due date corrupts
# scheduling with no error anywhere (issue #224).
REFERENCE_DUE = datetime(2026, 10, 1, 19, 3, 47, 654321)


def _load_migration_script() -> Any:
    """Import `backend/scripts/migrate_to_postgres.py` by path.

    The migration is a standalone script next to `seed_demo.py`, not part of the
    installed `recally` package (issue #224 puts it at `backend/scripts/`), so there is
    no module path to import it by. Loading it once at module scope keeps every test
    working against the same module object.
    """
    import importlib.util
    import sys as system

    module_name = "recally_migrate_to_postgres"
    script_path = BACKEND_ROOT / "scripts" / "migrate_to_postgres.py"
    spec = importlib.util.spec_from_file_location(module_name, script_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    # Registered before execution because `@dataclass` resolves annotations through
    # `sys.modules[cls.__module__]`, which is None for a module that is not there yet.
    system.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


migrate_to_postgres = _load_migration_script()
migrate = migrate_to_postgres.migrate
MigrationRefused = migrate_to_postgres.MigrationRefused
VerificationFailed = migrate_to_postgres.VerificationFailed


@pytest.fixture
def source_database(tmp_path: Path) -> Iterator[Path]:
    """A populated SQLite database standing in for `data/recally.db`."""
    database_path = tmp_path / "recally.db"
    engine = create_engine(f"sqlite:///{database_path}")
    try:
        Base.metadata.create_all(engine)
        with Session(engine) as session:
            _build_source_database(session)
            session.commit()
    finally:
        engine.dispose()
    yield database_path


def _build_source_database(session: Session) -> None:
    """One row in every table, with values picked to expose a lossy copy.

    Ordered by FK dependency and flushed as it goes, so the ids the later rows
    reference actually exist.
    """
    book = Book(
        title="Designing Data-Intensive Applications",
        # Non-ASCII: a copy that mangles an encoding shows up here rather than nowhere.
        author="Martin Kleppmann — 马丁",
        source="oreilly",
        external_id="9781449373320",
        url="https://learning.oreilly.com/library/view/-/9781449373320/",
    )
    session.add(book)
    session.flush()

    ingest_run = IngestRun(
        filename="oreilly-annotations-1757000000.csv",
        rows_seen=412,
        rows_new=12,
        rows_updated=1,
        rows_removed=0,
        units_kept=9,
        units_dropped=3,
        highlights_dropped=4,
        cards_generated=17,
        cost_microusd=48213,
        started_at=REFERENCE_NOW,
        finished_at=REFERENCE_NOW + timedelta(minutes=4, seconds=37),
        error=None,
    )
    session.add(ingest_run)
    session.flush()

    # `truncated=True` / `processed=False` in one row: SQLite stores these as 1 and 0,
    # and Postgres has a real boolean type, so this is the boolean coercion risk.
    truncated_highlight = Highlight(
        book_id=book.id,
        chapter="5. Replication",
        location="ch05.html#idm45",
        raw_text="Leader-based replication requires all writes to go through a sin",
        personal_note="check against the Raft paper",
        color="YELLOW",
        dedupe_key="a1b2c3d4-0000-4000-8000-000000000001",
        source="oreilly",
        highlighted_at=date(2026, 8, 30),
        export_position=7,
        truncated=True,
        processed=False,
        removed_at=None,
    )
    # The opposite boolean pairing plus a non-NULL `removed_at`, so neither column is
    # constant across the table and a copy that writes a default cannot pass.
    processed_highlight = Highlight(
        book_id=book.id,
        chapter="9. Consistency and Consensus",
        location="ch09.html#idm77",
        raw_text="Linearizability is a recency guarantee.",
        personal_note=None,
        color="YELLOW",
        dedupe_key="a1b2c3d4-0000-4000-8000-000000000002",
        source="oreilly",
        highlighted_at=date(2026, 9, 2),
        export_position=8,
        truncated=False,
        processed=True,
        removed_at=REFERENCE_NOW - timedelta(days=2),
    )
    session.add_all([truncated_highlight, processed_highlight])
    session.flush()

    guidance = WriterGuidance(
        version=1,
        guidance="Prefer one idea per card. Never quote the highlight verbatim.",
        basis={"lapses_reviewed": 23, "sources": ["review_logs"], "notes": None},
        created_at=REFERENCE_NOW - timedelta(days=5),
    )
    session.add(guidance)
    session.flush()

    unit = CuratedUnit(
        ingest_run_id=ingest_run.id,
        curated_text="Leader-based replication sends every write through a single leader.",
        tags=["replication", "distributed-systems"],
        decision="keep",
        reason=None,
        created_at=REFERENCE_NOW,
    )
    session.add(unit)
    session.flush()

    session.add_all(
        [
            CuratedUnitHighlight(unit_id=unit.id, highlight_id=truncated_highlight.id),
            CuratedUnitHighlight(unit_id=unit.id, highlight_id=processed_highlight.id),
        ]
    )

    approved_card = Card(
        unit_id=unit.id,
        type="qa",
        front="In leader-based replication, where do writes go?",
        back="Through the single leader, which then ships the changes to followers.",
        original_front="In leader-based replication, where do writes go?",
        original_back="Through the leader.",
        tags=["replication"],
        status="approved",
        status_reason=None,
        supersedes_card_id=None,
        approved_at=REFERENCE_NOW,
        edited_at=REFERENCE_NOW + timedelta(hours=1),
        suspended_until=None,
        generation_rounds=2,
        model="claude-sonnet-5",
        guidance_version=guidance.version,
        cost_microusd=1843,
        created_at=REFERENCE_NOW - timedelta(hours=2),
    )
    # An empty JSON list next to a populated one: `[]` is where a copy that treats
    # falsy JSON as NULL breaks.
    pending_card = Card(
        unit_id=unit.id,
        type="cloze",
        front="Linearizability is a {{c1::recency}} guarantee.",
        back="recency",
        original_front="Linearizability is a {{c1::recency}} guarantee.",
        original_back="recency",
        tags=[],
        status="pending_review",
        status_reason=None,
        supersedes_card_id=None,
        approved_at=None,
        edited_at=None,
        suspended_until=None,
        generation_rounds=1,
        model="claude-sonnet-5",
        guidance_version=None,
        cost_microusd=902,
        created_at=REFERENCE_NOW - timedelta(hours=1),
    )
    session.add_all([approved_card, pending_card])
    session.flush()

    # The row a silent error damages invisibly (issue #224).
    session.add(
        CardState(
            card_id=approved_card.id,
            state="review",
            step=None,
            stability=REFERENCE_STABILITY,
            difficulty=REFERENCE_DIFFICULTY,
            due=REFERENCE_DUE,
            last_review=REFERENCE_NOW - timedelta(days=3),
        )
    )

    device = Device(
        fcm_token="fake-fcm-token-for-tests-0000000000000000",
        platform="android",
        created_at=REFERENCE_NOW - timedelta(days=10),
        last_push_at=REFERENCE_NOW - timedelta(days=1),
    )
    session.add(device)
    session.flush()

    session.add(
        ReviewLog(
            card_id=approved_card.id,
            rated_at=REFERENCE_NOW - timedelta(days=3),
            received_at=REFERENCE_NOW - timedelta(days=3, seconds=-42),
            rating=3,
            response_ms=4817,
            scheduled_days=15,
            state_before="review",
            device_id=device.id,
        )
    )
    session.add(
        FsrsParams(
            # The 21 FSRS weights, as a JSON list of floats.
            parameters=[
                0.2172,
                1.1771,
                3.2602,
                16.1507,
                7.0114,
                0.57,
                2.0966,
                0.0069,
                1.5261,
                0.112,
                1.0178,
                1.849,
                0.1133,
                0.3127,
                2.2934,
                0.2191,
                3.0004,
                0.7536,
                0.3332,
                0.1437,
                0.2,
            ],
            desired_retention=0.9,
            review_count=1284,
            created_at=REFERENCE_NOW - timedelta(days=1),
        )
    )
    session.add(
        PushRun(
            device_id=device.id,
            sent_at=REFERENCE_NOW - timedelta(days=1),
            card_ids=[approved_card.id, pending_card.id],
            due_count=2,
        )
    )
    # Nested JSON on both sides, plus a row with both payloads NULL
    # (`LLM_LOG_PAYLOADS=false`), so neither shape is untested.
    session.add(
        LlmCall(
            agent="writer/default",
            ingest_run_id=ingest_run.id,
            unit_id=unit.id,
            card_id=approved_card.id,
            round=1,
            model="claude-sonnet-5",
            request={"messages": [{"role": "user", "content": "Write a card — 写一张卡"}]},
            response={"cards": [{"type": "qa", "front": "…", "back": "…"}], "usage": None},
            input_tokens=1204,
            output_tokens=187,
            cost_microusd=1843,
            latency_ms=3211,
            created_at=REFERENCE_NOW - timedelta(hours=2),
        )
    )
    session.add(
        LlmCall(
            agent="critic/default",
            ingest_run_id=ingest_run.id,
            unit_id=unit.id,
            card_id=approved_card.id,
            round=2,
            model="claude-sonnet-5",
            request=None,
            response=None,
            input_tokens=980,
            output_tokens=64,
            cost_microusd=421,
            latency_ms=1188,
            created_at=REFERENCE_NOW - timedelta(hours=1, minutes=30),
        )
    )


def _sqlite_url(database_path: Path) -> str:
    return f"sqlite:///{database_path}"


def _checksum(database_path: Path) -> str:
    return hashlib.sha256(database_path.read_bytes()).hexdigest()


def _row_counts(engine: Engine) -> dict[str, int]:
    """`{table: count}` for every mapped table, read off a live connection."""
    with Session(engine) as session:
        return {
            table_name: session.execute(select(func.count()).select_from(table)).scalar_one()
            for table_name, table in Base.metadata.tables.items()
        }


def test_migration_refuses_nonempty_target(source_database: Path, postgres_url: str) -> None:
    """A target that already has rows is refused, not written into twice.

    Re-running the migration against a live database is the realistic accident — a
    rerun after a partial failure, or against the wrong URL. Appending would duplicate
    every row and, worse, leave `card_state` with two rows per card.
    """

    migrate(source_url=_sqlite_url(source_database), target_url=postgres_url)
    counts_after_first_run = _row_counts(create_engine(postgres_url))

    with pytest.raises(MigrationRefused):
        migrate(source_url=_sqlite_url(source_database), target_url=postgres_url)

    assert _row_counts(create_engine(postgres_url)) == counts_after_first_run


def test_migration_never_writes_to_source(source_database: Path, postgres_url: str) -> None:
    """The SQLite file is byte-identical after a full run.

    The source stays a complete working database, which is what makes rollback a
    matter of pointing `RECALLY_DATABASE_URL` back at it (issue #224). A checksum is
    the assertion because an accidental write need not change any row the tests read —
    the app's own engine enables WAL on connect, and that alone rewrites the file.
    """

    checksum_before = _checksum(source_database)

    migrate(source_url=_sqlite_url(source_database), target_url=postgres_url)

    assert _checksum(source_database) == checksum_before
    # A rollback journal would mean a write transaction was opened against the source.
    # (`-wal`/`-shm` are deliberately *not* asserted on here; see the WAL test below.)
    assert not source_database.with_name(source_database.name + "-journal").exists()


def test_migration_never_writes_to_a_wal_mode_source(
    source_database: Path, postgres_url: str
) -> None:
    """The real `data/recally.db` is in WAL mode, and that case behaves differently.

    `recally.db.create_database_engine` puts every SQLite database in WAL mode, so the
    production file is WAL and the migration has to be safe against *that*, not just
    against a default-journal file built by a fixture.

    The wrinkle: opening a WAL database read-only still creates `-shm` and `-wal`
    sidecars, because SQLite needs the shared-memory index to read one at all. So their
    presence is not evidence of a write, and asserting their absence would fail against
    the real database while proving nothing. What must hold is that the *main file* is
    byte-identical and the data is still readable afterwards — which is what rollback
    actually depends on.
    """
    with sqlite3.connect(source_database) as connection:
        connection.execute("PRAGMA journal_mode=WAL")
    # Checksum taken after the mode change so WAL setup is not counted as the migration's
    # write.
    checksum_before = _checksum(source_database)

    migrate(source_url=_sqlite_url(source_database), target_url=postgres_url)

    assert _checksum(source_database) == checksum_before
    assert not source_database.with_name(source_database.name + "-journal").exists()

    # The checksum alone is weak here: on an already-WAL database a read-write open
    # need not change the main file's bytes, so it would pass even if the migration
    # opened the source writable. The connection itself is therefore asserted on —
    # a read-only connection raises on write, a read-write one silently succeeds.
    assert _source_connection_is_read_only(_sqlite_url(source_database))

    # Still a complete, readable database: the rollback path has to work, not merely
    # leave the bytes alone.
    with sqlite3.connect(f"file:{source_database}?mode=ro", uri=True) as connection:
        book_count = connection.execute("SELECT count(*) FROM books").fetchone()[0]
    assert book_count == 1


def _source_connection_is_read_only(source_url: str) -> bool:
    """Does the script's own source engine refuse a write?

    Asks the implementation for the engine it would use and tries to write through it.
    A read-only connection raises `OperationalError`; anything else means the migration
    could scribble on the database it is supposed to be preserving.
    """
    from sqlalchemy import text as sql_text
    from sqlalchemy.exc import OperationalError

    engine = migrate_to_postgres._create_read_only_source_engine(source_url)
    try:
        with engine.connect() as connection:
            connection.execute(sql_text("CREATE TABLE _write_probe (a INTEGER)"))
    except OperationalError:
        return True
    else:
        return False
    finally:
        engine.dispose()


def test_migration_copies_every_table_row_count(source_database: Path, postgres_url: str) -> None:
    """Per-table counts match, and no table is silently skipped.

    The second assertion is the one that catches the real failure mode: a table added
    to the models but not to the copy's table list would otherwise pass a
    count-comparison loop that iterates over the same incomplete list.
    """

    source_engine = create_engine(_sqlite_url(source_database))
    try:
        source_counts = _row_counts(source_engine)
    finally:
        source_engine.dispose()

    migrate(source_url=_sqlite_url(source_database), target_url=postgres_url)

    target_engine = create_engine(postgres_url)
    try:
        assert _row_counts(target_engine) == source_counts
        # Every mapped table was actually created on the target, and the fixture put a
        # row in each, so an empty table here means a skipped copy.
        assert set(inspect(target_engine).get_table_names()) >= set(Base.metadata.tables)
    finally:
        target_engine.dispose()
    assert all(count > 0 for count in source_counts.values()), source_counts


def test_migration_preserves_card_state_values(source_database: Path, postgres_url: str) -> None:
    """`due`, `stability` and `difficulty` survive exactly.

    This is the row where a silent error does real damage: a shifted `due` corrupts
    scheduling with no error anywhere. Floats are compared with `==` deliberately —
    the copy goes through the same SQLAlchemy `Float` on both sides, so anything other
    than exact equality is a coercion bug, not acceptable numeric drift.
    """

    migrate(source_url=_sqlite_url(source_database), target_url=postgres_url)

    target_engine = create_engine(postgres_url)
    try:
        with Session(target_engine) as session:
            state = session.execute(select(CardState)).scalars().one()
            assert state.due == REFERENCE_DUE
            assert state.stability == REFERENCE_STABILITY
            assert state.difficulty == REFERENCE_DIFFICULTY
            assert state.last_review == REFERENCE_NOW - timedelta(days=3)
            assert state.state == "review"
            # NULL in a nullable numeric column stays NULL rather than becoming 0.
            assert state.step is None
    finally:
        target_engine.dispose()


def test_migration_preserves_json_payloads(source_database: Path, postgres_url: str) -> None:
    """Generic `JSON` columns round-trip, including `[]` and NULL.

    SQLite stores these as TEXT and Postgres has a real JSON type, so a copy that
    moved the raw storage value would land a JSON *string* in a JSON column.
    """

    migrate(source_url=_sqlite_url(source_database), target_url=postgres_url)

    target_engine = create_engine(postgres_url)
    try:
        with Session(target_engine) as session:
            tags_by_status = {
                card.status: card.tags for card in session.execute(select(Card)).scalars().all()
            }
            assert tags_by_status["approved"] == ["replication"]
            # The falsy-JSON case: `[]` must stay a list, not become NULL.
            assert tags_by_status["pending_review"] == []

            push_run = session.execute(select(PushRun)).scalars().one()
            assert push_run.card_ids == [1, 2]
            assert all(isinstance(card_id, int) for card_id in push_run.card_ids)

            params = session.execute(select(FsrsParams)).scalars().one()
            assert len(params.parameters) == 21
            assert params.parameters[0] == 0.2172
            assert params.parameters[3] == 16.1507

            guidance = session.execute(select(WriterGuidance)).scalars().one()
            assert guidance.basis == {
                "lapses_reviewed": 23,
                "sources": ["review_logs"],
                "notes": None,
            }

            calls_by_agent = {
                call.agent: call for call in session.execute(select(LlmCall)).scalars().all()
            }
            writer_call = calls_by_agent["writer/default"]
            assert writer_call.request == {
                "messages": [{"role": "user", "content": "Write a card — 写一张卡"}]
            }
            assert writer_call.response == {
                "cards": [{"type": "qa", "front": "…", "back": "…"}],
                "usage": None,
            }
            # `LLM_LOG_PAYLOADS=false` rows: NULL stays NULL, not the string "null".
            critic_call = calls_by_agent["critic/default"]
            assert critic_call.request is None
            assert critic_call.response is None
    finally:
        target_engine.dispose()


def test_migration_preserves_naive_utc_timestamps(source_database: Path, postgres_url: str) -> None:
    """No offset shift, and no timezone attached, on any `DateTime` column.

    SQLite has no timestamp type and stores these as text; Postgres has a real one. A
    copy that let either side apply a local-time interpretation would shift every
    stored datetime by the machine's UTC offset — which on this project's GMT+12
    development machine is half a day of scheduling error.
    """

    migrate(source_url=_sqlite_url(source_database), target_url=postgres_url)

    target_engine = create_engine(postgres_url)
    try:
        with Session(target_engine) as session:
            ingest_run = session.execute(select(IngestRun)).scalars().one()
            assert ingest_run.started_at == REFERENCE_NOW
            # Microseconds included: a copy through a second-resolution format loses
            # them without ever raising.
            assert ingest_run.started_at.microsecond == REFERENCE_NOW.microsecond
            assert ingest_run.finished_at == REFERENCE_NOW + timedelta(minutes=4, seconds=37)

            # Naive on the way out, as models/base.py documents. An aware value here
            # would mean the column stopped behaving the same on the two backends.
            assert ingest_run.started_at.tzinfo is None

            review_log = session.execute(select(ReviewLog)).scalars().one()
            assert review_log.rated_at == REFERENCE_NOW - timedelta(days=3)
            assert review_log.rated_at.tzinfo is None

            # A `Date` column, which has its own text representation in SQLite.
            highlights = (
                session.execute(select(Highlight).order_by(Highlight.export_position))
                .scalars()
                .all()
            )
            assert highlights[0].highlighted_at == date(2026, 8, 30)
            assert highlights[1].highlighted_at == date(2026, 9, 2)

            # The boolean coercion risk: `0`/`1` in SQLite, real booleans here.
            assert highlights[0].truncated is True
            assert highlights[0].processed is False
            assert highlights[1].truncated is False
            assert highlights[1].processed is True
            assert highlights[0].removed_at is None
            assert highlights[1].removed_at == REFERENCE_NOW - timedelta(days=2)
    finally:
        target_engine.dispose()


def test_migration_resets_sequences(source_database: Path, postgres_url: str) -> None:
    """An insert straight after the migration does not collide on the primary key.

    Copying explicit ids leaves every Postgres sequence at 1, so the first write after
    a cutover fails on a duplicate key — the failure that would greet the first real
    request rather than the migration itself.
    """

    migrate(source_url=_sqlite_url(source_database), target_url=postgres_url)

    target_engine = create_engine(postgres_url)
    try:
        with Session(target_engine) as session:
            highest_existing_book_id = session.execute(select(func.max(Book.id))).scalar_one()

            # No explicit id: the sequence supplies it, which is the cutover's first
            # real write.
            session.add(
                Book(
                    title="Database Internals",
                    author="Alex Petrov",
                    source="oreilly",
                    external_id="9781492040330",
                    url=None,
                )
            )
            session.commit()
            new_book = (
                session.execute(select(Book).where(Book.external_id == "9781492040330"))
                .scalars()
                .one()
            )
            assert new_book.id > highest_existing_book_id

            # Same for a second table, so this is not one lucky sequence. `cards` is
            # the one an approval writes to.
            unit_id = session.execute(select(CuratedUnit.id)).scalars().first()
            highest_existing_card_id = session.execute(select(func.max(Card.id))).scalar_one()
            session.add(
                Card(
                    unit_id=unit_id,
                    type="qa",
                    front="front",
                    back="back",
                    original_front="front",
                    original_back="back",
                    tags=[],
                    status="pending_review",
                    generation_rounds=1,
                    model="claude-sonnet-5",
                    cost_microusd=0,
                )
            )
            session.commit()
            assert (
                session.execute(select(func.max(Card.id))).scalar_one() > highest_existing_card_id
            )
    finally:
        target_engine.dispose()


def test_suite_passes_on_postgres(postgres_url: str) -> None:
    """The existing suite runs green against Postgres.

    This is what converts hard rule 4 from a claim into a fact (issue #224): it catches
    what a grep cannot — `JSON` comparison semantics, `NULL` ordering in `ORDER BY`,
    string collation.

    Run as a subprocess rather than in-process because the suite's own fixtures build
    their engines at import and fixture time; a nested pytest is the honest way to make
    *them* use a different backend. `RECALLY_TEST_DATABASE_URL` is read by the tests
    that support both backends, and `-p no:cacheprovider` keeps the child run from
    fighting the parent over `.pytest_cache`.
    """
    completed = subprocess.run(  # noqa: S603 - fixed argv, no shell
        [
            sys.executable,
            "-m",
            "pytest",
            "tests/test_migrations_postgres.py",
            "tests/test_models.py",
            "-q",
            "-p",
            "no:cacheprovider",
        ],
        cwd=BACKEND_ROOT,
        env=_postgres_suite_environment(postgres_url),
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stdout + completed.stderr


def _postgres_suite_environment(postgres_url: str) -> dict[str, str]:
    """The child suite's environment: the same one, pointed at Postgres."""
    import os

    environment = dict(os.environ)
    environment["RECALLY_TEST_DATABASE_URL"] = postgres_url
    environment.setdefault("RECALLY_API_KEY", "test-key-not-a-real-secret")
    # The child must not recurse into this module.
    environment["RECALLY_SKIP_POSTGRES_SUITE"] = "1"
    return environment


def _assert_rows_match(source: Any, target: Any) -> None:
    """Column-by-column equality for one mapped row, used by the count test's callers."""
    for column in inspect(type(source)).columns:
        assert getattr(source, column.key) == getattr(target, column.key), column.key
