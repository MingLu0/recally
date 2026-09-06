"""The O'Reilly adapter and dedupe against the committed fixtures.

The fixtures are two trimmed exports of the same book, both Chapter 9 of *30 Agents
Every AI Engineer Must Build*, derived from the real export in gitignored `data/`
(roadmap step 1). Between them they cover every branch of hard rule 6:

- 11 UUIDs appear in both, unchanged        -> skipped
-  3 UUIDs appear only in A                 -> `removed_at` set
-  3 UUIDs appear only in B                 -> inserted
-  1 UUID appears in both with longer text  -> updated in place, `processed` reset

Fixture A also carries the clipped Chapter 9 row (`efaf55cf-...`, 149 characters, an
exact prefix of the full sentence pair in `bf9830d8-...`), which is the row hard rule
7 forbids reconstructing. Fixture B is that same UUID re-highlighted to the end of the
paragraph, which is what makes it the `updated` case.
"""

from datetime import date
from pathlib import Path

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session, sessionmaker

from recally.ingest import ingest_file
from recally.ingest.adapters import OReillyCsvAdapter
from recally.ingest.adapters.oreilly_csv import OReillyCsvError
from recally.models import Base, Book, Highlight

FIXTURES = Path(__file__).parent / "fixtures"
FIXTURE_A = FIXTURES / "oreilly-annotations-a.csv"
FIXTURE_B = FIXTURES / "oreilly-annotations-b.csv"

ISBN = "9781806109012"
# The clipped row: 149 characters, ending mid-sentence where the reader stopped.
CLIPPED_UUID = "efaf55cf-677f-4ec3-8aa7-74352fd1cbed"
CLIPPED_TEXT = (
    "Specifically, the generated code is written to the sandboxed file system within a "
    "Docker container, where it can be executed and tested in isolation."
)


@pytest.fixture
def session_factory() -> sessionmaker[Session]:
    """A fresh in-memory schema per test."""
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def ingest(session_factory: sessionmaker[Session], file: Path) -> tuple[int, int, int, int]:
    """Ingest one file in its own transaction; return (seen, new, updated, removed)."""
    with session_factory() as session:
        run = ingest_file(session, file, OReillyCsvAdapter())
        session.commit()
        return run.rows_seen, run.rows_new, run.rows_updated, run.rows_removed


# --- the adapter --------------------------------------------------------------------


def test_adapter_reads_every_row_in_export_order() -> None:
    highlights = OReillyCsvAdapter().parse(FIXTURE_A)

    assert len(highlights) == 15
    assert [h.export_position for h in highlights] == list(range(15))


def test_adapter_takes_the_dedupe_key_from_the_annotation_url() -> None:
    """Hard rule 6: the key is the annotation UUID, not the text."""
    highlights = OReillyCsvAdapter().parse(FIXTURE_A)

    clipped = next(h for h in highlights if h.dedupe_key == CLIPPED_UUID)
    assert clipped.raw_text == CLIPPED_TEXT
    assert clipped.chapter == "Chapter 9: Software Development Agents"
    assert clipped.highlighted_at == date(2026, 6, 15)


def test_adapter_takes_the_book_key_from_the_book_url() -> None:
    """Hard rule 6: the book dedupe key is the ISBN."""
    books = {h.book for h in OReillyCsvAdapter().parse(FIXTURE_A)}

    assert len(books) == 1
    book = books.pop()
    assert book.external_id == ISBN
    assert book.source == "oreilly"
    # The O'Reilly export has no author column.
    assert book.author is None


def test_adapter_never_repairs_a_clipped_highlight() -> None:
    """Hard rule 7: the lost text exists nowhere else, so nothing reconstructs it."""
    highlights = OReillyCsvAdapter().parse(FIXTURE_A)

    clipped = next(h for h in highlights if h.dedupe_key == CLIPPED_UUID)
    full = next(h for h in highlights if h.raw_text.startswith("Specifically") and h != clipped)

    assert len(clipped.raw_text) == 149
    # The clipped row is a strict prefix of the full one, and stays that way.
    assert full.raw_text.startswith(clipped.raw_text)
    assert len(full.raw_text) > len(clipped.raw_text)


def test_adapter_leaves_truncated_to_the_curator() -> None:
    """`truncated` is a Curator flag (docs/data-model.md); ingestion never guesses it."""
    highlights = OReillyCsvAdapter().parse(FIXTURE_A)

    assert not hasattr(highlights[0], "truncated")


def test_adapter_reads_empty_cells_as_absent() -> None:
    highlights = OReillyCsvAdapter().parse(FIXTURE_A)

    assert all(h.personal_note is None for h in highlights)
    assert all(h.color == "YELLOW" for h in highlights)


def test_adapter_rejects_a_file_that_is_not_this_export(tmp_path: Path) -> None:
    not_an_export = tmp_path / "oreilly-annotations-wrong.csv"
    not_an_export.write_text("Title,Text\nsomething,else\n")

    with pytest.raises(OReillyCsvError, match="missing column"):
        OReillyCsvAdapter().parse(not_an_export)


