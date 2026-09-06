"""The model layer against docs/data-model.md.

These assert the parts of the schema the docs and AGENTS.md hard rules single out:
the uniqueness constraints that make re-ingestion idempotent, `truncated` living only
on `highlights` (hard rule 7), and money stored as integer micro-USD.
"""

from sqlalchemy import Integer, UniqueConstraint, create_engine
from sqlalchemy.orm import Session

from recally.models import Base, Book, Card, CuratedUnit, Highlight


def test_book_is_unique_per_source_and_external_id() -> None:
    unique_column_sets = {
        tuple(constraint.columns.keys())
        for constraint in Book.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert ("source", "external_id") in unique_column_sets


def test_highlight_dedupe_key_is_unique() -> None:
    """Hard rule 6: same key never inserts a second row."""
    assert Highlight.__table__.columns["dedupe_key"].unique is True


def test_review_log_is_unique_per_card_and_client_timestamp() -> None:
    """Makes an offline batch re-sync idempotent (docs/data-model.md, review_logs)."""
    from recally.models import ReviewLog

    unique_column_sets = {
        tuple(constraint.columns.keys())
        for constraint in ReviewLog.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert ("card_id", "rated_at") in unique_column_sets


def test_truncated_lives_only_on_highlights() -> None:
    """Hard rule 7: units and cards derive 'any source truncated'; they never store it."""
    assert "truncated" in Highlight.__table__.columns
    assert "truncated" not in CuratedUnit.__table__.columns
    assert "truncated" not in Card.__table__.columns


def test_costs_are_integer_micro_usd() -> None:
    """No cents, no float dollars (docs/data-model.md)."""
    from recally.models import IngestRun, LlmCall

    for table in (Card.__table__, IngestRun.__table__, LlmCall.__table__):
        cost_column = table.columns["cost_microusd"]
        assert isinstance(cost_column.type, Integer), table.name


def test_user_id_defaults_to_one_on_insert() -> None:
    """The server default is what fills `user_id` for a v1 single-user insert."""
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        book = Book(title="30 Agents", source="oreilly", external_id="9781098150952")
        session.add(book)
        session.commit()
        session.refresh(book)
        assert book.user_id == 1
    engine.dispose()
