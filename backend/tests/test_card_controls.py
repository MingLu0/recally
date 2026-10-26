"""The step 3d gate: ADR-008 approved-card controls.

`PATCH /cards/{id}` edits a card without touching its FSRS state (an edit
is a correction to the same retrieval task, not a new card); bury/suspend/
unsuspend move `cards.suspended_until` and nothing else. None of the four
endpoints changes `cards.status` or makes an LLM call.

Since issue #287 (ADR-018) `PATCH` also saves the wording of a queued card
(`pending_review`/`needs_human`) without approving it: status, `approved_at`,
`status_reason`, the Writer's originals and `edited_at` all survive, and no
`card_state` row is created. The pre-approval tests below assert absence
from `/reviews/due` directly; the older ADR-008 tests keep asserting through
`GET /decks`' due count (the same predicate) and through the service's `now`
parameter for the post-boundary cases.
"""

from collections.abc import Iterator
from datetime import datetime, timedelta
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient
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
from recally.services.decks import list_decks

TEST_API_KEY = "test-key-not-a-real-secret"
AUTH = {"X-API-Key": TEST_API_KEY}

# A fixed "now" for seed data; the due dates are in the past relative to it so the
# card counts as due no matter when the suite runs.
NOW = datetime(2026, 9, 8, 10, 0, 0)

FRONT = "Why evaluate traces rather than individual steps?"
BACK = "An LLM pipeline's behaviour only makes sense end-to-end."

CARD_STATE_COLUMNS = ("state", "step", "stability", "difficulty", "due", "last_review")


def _build_container(**env: str) -> Container:
    """A container on a fresh in-memory database (see test_api.py for StaticPool)."""
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    settings = Settings(RECALLY_DATABASE_URL="sqlite://", RECALLY_API_KEY=TEST_API_KEY, **env)
    return Container(settings, engine=engine)


@pytest.fixture
def container() -> Iterator[Container]:
    container = _build_container()
    try:
        yield container
    finally:
        container.engine.dispose()


def _build_client(container: Container, monkeypatch: pytest.MonkeyPatch) -> TestClient:
    app = create_app()
    app.dependency_overrides[container_dependency] = lambda: container
    monkeypatch.setenv("RECALLY_API_KEY", TEST_API_KEY)
    get_settings.cache_clear()
    return TestClient(app)


