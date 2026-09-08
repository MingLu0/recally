"""Step 3b gate: `GET /reviews/due`, `POST /reviews/{card_id}/rate` and
`POST /reviews/rate-batch` (docs/api-spec.md, "Reviews"; ADR-005, ADR-008).

The container is overridden onto an in-memory SQLite database (docs/backend.md,
"Testing shape per layer"). Every rating posts the client's `rated_at`; the server
is authoritative for FSRS state but never for the clock (hard rule 5).
"""

import itertools
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from datetime import datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
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
    Device,
    Highlight,
    IngestRun,
    ReviewLog,
)

TEST_API_KEY = "test-key-not-a-real-secret"
AUTH = {"X-API-Key": TEST_API_KEY}

# A fixed reference instant for client timestamps; real "now" only shows up in
# `received_at`, which is exactly the distinction test_rate_writes_review_log reads.
NOW = datetime(2026, 9, 8, 12, 0, 0)

_PROVENANCE_COUNTER = itertools.count(1)


def _iso(moment: datetime) -> str:
    """A client timestamp in the wire shape the app sends (`...Z`)."""
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def _parse(wire: str) -> datetime:
    """A response datetime back to naive UTC, matching what the columns store."""
    return datetime.fromisoformat(wire.replace("Z", "+00:00")).replace(tzinfo=None)


@pytest.fixture
def make_container() -> Iterator[Callable[..., Container]]:
    """Container factory so a test can override settings (NEW_CARDS_PER_DAY, ...)."""
    built: list[Container] = []

    def factory(**settings_overrides: object) -> Container:
        engine = create_engine(
            "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
        )
        Base.metadata.create_all(engine)
        settings = Settings(
            RECALLY_DATABASE_URL="sqlite://",
            RECALLY_API_KEY=TEST_API_KEY,
            **settings_overrides,  # type: ignore[arg-type]
        )
        container = Container(settings, engine=engine)
        built.append(container)
        return container

    try:
        yield factory
    finally:
        for container in built:
            container.engine.dispose()


