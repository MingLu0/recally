"""Step 3c gate: offline replay — duplicate no-ops and out-of-order recompute
(docs/api-spec.md, `POST /reviews/{card_id}/rate` duplicate/out-of-order paragraphs
and `POST /reviews/rate-batch`; ADR-005; hard rule 5).

Two behaviours under test, both against the live API with the container on an
in-memory SQLite database (same shape as test_reviews_api.py):

- a duplicate `(card_id, rated_at)` is a 200 no-op carrying `duplicate: true` and
  `lapsed: false`, detected before the UNIQUE constraint can raise;
- a rating whose `rated_at` predates the card's `last_review` rebuilds the card by
  replaying its full `review_logs` in `rated_at` order — the log rows themselves are
  training data and are never rewritten by a recompute.
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
    Highlight,
    IngestRun,
    ReviewLog,
)
from recally.models.base import utc_now
from recally.scheduling.fsrs import FsrsScheduler

TEST_API_KEY = "test-key-not-a-real-secret"
AUTH = {"X-API-Key": TEST_API_KEY}

# Same reference-instant rationale as test_reviews_api.py: every client timestamp is
# relative to one captured NOW so `due=NOW` seeds are due at test time.
NOW = utc_now()

_PROVENANCE_COUNTER = itertools.count(1)

# The three timestamps and ratings the permutation tests share. Spaced a day apart
# so the learning steps (1m/10m) are always long elapsed and the Again mid-sequence
# exercises a lapse during replay.
T1 = NOW - timedelta(days=2)
T2 = NOW - timedelta(days=1)
T3 = NOW
RATINGS = (3, 1, 2)  # Good, Again, Hard — a lapse in the middle of the sequence


def _iso(moment: datetime) -> str:
    """A client timestamp in the wire shape the app sends (`...Z`), microseconds kept."""
    return moment.isoformat() + "Z"


def _parse(wire: str) -> datetime:
    """A response datetime back to naive UTC, matching what the columns store."""
    return datetime.fromisoformat(wire.replace("Z", "+00:00")).replace(tzinfo=None)


@pytest.fixture
def make_container() -> Iterator[Callable[..., Container]]:
    """Container factory so a test can override settings."""
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


def _seed_card(session: Session, *, state: str = "learning", due: datetime = NOW) -> Card:
    """One approved card with the full provenance chain and its 3a `card_state` row."""
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
        status="approved",
        approved_at=NOW - timedelta(days=3),
        model="claude-sonnet-5",
        user_id=1,
    )
    session.add(card)
    session.flush()
    session.add(
        CardState(
            card_id=card.id,
            state=state,
            step=0 if state == "learning" else None,
            due=due,
            user_id=1,
        )
    )
    session.commit()
    return card


def _seed_review_card(session: Session) -> Card:
    """A graduated card mid-`review`, with plausible FSRS values."""
    card = _seed_card(session, state="review")
    state = session.get(CardState, card.id)
    assert state is not None
    state.stability = 12.5
    state.difficulty = 4.2
    state.last_review = NOW - timedelta(days=1)
    session.commit()
    return card


def _rate(
    client: TestClient,
    card_id: int,
    *,
    rating: int = 3,
    rated_at: datetime = NOW,
    response_ms: int = 5000,
) -> dict[str, object]:
    """POST one rating and assert 200. Tests for duplicates use `_rate_raw`."""
    body = _rate_raw(client, card_id, rating=rating, rated_at=rated_at, response_ms=response_ms)
    return body


def _rate_raw(
    client: TestClient,
    card_id: int,
    *,
    rating: int,
    rated_at: datetime,
    response_ms: int,
) -> dict[str, object]:
    response = client.post(
        f"/reviews/{card_id}/rate",
        headers=AUTH,
        json={"rating": rating, "response_ms": response_ms, "rated_at": _iso(rated_at)},
    )
    assert response.status_code == 200, response.text
    return response.json()


def _post_batch(client: TestClient, ratings: list[dict[str, object]]) -> list[dict[str, object]]:
    response = client.post("/reviews/rate-batch", headers=AUTH, json={"ratings": ratings})
    assert response.status_code == 200, response.text
    return response.json()["results"]


def _batch_item(
    card_id: int, rating: int, rated_at: datetime, response_ms: int
) -> dict[str, object]:
    return {
        "card_id": card_id,
        "rating": rating,
        "response_ms": response_ms,
        "rated_at": _iso(rated_at),
    }


def _card_state(container: Container, card_id: int) -> CardState:
    with container.session() as session:
        state = session.get(CardState, card_id)
        assert state is not None
        session.expunge(state)
        return state


def _review_logs(container: Container, card_id: int) -> list[ReviewLog]:
    with container.session() as session:
        rows = list(
            session.scalars(
                select(ReviewLog).where(ReviewLog.card_id == card_id).order_by(ReviewLog.id)
            ).all()
        )
        for row in rows:
            session.expunge(row)
        return rows


def _state_tuple(state: CardState) -> tuple[object, ...]:
    """Every scheduling column, for byte-identical comparisons between cards/runs."""
    return (
        state.state,
        state.step,
        state.stability,
        state.difficulty,
        state.due,
        state.last_review,
    )


def _log_tuple(row: ReviewLog) -> tuple[object, ...]:
    """The training-data columns a recompute must never rewrite."""
    return (row.id, row.rating, row.rated_at, row.response_ms, row.scheduled_days)


def _apply_sequence(client: TestClient, card_id: int, arrival_order: tuple[int, ...]) -> None:
    """Rate one card with RATINGS/T1..T3 applied in the given arrival permutation."""
    timestamps = (T1, T2, T3)
    for position in arrival_order:
        _rate(client, card_id, rating=RATINGS[position], rated_at=timestamps[position])


# --- Out-of-order recompute ----------------------------------------------------


def test_late_arriving_earlier_rating_recomputes_from_full_log(
    client: TestClient, container: Container
) -> None:
    """Apply T2, then T1 (T1 < T2): the card ends exactly as if T1 then T2 had
    arrived in order (docs/api-spec.md, POST rate; ADR-005)."""
    with container.session() as session:
        late_card = _seed_card(session)
        in_order_card = _seed_card(session)

    _rate(client, late_card.id, rating=3, rated_at=T2)
    _rate(client, late_card.id, rating=1, rated_at=T1)
    _rate(client, in_order_card.id, rating=1, rated_at=T1)
    _rate(client, in_order_card.id, rating=3, rated_at=T2)

    assert _state_tuple(_card_state(container, late_card.id)) == _state_tuple(
        _card_state(container, in_order_card.id)
    )


def test_recompute_matches_in_order_application(client: TestClient, container: Container) -> None:
    """The roadmap gate at three ratings: every arrival permutation of the same
    three timestamps ends in an identical `card_state`."""
    permutations = list(itertools.permutations(range(3)))
    with container.session() as session:
        cards = {order: _seed_card(session) for order in permutations}

    for order in permutations:
        _apply_sequence(client, cards[order].id, order)

    reference = _state_tuple(_card_state(container, cards[(0, 1, 2)].id))
    for order in permutations:
        assert _state_tuple(_card_state(container, cards[order].id)) == reference, order


def test_recompute_preserves_review_log_rows(client: TestClient, container: Container) -> None:
    """Negative: a recompute rewrites `card_state` only — the existing log rows keep
    their count, `rating`, `rated_at` and `response_ms`; the log is the Learner's
    training data, not scratch space (docs/data-model.md, `review_logs`)."""
    with container.session() as session:
        card = _seed_card(session)

    _rate(client, card.id, rating=3, rated_at=T2, response_ms=4000)
    _rate(client, card.id, rating=2, rated_at=T3, response_ms=6000)
    before = [_log_tuple(row) for row in _review_logs(container, card.id)]
    assert len(before) == 2

    _rate(client, card.id, rating=1, rated_at=T1, response_ms=9000)

    after = _review_logs(container, card.id)
    assert len(after) == 3, "the late rating is appended, never replacing rows"
    surviving = {_log_tuple(row) for row in after}
    for row_tuple in before:
        assert row_tuple in surviving, "an existing row was rewritten by the recompute"
    new_row = next(row for row in after if _log_tuple(row) not in before)
    assert new_row.rated_at == T1
    assert new_row.rating == 1
    assert new_row.response_ms == 9000


def test_in_order_rating_does_not_trigger_a_recompute(
    client: TestClient, container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Negative: the ordinary path is a single `review_card` call per rating. A
    recompute on every rating would be correct and needlessly expensive; the spy
    also proves it can see a recompute (the out-of-order call replays the log)."""
    calls: list[datetime] = []
    original = FsrsScheduler.review_card

    def spying_review_card(
        self: FsrsScheduler, state: CardState, rating: object, *, review_datetime: datetime
    ) -> object:
        calls.append(review_datetime)
        return original(self, state, rating, review_datetime=review_datetime)  # type: ignore[arg-type]

    monkeypatch.setattr(FsrsScheduler, "review_card", spying_review_card)

    with container.session() as session:
        in_order_card = _seed_card(session)
        out_of_order_card = _seed_card(session)

    _rate(client, in_order_card.id, rating=3, rated_at=T1)
    _rate(client, in_order_card.id, rating=1, rated_at=T2)
    _rate(client, in_order_card.id, rating=2, rated_at=T3)
    assert len(calls) == 3, "three in-order ratings, three scoring calls — no replay"

    calls.clear()
    _rate(client, out_of_order_card.id, rating=3, rated_at=T2)
    _rate(client, out_of_order_card.id, rating=1, rated_at=T1)
    assert len(calls) == 1 + 2, "the out-of-order arrival re-scores the full log"


