"""Step 3a gate: approving a card creates its `card_state` row (enters FSRS).

Hard rule 1 works in one direction: only approval enters scheduling, so
`POST /cards/{id}/approve` writes the row and `POST /cards/{id}/reject` never
does (docs/api-spec.md, "Approval queue"; docs/data-model.md, `card_state`).
"""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
from recally.container import Container
from recally.main import create_app
from recally.models import Base, Card, CardState, CuratedUnit, IngestRun

TEST_API_KEY = "test-key-not-a-real-secret"


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


@pytest.fixture
def client(container: Container, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    app = create_app()
    app.dependency_overrides[container_dependency] = lambda: container
    monkeypatch.setenv("RECALLY_API_KEY", TEST_API_KEY)
    get_settings.cache_clear()
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        get_settings.cache_clear()


def _queued_card(session: Session, *, status: str = "pending_review") -> Card:
    """One unit and one card awaiting the human; the minimum provenance chain."""
    run = IngestRun(filename="seed-oreilly-annotations.csv", user_id=1)
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
        front="What is the Gulf of Specification?",
        back="The gap between intent and instructions.",
        original_front="What is the Gulf of Specification?",
        original_back="The gap between intent and instructions.",
        tags=[],
        status=status,
        model="test-model",
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def _card_states(session: Session, card_id: int) -> list[CardState]:
    return list(session.scalars(select(CardState).where(CardState.card_id == card_id)).all())


def test_approve_creates_card_state_row(client: TestClient, container: Container) -> None:
    """Approval enters FSRS: one row, `learning` at step 0, `due` at the approval
    time, stability/difficulty/last_review NULL (nothing reviewed yet)."""
    with container.session() as session:
        card = _queued_card(session)
        session.commit()
        card_id = card.id

    response = client.post(f"/cards/{card_id}/approve", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    with container.session() as session:
        rows = _card_states(session, card_id)
        assert len(rows) == 1
        state = rows[0]
        assert state.state == "learning"
        assert state.step == 0
        assert state.due is not None
        assert state.stability is None
        assert state.difficulty is None
        assert state.last_review is None
        approved = session.get(Card, card_id)
        assert approved is not None and approved.approved_at is not None
        assert state.due == approved.approved_at


def test_approve_with_edits_still_creates_card_state(
    client: TestClient, container: Container
) -> None:
    """The step 2 edit path keeps working and the row is created either way."""
    with container.session() as session:
        card = _queued_card(session)
        session.commit()
        card_id = card.id

    response = client.post(
        f"/cards/{card_id}/approve",
        headers={"X-API-Key": TEST_API_KEY},
        json={"front": "Edited front?", "back": "Edited back."},
    )

    assert response.status_code == 200
    assert response.json()["front"] == "Edited front?"
    with container.session() as session:
        rows = _card_states(session, card_id)
        assert len(rows) == 1
        assert rows[0].state == "learning"
        assert rows[0].step == 0
        assert rows[0].due is not None


def test_reject_creates_no_card_state(client: TestClient, container: Container) -> None:
    """Negative: hard rule 1 works in one direction — rejection is not an entry
    into scheduling, so a rejected card never gets a `card_state` row."""
    with container.session() as session:
        approved_card = _queued_card(session)
        rejected_card = _queued_card(session)
        session.commit()
        approved_id, rejected_id = approved_card.id, rejected_card.id

    client.post(f"/cards/{approved_id}/approve", headers={"X-API-Key": TEST_API_KEY})
    response = client.post(
        f"/cards/{rejected_id}/reject",
        headers={"X-API-Key": TEST_API_KEY},
        json={"reason": "Too trivial to be worth a card."},
    )

    assert response.status_code == 200
    with container.session() as session:
        assert _card_states(session, rejected_id) == []
        # The approve above is what proves the table is writable: exactly its one row.
        assert len(list(session.scalars(select(CardState)).all())) == 1


def test_approve_is_not_repeatable(client: TestClient, container: Container) -> None:
    """Negative: the second approve of an already-`approved` card is the existing
    409 (`cards.py`, `_queued_card`) and neither creates a second row nor resets
    the first."""
    with container.session() as session:
        card = _queued_card(session)
        session.commit()
        card_id = card.id

    first = client.post(f"/cards/{card_id}/approve", headers={"X-API-Key": TEST_API_KEY})
    assert first.status_code == 200
    with container.session() as session:
        rows_before = _card_states(session, card_id)
        assert len(rows_before) == 1
        due_before = rows_before[0].due

    second = client.post(f"/cards/{card_id}/approve", headers={"X-API-Key": TEST_API_KEY})

    assert second.status_code == 409
    with container.session() as session:
        rows_after = _card_states(session, card_id)
        assert len(rows_after) == 1
        assert rows_after[0].due == due_before
