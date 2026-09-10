"""The step 1e gate: `X-API-Key` auth and the two endpoints the step 1 gate reads.

The container is overridden onto an in-memory SQLite database (docs/backend.md,
"Testing shape per layer"), so no test touches the real one and no test needs a
`.env`.
"""

from collections.abc import Iterator
from datetime import date, datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
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


def test_startup_fails_when_settings_are_invalid(monkeypatch: pytest.MonkeyPatch) -> None:
    """The step 1e intent: an unusable key fails boot, not the first request with a 500."""
    monkeypatch.setenv("RECALLY_API_KEY", "")
    get_settings.cache_clear()
    try:
        with pytest.raises(ValidationError), TestClient(create_app()):
            pass
    finally:
        get_settings.cache_clear()


def test_an_empty_api_key_fails_settings_validation(monkeypatch: pytest.MonkeyPatch) -> None:
    """`.env.example` ships `RECALLY_API_KEY=` empty; that must fail like a missing key."""
    monkeypatch.setenv("RECALLY_API_KEY", "")
    get_settings.cache_clear()
    try:
        with pytest.raises(ValidationError):
            get_settings()
    finally:
        get_settings.cache_clear()


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


def test_an_unexpected_error_is_also_problem_json(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The one body shape covers a crash inside a route, without leaking the exception."""
    app = create_app()
    app.dependency_overrides[container_dependency] = lambda: container
    monkeypatch.setenv("RECALLY_API_KEY", TEST_API_KEY)
    get_settings.cache_clear()

    @app.get("/__boom")
    def boom() -> None:
        raise RuntimeError("sqlite3.OperationalError: database is locked")

    try:
        with TestClient(app, raise_server_exceptions=False) as test_client:
            response = test_client.get("/__boom", headers={"X-API-Key": TEST_API_KEY})
    finally:
        get_settings.cache_clear()

    assert response.status_code == 500
    assert response.json() == {"status": 500, "detail": "Internal server error."}
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


def test_deck_progress_counts_review_state_cards(client: TestClient, container: Container) -> None:
    """The G2 definition: 48 approved cards, 30 in FSRS `review` state → 0.625."""
    with container.session() as session:
        _book_with_cards(session, approved=48, in_review_state=30)

    body = client.get("/decks", headers={"X-API-Key": TEST_API_KEY}).json()

    assert body["decks"][0]["total"] == 48
    assert body["decks"][0]["progress"] == 0.625


def test_deck_with_no_cards_reports_zero_progress_not_a_divide_by_zero(
    client: TestClient, container: Container
) -> None:
    """A book with no approved cards reports `progress: 0.0` rather than raising."""
    with container.session() as session:
        session.add(_book(title="Evals for AI Engineers", external_id="9781098188283"))
        session.commit()

    response = client.get("/decks", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    assert response.json()["decks"][0]["progress"] == 0.0


def test_deck_progress_ignores_unapproved_cards(client: TestClient, container: Container) -> None:
    """pending_review cards are in neither the numerator nor the denominator (hard rule 1)."""
    with container.session() as session:
        _book_with_cards(session, approved=4, in_review_state=1, pending_review=10)

    body = client.get("/decks", headers={"X-API-Key": TEST_API_KEY}).json()

    assert body["decks"][0]["total"] == 4
    assert body["decks"][0]["progress"] == 0.25


def _book_with_cards(
    session, *, approved: int, in_review_state: int, pending_review: int = 0
) -> None:
    """One book whose cards all hang off a single highlight → unit chain."""
    now = datetime(2026, 9, 6, 12, 0, 0)
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
    unit = CuratedUnit(ingest_run_id=run.id, curated_text="…", decision="keep", tags=[], user_id=1)
    session.add(unit)
    session.flush()
    session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))

    for index in range(approved):
        card = _card(unit.id, status="approved")
        session.add(card)
        session.flush()
        session.add(
            CardState(
                card_id=card.id,
                state="review" if index < in_review_state else "learning",
                due=now,
                user_id=1,
            )
        )
    for _ in range(pending_review):
        session.add(_card(unit.id, status="pending_review"))
    session.commit()


def _book(*, title: str, external_id: str) -> Book:
    return Book(title=title, source="oreilly", external_id=external_id, user_id=1)


def _seed_queued_card(
    session: Session,
    *,
    book: Book,
    status: str,
    dedupe_key: str,
    chapter: str | None = "1. Introduction",
    export_position: int = 0,
) -> Card:
    """A card awaiting the human, wired to one source highlight — the queue skips
    sourceless cards, so a bare `_card` row would be invisible to `GET /cards/pending`."""
    run = IngestRun(filename="seed-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    unit = CuratedUnit(ingest_run_id=run.id, curated_text="…", decision="keep", tags=[], user_id=1)
    session.add(unit)
    session.flush()
    highlight = Highlight(
        book_id=book.id,
        chapter=chapter,
        raw_text="The Gulf of Specification is this gap.",
        dedupe_key=dedupe_key,
        source="oreilly",
        highlighted_at=date(2026, 6, 19),
        export_position=export_position,
        user_id=1,
    )
    session.add(highlight)
    session.flush()
    session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
    card = _card(unit.id, status=status)
    session.add(card)
    session.flush()
    return card


def test_pending_counts_match_the_queue(client: TestClient, container: Container) -> None:
    """The G1 gate (issue #132): `counts` reports the two queue buckets."""
    with container.session() as session:
        book = _book(title="Evals for AI Engineers", external_id="9781098188283")
        session.add(book)
        session.flush()
        for index in range(5):
            _seed_queued_card(session, book=book, status="pending_review", dedupe_key=f"pr-{index}")
        for index in range(3):
            _seed_queued_card(session, book=book, status="needs_human", dedupe_key=f"nh-{index}")
        session.commit()

    body = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()

    assert body["counts"] == {"pending_review": 5, "needs_human": 3}
    assert len(body["cards"]) == 8


def test_approving_a_card_decrements_the_right_bucket(
    client: TestClient, container: Container
) -> None:
    """Approving one `pending_review` card leaves the `needs_human` bucket unchanged."""
    with container.session() as session:
        book = _book(title="Evals for AI Engineers", external_id="9781098188283")
        session.add(book)
        session.flush()
        for index in range(5):
            _seed_queued_card(session, book=book, status="pending_review", dedupe_key=f"pr-{index}")
        for index in range(3):
            _seed_queued_card(session, book=book, status="needs_human", dedupe_key=f"nh-{index}")
        session.commit()

    before = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()
    assert before["counts"] == {"pending_review": 5, "needs_human": 3}

    approved_id = next(card["id"] for card in before["cards"] if card["status"] == "pending_review")
    response = client.post(f"/cards/{approved_id}/approve", headers={"X-API-Key": TEST_API_KEY})
    assert response.status_code == 200

    after = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()
    assert after["counts"] == {"pending_review": 4, "needs_human": 3}


def test_pending_counts_ignore_the_book_and_chapter_filters(
    client: TestClient, container: Container
) -> None:
    """`counts` is collection-wide (issue #132): a filtered call returns a shorter
    `cards[]` but the same counts — Today's tiles must be right before any filter
    exists, so the counts must not follow the route's query params."""
    with container.session() as session:
        evals = _book(title="Evals for AI Engineers", external_id="9781098188283")
        agents = _book(title="30 Agents Every AI Engineer Must Build", external_id="1")
        session.add_all([evals, agents])
        session.flush()
        for index in range(2):
            _seed_queued_card(
                session, book=evals, status="pending_review", dedupe_key=f"e-pr-{index}"
            )
        _seed_queued_card(session, book=evals, status="needs_human", dedupe_key="e-nh-0")
        for index in range(3):
            _seed_queued_card(
                session,
                book=agents,
                status="pending_review",
                dedupe_key=f"a-pr-{index}",
                chapter="2. Tools",
            )
        for index in range(2):
            _seed_queued_card(
                session,
                book=agents,
                status="needs_human",
                dedupe_key=f"a-nh-{index}",
                chapter="2. Tools",
            )
        session.commit()
        evals_id = evals.id

    unfiltered = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()
    filtered = client.get(
        f"/cards/pending?book_id={evals_id}", headers={"X-API-Key": TEST_API_KEY}
    ).json()

    assert len(filtered["cards"]) == 3, "the list still honours the book filter"
    assert len(unfiltered["cards"]) == 8
    assert filtered["counts"] == unfiltered["counts"] == {"pending_review": 5, "needs_human": 3}


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


def test_health_is_unauthenticated(client: TestClient) -> None:
    """`GET /health` is the load-balancer probe, so it must not need the key."""
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_health_auth_requires_the_api_key(client: TestClient) -> None:
    """The Settings connection test tells a wrong key apart from an unreachable
    server, so `/health/auth` must 401 rather than 200 without the key."""
    missing = client.get("/health/auth")
    wrong = client.get("/health/auth", headers={"X-API-Key": "wrong-key"})

    assert missing.status_code == 401
    assert wrong.status_code == 401


def test_health_auth_accepts_the_configured_key(client: TestClient) -> None:
    response = client.get("/health/auth", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    # The authenticated probe also reports the running build's version, so
    # version skew is readable in the Settings connection test rather than only
    # as a failed decode in the app (issue #195); tests/api/test_health.py owns
    # that contract. Here the point is only that the key is accepted.
    assert response.json()["status"] == "ok"


def test_approve_batch_approves_every_clean_card(client: TestClient, container: Container) -> None:
    """G4: a batch of `pending_review` ids is approved in one call."""
    with container.session() as session:
        book = _book(title="Evals for AI Engineers", external_id="9781098188283")
        session.add(book)
        session.flush()
        for index in range(3):
            _seed_queued_card(session, book=book, status="pending_review", dedupe_key=f"pr-{index}")
        session.commit()

    pending = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()
    card_ids = [card["id"] for card in pending["cards"]]

    response = client.post(
        "/cards/approve-batch",
        json={"card_ids": card_ids},
        headers={"X-API-Key": TEST_API_KEY},
    )

    assert response.status_code == 200
    results = response.json()["results"]
    assert [item["ok"] for item in results] == [True, True, True]
    assert all(item["status"] == "approved" for item in results)

    after = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()
    assert after["counts"] == {"pending_review": 0, "needs_human": 0}


def test_approve_batch_never_approves_a_needs_human_card(
    client: TestClient, container: Container
) -> None:
    """Hard rule 1: `needs_human` is excluded from every bulk path, server-side.

    The card must still be `needs_human` afterwards — a bulk action can never be
    the thing that enters it into FSRS.
    """
    with container.session() as session:
        book = _book(title="Evals for AI Engineers", external_id="9781098188283")
        session.add(book)
        session.flush()
        _seed_queued_card(session, book=book, status="pending_review", dedupe_key="pr-0")
        _seed_queued_card(session, book=book, status="needs_human", dedupe_key="nh-0")
        session.commit()

    pending = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()
    clean_id = next(c["id"] for c in pending["cards"] if c["status"] == "pending_review")
    needs_human_id = next(c["id"] for c in pending["cards"] if c["status"] == "needs_human")

    response = client.post(
        "/cards/approve-batch",
        json={"card_ids": [clean_id, needs_human_id]},
        headers={"X-API-Key": TEST_API_KEY},
    )

    assert response.status_code == 200
    results = {item["card_id"]: item for item in response.json()["results"]}
    assert results[clean_id]["ok"] is True
    assert results[needs_human_id]["ok"] is False

    after = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()
    assert after["counts"] == {"pending_review": 0, "needs_human": 1}
    still_queued = next(c for c in after["cards"] if c["id"] == needs_human_id)
    assert still_queued["status"] == "needs_human"


def test_approve_batch_results_are_in_request_order(
    client: TestClient, container: Container
) -> None:
    """One entry per request item, in request order — the client matches by position."""
    with container.session() as session:
        book = _book(title="Evals for AI Engineers", external_id="9781098188283")
        session.add(book)
        session.flush()
        for index in range(2):
            _seed_queued_card(session, book=book, status="pending_review", dedupe_key=f"pr-{index}")
        session.commit()

    pending = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()
    real_ids = [card["id"] for card in pending["cards"]]
    requested = [real_ids[0], 999_999, real_ids[1]]

    response = client.post(
        "/cards/approve-batch",
        json={"card_ids": requested},
        headers={"X-API-Key": TEST_API_KEY},
    )

    results = response.json()["results"]
    assert [item["card_id"] for item in results] == requested
    assert [item["ok"] for item in results] == [True, False, True]


def test_approve_batch_partial_failure_leaves_no_card_inconsistent(
    client: TestClient, container: Container
) -> None:
    """An unknown id fails only its own entry; the rest approve exactly once."""
    with container.session() as session:
        book = _book(title="Evals for AI Engineers", external_id="9781098188283")
        session.add(book)
        session.flush()
        for index in range(2):
            _seed_queued_card(session, book=book, status="pending_review", dedupe_key=f"pr-{index}")
        session.commit()

    pending = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()
    card_ids = [card["id"] for card in pending["cards"]]

    response = client.post(
        "/cards/approve-batch",
        json={"card_ids": [card_ids[0], 999_999, card_ids[1]]},
        headers={"X-API-Key": TEST_API_KEY},
    )
    assert response.status_code == 200

    with container.session() as session:
        for card_id in card_ids:
            states = session.query(CardState).filter(CardState.card_id == card_id).all()
            assert len(states) == 1


def test_approve_batch_rejects_a_malformed_body(client: TestClient) -> None:
    """A body that is not `{"card_ids": [...]}` is a 422, as `rate-batch` is."""
    response = client.post(
        "/cards/approve-batch",
        json={"ids": [1, 2]},
        headers={"X-API-Key": TEST_API_KEY},
    )

    assert response.status_code == 422


# --- G5: per-card FSRS state/due on browse, chapter count on /decks (issue #172) ---


def _book_with_chaptered_cards(
    session: Session,
    *,
    chapters: list[str | None],
    title: str = "Evals for AI Engineers",
    external_id: str = "9781098188283",
) -> Book:
    """One approved card per entry in `chapters`, each on its own source highlight.

    `export_position` follows list order so a test can assert the endpoint's ordering
    is the export's, not the chapter string's.
    """
    book = _book(title=title, external_id=external_id)
    session.add(book)
    session.flush()
    run = IngestRun(filename="g5-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    for position, chapter in enumerate(chapters):
        highlight = Highlight(
            book_id=book.id,
            chapter=chapter,
            raw_text="An LLM pipeline's behaviour only makes sense end-to-end.",
            dedupe_key=f"g5-highlight-{position}",
            source="oreilly",
            highlighted_at=date(2026, 6, 19),
            export_position=position,
            user_id=1,
        )
        unit = CuratedUnit(
            ingest_run_id=run.id, curated_text="…", decision="keep", tags=[], user_id=1
        )
        session.add_all([highlight, unit])
        session.flush()
        session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
        session.add(_card(unit.id, status="approved"))
    session.commit()
    return book


def test_book_cards_include_state_and_due_for_a_scheduled_card(
    client: TestClient, container: Container
) -> None:
    """A card in `review` reports its FSRS `state` and its `card_state.due`.

    The book-detail row renders "Due in 4h" from this value; it is servable because
    `card_state.due` exists, and hard rule 5 holds as long as the number comes from
    here rather than being computed on the phone.
    """
    scheduled_due = datetime(2026, 9, 9, 8, 0, 0)
    with container.session() as session:
        book = _book_with_chaptered_cards(session, chapters=["3. Error Analysis"])
        book_id = book.id
        card_id = session.query(Card).one().id
        session.add(
            CardState(
                card_id=card_id,
                state="review",
                step=None,
                due=scheduled_due,
                last_review=datetime(2026, 9, 4, 8, 0, 0),
                user_id=1,
            )
        )
        session.commit()

    body = client.get(f"/decks/{book_id}/cards", headers={"X-API-Key": TEST_API_KEY}).json()

    assert len(body["cards"]) == 1
    card = body["cards"][0]
    assert card["state"] == "review"
    assert card["due"] == "2026-09-09T08:00:00Z"
    # The documented shape is unchanged around the two new fields.
    assert card["id"] == card_id
    assert card["type"] == "qa"
    assert card["chapter"] == "3. Error Analysis"


def test_book_cards_omit_due_for_a_card_at_learning_step_zero(
    client: TestClient, container: Container
) -> None:
    """A never-reviewed card reports `due: null`, not the approval timestamp.

    Approval writes `card_state` at `learning` step 0 due immediately, so the stored
    `due` for such a card is an availability marker, not an FSRS-scheduled date. The
    browse row must show no date rather than a fabricated one.
    """
    with container.session() as session:
        book = _book_with_chaptered_cards(session, chapters=["3. Error Analysis"])
        book_id = book.id
        card_id = session.query(Card).one().id
        session.add(
            CardState(
                card_id=card_id,
                state="learning",
                step=0,
                due=datetime(2026, 9, 6, 12, 0, 0),
                last_review=None,
                user_id=1,
            )
        )
        session.commit()

    body = client.get(f"/decks/{book_id}/cards", headers={"X-API-Key": TEST_API_KEY}).json()

    card = body["cards"][0]
    assert card["state"] == "learning"
    assert card["due"] is None, "a fresh card has no scheduled due date to report"


def test_book_cards_respect_the_chapter_filter(client: TestClient, container: Container) -> None:
    """`?chapter=` narrows the list without changing any card's shape."""
    with container.session() as session:
        book = _book_with_chaptered_cards(
            session, chapters=["3. Error Analysis", "4. Evaluators", "3. Error Analysis"]
        )
        book_id = book.id
        for card in session.query(Card).order_by(Card.id).all():
            session.add(
                CardState(
                    card_id=card.id,
                    state="review",
                    due=datetime(2026, 9, 9, 8, 0, 0),
                    last_review=datetime(2026, 9, 4, 8, 0, 0),
                    user_id=1,
                )
            )
        session.commit()

    unfiltered = client.get(f"/decks/{book_id}/cards", headers={"X-API-Key": TEST_API_KEY}).json()
    filtered = client.get(
        f"/decks/{book_id}/cards",
        params={"chapter": "3. Error Analysis"},
        headers={"X-API-Key": TEST_API_KEY},
    ).json()

    assert len(unfiltered["cards"]) == 3
    assert len(filtered["cards"]) == 2
    assert {card["chapter"] for card in filtered["cards"]} == {"3. Error Analysis"}
    # Same keys, same values per card — the filter selects rows, it does not reshape them.
    assert sorted(filtered["cards"][0]) == sorted(unfiltered["cards"][0])
    by_id = {card["id"]: card for card in unfiltered["cards"]}
    assert all(card == by_id[card["id"]] for card in filtered["cards"])


def test_decks_report_a_chapter_count(client: TestClient, container: Container) -> None:
    """The Decks row's "48 cards · 9 chapters": distinct chapters over approved cards."""
    with container.session() as session:
        _book_with_chaptered_cards(session, chapters=[f"{n}. Chapter" for n in range(1, 10)])

    body = client.get("/decks", headers={"X-API-Key": TEST_API_KEY}).json()

    assert body["decks"][0]["total"] == 9
    assert body["decks"][0]["chapters"] == 9


def test_decks_chapter_count_is_zero_for_an_empty_book(
    client: TestClient, container: Container
) -> None:
    """A book with no approved cards reports 0 chapters, as `progress` reports 0.0."""
    with container.session() as session:
        session.add(_book(title="Evals for AI Engineers", external_id="9781098188283"))
        session.commit()

    response = client.get("/decks", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    assert response.json()["decks"][0]["chapters"] == 0


# --- G6: truncated count per book (issue #173) ---


def _book_with_truncated_highlights(
    session: Session,
    *,
    truncated_flags: list[bool],
    card_status: str = "approved",
    title: str = "30 Agents Every AI Engineer Must Build",
    external_id: str = "1",
) -> Book:
    """One card per entry in `truncated_flags`, each on its own source highlight.

    The flag is the source highlight's `truncated` column — the only place it lives
    (hard rule 7) — so a `False` entry is a clean highlight and a `True` one a clipped
    export row. `card_status` lets a test seed the same book entirely unapproved.
    """
    book = _book(title=title, external_id=external_id)
    session.add(book)
    session.flush()
    run = IngestRun(filename="g6-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    for position, is_truncated in enumerate(truncated_flags):
        highlight = Highlight(
            book_id=book.id,
            chapter="3. Error Analysis",
            raw_text="An LLM pipeline's behaviour only makes sense end-to-",
            dedupe_key=f"g6-highlight-{position}",
            source="oreilly",
            highlighted_at=date(2026, 6, 19),
            export_position=position,
            truncated=is_truncated,
            user_id=1,
        )
        unit = CuratedUnit(
            ingest_run_id=run.id, curated_text="…", decision="keep", tags=[], user_id=1
        )
        session.add_all([highlight, unit])
        session.flush()
        session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
        session.add(_card(unit.id, status=card_status))
    session.commit()
    return book


def test_deck_truncated_count_includes_approved_cards(
    client: TestClient, container: Container
) -> None:
    """The assertion G6 names: two truncated highlights report 2 either side of approval.

    Unlike `total`, `due`, `progress` and `chapters` — all scoped to approved cards
    (hard rule 1) — the truncated count is a property of the *export*, not of the
    scheduling population. Clipping does not stop being true because the card it
    produced is still in the queue, so the count spans every card status. The badge
    is informational: nothing here or on the app reconstructs the lost text (hard
    rule 7).
    """
    with container.session() as session:
        _book_with_truncated_highlights(
            session, truncated_flags=[True, True, False], card_status="approved"
        )

    approved_body = client.get("/decks", headers={"X-API-Key": TEST_API_KEY}).json()

    assert approved_body["decks"][0]["truncated"] == 2

    # The same book with none of its cards approved reports the same 2.
    with container.session() as session:
        session.query(Card).delete()
        session.query(CuratedUnitHighlight).delete()
        session.query(CuratedUnit).delete()
        session.query(Highlight).delete()
        session.query(Book).delete()
        session.commit()
    with container.session() as session:
        _book_with_truncated_highlights(
            session, truncated_flags=[True, True, False], card_status="pending_review"
        )

    pending_body = client.get("/decks", headers={"X-API-Key": TEST_API_KEY}).json()

    assert pending_body["decks"][0]["total"] == 0, "no card is approved (hard rule 1)"
    assert pending_body["decks"][0]["truncated"] == 2, (
        "truncation is a property of the export, not of the approved population"
    )


def test_deck_truncated_count_is_zero_when_nothing_is_clipped(
    client: TestClient, container: Container
) -> None:
    """A clean book reports 0, not null — the badge hides on 0 rather than on absence."""
    with container.session() as session:
        _book_with_truncated_highlights(session, truncated_flags=[False, False])

    body = client.get("/decks", headers={"X-API-Key": TEST_API_KEY}).json()

    assert body["decks"][0]["truncated"] == 0
    assert body["decks"][0]["truncated"] is not None


def test_deck_truncated_count_does_not_double_count_a_shared_highlight(
    client: TestClient, container: Container
) -> None:
    """A highlight backing two cards counts once: the count is over highlights.

    The Book → Highlight → unit → Card join fans out, so counting rows would report
    a truncated highlight once per card it produced. `total` already guards this with
    DISTINCT over `Card.id`; this count needs DISTINCT over `Highlight.id`.
    """
    with container.session() as session:
        book = _book(title="Evals for AI Engineers", external_id="9781098188283")
        session.add(book)
        session.flush()
        run = IngestRun(filename="g6-oreilly-annotations.csv", user_id=1)
        highlight = Highlight(
            book_id=book.id,
            chapter="3. Error Analysis",
            raw_text="An LLM pipeline's behaviour only makes sense end-to-",
            dedupe_key="g6-shared-highlight",
            source="oreilly",
            highlighted_at=date(2026, 6, 19),
            export_position=0,
            truncated=True,
            user_id=1,
        )
        session.add_all([run, highlight])
        session.flush()
        unit = CuratedUnit(
            ingest_run_id=run.id, curated_text="…", decision="keep", tags=[], user_id=1
        )
        session.add(unit)
        session.flush()
        session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
        # Two cards off the one unit, both fed by the single truncated highlight.
        session.add_all([_card(unit.id, status="approved"), _card(unit.id, status="approved")])
        session.commit()

    body = client.get("/decks", headers={"X-API-Key": TEST_API_KEY}).json()

    assert body["decks"][0]["total"] == 2, "two cards, so the join really does fan out"
    assert body["decks"][0]["truncated"] == 1