# --- Duplicates -------------------------------------------------------------------


def test_duplicate_card_id_rated_at_is_a_no_op(client: TestClient, container: Container) -> None:
    """The existing log row is kept untouched and the current state comes back with
    200, so a retried flush is safe (docs/api-spec.md)."""
    with container.session() as session:
        card = _seed_card(session)

    first = _rate(client, card.id, rating=3, rated_at=T1, response_ms=4000)
    second = _rate(client, card.id, rating=4, rated_at=T1, response_ms=9999)

    logs = _review_logs(container, card.id)
    assert len(logs) == 1, "the retry must not write a second row"
    assert logs[0].rating == 3 and logs[0].response_ms == 4000, "the row is untouched"
    for field in ("next_due", "state", "step"):
        assert second[field] == first[field], field


def test_duplicate_returns_duplicate_true_and_lapsed_false(
    client: TestClient, container: Container
) -> None:
    """The replay flags: `duplicate: true` and — always on a no-op — `lapsed: false`,
    so the client dequeues and its lapse count is untouched (docs/api-spec.md)."""
    with container.session() as session:
        card = _seed_card(session)

    first = _rate(client, card.id, rating=3, rated_at=T1)
    assert first["duplicate"] is False

    second = _rate(client, card.id, rating=3, rated_at=T1)
    assert second["duplicate"] is True
    assert second["lapsed"] is False


