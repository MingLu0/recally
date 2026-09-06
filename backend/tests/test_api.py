"""The step 1e gate: `X-API-Key` auth and the two endpoints the step 1 gate reads.

The container is overridden onto an in-memory SQLite database (docs/backend.md,
"Testing shape per layer"), so no test touches the real one and no test needs a
`.env`.
"""

from collections.abc import Iterator
from datetime import datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool

from recally.api.deps import container_dependency
from recally.config import Settings
from recally.container import Container
from recally.main import create_app
from recally.models import (
    Base,
    Book,
    Card,
    CardState,
    CuratedUnit,
    CuratedUnitHighlight,
    Highlight,
    IngestRun,
)

TEST_API_KEY = "test-key-not-a-real-secret"


@pytest.fixture
def container() -> Iterator[Container]:
    """A container on a fresh in-memory database.

    `StaticPool` on a single connection is what makes `sqlite://` usable here: without
    it every checkout gets its own empty database and the schema vanishes between the
    fixture and the request.
    """
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
def client(container: Container) -> Iterator[TestClient]:
    app = create_app()
    app.dependency_overrides[container_dependency] = lambda: container
    with TestClient(app) as test_client:
        yield test_client


def test_request_without_the_api_key_is_rejected(client: TestClient) -> None:
    """The step 1 merge gate: no key, no access."""
    response = client.get("/decks")

    assert response.status_code == 401
    assert response.json() == {
        "status": 401,
        "detail": "Invalid or missing X-API-Key header.",
    }


def test_request_with_the_api_key_is_accepted(client: TestClient) -> None:
    """The other half of the gate: the configured key gets 200."""
    response = client.get("/decks", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    assert response.json() == {"decks": []}


def test_request_with_a_wrong_api_key_is_rejected(client: TestClient) -> None:
    """A wrong key is indistinguishable from a missing one, by design."""
    missing = client.get("/decks")
    wrong = client.get("/decks", headers={"X-API-Key": "wrong-key"})

    assert wrong.status_code == 401
    assert wrong.json() == missing.json()


def test_ingest_status_is_also_behind_the_key(client: TestClient) -> None:
    """Every route carries the guard, not just the one the gate names."""
    assert client.get("/ingest/status").status_code == 401


def test_errors_use_problem_json(client: TestClient) -> None:
    """docs/api-spec.md, "Errors": one body shape, including for routes we never wrote."""
    response = client.get("/nonexistent", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 404
    assert response.json() == {"status": 404, "detail": "Not Found"}
    assert response.headers["content-type"].startswith("application/problem+json")


def test_ingest_status_reports_the_latest_run(client: TestClient, container: Container) -> None:
    """The endpoint reads the newest `ingest_runs` row, with `rows_unchanged` derived."""
    with container.session() as session:
        session.add(
            IngestRun(filename="older-oreilly-annotations.csv", rows_seen=1, rows_new=1, user_id=1)
        )
        session.add(
            IngestRun(
                filename="30-agents-every-oreilly-annotations.csv",
                rows_seen=380,
                rows_new=56,
                rows_updated=0,
                rows_removed=2,
                user_id=1,
            )
        )
        session.commit()

    body = client.get("/ingest/status", headers={"X-API-Key": TEST_API_KEY}).json()

    assert body["filename"] == "30-agents-every-oreilly-annotations.csv"
    assert body["rows_seen"] == 380
    assert body["rows_new"] == 56
    assert body["rows_removed"] == 2
    # 380 seen - 56 new - 0 updated; derived, never stored (docs/data-model.md).
    assert body["rows_unchanged"] == 324


def test_ingest_status_is_404_before_the_first_ingest(client: TestClient) -> None:
    response = client.get("/ingest/status", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 404
    assert response.json()["status"] == 404


def test_decks_lists_books_with_no_cards_yet(client: TestClient, container: Container) -> None:
    """The roadmap step 1 gate reads the export's books off /decks, before any card exists."""
    with container.session() as session:
        session.add_all(
            [
                _book(title="Evals for AI Engineers", external_id="9781098188283"),
                _book(title="30 Agents Every AI Engineer Must Build", external_id="1"),
            ]
        )
        session.commit()

    body = client.get("/decks", headers={"X-API-Key": TEST_API_KEY}).json()

    # Ordered by title, and an ingested-but-uncurated book reports zero rather than
    # dropping out of the list.
    assert [deck["title"] for deck in body["decks"]] == [
        "30 Agents Every AI Engineer Must Build",
        "Evals for AI Engineers",
    ]
    assert all(deck["total"] == 0 and deck["due"] == 0 for deck in body["decks"])


def test_decks_counts_only_approved_cards_and_only_due_ones_as_due(
    client: TestClient, container: Container
) -> None:
    """Hard rule 1: an unapproved card is in no deck. ADR-008: a buried one is not due."""
    now = datetime(2026, 9, 6, 12, 0, 0)
    with container.session() as session:
        book = _book(title="Evals for AI Engineers", external_id="9781098188283")
        session.add(book)
        session.flush()
        highlight = Highlight(
            book_id=book.id,
            raw_text="An LLM pipeline's behaviour only makes sense end-to-end.",
            dedupe_key="e3b0c442-98fc-1c14-9afb-f4c8996fb924",
            source="oreilly",
            highlighted_at=now.date(),
            export_position=0,
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

        due_card = _card(unit.id, status="approved")
        not_yet_due_card = _card(unit.id, status="approved")
        buried_card = _card(unit.id, status="approved", suspended_until=now + timedelta(hours=6))
        pending_card = _card(unit.id, status="pending_review")
        session.add_all([due_card, not_yet_due_card, buried_card, pending_card])
        session.flush()
        session.add_all(
            [
                CardState(
                    card_id=due_card.id, state="review", due=now - timedelta(days=1), user_id=1
                ),
                CardState(
                    card_id=not_yet_due_card.id,
                    state="review",
                    due=now + timedelta(days=3),
                    user_id=1,
                ),
                CardState(
                    card_id=buried_card.id, state="review", due=now - timedelta(days=1), user_id=1
                ),
            ]
        )
        session.commit()

    # The service takes `now` so the assertion does not race the clock; the router
    # passes the real one.
    from recally.services.decks import list_decks

    with container.session() as session:
        decks = list_decks(session, now=now)

    assert len(decks) == 1
    assert decks[0].total == 3, "the pending_review card is in no deck (hard rule 1)"
    assert decks[0].due == 1, "the future-due and buried cards are not due (ADR-008)"


def _book(*, title: str, external_id: str) -> Book:
    return Book(title=title, source="oreilly", external_id=external_id, user_id=1)


def _card(unit_id: int, *, status: str, suspended_until: datetime | None = None) -> Card:
    return Card(
        unit_id=unit_id,
        type="qa",
        front="Why evaluate traces rather than individual steps?",
        back="An LLM pipeline's behaviour only makes sense end-to-end.",
        original_front="Why evaluate traces rather than individual steps?",
        original_back="An LLM pipeline's behaviour only makes sense end-to-end.",
        tags=[],
        status=status,
        suspended_until=suspended_until,
        model="claude-sonnet-5",
        user_id=1,
    )