def test_adapter_rejects_a_short_row(tmp_path: Path) -> None:
    """A partially downloaded export yields None cells; that is the same clear error."""
    short_row = tmp_path / "oreilly-annotations-truncated.csv"
    short_row.write_text(
        "Book Title,Chapter Title,Date of Highlight,Book URL,Chapter URL,"
        "Annotation URL,Highlight,Color,Personal Note\n"
        "30 Agents Every AI Engineer Must Build,Chapter 9,2026-06-19\n"
    )

    with pytest.raises(OReillyCsvError, match="short row"):
        OReillyCsvAdapter().parse(short_row)


# --- dedupe -------------------------------------------------------------------------


def test_first_ingest_inserts_every_row(session_factory: sessionmaker[Session]) -> None:
    assert ingest(session_factory, FIXTURE_A) == (15, 15, 0, 0)

    with session_factory() as session:
        assert session.scalar(select(Book.external_id)) == ISBN
        assert len(session.scalars(select(Highlight)).all()) == 15


def test_second_export_adds_removes_and_updates(session_factory: sessionmaker[Session]) -> None:
    """The counts the roadmap step 1 gate asks for, exactly."""
    ingest(session_factory, FIXTURE_A)

    assert ingest(session_factory, FIXTURE_B) == (15, 3, 1, 3)

    with session_factory() as session:
        # Removals are flagged, never deleted: their cards are kept (hard rule 6).
        assert len(session.scalars(select(Highlight)).all()) == 18
        removed = session.scalars(select(Highlight).where(Highlight.removed_at.is_not(None))).all()
        assert len(removed) == 3


def test_re_ingesting_the_same_file_changes_nothing(
    session_factory: sessionmaker[Session],
) -> None:
    """A third ingest of the latest file adds nothing: ingestion is idempotent."""
    ingest(session_factory, FIXTURE_A)
    ingest(session_factory, FIXTURE_B)

    assert ingest(session_factory, FIXTURE_B) == (15, 0, 0, 0)
    assert ingest(session_factory, FIXTURE_B) == (15, 0, 0, 0)


def test_re_ingesting_an_older_export_restores_what_it_held(
    session_factory: sessionmaker[Session],
) -> None:
    """Ingestion is idempotent per file, not order-free: a file is applied as it reads.

    Feeding A back after B is the out-of-order case the watcher can produce if the
    reader drops an old download in. A says those 3 UUIDs exist and the clipped row
    reads as it did, so all four come back — and a second pass then changes nothing.
    """
    ingest(session_factory, FIXTURE_A)
    ingest(session_factory, FIXTURE_B)

    # 3 of B's rows are absent from A, and A's clipped text differs from B's edit.
    assert ingest(session_factory, FIXTURE_A) == (15, 0, 1, 3)
    assert ingest(session_factory, FIXTURE_A) == (15, 0, 0, 0)


def test_a_changed_highlight_updates_in_place_and_resets_processed(
    session_factory: sessionmaker[Session],
) -> None:
    """Hard rule 6: same UUID + different text -> update this row, never a second one."""
    ingest(session_factory, FIXTURE_A)

    with session_factory() as session:
        before = session.scalar(select(Highlight).where(Highlight.dedupe_key == CLIPPED_UUID))
        assert before is not None
        before.processed = True
        original_id = before.id
        session.commit()

    ingest(session_factory, FIXTURE_B)

    with session_factory() as session:
        rows = session.scalars(select(Highlight).where(Highlight.dedupe_key == CLIPPED_UUID)).all()
        assert len(rows) == 1
        updated = rows[0]
        assert updated.id == original_id
        assert updated.raw_text != CLIPPED_TEXT
        assert updated.personal_note == "re-highlighted to the end of the paragraph"
        assert updated.processed is False


def test_an_unchanged_highlight_keeps_its_processed_flag(
    session_factory: sessionmaker[Session],
) -> None:
    """Only changed rows go back to the pipeline; the other 11 stay done."""
    ingest(session_factory, FIXTURE_A)

    with session_factory() as session:
        for highlight in session.scalars(select(Highlight)).all():
            highlight.processed = True
        session.commit()

    ingest(session_factory, FIXTURE_B)

    with session_factory() as session:
        unprocessed = session.scalars(select(Highlight).where(Highlight.processed.is_(False))).all()
        # The 3 newly inserted rows plus the 1 that changed.
        assert len(unprocessed) == 4


def test_a_restored_highlight_clears_removed_at(
    session_factory: sessionmaker[Session],
) -> None:
    """A highlight the reader deleted and later restored comes back into rotation."""
    ingest(session_factory, FIXTURE_A)
    ingest(session_factory, FIXTURE_B)

    with session_factory() as session:
        assert session.scalars(select(Highlight).where(Highlight.removed_at.is_not(None))).all()

    ingest(session_factory, FIXTURE_A)

    with session_factory() as session:
        restored = session.scalars(select(Highlight).where(Highlight.removed_at.is_(None))).all()
        assert len(restored) == 15


def test_one_book_row_across_both_exports(session_factory: sessionmaker[Session]) -> None:
    """The ISBN dedupe key means a re-export never creates a second book."""
    ingest(session_factory, FIXTURE_A)
    ingest(session_factory, FIXTURE_B)

    with session_factory() as session:
        assert len(session.scalars(select(Book)).all()) == 1