def test_duplicate_does_not_raise_on_the_unique_constraint(
    client: TestClient, container: Container
) -> None:
    """Negative: `UNIQUE (card_id, rated_at)` exists to make the replay idempotent;
    the duplicate is detected and answered 200, never a 500 from an IntegrityError."""
    with container.session() as session:
        card = _seed_card(session)

    _rate(client, card.id, rating=3, rated_at=T1)
    response = client.post(
        f"/reviews/{card.id}/rate",
        headers=AUTH,
        json={"rating": 3, "response_ms": 5000, "rated_at": _iso(T1)},
    )

    assert response.status_code == 200, response.text
    assert response.json()["duplicate"] is True


def test_replay_never_double_counts_a_lapse(client: TestClient, container: Container) -> None:
    """Negative: re-sending a rating that caused a lapse returns `lapsed: false` the
    second time — the client counts lapses only from `duplicate: false` responses,
    so its session summary cannot double-count (docs/api-spec.md)."""
    with container.session() as session:
        card = _seed_review_card(session)

    first = _rate(client, card.id, rating=1, rated_at=T3)
    assert first["lapsed"] is True and first["duplicate"] is False

    second = _rate(client, card.id, rating=1, rated_at=T3)
    assert second["duplicate"] is True
    assert second["lapsed"] is False, "a no-op moved nothing, so it is never a lapse"

    counted_lapses = sum(1 for body in (first, second) if not body["duplicate"] and body["lapsed"])
    assert counted_lapses == 1


