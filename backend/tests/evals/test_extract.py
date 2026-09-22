"""The #242 gate: extracting the human's deliberate judgments into the eval set.

`recally.evals.extract` builds `tests/fixtures/eval/writer_eval.jsonl` from cards the
human approved or rejected, grouped by curated unit (the harness in #243 replays the
Writer ⇄ Critic loop per unit). The 116-card accidental bulk tap of
2026-09-11 10:43:33 (#240) is excluded by predicate, and the extractor opens the
database read-only so eval work can never write to production data.
"""

from collections.abc import Callable, Iterator
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import create_engine, delete, insert
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from recally.evals.extract import create_read_only_engine, extract_eval_set
from recally.models import (
    Base,
    Book,
    Card,
    CuratedUnit,
    CuratedUnitHighlight,
    Highlight,
    IngestRun,
    ReviewLog,
)

# The accidental "Approve all clean" tap (#240); approvals inside this second carry no
# judgment and must never reach the eval set.
BULK_SECOND = datetime(2026, 9, 11, 10, 43, 33)
DELIBERATE_AT = datetime(2026, 9, 12, 18, 0, 0)

REJECTION_REASON = "too trivial relative to why I am reading this book"


_BOOK_SEQ = 0


def _add_unit(
    session: Session, *, curated_text: str = "Curated text.", truncated: bool = False
) -> CuratedUnit:
    """A unit plus its provenance chain, optionally with a truncated source."""
    global _BOOK_SEQ
    _BOOK_SEQ += 1
    book = Book(
        title="Evals for AI Engineers", source="oreilly", external_id=f"9781098188283-{_BOOK_SEQ}"
    )
    run = IngestRun(filename="a-oreilly-annotations.csv")
    session.add_all([book, run])
    session.flush()
    highlight = Highlight(
        book_id=book.id,
        raw_text=curated_text,
        dedupe_key=f"uuid-{_BOOK_SEQ}",
        source="oreilly",
        highlighted_at=DELIBERATE_AT.date(),
        export_position=0,
        truncated=truncated,
    )
    session.add(highlight)
    session.flush()
    unit = CuratedUnit(ingest_run_id=run.id, curated_text=curated_text, decision="keep", tags=[])
    session.add(unit)
    session.flush()
    session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id))
    session.flush()
    return unit


def _add_card(
    session: Session,
    unit: CuratedUnit,
    *,
    status: str,
    status_reason: str | None = None,
    approved_at: datetime | None = None,
) -> Card:
    card = Card(
        unit_id=unit.id,
        type="qa",
        front="Why evaluate traces rather than individual steps?",
        back="Behaviour only makes sense end-to-end.",
        original_front="Why evaluate traces rather than individual steps?",
        original_back="Behaviour only makes sense end-to-end.",
        tags=[],
        status=status,
        status_reason=status_reason,
        approved_at=approved_at,
        model="claude-sonnet-5",
    )
    session.add(card)
    session.flush()
    return card


def _add_review(session: Session, card: Card, *, rating: int) -> None:
    session.add(
        ReviewLog(
            card_id=card.id,
            rated_at=DELIBERATE_AT + timedelta(days=len(card.front) + rating),
            rating=rating,
            response_ms=3000,
            scheduled_days=1,
            state_before="review",
        )
    )


@pytest.fixture
def seeded_url(tmp_path: Path) -> Iterator[Callable[[Callable[[Session], None]], str]]:
    """Build a file-backed database per seed function and hand out its URL.

    File-backed rather than in-memory because the read-only engine test needs to
    reopen the same database through SQLite's `mode=ro` URI.
    """

    def build(seed: Callable[[Session], None]) -> str:
        path = tmp_path / "eval.db"
        engine = create_engine(f"sqlite:///{path}")
        Base.metadata.create_all(engine)
        with Session(engine) as session:
            seed(session)
            session.commit()
        engine.dispose()
        return f"sqlite:///{path}"

    yield build


