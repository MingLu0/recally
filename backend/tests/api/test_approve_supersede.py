"""Step 6b-b gate: approving a leech rewrite supersedes the card it replaces.

Spec: docs/agents.md §7 ("the leech keeps its FSRS state until the human
approves the rewrite, at which point the old card is set to `rejected` with
`status_reason="superseded by <id>"`"); docs/api-spec.md,
`POST /cards/{id}/approve`; ADR-008 (FSRS state is never recomputed); hard
rule 1 (only a human decision moves a card to `approved`/`rejected`).

The supersede lives in `services/cards.py` (`record_approval`) — the single
approval-gate implementation both the API route and the CLI call
(test_design_invariants.py, `CARD_STATUS_WRITE_FILES`).
`cards.supersedes_card_id` does not exist until the implementation lands, so
the negatives below are red before it does (ADR-012).
"""

from collections.abc import Iterator
from datetime import timedelta
from typing import Any

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


def _unit(session: Session) -> CuratedUnit:
    run = IngestRun(filename="supersede-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    unit = CuratedUnit(
        ingest_run_id=run.id, curated_text="The curated text.", tags=[], decision="keep", user_id=1
    )
    session.add(unit)
    session.flush()
    return unit


def _card(
    session: Session,
    unit: CuratedUnit,
    *,
    status: str,
    front: str,
    supersedes_card_id: int | None = None,
) -> Card:
    card = Card(
        unit_id=unit.id,
        type="qa",
        front=front,
        back=f"Back for: {front}",
        original_front=front,
        original_back=f"Back for: {front}",
        tags=[],
        status=status,
        approved_at=NOW - timedelta(days=30) if status == "approved" else None,
        model="test-model",
        supersedes_card_id=supersedes_card_id,
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def _seed_leech_with_state(session: Session, unit: CuratedUnit) -> Card:
    """An approved leech with a real FSRS history: `review` state, fitted
    stability/difficulty, a future `due` — the row ADR-008 says never moves."""
    leech = _card(session, unit, status="approved", front="The leech front.")
    session.add(
        CardState(
            card_id=leech.id,
            state="review",
            step=None,
            stability=42.5,
            difficulty=6.7,
            due=NOW + timedelta(days=9),
            last_review=NOW - timedelta(hours=1),
            user_id=1,
        )
    )
    session.flush()
    return leech


def _seed_pair(session: Session) -> tuple[Card, Card]:
    """One approved leech (with `card_state`) and its queued rewrite."""
    unit = _unit(session)
    leech = _seed_leech_with_state(session, unit)
    rewrite = _card(
        session,
        unit,
        status="pending_review",
        front="The rewrite front.",
        supersedes_card_id=leech.id,
    )
    return leech, rewrite


def _card_state_snapshot(session: Session, card_id: int) -> tuple[Any, ...]:
    state = session.get(CardState, card_id)
    assert state is not None
    return (
        state.state,
        state.step,
        state.stability,
        state.difficulty,
        state.due,
        state.last_review,
    )


def test_approving_a_rewrite_rejects_the_superseded_card(
    client: TestClient, container: Container
) -> None:
    """The roadmap's named gate: the old card becomes `rejected` with
    `status_reason="superseded by <id>"`, `<id>` being the *rewrite's* id."""
    with container.session() as session:
        leech, rewrite = _seed_pair(session)
        session.commit()
        leech_id, rewrite_id = leech.id, rewrite.id

    response = client.post(f"/cards/{rewrite_id}/approve", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    assert response.json()["status"] == "approved"
    with container.session() as session:
        superseded = session.get(Card, leech_id)
        assert superseded is not None
        assert superseded.status == "rejected"
        assert superseded.status_reason == f"superseded by {rewrite_id}"
        # And the rewrite entered FSRS like any approved card.
        assert session.get(CardState, rewrite_id) is not None


def test_approving_an_ordinary_card_supersedes_nothing(
    client: TestClient, container: Container
) -> None:
    """Negative: `supersedes_card_id` NULL touches exactly one row. Approving an
    ordinary card leaves a neighbouring approved card — and its state — alone."""
    with container.session() as session:
        unit = _unit(session)
        neighbour = _seed_leech_with_state(session, unit)
        ordinary = _card(session, unit, status="pending_review", front="An ordinary card.")
        session.commit()
        neighbour_id, ordinary_id = neighbour.id, ordinary.id
        neighbour_state_before = _card_state_snapshot(session, neighbour_id)

    response = client.post(f"/cards/{ordinary_id}/approve", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    with container.session() as session:
        ordinary = session.get(Card, ordinary_id)
        neighbour = session.get(Card, neighbour_id)
        assert ordinary is not None and neighbour is not None
        assert ordinary.supersedes_card_id is None
        assert ordinary.status == "approved"
        assert neighbour.status == "approved", (
            "approving an ordinary card changed a card it does not replace"
        )
        assert neighbour.status_reason is None
        assert _card_state_snapshot(session, neighbour_id) == neighbour_state_before


def test_rejecting_a_rewrite_leaves_the_leech_approved(
    client: TestClient, container: Container
) -> None:
    """Negative: a turned-down replacement must not take the original out of
    rotation — the leech stays `approved`, with its reason fields untouched."""
    with container.session() as session:
        leech, rewrite = _seed_pair(session)
        session.commit()
        leech_id, rewrite_id = leech.id, rewrite.id

    response = client.post(
        f"/cards/{rewrite_id}/reject",
        headers={"X-API-Key": TEST_API_KEY},
        json={"reason": "The rewrite is worse than the original."},
    )

    assert response.status_code == 200
    with container.session() as session:
        leech = session.get(Card, leech_id)
        rewrite = session.get(Card, rewrite_id)
        assert leech is not None and rewrite is not None
        assert rewrite.status == "rejected"
        assert rewrite.status_reason == "The rewrite is worse than the original."
        assert leech.status == "approved", "rejecting the rewrite retired the leech"
        assert leech.status_reason is None


def test_supersede_does_not_touch_the_leeches_card_state(
    client: TestClient, container: Container
) -> None:
    """Negative, ADR-008: statuses change, FSRS state is never recomputed — the
    superseded card's `card_state` row survives the status change untouched."""
    with container.session() as session:
        leech, rewrite = _seed_pair(session)
        session.commit()
        leech_id, rewrite_id = leech.id, rewrite.id
        before = _card_state_snapshot(session, leech_id)

    response = client.post(f"/cards/{rewrite_id}/approve", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    with container.session() as session:
        leech = session.get(Card, leech_id)
        assert leech is not None and leech.status == "rejected", (
            "the supersede did not happen, so there is no state-change to survive"
        )
        assert _card_state_snapshot(session, leech_id) == before, (
            "the superseded card's FSRS state was recomputed (ADR-008)"
        )


def test_supersede_counts_in_card_state_table(client: TestClient, container: Container) -> None:
    """The approval creates exactly one new `card_state` row — the rewrite's —
    and never deletes or duplicates the leech's."""
    with container.session() as session:
        leech, rewrite = _seed_pair(session)
        session.commit()
        leech_id, rewrite_id = leech.id, rewrite.id

    client.post(f"/cards/{rewrite_id}/approve", headers={"X-API-Key": TEST_API_KEY})

    with container.session() as session:
        rows = list(session.scalars(select(CardState)).all())
    assert sorted(row.card_id for row in rows) == sorted([leech_id, rewrite_id])