# --- Batch, end to end ------------------------------------------------------------


def test_resending_the_same_batch_changes_nothing(client: TestClient, container: Container) -> None:
    """The roadmap gate's resend clause: every item comes back `ok: true`,
    `duplicate: true`, `lapsed: false`, and `card_state` is byte-identical."""
    with container.session() as session:
        card = _seed_card(session)
    batch = [
        _batch_item(card.id, 3, T1, 4000),
        _batch_item(card.id, 1, T2, 6000),
    ]

    first = _post_batch(client, batch)
    assert all(item["ok"] and not item["duplicate"] for item in first)
    state_before = _state_tuple(_card_state(container, card.id))

    second = _post_batch(client, batch)

    assert len(second) == 2
    for item in second:
        assert item["ok"] is True
        assert item["duplicate"] is True
        assert item["lapsed"] is False
    assert _state_tuple(_card_state(container, card.id)) == state_before
    assert len(_review_logs(container, card.id)) == 2


def test_batch_mixing_new_and_duplicate_items_applies_only_the_new_ones(
    client: TestClient, container: Container
) -> None:
    """A partially flushed queue retried with a new rating appended: the duplicate
    is a no-op, the new one applies, and the log grows by exactly one row."""
    with container.session() as session:
        card = _seed_card(session)
    _rate(client, card.id, rating=3, rated_at=T1, response_ms=4000)

    results = _post_batch(
        client,
        [
            _batch_item(card.id, 3, T1, 4000),  # the duplicate
            _batch_item(card.id, 1, T2, 6000),  # the new rating
        ],
    )

    assert [item["ok"] for item in results] == [True, True]
    assert results[0]["duplicate"] is True and results[0]["lapsed"] is False
    assert results[1]["duplicate"] is False
    logs = _review_logs(container, card.id)
    assert len(logs) == 2, "only the new item wrote a row"
    assert {row.rated_at for row in logs} == {T1, T2}


def test_out_of_order_batch_matches_in_order_batch(
    client: TestClient, container: Container
) -> None:
    """The roadmap gate's reversed-batch clause, as a test: the same two ratings
    posted reversed on one card and in order on another end with identical
    `next_due` — and identical everything else."""
    with container.session() as session:
        reversed_card = _seed_card(session)
        in_order_card = _seed_card(session)

    reversed_results = _post_batch(
        client,
        [
            _batch_item(reversed_card.id, 3, T2, 4000),
            _batch_item(reversed_card.id, 1, T1, 4000),
        ],
    )
    in_order_results = _post_batch(
        client,
        [
            _batch_item(in_order_card.id, 1, T1, 4000),
            _batch_item(in_order_card.id, 3, T2, 4000),
        ],
    )

    assert all(item["ok"] for item in reversed_results + in_order_results)
    # Results align with request position, so the chronologically-last rating (T2)
    # is the reversed batch's first item and the in-order batch's last.
    reversed_next_due = _parse(str(reversed_results[0]["next_due"]))
    in_order_next_due = _parse(str(in_order_results[1]["next_due"]))
    assert reversed_next_due == in_order_next_due
    assert _state_tuple(_card_state(container, reversed_card.id)) == _state_tuple(
        _card_state(container, in_order_card.id)
    )