def test_extractor_excludes_bulk_approved_cards(
    seeded_url: Callable[[Callable[[Session], None]], str],
) -> None:
    held: dict[str, int] = {}

    def seed(session: Session) -> None:
        bulk_unit = _add_unit(session, curated_text="Bulk-tapped unit.")
        bulk_at = BULK_SECOND + timedelta(microseconds=400_000)
        held["bulk"] = _add_card(session, bulk_unit, status="approved", approved_at=bulk_at).id
        deliberate_unit = _add_unit(session, curated_text="Deliberately judged unit.")
        held["deliberate"] = _add_card(
            session, deliberate_unit, status="approved", approved_at=DELIBERATE_AT
        ).id

    entries = extract_eval_set(seeded_url(seed))

    labelled_ids = [label["card_id"] for entry in entries for label in entry["labels"]]
    assert held["bulk"] not in labelled_ids
    assert held["deliberate"] in labelled_ids


def test_extractor_never_writes_to_the_database(
    seeded_url: Callable[[Callable[[Session], None]], str],
) -> None:
    def seed(session: Session) -> None:
        unit = _add_unit(session)
        _add_card(session, unit, status="approved", approved_at=DELIBERATE_AT)

    url = seeded_url(seed)

    engine = create_read_only_engine(url)
    try:
        with engine.connect() as connection:
            with pytest.raises(OperationalError):
                connection.execute(insert(Card.__table__).values(unit_id=1))
    finally:
        engine.dispose()

    # The extractor itself runs over the read-only connection without error.
    assert len(extract_eval_set(url)) == 1


def test_extractor_groups_multiple_cards_under_one_unit(
    seeded_url: Callable[[Callable[[Session], None]], str],
) -> None:
    def seed(session: Session) -> None:
        unit = _add_unit(session, curated_text="One unit, two judged cards.", truncated=True)
        kept = _add_card(session, unit, status="approved", approved_at=DELIBERATE_AT)
        _add_card(session, unit, status="rejected", status_reason=REJECTION_REASON)
        _add_review(session, kept, rating=3)
        _add_review(session, kept, rating=4)
        _add_review(session, kept, rating=1)

    entries = extract_eval_set(seeded_url(seed))

    assert len(entries) == 1
    entry = entries[0]
    assert len(entry["labels"]) == 2
    assert {label["verdict"] for label in entry["labels"]} == {"approve", "reject"}
    assert entry["source_truncated"] is True
    assert entry["review_signal"] == {"reviews": 3, "again_hard": 1, "good_easy": 2}


def test_extractor_keeps_rejection_reason_text(
    seeded_url: Callable[[Callable[[Session], None]], str],
) -> None:
    def seed(session: Session) -> None:
        unit = _add_unit(session)
        _add_card(session, unit, status="rejected", status_reason=REJECTION_REASON)

    entries = extract_eval_set(seeded_url(seed))

    assert len(entries) == 1
    (label,) = entries[0]["labels"]
    assert label["verdict"] == "reject"
    assert label["reason"] == REJECTION_REASON


def test_extractor_skips_cards_whose_unit_is_gone(
    seeded_url: Callable[[Callable[[Session], None]], str],
) -> None:
    def seed(session: Session) -> None:
        gone = _add_unit(session, curated_text="Unit deleted after judgement.")
        _add_card(session, gone, status="approved", approved_at=DELIBERATE_AT)
        kept = _add_unit(session, curated_text="Unit still present.")
        _add_card(session, kept, status="approved", approved_at=DELIBERATE_AT)

    url = seeded_url(seed)

    # Delete the unit behind the ORM's back, leaving a card with a dangling unit_id.
    # SQLite does not enforce foreign keys here, mirroring how the live database was
    # pruned; the extractor must drop the card rather than emit a null unit.
    engine = create_engine(url)
    with engine.begin() as connection:
        connection.execute(
            delete(CuratedUnit).where(CuratedUnit.curated_text == "Unit deleted after judgement.")
        )
    engine.dispose()

    entries = extract_eval_set(url)

    assert len(entries) == 1
    assert entries[0]["curated_text"] == "Unit still present."
    assert all(entry["unit_id"] is not None for entry in entries)
