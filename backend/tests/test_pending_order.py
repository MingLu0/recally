"""Issue #232: `GET /cards/pending` must return the same order on every backend.

`list_pending_cards` sorts in Python on (book, chapter, `export_position`) — a key
that ties for cards written from the same curated unit (a QA and a cloze, say).
Pre-fix the tie inherited the arrival order of the underlying `select(Card)`,
which had no `ORDER BY`: SQLite happens to return rowid order, Postgres returns
whatever the heap says. `/reviews/due` already tie-breaks on a unique column
(`services/reviews.py`); this is the one list that did not.

`test_pending_cards_order_is_deterministic_within_a_tie` runs on the embedded
Postgres because that is where "no `ORDER BY`" is observable: it rewrites one
tied row so the heap returns it *after* the other, which no SQLite insertion
pattern can reproduce (rowid order is arrival order there).
"""

from __future__ import annotations

from collections.abc import Iterator
from datetime import date
from typing import TYPE_CHECKING

import pytest
from sqlalchemy import create_engine, update
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from recally.models import (
    Base,
    Book,
    Card,
    CuratedUnit,
    CuratedUnitHighlight,
    Highlight,
    IngestRun,
)
from recally.services.cards import list_pending_cards

if TYPE_CHECKING:
    from sqlalchemy import Engine

HIGHLIGHTED_AT = date(2026, 9, 1)


def _sqlite_engine() -> Engine:
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    return engine


@pytest.fixture
def postgres_schema(postgres_url: str) -> Iterator[Engine]:
    """An empty Postgres database with the schema `create_all` builds."""
    engine = create_engine(postgres_url)
    Base.metadata.create_all(engine)
    try:
        yield engine
    finally:
        engine.dispose()


def _add_book(session: Session, *, title: str, external_id: str) -> Book:
    book = Book(title=title, source="oreilly", external_id=external_id, user_id=1)
    session.add(book)
    session.flush()
    return book


def _add_pending_card(
    session: Session,
    *,
    book: Book,
    chapter: str | None,
    export_position: int,
    front: str,
    unit: CuratedUnit | None = None,
) -> Card:
    """A pending card with its full provenance chain.

    Pass `unit` to write a second card from the same curated unit — same
    highlight, so book, chapter and `export_position` all tie. That is the
    issue's case: two cards generated from one unit.
    """
    if unit is None:
        highlight = Highlight(
            book_id=book.id,
            chapter=chapter,
            raw_text=f"Source text for {front}.",
            dedupe_key=f"uuid-{front}",
            source="oreilly",
            highlighted_at=HIGHLIGHTED_AT,
            export_position=export_position,
            user_id=1,
        )
        run = IngestRun(filename="a-oreilly-annotations.csv", user_id=1)
        session.add_all([highlight, run])
        session.flush()
        unit = CuratedUnit(
            ingest_run_id=run.id, curated_text="…", decision="keep", tags=[], user_id=1
        )
        session.add(unit)
        session.flush()
        session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
    card = Card(
        unit_id=unit.id,
        type="qa",
        front=front,
        back=f"Back for {front}.",
        original_front=front,
        original_back=f"Back for {front}.",
        tags=[],
        status="pending_review",
        generation_rounds=1,
        model="claude-sonnet-5",
        cost_microusd=0,
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def test_pending_cards_order_is_deterministic_within_a_tie(
    postgres_schema: Engine,
) -> None:
    """Tied cards come back id-ascending no matter what order the heap returns.

    Rewrites the first-inserted card so Postgres moves its tuple behind the
    second one's; an unordered `select(Card)` then arrives [second, first].
    Pre-fix the stable sort keeps that arrival order inside the tie, and this
    test fails. On SQLite the same insertion pattern always arrives id-ordered,
    so this half cannot be exercised there — hence the embedded server.
    """
    with Session(postgres_schema) as session:
        book = _add_book(session, title="Evals for AI Engineers", external_id="9781098188283")
        first = _add_pending_card(
            session, book=book, chapter="1. Introduction", export_position=0, front="First card"
        )
        second = _add_pending_card(
            session,
            book=book,
            chapter="1. Introduction",
            export_position=0,
            front="Second card",
            unit=first.unit,
        )
        # Grow the row so Postgres places the new tuple version behind the
        # second card's on the heap; the sort key is untouched either way.
        session.execute(update(Card).where(Card.id == first.id).values(status_reason="x" * 500))
        session.commit()
        first_id, second_id = first.id, second.id

        pending = list_pending_cards(session)

    assert [card.id for card in pending] == [first_id, second_id]


def test_pending_cards_keeps_documented_ordering() -> None:
    """Book, then chapter, then `export_position` still governs across units."""
    engine = _sqlite_engine()
    try:
        with Session(engine) as session:
            ddia = _add_book(
                session, title="Designing Data-Intensive Applications", external_id="a"
            )
            micro = _add_book(session, title="Building Microservices", external_id="b")
            # Insertion order is deliberately not the documented order.
            replication = _add_pending_card(
                session, book=ddia, chapter="5. Replication", export_position=1, front="rep"
            )
            testing = _add_pending_card(
                session, book=micro, chapter="9. Testing", export_position=0, front="test"
            )
            reliable = _add_pending_card(
                session, book=ddia, chapter="1. Reliable", export_position=9, front="reliable"
            )
            replication_late = _add_pending_card(
                session, book=ddia, chapter="5. Replication", export_position=3, front="rep-late"
            )
            session.commit()

            pending = list_pending_cards(session)

        assert [card.id for card in pending] == [
            testing.id,
            reliable.id,
            replication.id,
            replication_late.id,
        ], "book, then chapter, then export_position must still govern"
    finally:
        engine.dispose()


@pytest.mark.postgres
def test_pending_cards_match_across_backends(postgres_schema: Engine) -> None:
    """The same fixture yields an identical payload on SQLite and Postgres.

    The regression test for the issue itself: 430 pending cards returned in a
    different order on the two backends. The fixture carries a tied pair (two
    cards, one unit), a card in another chapter, and a card with no chapter.
    """
    engines = [_sqlite_engine(), postgres_schema]
    try:
        for engine in engines:
            with Session(engine) as session:
                ddia = _add_book(
                    session, title="Designing Data-Intensive Applications", external_id="a"
                )
                micro = _add_book(session, title="Building Microservices", external_id="b")
                tied_first = _add_pending_card(
                    session, book=ddia, chapter="5. Replication", export_position=7, front="tied-1"
                )
                _add_pending_card(
                    session,
                    book=ddia,
                    chapter="5. Replication",
                    export_position=7,
                    front="tied-2",
                    unit=tied_first.unit,
                )
                _add_pending_card(
                    session, book=ddia, chapter="1. Reliable", export_position=2, front="earlier"
                )
                _add_pending_card(
                    session, book=micro, chapter=None, export_position=0, front="no-chapter"
                )
                session.commit()

        payloads = []
        for engine in engines:
            with Session(engine) as session:
                payloads.append(list_pending_cards(session))

        on_sqlite, on_postgres = payloads
        assert on_sqlite == on_postgres
        assert [card.front for card in on_postgres] == ["no-chapter", "earlier", "tied-1", "tied-2"]
    finally:
        for engine in engines:
            engine.dispose()