@pytest.fixture
def client(container: Container, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    with _build_client(container, monkeypatch) as test_client:
        yield test_client
    get_settings.cache_clear()


def _add_book(session: Session, *, title: str = "Evals for AI Engineers") -> Book:
    book = Book(title=title, source="oreilly", external_id="9781098188283", user_id=1)
    session.add(book)
    session.flush()
    return book


def _add_card(
    session: Session,
    book_id: int,
    *,
    status: str = "approved",
    front: str = FRONT,
    back: str = BACK,
    tags: list[str] | None = None,
    chapter: str | None = "1. Introduction",
) -> Card:
    """A card plus its whole provenance chain, so the deck queries can reach it."""
    highlight = Highlight(
        book_id=book_id,
        raw_text="An LLM pipeline's behaviour only makes sense end-to-end.",
        dedupe_key=f"uuid-{book_id}-{front[:8]}-{status}",
        source="oreilly",
        chapter=chapter,
        highlighted_at=NOW.date(),
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
    card = Card(
        unit_id=unit.id,
        type="qa",
        front=front,
        back=back,
        original_front=front,
        original_back=back,
        tags=tags if tags is not None else [],
        status=status,
        approved_at=NOW if status == "approved" else None,
        model="claude-sonnet-5",
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def _add_review_state(session: Session, card_id: int, *, due: datetime) -> CardState:
    """A `card_state` row mid-`review`, with real stability/difficulty values, so the
    column-by-column invariants assert actual data rather than a fresh NULL row."""
    state = CardState(
        card_id=card_id,
        state="review",
        step=None,
        stability=12.3,
        difficulty=4.5,
        due=due,
        last_review=NOW - timedelta(days=4),
        user_id=1,
    )
    session.add(state)
    session.flush()
    return state


def _state_snapshot(session: Session, card_id: int) -> dict[str, object]:
    state = session.get(CardState, card_id)
    assert state is not None
    return {column: getattr(state, column) for column in CARD_STATE_COLUMNS}


def _due_count(container: Container, book_title: str = "Evals for AI Engineers") -> int:
    with container.session() as session:
        decks = list_decks(session)
    return next(deck.due for deck in decks if deck.title == book_title)


# --- PATCH /cards/{id} ---------------------------------------------------------


def test_patch_changes_front_and_sets_edited_at(client: TestClient, container: Container) -> None:
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    response = client.patch(
        f"/cards/{card_id}", json={"front": "Why evaluate traces end-to-end?"}, headers=AUTH
    )

    assert response.status_code == 200
    body = response.json()
    assert body["front"] == "Why evaluate traces end-to-end?"
    assert body["edited_at"] is not None
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.front == "Why evaluate traces end-to-end?"
        assert persisted.edited_at is not None


def test_patch_leaves_every_card_state_column_identical(
    client: TestClient, container: Container
) -> None:
    """The ADR-008 invariant, column by column: an edit is a correction to the same
    retrieval task, so stability, difficulty, due and step all survive."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id
        before = _state_snapshot(session, card_id)

    response = client.patch(
        f"/cards/{card_id}",
        json={"front": "Edited front", "back": "Edited back", "tags": ["evals"]},
        headers=AUTH,
    )
    assert response.status_code == 200

    with container.session() as session:
        after = _state_snapshot(session, card_id)
    for column in CARD_STATE_COLUMNS:
        assert before[column] == after[column], f"card_state.{column} changed across an edit"


def test_patch_saves_pending_review_edits_without_approving(
    client: TestClient, container: Container
) -> None:
    """Save ≠ approve (issue #287, ADR-018): the queued card's wording changes,
    its status does not. Replaces `test_patch_on_pending_review_card_returns_409`,
    whose 409 assertion this ticket deliberately reverses."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="pending_review")
        session.commit()
        card_id = card.id

    response = client.patch(
        f"/cards/{card_id}", json={"front": "Edited front", "back": "Edited back"}, headers=AUTH
    )

    assert response.status_code == 200
    body = response.json()
    assert body["front"] == "Edited front"
    assert body["back"] == "Edited back"
    assert body["status"] == "pending_review"
    assert body["approved_at"] is None
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.status == "pending_review"
        assert persisted.approved_at is None


def test_patch_saves_needs_human_edits_without_approving(
    client: TestClient, container: Container
) -> None:
    """The same contract for `needs_human`, including its `status_reason`
    (the Critic critique stays for the next review). Replaces
    `test_patch_on_needs_human_card_returns_409`."""
    critique = "Critic: potentially ambiguous term; Writer 3 rounds unresolved."
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="needs_human")
        card.status_reason = critique
        session.commit()
        card_id = card.id

    response = client.patch(f"/cards/{card_id}", json={"front": "Edited front"}, headers=AUTH)

    assert response.status_code == 200
    body = response.json()
    assert body["front"] == "Edited front"
    assert body["status"] == "needs_human"
    assert body["status_reason"] == critique
    assert body["approved_at"] is None
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.status == "needs_human"
        assert persisted.status_reason == critique
        assert persisted.approved_at is None


def test_preapproval_patch_never_creates_card_state(
    client: TestClient, container: Container
) -> None:
    """Negative: hard rule 1 is untouched — Save alone never enters FSRS, so
    no `card_state` row exists and the card is absent from `/reviews/due`."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="pending_review")
        session.commit()
        card_id = card.id

    response = client.patch(f"/cards/{card_id}", json={"front": "Edited front"}, headers=AUTH)

    assert response.status_code == 200
    with container.session() as session:
        assert session.get(CardState, card_id) is None, "Save alone must not create FSRS state"
    due = client.get("/reviews/due", headers=AUTH).json()
    assert card_id not in [due_card["id"] for due_card in due["cards"]]
    assert due["due_count"] == 0


def test_preapproval_patch_preserves_original_text_and_leaves_edited_at_null(
    client: TestClient, container: Container
) -> None:
    """The Writer's text survives for the Learner, and `edited_at` stays the
    distinct post-approval edit signal — a pre-approval save must not set it."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="pending_review")
        session.commit()
        card_id = card.id

    response = client.patch(
        f"/cards/{card_id}", json={"front": "Edited front", "back": "Edited back"}, headers=AUTH
    )

    assert response.status_code == 200
    body = response.json()
    assert body["original_front"] == FRONT
    assert body["original_back"] == BACK
    assert body["edited_at"] is None
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.original_front == FRONT
        assert persisted.original_back == BACK
        assert persisted.edited_at is None


def test_saved_edits_appear_in_pending_queue(client: TestClient, container: Container) -> None:
    """A pre-approval save is visible on the next queue fetch, and the
    collection-wide counts are unchanged — Save is not a decision."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="pending_review")
        session.commit()
        card_id = card.id

    before = client.get("/cards/pending", headers=AUTH).json()
    response = client.patch(f"/cards/{card_id}", json={"front": "Edited front"}, headers=AUTH)
    assert response.status_code == 200

    after = client.get("/cards/pending", headers=AUTH).json()
    saved = next(card for card in after["cards"] if card["id"] == card_id)
    assert saved["front"] == "Edited front"
    assert after["counts"] == before["counts"]


def test_approve_after_saved_edits_uses_saved_text_and_enters_fsrs(
    client: TestClient, container: Container
) -> None:
    """The saved text is what approval enters into FSRS: a later bodyless
    Approve keeps it, stamps `approved_at` and creates exactly one
    learning/step-0 `card_state` row."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="pending_review")
        session.commit()
        card_id = card.id

    saved = client.patch(
        f"/cards/{card_id}", json={"front": "Edited front", "back": "Edited back"}, headers=AUTH
    )
    assert saved.status_code == 200

    approved = client.post(f"/cards/{card_id}/approve", headers=AUTH)

    assert approved.status_code == 200
    assert approved.json()["front"] == "Edited front"
    assert approved.json()["approved_at"] is not None
    with container.session() as session:
        state = session.get(CardState, card_id)
        assert state is not None
        assert state.state == "learning"
        assert state.step == 0


def test_patch_does_not_touch_original_front_back(client: TestClient, container: Container) -> None:
    """The Writer's text survives for the Learner (docs/agents.md, quality signals)."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    response = client.patch(
        f"/cards/{card_id}", json={"front": "Edited front", "back": "Edited back"}, headers=AUTH
    )

    assert response.status_code == 200
    body = response.json()
    assert body["original_front"] == FRONT
    assert body["original_back"] == BACK
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.original_front == FRONT
        assert persisted.original_back == BACK


def test_patch_omitted_fields_are_left_alone(client: TestClient, container: Container) -> None:
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, tags=["evals", "traces"])
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    response = client.patch(f"/cards/{card_id}", json={"front": "Edited front"}, headers=AUTH)

    assert response.status_code == 200
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.front == "Edited front"
        assert persisted.back == BACK, "an omitted back must not be blanked"
        assert persisted.tags == ["evals", "traces"], "omitted tags must not be blanked"


def test_patch_never_changes_card_status(client: TestClient, container: Container) -> None:
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    response = client.patch(f"/cards/{card_id}", json={"front": "Edited"}, headers=AUTH)

    assert response.status_code == 200
    assert response.json()["status"] == "approved"
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.status == "approved"


# --- bury / suspend / unsuspend -------------------------------------------------


def test_buried_card_is_absent_from_due_and_present_in_deck_cards(
    client: TestClient, container: Container
) -> None:
    """The roadmap gate, both halves. Due-absence is asserted through `GET /decks`'
    due count, which applies the same ADR-008 predicate `/reviews/due` will share."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id, book_id = card.id, book.id

    assert _due_count(container) == 1

    response = client.post(f"/cards/{card_id}/bury", headers=AUTH)
    assert response.status_code == 200

    assert _due_count(container) == 0, "a buried card is excluded from the due set"

    deck_cards = client.get(f"/decks/{book_id}/cards", headers=AUTH).json()["cards"]
    assert [deck_card["id"] for deck_card in deck_cards] == [card_id]
    assert deck_cards[0]["suspended_until"] is not None


def test_bury_sets_next_day_boundary_in_configured_timezone(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """With a non-UTC `RECALLY_TIMEZONE` the boundary is that zone's next midnight."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    # 10:00 UTC is 22:00 in Pacific/Auckland (UTC+12), so Auckland's next midnight
    # (12:00 UTC) is not UTC's next midnight (2026-09-09T00:00Z).
    monkeypatch.setattr("recally.api.routers.cards.utc_now", lambda: NOW)
    with _build_client(container, monkeypatch) as frozen_client:
        response = frozen_client.post(f"/cards/{card_id}/bury", headers=AUTH)
    get_settings.cache_clear()

    assert response.status_code == 200
    # Same instant as 2026-09-09T00:00:00+12:00, rendered as UTC: every
    # timestamp in docs/api-spec.md is Z-suffixed (schemas/types.py).
    assert response.json() == {"suspended_until": "2026-09-08T12:00:00Z"}
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.suspended_until == datetime(2026, 9, 8, 12, 0, 0)
        assert persisted.suspended_until != datetime(2026, 9, 9, 0, 0, 0), (
            "the boundary is the configured zone's midnight, not UTC's"
        )


def test_buried_card_returns_after_the_day_boundary(
    client: TestClient, container: Container
) -> None:
    """No action taken: once `now` passes the boundary the card is due again."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    response = client.post(f"/cards/{card_id}/bury", headers=AUTH)
    assert response.status_code == 200

    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None and persisted.suspended_until is not None
        boundary = persisted.suspended_until
        decks_before = list_decks(session, now=boundary - timedelta(seconds=1))
        decks_after = list_decks(session, now=boundary + timedelta(seconds=1))

    assert decks_before[0].due == 0
    assert decks_after[0].due == 1, "the bury clears itself at the day boundary"


def test_suspend_sets_far_future_sentinel_and_card_stays_out(
    client: TestClient, container: Container
) -> None:
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    response = client.post(f"/cards/{card_id}/suspend", headers=AUTH)

    assert response.status_code == 200
    assert response.json() == {"suspended_until": "9999-12-31T00:00:00Z"}
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.suspended_until == datetime(9999, 12, 31, 0, 0, 0)
        decks = list_decks(session, now=datetime(3000, 1, 1))
    assert decks[0].due == 0, "only unsuspend brings a suspended card back"


def test_unsuspend_restores_the_same_due(client: TestClient, container: Container) -> None:
    """The roadmap gate: the `due` the card already held, not a recomputed one."""
    due_before = NOW - timedelta(days=1)
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=due_before)
        session.commit()
        card_id = card.id

    assert client.post(f"/cards/{card_id}/suspend", headers=AUTH).status_code == 200
    response = client.post(f"/cards/{card_id}/unsuspend", headers=AUTH)

    assert response.status_code == 200
    assert response.json() == {"suspended_until": None}
    with container.session() as session:
        state = session.get(CardState, card_id)
        assert state is not None
        assert state.due == due_before, "unsuspend must not recompute the due date"
    assert _due_count(container) == 1


def test_unsuspend_clears_a_bury_too(client: TestClient, container: Container) -> None:
    """One column backs both controls; unsuspend clears either value."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    assert client.post(f"/cards/{card_id}/bury", headers=AUTH).status_code == 200
    response = client.post(f"/cards/{card_id}/unsuspend", headers=AUTH)

    assert response.status_code == 200
    assert response.json() == {"suspended_until": None}
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.suspended_until is None
    assert _due_count(container) == 1


def test_unsuspend_recomputes_nothing(client: TestClient, container: Container) -> None:
    """Every `card_state` column is identical across suspend → unsuspend."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id
        before = _state_snapshot(session, card_id)

    assert client.post(f"/cards/{card_id}/suspend", headers=AUTH).status_code == 200
    assert client.post(f"/cards/{card_id}/unsuspend", headers=AUTH).status_code == 200

    with container.session() as session:
        after = _state_snapshot(session, card_id)
    for column in CARD_STATE_COLUMNS:
        assert before[column] == after[column], (
            f"card_state.{column} changed across suspend → unsuspend"
        )


def test_controls_never_change_card_status(client: TestClient, container: Container) -> None:
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    for endpoint in ("bury", "suspend", "unsuspend"):
        assert client.post(f"/cards/{card_id}/{endpoint}", headers=AUTH).status_code == 200
        with container.session() as session:
            persisted = session.get(Card, card_id)
            assert persisted is not None
            assert persisted.status == "approved", f"/{endpoint} changed cards.status"


def test_controls_make_no_llm_call(
    client: TestClient, container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """ADR-008: all four controls are deterministic — llm.py is never reached."""
    completion = Mock(side_effect=AssertionError("card controls must not call an LLM"))
    monkeypatch.setattr("recally.llm.litellm.completion", completion)
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id)
        _add_review_state(session, card.id, due=NOW - timedelta(days=1))
        session.commit()
        card_id = card.id

    patched = client.patch(f"/cards/{card_id}", json={"front": "Edited"}, headers=AUTH)
    assert patched.status_code == 200
    for endpoint in ("bury", "suspend", "unsuspend"):
        assert client.post(f"/cards/{card_id}/{endpoint}", headers=AUTH).status_code == 200

    assert completion.call_count == 0


def test_deck_cards_does_not_filter_suspended(client: TestClient, container: Container) -> None:
    """The browse view is where unsuspend is reached, so it shows suspended cards."""
    with container.session() as session:
        book = _add_book(session)
        suspended = _add_card(session, book.id, front="Suspended card front")
        in_rotation = _add_card(session, book.id, front="In-rotation card front")
        _add_review_state(session, suspended.id, due=NOW - timedelta(days=1))
        _add_review_state(session, in_rotation.id, due=NOW - timedelta(days=1))
        session.commit()
        book_id, suspended_id, in_rotation_id = book.id, suspended.id, in_rotation.id

    assert client.post(f"/cards/{suspended_id}/suspend", headers=AUTH).status_code == 200

    deck_cards = client.get(f"/decks/{book_id}/cards", headers=AUTH).json()["cards"]
    by_id = {deck_card["id"]: deck_card for deck_card in deck_cards}
    assert set(by_id) == {suspended_id, in_rotation_id}
    assert by_id[suspended_id]["suspended_until"] is not None
    assert by_id[in_rotation_id]["suspended_until"] is None


# --- auth ------------------------------------------------------------------------


def test_card_control_endpoints_require_the_api_key(client: TestClient) -> None:
    for endpoint in ("bury", "suspend", "unsuspend"):
        assert client.post(f"/cards/1/{endpoint}").status_code == 401
    assert client.patch("/cards/1", json={"front": "Edited"}).status_code == 401
