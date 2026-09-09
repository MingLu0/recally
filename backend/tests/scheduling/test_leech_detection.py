"""Step 6b-b gate: deterministic leech detection (docs/agents.md §7, leech bullet).

A leech is an *approved* card with 3 or more lifetime `review_logs` rows at
`rating=1` (Again). The count is defined here because `review_logs` has no lapse
counter and docs/data-model.md says `reps`/`lapses` are "derived from
`review_logs`"; a card that never entered FSRS has no review history and can
never be a leech. Detection is deterministic — no LLM (hard rule 2).

`learner.detect_leech_card_ids` does not exist until the implementation lands,
so the negatives below are red before it does (ADR-012).
"""

from collections.abc import Iterator
from datetime import timedelta

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from recally.config import Settings
from recally.container import Container
from recally.models import Base, Card, CuratedUnit, IngestRun, ReviewLog
from recally.models.base import utc_now

TEST_API_KEY = "test-key-not-a-real-secret"
NOW = utc_now()


@pytest.fixture
def container() -> Iterator[Container]:
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    settings = Settings(RECALLY_DATABASE_URL="sqlite://", RECALLY_API_KEY=TEST_API_KEY)
    try:
        yield Container(settings, engine=engine)
    finally:
        engine.dispose()


def _seed_card(session: Session, *, status: str = "approved") -> Card:
    """One unit and one card; `approved` by default, since only approved cards
    can have a review history (hard rule 1)."""
    run = IngestRun(filename="leech-detection-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    unit = CuratedUnit(
        ingest_run_id=run.id, curated_text="The curated text.", tags=[], decision="keep", user_id=1
    )
    session.add(unit)
    session.flush()
    card = Card(
        unit_id=unit.id,
        type="qa",
        front="What makes a card a leech?",
        back="Three or more lifetime Again ratings.",
        original_front="What makes a card a leech?",
        original_back="Three or more lifetime Again ratings.",
        tags=[],
        status=status,
        approved_at=NOW - timedelta(days=30) if status == "approved" else None,
        model="test-model",
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def _seed_ratings(session: Session, card_id: int, ratings: list[int]) -> None:
    """One `review_logs` row per rating, one hour apart, oldest first."""
    for index, rating in enumerate(ratings):
        session.add(
            ReviewLog(
                card_id=card_id,
                rated_at=NOW - timedelta(hours=len(ratings) - index),
                rating=rating,
                response_ms=4200,
                scheduled_days=0,
                state_before="review",
                user_id=1,
            )
        )


def _detected(container: Container) -> list[int]:
    from recally.scheduling import learner

    with container.session() as session:
        return learner.detect_leech_card_ids(session)


def test_card_with_three_again_ratings_is_a_leech(container: Container) -> None:
    """The threshold, at exactly 3: three lifetime rating=1 rows flag the card."""
    with container.session() as session:
        leech = _seed_card(session)
        ordinary = _seed_card(session)
        _seed_ratings(session, leech.id, [1, 1, 1])
        _seed_ratings(session, ordinary.id, [3, 3, 3])
        session.commit()
        leech_id, ordinary_id = leech.id, ordinary.id

    detected = _detected(container)

    assert leech_id in detected
    assert ordinary_id not in detected


def test_card_with_two_again_ratings_is_not_a_leech(container: Container) -> None:
    """Negative: the off-by-one on the other side — two Agains must not flag."""
    with container.session() as session:
        card = _seed_card(session)
        _seed_ratings(session, card.id, [1, 1])
        session.commit()
        card_id = card.id

    assert _detected(container) == [] or card_id not in _detected(container)


def test_unapproved_cards_are_never_leeches(container: Container) -> None:
    """Negative: only `approved` cards can be leeches — a card that never entered
    FSRS has no review history. Seeded with three Again rows anyway, so the test
    exercises the status filter rather than passing on an empty history."""
    with container.session() as session:
        pending = _seed_card(session, status="pending_review")
        _seed_ratings(session, pending.id, [1, 1, 1])
        session.commit()
        pending_id = pending.id

    assert pending_id not in _detected(container)


def test_good_and_easy_ratings_do_not_count_toward_the_leech_threshold(
    container: Container,
) -> None:
    """Negative: only rating=1 counts. Two Agains padded with Hard/Good/Easy rows
    stay below the threshold — a frequently reviewed card is not a leech unless
    it keeps failing."""
    with container.session() as session:
        card = _seed_card(session)
        _seed_ratings(session, card.id, [1, 3, 1, 4, 2, 3])
        session.commit()
        card_id = card.id

    assert card_id not in _detected(container)