@contextmanager
def _client_for(container: Container, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    app = create_app()
    app.dependency_overrides[container_dependency] = lambda: container
    monkeypatch.setenv("RECALLY_API_KEY", TEST_API_KEY)
    get_settings.cache_clear()
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        get_settings.cache_clear()


@pytest.fixture
def container(make_container: Callable[..., Container]) -> Container:
    return make_container()


@pytest.fixture
def client(container: Container, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    with _client_for(container, monkeypatch) as test_client:
        yield test_client


def _seed_card(
    session: Session,
    *,
    status: str = "approved",
    due: datetime = NOW,
    state: str = "learning",
    step: int | None = 0,
    stability: float | None = None,
    difficulty: float | None = None,
    last_review: datetime | None = None,
    suspended_until: datetime | None = None,
    with_state: bool | None = None,
) -> Card:
    """One approved (or queued) card with the full provenance chain.

    `with_state=None` creates the `card_state` row exactly when the card is
    `approved`, mirroring the 3a approval gate.
    """
    suffix = next(_PROVENANCE_COUNTER)
    book = Book(
        title="Evals for AI Engineers",
        source="oreilly",
        external_id=f"9781098188283-{suffix}",
        user_id=1,
    )
    run = IngestRun(filename=f"seed-{suffix}-oreilly-annotations.csv", user_id=1)
    session.add_all([book, run])
    session.flush()
    highlight = Highlight(
        book_id=book.id,
        chapter="3. Error Analysis",
        raw_text="An LLM pipeline's behaviour only makes sense end-to-end.",
        dedupe_key=f"00000000-0000-0000-0000-{suffix:012d}",
        source="oreilly",
        highlighted_at=NOW.date(),
        export_position=suffix,
        user_id=1,
    )
    session.add(highlight)
    session.flush()
    unit = CuratedUnit(
        ingest_run_id=run.id, curated_text="…", tags=["evals"], decision="keep", user_id=1
    )
    session.add(unit)
    session.flush()
    session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
    card = Card(
        unit_id=unit.id,
        type="qa",
        front="Why evaluate traces rather than individual steps?",
        back="An LLM pipeline's behaviour only makes sense end-to-end.",
        original_front="Why evaluate traces rather than individual steps?",
        original_back="An LLM pipeline's behaviour only makes sense end-to-end.",
        tags=["evals"],
        status=status,
        suspended_until=suspended_until,
        approved_at=due if status == "approved" else None,
        model="claude-sonnet-5",
        user_id=1,
    )
    session.add(card)
    session.flush()
    if with_state is None:
        with_state = status == "approved"
    if with_state:
        session.add(
            CardState(
                card_id=card.id,
                state=state,
                step=step,
                stability=stability,
                difficulty=difficulty,
                due=due,
                last_review=last_review,
                user_id=1,
            )
        )
    session.commit()
    return card


def _seed_review_card(session: Session, *, due: datetime = NOW) -> Card:
    """A graduated card mid-`review`, with plausible FSRS values."""
    return _seed_card(
        session,
        due=due,
        state="review",
        step=None,
        stability=12.5,
        difficulty=4.2,
        last_review=NOW - timedelta(days=1),
    )


def _rate(
    client: TestClient, card_id: int, *, rating: int = 3, rated_at: datetime = NOW, **extra: object
) -> dict[str, object]:
    payload: dict[str, object] = {
        "rating": rating,
        "response_ms": 5000,
        "rated_at": _iso(rated_at),
        **extra,
    }
    response = client.post(f"/reviews/{card_id}/rate", headers=AUTH, json=payload)
    assert response.status_code == 200, response.text
    return response.json()


def _card_state(container: Container, card_id: int) -> CardState:
    with container.session() as session:
        state = session.get(CardState, card_id)
        assert state is not None
        session.expunge(state)
        return state


def _review_logs(container: Container, card_id: int) -> list[ReviewLog]:
    with container.session() as session:
        return list(
            session.scalars(
                select(ReviewLog).where(ReviewLog.card_id == card_id).order_by(ReviewLog.id)
            ).all()
        )


# --- The roadmap gate ---------------------------------------------------------


def test_again_puts_card_back_due_within_first_learning_step(
    client: TestClient, container: Container
) -> None:
    """An Again on a due card re-queues it in-session (ADR-005): still `learning`,
    due no later than the first learning step (default 1 minute) after `rated_at`."""
    with container.session() as session:
        card = _seed_card(session)

    body = _rate(client, card.id, rating=1, rated_at=NOW)

    assert body["state"] == "learning"
    next_due = _parse(str(body["next_due"]))
    assert NOW < next_due <= NOW + timedelta(minutes=1)


def test_never_reviewed_card_appears_as_learning_step_zero_with_due(
    client: TestClient, container: Container
) -> None:
    """A card approved but never rated has the 3a `card_state` row: it is in the due
    list as `learning` at step 0 with a non-null `due`, no special case."""
    with container.session() as session:
        card = _seed_card(session, due=NOW - timedelta(hours=1))

    body = client.get("/reviews/due", headers=AUTH).json()

    entry = next(item for item in body["cards"] if item["id"] == card.id)
    assert entry["state"] == "learning"
    assert entry["step"] == 0
    assert entry["due"] is not None


def test_rate_batch_out_of_order_matches_in_order(client: TestClient, container: Container) -> None:
    """Batch items apply in `rated_at` order per card: the same two ratings submitted
    reversed leave the card in exactly the state the in-order submission produces."""
    earlier, later = NOW - timedelta(hours=2), NOW - timedelta(hours=1)
    with container.session() as session:
        reversed_card = _seed_card(session)
        in_order_card = _seed_card(session)

    response = client.post(
        "/reviews/rate-batch",
        headers=AUTH,
        json={
            "ratings": [
                {
                    "card_id": reversed_card.id,
                    "rating": 1,
                    "response_ms": 4000,
                    "rated_at": _iso(later),
                },
                {
                    "card_id": reversed_card.id,
                    "rating": 3,
                    "response_ms": 4000,
                    "rated_at": _iso(earlier),
                },
                {
                    "card_id": in_order_card.id,
                    "rating": 3,
                    "response_ms": 4000,
                    "rated_at": _iso(earlier),
                },
                {
                    "card_id": in_order_card.id,
                    "rating": 1,
                    "response_ms": 4000,
                    "rated_at": _iso(later),
                },
            ]
        },
    )

    assert response.status_code == 200, response.text
    assert all(item["ok"] for item in response.json()["results"])
    reversed_state = _card_state(container, reversed_card.id)
    in_order_state = _card_state(container, in_order_card.id)
    for field in ("state", "step", "due", "stability", "difficulty", "last_review"):
        assert getattr(reversed_state, field) == getattr(in_order_state, field), field


def test_rate_batch_unknown_card_marks_only_that_item_not_ok(
    client: TestClient, container: Container
) -> None:
    """Negative: one unknown card fails only its own item — the valid items still
    apply, results keep request order, and the failure carries the top-level 404."""
    with container.session() as session:
        card = _seed_card(session)

    response = client.post(
        "/reviews/rate-batch",
        headers=AUTH,
        json={
            "ratings": [
                {"card_id": 999, "rating": 3, "response_ms": 4000, "rated_at": _iso(NOW)},
                {"card_id": card.id, "rating": 3, "response_ms": 4000, "rated_at": _iso(NOW)},
            ]
        },
    )

    assert response.status_code == 200, response.text
    results = response.json()["results"]
    assert len(results) == 2, "exactly one result per request item"
    assert results[0]["ok"] is False
    assert results[0]["status"] == 404
    assert results[0]["card_id"] == 999
    assert results[1]["ok"] is True
    assert results[1]["card_id"] == card.id
    assert len(_review_logs(container, card.id)) == 1, "the valid item still applied"


def test_review_to_relearning_returns_lapsed_true(client: TestClient, container: Container) -> None:
    """An Again that moves a `review` card into `relearning` is a lapse — the client
    cannot derive this, so the response carries it (docs/api-spec.md)."""
    with container.session() as session:
        card = _seed_review_card(session)

    body = _rate(client, card.id, rating=1, rated_at=NOW)

    assert body["state"] == "relearning"
    assert body["lapsed"] is True


def test_again_in_learning_returns_lapsed_false(client: TestClient, container: Container) -> None:
    """Negative: an Again on a card already in `learning` moved nothing out of
    `review`, so it is not a lapse."""
    with container.session() as session:
        card = _seed_card(session)

    body = _rate(client, card.id, rating=1, rated_at=NOW)

    assert body["state"] == "learning"
    assert body["lapsed"] is False


# --- GET /reviews/due ----------------------------------------------------------


def test_due_excludes_suspended_and_buried(client: TestClient, container: Container) -> None:
    """Negative (ADR-008): a card whose `suspended_until` is in the future — buried
    or suspended — is absent from the due list."""
    with container.session() as session:
        buried = _seed_card(session, suspended_until=NOW + timedelta(hours=6))
        suspended = _seed_card(session, suspended_until=NOW + timedelta(days=3650))
        in_rotation = _seed_card(session)

    body = client.get("/reviews/due", headers=AUTH).json()

    ids = [item["id"] for item in body["cards"]]
    assert buried.id not in ids
    assert suspended.id not in ids
    assert in_rotation.id in ids


def test_due_includes_a_card_whose_suspension_has_passed(
    client: TestClient, container: Container
) -> None:
    """The other side of the ADR-008 predicate: `suspended_until` at or before now
    is back in rotation."""
    with container.session() as session:
        card = _seed_card(session, suspended_until=NOW - timedelta(minutes=1))

    body = client.get("/reviews/due", headers=AUTH).json()

    assert card.id in [item["id"] for item in body["cards"]]


def test_due_returns_configured_learning_steps_minutes(
    make_container: Callable[..., Container], monkeypatch: pytest.MonkeyPatch
) -> None:
    """ADR-005: the client re-queues in-session from the steps the wrapper has in
    effect, so the response reports the configured values, not a hard-coded list."""
    container = make_container(FSRS_LEARNING_STEPS_MINUTES="2,20")
    with _client_for(container, monkeypatch) as test_client:
        body = test_client.get("/reviews/due", headers=AUTH).json()

    assert body["learning_steps_minutes"] == [2, 20]


def test_due_step_is_null_in_review_state(client: TestClient, container: Container) -> None:
    """`step` only exists while learning/relearning; a graduated card reports null
    (docs/api-spec.md)."""
    with container.session() as session:
        card = _seed_review_card(session, due=NOW - timedelta(days=1))

    body = client.get("/reviews/due", headers=AUTH).json()

    entry = next(item for item in body["cards"] if item["id"] == card.id)
    assert entry["state"] == "review"
    assert entry["step"] is None


def test_new_cards_capped_by_new_cards_per_day(
    make_container: Callable[..., Container], monkeypatch: pytest.MonkeyPatch
) -> None:
    """`NEW_CARDS_PER_DAY` caps the never-reviewed allotment in the response."""
    container = make_container(NEW_CARDS_PER_DAY=2)
    with _client_for(container, monkeypatch) as test_client:
        with container.session() as session:
            for _ in range(5):
                _seed_card(session)

        body = test_client.get("/reviews/due", headers=AUTH).json()

    assert body["new_count"] == 2
    assert len(body["cards"]) == 2


def test_due_excludes_unapproved_cards(client: TestClient, container: Container) -> None:
    """Negative (hard rule 1): nothing `pending_review` or `needs_human` is ever
    scheduled, so neither can appear in the due list."""
    with container.session() as session:
        pending = _seed_card(session, status="pending_review")
        needs_human = _seed_card(session, status="needs_human")
        approved = _seed_card(session)

    body = client.get("/reviews/due", headers=AUTH).json()

    ids = [item["id"] for item in body["cards"]]
    assert pending.id not in ids
    assert needs_human.id not in ids
    assert approved.id in ids


# --- POST /reviews/{card_id}/rate ----------------------------------------------


def test_rate_writes_review_log_with_client_rated_at(
    client: TestClient, container: Container
) -> None:
    """`rated_at` is the client timestamp and `received_at` the server's; they are
    different clocks and both land on the `review_logs` row (docs/data-model.md)."""
    client_rated_at = NOW - timedelta(days=2)
    with container.session() as session:
        card = _seed_card(session)

    _rate(client, card.id, rated_at=client_rated_at)

    logs = _review_logs(container, card.id)
    assert len(logs) == 1
    assert logs[0].rated_at == client_rated_at
    assert logs[0].received_at != logs[0].rated_at
    assert logs[0].received_at > client_rated_at


def test_rate_persists_response_ms_and_device_id(client: TestClient, container: Container) -> None:
    """Flip-to-rate duration and the originating device land on the log row."""
    with container.session() as session:
        card = _seed_card(session)
        device = Device(fcm_token="fcm-token-1", platform="android", user_id=1)
        session.add(device)
        session.commit()
        device_id = device.id

    _rate(client, card.id, response_ms=8200, device_id=device_id)

    logs = _review_logs(container, card.id)
    assert len(logs) == 1
    assert logs[0].response_ms == 8200
    assert logs[0].device_id == device_id


def test_rate_uses_rated_at_as_review_datetime(client: TestClient, container: Container) -> None:
    """Hard rule 5: the server never substitutes its own clock. The same rating an
    hour earlier produces a `next_due` an hour earlier."""
    with container.session() as session:
        rated_now = _seed_card(session)
        rated_earlier = _seed_card(session)

    now_body = _rate(client, rated_now.id, rated_at=NOW)
    earlier_body = _rate(client, rated_earlier.id, rated_at=NOW - timedelta(hours=1))

    assert _parse(str(now_body["next_due"])) - _parse(str(earlier_body["next_due"])) == timedelta(
        hours=1
    )


def test_rate_on_unapproved_card_is_rejected(client: TestClient, container: Container) -> None:
    """Negative (hard rule 1): a card still in the approval queue has no FSRS state
    and cannot be rated."""
    with container.session() as session:
        card = _seed_card(session, status="pending_review")

    response = client.post(
        f"/reviews/{card.id}/rate",
        headers=AUTH,
        json={"rating": 3, "response_ms": 5000, "rated_at": _iso(NOW)},
    )

    assert response.status_code == 409
    assert response.json()["status"] == 409
    assert _review_logs(container, card.id) == []


def test_rate_rejects_rating_outside_one_to_four(client: TestClient, container: Container) -> None:
    """Negative: only 1=Again … 4=Easy exist (docs/api-spec.md)."""
    with container.session() as session:
        card = _seed_card(session)

    for rating in (0, 5):
        response = client.post(
            f"/reviews/{card.id}/rate",
            headers=AUTH,
            json={"rating": rating, "response_ms": 5000, "rated_at": _iso(NOW)},
        )
        assert response.status_code == 422, rating
        assert response.json()["status"] == 422

    assert _review_logs(container, card.id) == []


# --- POST /reviews/rate-batch ----------------------------------------------------


def test_batch_returns_one_result_per_item_in_request_order(
    client: TestClient, container: Container
) -> None:
    """The client matches results to its queue by position (docs/api-spec.md)."""
    with container.session() as session:
        first = _seed_card(session)
        second = _seed_card(session)
        third = _seed_card(session)

    response = client.post(
        "/reviews/rate-batch",
        headers=AUTH,
        json={
            "ratings": [
                {"card_id": third.id, "rating": 3, "response_ms": 1000, "rated_at": _iso(NOW)},
                {"card_id": first.id, "rating": 4, "response_ms": 2000, "rated_at": _iso(NOW)},
                {"card_id": second.id, "rating": 2, "response_ms": 3000, "rated_at": _iso(NOW)},
            ]
        },
    )

    assert response.status_code == 200, response.text
    results = response.json()["results"]
    assert [item["card_id"] for item in results] == [third.id, first.id, second.id]
    assert all(item["ok"] for item in results)


def test_malformed_item_does_not_fail_the_batch(client: TestClient, container: Container) -> None:
    """Negative: items are validated individually, so a malformed item is an `ok:
    false` entry — never a failed call, and never a rolled-back neighbour."""
    with container.session() as session:
        card = _seed_card(session)

    response = client.post(
        "/reviews/rate-batch",
        headers=AUTH,
        json={
            "ratings": [
                {"card_id": card.id, "rating": 9, "response_ms": 4000, "rated_at": _iso(NOW)},
                {"card_id": card.id, "rating": 3, "response_ms": 4000},  # no rated_at
                {"card_id": card.id, "rating": 3, "response_ms": 4000, "rated_at": _iso(NOW)},
            ]
        },
    )

    assert response.status_code == 200, response.text
    results = response.json()["results"]
    assert len(results) == 3
    assert results[0]["ok"] is False and results[0]["status"] == 422
    assert results[1]["ok"] is False and results[1]["status"] == 422
    assert results[2]["ok"] is True
    assert len(_review_logs(container, card.id)) == 1, "only the valid item applied"


def test_unparseable_body_is_top_level_422(client: TestClient) -> None:
    """A body that is not `{"ratings": [...]}` at all fails the whole call; the
    client discards such a batch rather than re-flushing it (docs/api-spec.md)."""
    for body in ({}, {"ratings": "not-a-list"}, {"ratings": None}):
        response = client.post("/reviews/rate-batch", headers=AUTH, json=body)
        assert response.status_code == 422, body
        assert response.json()["status"] == 422


def test_batch_applies_per_card_in_rated_at_order(client: TestClient, container: Container) -> None:
    """Two cards interleaved in one batch each get their own `rated_at` ordering:
    card A is Good-then-Again (ends back in `learning` at step 0) while card B is
    Good-then-Good (graduates to `review`)."""
    earlier, later = NOW - timedelta(hours=2), NOW - timedelta(hours=1)
    with container.session() as session:
        card_a = _seed_card(session)
        card_b = _seed_card(session)

    response = client.post(
        "/reviews/rate-batch",
        headers=AUTH,
        json={
            "ratings": [
                {"card_id": card_a.id, "rating": 1, "response_ms": 1000, "rated_at": _iso(later)},
                {"card_id": card_b.id, "rating": 3, "response_ms": 1000, "rated_at": _iso(later)},
                {"card_id": card_a.id, "rating": 3, "response_ms": 1000, "rated_at": _iso(earlier)},
                {"card_id": card_b.id, "rating": 3, "response_ms": 1000, "rated_at": _iso(earlier)},
            ]
        },
    )

    assert response.status_code == 200, response.text
    assert all(item["ok"] for item in response.json()["results"])
    state_a = _card_state(container, card_a.id)
    state_b = _card_state(container, card_b.id)
    assert (state_a.state, state_a.step) == ("learning", 0)
    assert (state_b.state, state_b.step) == ("review", None)
    assert state_a.due == later + timedelta(minutes=1)


# --- Auth -------------------------------------------------------------------------


def test_reviews_endpoints_require_the_api_key(client: TestClient, container: Container) -> None:
    """Every route carries the guard: 401 without `X-API-Key` on all three."""
    with container.session() as session:
        card = _seed_card(session)

    rating = {"rating": 3, "response_ms": 5000, "rated_at": _iso(NOW)}
    assert client.get("/reviews/due").status_code == 401
    assert client.post(f"/reviews/{card.id}/rate", json=rating).status_code == 401
    assert client.post("/reviews/rate-batch", json={"ratings": []}).status_code == 401
