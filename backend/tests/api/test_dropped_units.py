"""Issue #365: the Curator's drops, listed and resolved from the Approve screen.

Spec: docs/api-spec.md, "Dropped units"; docs/data-model.md, `curated_units`
(`drop_resolution`, `drop_resolved_at`); docs/agents.md, "Pipeline runner and
handoffs" (the rescue path); ADR-019.

Three invariants these tests hold, each of which looks like a bug without the doc:

- **The Curator's decision is never rewritten.** A rescued unit keeps
  `decision='drop'` — it is the Curator's output and eval truth. Resolution is the
  two new columns.
- **Nothing a rescue writes is approved** (hard rule 1), even with
  `AUTO_APPROVE_ROUND1_ACCEPT` on: the human asked for these cards and still
  decides them in the queue.
- **Generation is the normal Writer ⇄ Critic loop** (hard rule 9): the 3-round
  cap applies, and the Curator's drop reason never reaches the Writer — it would
  argue against the card.

Generation runs against a test-local registry of stub agents with `litellm`
mocked, so the `llm_calls` rows are real and no provider is ever called.
"""

import json
from collections.abc import Callable, Iterator
from datetime import date
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from recally.agents.base import (
    CardDraft,
    CardVerdict,
    CriticRequest,
    CriticResult,
    WriterRequest,
    WriterResult,
)
from recally.agents.registry import AgentRegistry
from recally.agents.writer.default import DefaultWriter
from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
from recally.container import Container
from recally.main import create_app
from recally.models import (
    Base,
    Book,
    Card,
    CuratedUnit,
    CuratedUnitHighlight,
    Highlight,
    IngestRun,
    LlmCall,
)
from recally.models.base import utc_now

TEST_API_KEY = "test-key-not-a-real-secret"
HEADERS = {"X-API-Key": TEST_API_KEY}

DROP_REASON = "Pure narrative framing with no retrievable claim; DROP-REASON-SENTINEL."
HIGHLIGHT_TEXT = "Evals turn a vague sense of quality into a number you can track."
GENERATED_DRAFT = CardDraft(
    type="qa",
    front="What do evals turn a vague sense of quality into?",
    back="A number you can track.",
    rationale="The highlight's one claim.",
    guidance_version=None,
)


# --- stub agents -------------------------------------------------------------------


class StubWriter:
    """Replays fixed drafts and records each request; calls `ctx.llm` so a trace row exists."""

    def __init__(self, drafts: list[CardDraft] | None = None) -> None:
        self.drafts = drafts or [GENERATED_DRAFT]
        self.requests: list[WriterRequest] = []

    def __call__(self, request: WriterRequest, ctx: Any) -> WriterResult:
        self.requests.append(request)
        ctx.llm(
            [{"role": "user", "content": "writer stub"}],
            model="test-writer-model",
            agent="writer/stub",
            ingest_run_id=ctx.ingest_run_id,
        )
        return WriterResult(cards=self.drafts)


class StubCritic:
    """Gives one verdict per card shown; `raises` makes every call fail instead."""

    def __init__(
        self,
        verdict: str = "accept",
        *,
        quotes: tuple[str, ...] = ("a number you can track",),
        raises: bool = False,
    ) -> None:
        self.verdict = verdict
        self.quotes = quotes
        self.raises = raises
        self.requests: list[CriticRequest] = []

    def __call__(self, request: CriticRequest, ctx: Any) -> CriticResult:
        self.requests.append(request)
        ctx.llm(
            [{"role": "user", "content": "critic stub"}],
            model="test-critic-model",
            agent="critic/stub",
            ingest_run_id=ctx.ingest_run_id,
        )
        if self.raises:
            raise RuntimeError("provider timed out mid-loop")
        return CriticResult(
            verdicts=[
                CardVerdict(
                    verdict=self.verdict,  # type: ignore[arg-type]
                    critique="stub critique",
                    quotes=self.quotes,
                )
                for _ in request.cards
            ]
        )


# --- fixtures ----------------------------------------------------------------------


WRITER_JSON = json.dumps(
    {
        "cards": [
            {
                "type": "qa",
                "front": GENERATED_DRAFT.front,
                "back": GENERATED_DRAFT.back,
                "rationale": GENERATED_DRAFT.rationale,
            }
        ]
    }
)


@pytest.fixture
def make_client(
    monkeypatch: pytest.MonkeyPatch,
) -> Iterator[Callable[..., tuple[TestClient, Container]]]:
    """A TestClient factory on a fresh in-memory database.

    `litellm` is mocked so stub `ctx.llm` calls write real `llm_calls` rows, and so
    the real `DefaultWriter` gets a parseable reply when a test registers it.
    """

    def fake_completion(*, model: str, messages: list[dict[str, Any]], **kwargs: Any) -> Any:
        return SimpleNamespace(
            model=model,
            choices=[
                SimpleNamespace(message=SimpleNamespace(role="assistant", content=WRITER_JSON))
            ],
            usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5),
        )

    monkeypatch.setattr("recally.llm.litellm.completion", fake_completion)
    monkeypatch.setattr("recally.llm.litellm.completion_cost", lambda **kwargs: 0.000123)
    monkeypatch.setenv("RECALLY_API_KEY", TEST_API_KEY)
    get_settings.cache_clear()

    engines: list[Any] = []
    clients: list[TestClient] = []

    def factory(
        *, writer: Any = None, critic: Any = None, **settings_overrides: object
    ) -> tuple[TestClient, Container]:
        engine = create_engine(
            "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
        )
        engines.append(engine)
        Base.metadata.create_all(engine)
        settings = Settings(
            RECALLY_DATABASE_URL="sqlite://",
            RECALLY_API_KEY=TEST_API_KEY,
            LLM_CONCURRENCY=1,
            **settings_overrides,  # type: ignore[arg-type]
        )
        registry = AgentRegistry()
        registry.register("writer", "default", writer or StubWriter())
        registry.register("critic", "default", critic or StubCritic())
        container = Container(settings, engine=engine, registry=registry)
        app = create_app()
        app.dependency_overrides[container_dependency] = lambda: container
        client = TestClient(app)
        client.__enter__()
        clients.append(client)
        return client, container

    try:
        yield factory
    finally:
        for client in clients:
            client.__exit__(None, None, None)
        for engine in engines:
            engine.dispose()
        get_settings.cache_clear()


# --- seed helpers ------------------------------------------------------------------

_seed_counter = 0


def _next_seed() -> int:
    global _seed_counter
    _seed_counter += 1
    return _seed_counter


def _run(session: Session) -> IngestRun:
    run = IngestRun(filename=f"run-{_next_seed()}-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    return run


def _book(session: Session, title: str = "Evals for AI Engineers") -> Book:
    book = Book(title=title, source="oreilly", external_id=f"978{_next_seed():010d}", user_id=1)
    session.add(book)
    session.flush()
    return book


def _highlight(
    session: Session,
    book: Book,
    *,
    text: str = HIGHLIGHT_TEXT,
    chapter: str | None = "1. Introduction",
    position: int = 1,
    truncated: bool = False,
    removed: bool = False,
) -> Highlight:
    highlight = Highlight(
        book_id=book.id,
        chapter=chapter,
        raw_text=text,
        dedupe_key=f"uuid-{_next_seed()}",
        source="oreilly",
        highlighted_at=date(2026, 9, 1),
        export_position=position,
        truncated=truncated,
        processed=True,
        removed_at=utc_now() if removed else None,
        user_id=1,
    )
    session.add(highlight)
    session.flush()
    return highlight


def _unit(
    session: Session,
    run: IngestRun,
    highlights: list[Highlight],
    *,
    decision: str = "drop",
    reason: str | None = DROP_REASON,
    curated_text: str | None = None,
    tags: list[str] | None = None,
) -> CuratedUnit:
    unit = CuratedUnit(
        ingest_run_id=run.id,
        curated_text=curated_text or " ".join(h.raw_text for h in highlights),
        tags=tags if tags is not None else ["evals"],
        decision=decision,
        reason=reason,
        user_id=1,
    )
    session.add(unit)
    session.flush()
    for highlight in highlights:
        session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
    session.flush()
    return unit


def _seed_one_drop(container: Container, **unit_kwargs: Any) -> int:
    """One unresolved drop over one highlight; returns the unit id."""
    with container.session() as session:
        book = _book(session)
        unit = _unit(session, _run(session), [_highlight(session, book)], **unit_kwargs)
        session.commit()
        return unit.id


def _dropped(client: TestClient, **params: Any) -> dict[str, Any]:
    response = client.get("/units/dropped", headers=HEADERS, params=params)
    assert response.status_code == 200, response.text
    body: dict[str, Any] = response.json()
    return body


def _dropped_ids(client: TestClient) -> list[int]:
    return [unit["id"] for unit in _dropped(client)["units"]]


def _llm_call_count(container: Container) -> int:
    with container.session() as session:
        return int(session.scalar(select(func.count()).select_from(LlmCall)) or 0)


def _unit_row(container: Container, unit_id: int) -> CuratedUnit:
    with container.session() as session:
        unit = session.get(CuratedUnit, unit_id)
        assert unit is not None
        return unit


def _cards_of(container: Container, unit_id: int) -> list[Card]:
    with container.session() as session:
        return list(session.scalars(select(Card).where(Card.unit_id == unit_id)).all())


def _resolve(container: Container, unit_id: int, resolution: str) -> None:
    with container.session() as session:
        unit = session.get(CuratedUnit, unit_id)
        assert unit is not None
        unit.drop_resolution = resolution  # type: ignore[attr-defined]
        unit.drop_resolved_at = utc_now()  # type: ignore[attr-defined]
        session.commit()


# --- GET /units/dropped ------------------------------------------------------------


def test_dropped_lists_unresolved_drops_from_every_run(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    with container.session() as session:
        book = _book(session, "Evals for AI Engineers")
        first_run, second_run = _run(session), _run(session)
        single = _unit(session, first_run, [_highlight(session, book, position=1)])
        grouped = _unit(
            session,
            second_run,
            [
                _highlight(session, book, text="Heading one.", position=2),
                _highlight(session, book, text="Heading two.", position=3),
            ],
            reason="Sibling headings with no content.",
        )
        session.commit()
        single_id, grouped_id, book_id = single.id, grouped.id, book.id

    units = {unit["id"]: unit for unit in _dropped(client)["units"]}

    assert set(units) == {single_id, grouped_id}
    assert units[single_id]["reason"] == DROP_REASON
    assert units[single_id]["source_highlights"] == [HIGHLIGHT_TEXT]
    assert units[grouped_id]["reason"] == "Sibling headings with no content."
    assert units[grouped_id]["source_highlights"] == ["Heading one.", "Heading two."]
    for unit in units.values():
        assert unit["book_id"] == book_id
        assert unit["book"] == "Evals for AI Engineers"
        assert unit["chapter"] == "1. Introduction"
        assert unit["curated_text"]
        assert unit["created_at"].endswith("Z")
        assert unit["truncated"] is False


def test_dropped_never_lists_keep_units(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    with container.session() as session:
        book = _book(session)
        run = _run(session)
        kept = _unit(session, run, [_highlight(session, book, position=1)], decision="keep")
        dropped = _unit(session, run, [_highlight(session, book, position=2)])
        session.commit()
        kept_id, dropped_id = kept.id, dropped.id

    listed = _dropped(client)

    assert kept_id not in [unit["id"] for unit in listed["units"]]
    assert [unit["id"] for unit in listed["units"]] == [dropped_id]
    assert listed["count"] == 1


def test_dropped_excludes_resolved_units(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    dismissed_id = _seed_one_drop(container)
    rescued_id = _seed_one_drop(container)
    open_id = _seed_one_drop(container)
    _resolve(container, dismissed_id, "dismissed")
    _resolve(container, rescued_id, "rescued")

    listed = _dropped(client)

    assert [unit["id"] for unit in listed["units"]] == [open_id]
    assert listed["count"] == 1


def test_dropped_excludes_a_drop_superseded_by_a_newer_unit(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    """A changed re-import resets `processed=false` (hard rule 6) and the Curator
    writes a new unit over the same highlight; the old drop no longer describes the
    current text, so it must not be offered for rescue."""
    client, container = make_client()
    with container.session() as session:
        book = _book(session)
        reimported = _highlight(session, book, position=1)
        untouched = _highlight(session, book, text="An untouched dropped highlight.", position=2)
        stale_drop = _unit(session, _run(session), [reimported])
        current_drop = _unit(session, _run(session), [untouched])
        # The re-curation: the same highlight, a newer unit (here a keep).
        _unit(session, _run(session), [reimported], decision="keep", reason=None)
        session.commit()
        stale_id, current_id = stale_drop.id, current_drop.id

    ids = _dropped_ids(client)

    assert stale_id not in ids
    assert ids == [current_id]


def test_dropped_excludes_units_whose_highlights_are_all_removed(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    with container.session() as session:
        book = _book(session)
        run = _run(session)
        all_removed = _unit(
            session,
            run,
            [
                _highlight(session, book, text="Gone one.", position=1, removed=True),
                _highlight(session, book, text="Gone two.", position=2, removed=True),
            ],
        )
        partly_removed = _unit(
            session,
            run,
            [
                _highlight(session, book, text="Gone three.", position=3, removed=True),
                _highlight(session, book, text="Still exported.", position=4),
            ],
        )
        session.commit()
        all_removed_id, partly_removed_id = all_removed.id, partly_removed.id

    ids = _dropped_ids(client)

    assert all_removed_id not in ids
    assert ids == [partly_removed_id]


def test_dropped_orders_by_book_chapter_then_export_position(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    with container.session() as session:
        alpha, beta = _book(session, "Alpha Book"), _book(session, "Beta Book")
        run = _run(session)
        # Inserted out of order on every key so an id-ordered result fails.
        beta_unit = _unit(session, run, [_highlight(session, beta, chapter="1. One", position=1)])
        alpha_ch2 = _unit(session, run, [_highlight(session, alpha, chapter="2. Two", position=1)])
        alpha_ch1_late = _unit(
            session, run, [_highlight(session, alpha, chapter="1. One", position=9)]
        )
        # A group whose smallest export_position (3) is what places it.
        alpha_ch1_early = _unit(
            session,
            run,
            [
                _highlight(session, alpha, chapter="1. One", position=12),
                _highlight(session, alpha, chapter="1. One", position=3),
            ],
        )
        session.commit()
        expected = [alpha_ch1_early.id, alpha_ch1_late.id, alpha_ch2.id, beta_unit.id]

    assert _dropped_ids(client) == expected


def test_dropped_count_is_collection_wide(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    """`count` ignores the filters, like `GET /cards/pending`'s `counts`: the header
    must be right whatever the list shows."""
    client, container = make_client()
    with container.session() as session:
        alpha, beta = _book(session, "Alpha Book"), _book(session, "Beta Book")
        run = _run(session)
        _unit(session, run, [_highlight(session, alpha, position=1)])
        _unit(session, run, [_highlight(session, alpha, position=2)])
        beta_unit = _unit(session, run, [_highlight(session, beta, position=1)])
        session.commit()
        beta_id, beta_unit_id = beta.id, beta_unit.id

    filtered = _dropped(client, book_id=beta_id)

    assert [unit["id"] for unit in filtered["units"]] == [beta_unit_id]
    assert filtered["count"] == 3


def test_dropped_flags_truncated_when_any_source_is_clipped(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    with container.session() as session:
        book = _book(session)
        run = _run(session)
        clipped = _unit(
            session,
            run,
            [
                _highlight(session, book, text="Whole sentence.", position=1),
                _highlight(session, book, text="Clipped mid-wo", position=2, truncated=True),
            ],
        )
        whole = _unit(session, run, [_highlight(session, book, position=3)])
        session.commit()
        clipped_id, whole_id = clipped.id, whole.id

    units = {unit["id"]: unit for unit in _dropped(client)["units"]}

    assert units[clipped_id]["truncated"] is True
    # Hard rule 7: flagged, never repaired — the clipped text is returned as exported.
    assert units[clipped_id]["source_highlights"][1] == "Clipped mid-wo"
    assert units[whole_id]["truncated"] is False


# --- POST /units/{id}/dismiss ------------------------------------------------------


def test_dismiss_sets_resolution_and_timestamp(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    unit_id = _seed_one_drop(container)
    before = utc_now()

    response = client.post(f"/units/{unit_id}/dismiss", headers=HEADERS)

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["id"] == unit_id
    assert body["drop_resolution"] == "dismissed"
    assert body["drop_resolved_at"] is not None
    unit = _unit_row(container, unit_id)
    assert unit.drop_resolution == "dismissed"  # type: ignore[attr-defined]
    assert unit.drop_resolved_at is not None  # type: ignore[attr-defined]
    assert unit.drop_resolved_at >= before  # type: ignore[attr-defined]
    assert unit.decision == "drop"
    assert unit_id not in _dropped_ids(client)


def test_dismiss_refuses_a_keep_unit_with_409(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    keep_id = _seed_one_drop(container, decision="keep", reason=None)

    response = client.post(f"/units/{keep_id}/dismiss", headers=HEADERS)

    assert response.status_code == 409, response.text
    assert response.json()["status"] == 409
    assert _unit_row(container, keep_id).drop_resolution is None  # type: ignore[attr-defined]


# --- shared action errors ----------------------------------------------------------


def _manual_body() -> dict[str, str]:
    return {"type": "qa", "front": "What do evals produce?", "back": "A trackable number."}


def test_actions_refuse_an_already_resolved_unit_with_409(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    writer, critic = StubWriter(), StubCritic()
    client, container = make_client(writer=writer, critic=critic)
    dismissed_id = _seed_one_drop(container)
    rescued_id = _seed_one_drop(container)
    _resolve(container, dismissed_id, "dismissed")
    _resolve(container, rescued_id, "rescued")

    for unit_id in (dismissed_id, rescued_id):
        dismiss = client.post(f"/units/{unit_id}/dismiss", headers=HEADERS)
        generate = client.post(f"/units/{unit_id}/generate", headers=HEADERS)
        manual = client.post(f"/units/{unit_id}/cards", headers=HEADERS, json=_manual_body())
        assert dismiss.status_code == 409, dismiss.text
        assert generate.status_code == 409, generate.text
        assert manual.status_code == 409, manual.text
        assert _cards_of(container, unit_id) == []

    # A second tap must not generate twice: the refusal comes before any LLM call.
    assert writer.requests == []
    assert critic.requests == []
    assert _llm_call_count(container) == 0
    assert _unit_row(container, dismissed_id).drop_resolution == "dismissed"  # type: ignore[attr-defined]


def test_actions_on_unknown_unit_return_404(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    writer = StubWriter()
    client, container = make_client(writer=writer)
    _seed_one_drop(container)
    unknown = 9999

    responses = [
        client.post(f"/units/{unknown}/dismiss", headers=HEADERS),
        client.post(f"/units/{unknown}/generate", headers=HEADERS),
        client.post(f"/units/{unknown}/cards", headers=HEADERS, json=_manual_body()),
    ]

    for response in responses:
        assert response.status_code == 404, response.text
        assert response.json() == {"status": 404, "detail": f"Unit {unknown} not found."}
    assert writer.requests == []


# --- POST /units/{id}/generate -----------------------------------------------------


def test_generate_writes_cards_and_marks_unit_rescued(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    unit_id = _seed_one_drop(container, tags=["evals", "metrics"])

    response = client.post(f"/units/{unit_id}/generate", headers=HEADERS)

    assert response.status_code == 200, response.text
    returned = response.json()["cards"]
    cards = _cards_of(container, unit_id)
    assert [card["id"] for card in returned] == [card.id for card in cards]
    assert len(cards) == 1
    assert returned[0]["front"] == GENERATED_DRAFT.front
    assert returned[0]["status"] == "pending_review"
    assert returned[0]["source_highlights"] == [HIGHLIGHT_TEXT]
    assert returned[0]["book"] == "Evals for AI Engineers"
    assert cards[0].tags == ["evals", "metrics"]
    assert cards[0].generation_rounds == 1
    assert cards[0].cost_microusd > 0
    unit = _unit_row(container, unit_id)
    assert unit.decision == "drop", "the Curator's decision is eval truth; never rewritten"
    assert unit.drop_resolution == "rescued"  # type: ignore[attr-defined]
    assert unit.drop_resolved_at is not None  # type: ignore[attr-defined]
    assert unit_id not in _dropped_ids(client)


def test_generate_never_auto_approves_even_with_flag_on(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client(AUTO_APPROVE_ROUND1_ACCEPT=True)
    unit_id = _seed_one_drop(container)

    response = client.post(f"/units/{unit_id}/generate", headers=HEADERS)

    assert response.status_code == 200, response.text
    cards = _cards_of(container, unit_id)
    assert [card.status for card in cards] == ["pending_review"]
    assert cards[0].generation_rounds == 1, "a round-1 accept: the case the flag would approve"
    assert cards[0].approved_at is None
    assert [card["status"] for card in response.json()["cards"]] == ["pending_review"]


def test_generate_respects_the_three_round_cap(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    writer, critic = StubWriter(), StubCritic("revise")
    client, container = make_client(writer=writer, critic=critic)
    unit_id = _seed_one_drop(container)

    response = client.post(f"/units/{unit_id}/generate", headers=HEADERS)

    assert response.status_code == 200, response.text
    cards = _cards_of(container, unit_id)
    assert [card.status for card in cards] == ["needs_human"]
    assert cards[0].generation_rounds == 3
    assert "Writer 3 rounds unresolved" in (cards[0].status_reason or "")
    assert len(critic.requests) == 3
    assert len(writer.requests) == 3  # round 1 + two revisions
    assert _unit_row(container, unit_id).drop_resolution == "rescued"  # type: ignore[attr-defined]


def test_generate_logs_llm_calls_with_unit_id_and_no_ingest_run(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client(critic=StubCritic("revise"))
    unit_id = _seed_one_drop(container)

    response = client.post(f"/units/{unit_id}/generate", headers=HEADERS)

    assert response.status_code == 200, response.text
    with container.session() as session:
        calls = session.scalars(select(LlmCall)).all()
        assert len(calls) == 6  # three Writer and three Critic calls
        assert {call.unit_id for call in calls} == {unit_id}
        assert {call.ingest_run_id for call in calls} == {None}


def test_generate_failure_leaves_unit_unresolved(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client(critic=StubCritic(raises=True))
    unit_id = _seed_one_drop(container)

    response = client.post(f"/units/{unit_id}/generate", headers=HEADERS)

    assert response.status_code == 502, response.text
    assert response.json()["status"] == 502
    assert _cards_of(container, unit_id) == []
    assert _unit_row(container, unit_id).drop_resolution is None  # type: ignore[attr-defined]
    assert unit_id in _dropped_ids(client)


def test_generate_does_not_send_the_curator_reason_to_the_writer(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    """The real Writer renders its prompt; the logged request must not hold the
    drop reason, which would argue against the card it is asked to write."""
    client, container = make_client(writer=DefaultWriter())
    unit_id = _seed_one_drop(container)

    response = client.post(f"/units/{unit_id}/generate", headers=HEADERS)

    assert response.status_code == 200, response.text
    with container.session() as session:
        writer_calls = session.scalars(
            select(LlmCall).where(LlmCall.agent == "writer/default")
        ).all()
    assert writer_calls, "the real Writer must have rendered and logged its prompt"
    for call in writer_calls:
        rendered = json.dumps(call.request)
        assert HIGHLIGHT_TEXT in rendered, "the prompt was logged, so the check is not vacuous"
        assert "DROP-REASON-SENTINEL" not in rendered


# --- POST /units/{id}/cards --------------------------------------------------------


def test_manual_card_lands_in_pending_review_and_marks_unit_rescued(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    unit_id = _seed_one_drop(container, tags=["evals"])
    body = {
        "type": "cloze",
        "front": "Evals turn quality into a {{c1::number}} you can track.",
        "back": "",
    }

    response = client.post(f"/units/{unit_id}/cards", headers=HEADERS, json=body)

    assert response.status_code == 200, response.text
    returned = response.json()
    assert returned["status"] == "pending_review"
    assert returned["type"] == "cloze"
    assert returned["source_highlights"] == [HIGHLIGHT_TEXT]
    cards = _cards_of(container, unit_id)
    assert len(cards) == 1
    card = cards[0]
    assert card.id == returned["id"]
    assert card.status == "pending_review"
    assert card.approved_at is None
    assert (card.front, card.back) == (body["front"], body["back"])
    assert (card.original_front, card.original_back) == (body["front"], body["back"])
    assert card.model == "human"
    assert card.generation_rounds == 0
    assert card.guidance_version is None
    assert card.cost_microusd == 0
    assert card.tags == ["evals"]
    unit = _unit_row(container, unit_id)
    assert unit.decision == "drop"
    assert unit.drop_resolution == "rescued"  # type: ignore[attr-defined]
    assert unit.drop_resolved_at is not None  # type: ignore[attr-defined]


def test_manual_card_makes_no_llm_call(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    writer, critic = StubWriter(), StubCritic()
    client, container = make_client(writer=writer, critic=critic)
    unit_id = _seed_one_drop(container)
    before = _llm_call_count(container)

    response = client.post(f"/units/{unit_id}/cards", headers=HEADERS, json=_manual_body())

    assert response.status_code == 200, response.text
    assert _llm_call_count(container) == before
    assert writer.requests == []
    assert critic.requests == []


def test_manual_cloze_without_a_deletion_is_rejected_with_422(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    unit_id = _seed_one_drop(container)
    body = {"type": "cloze", "front": "Evals turn quality into a number.", "back": ""}

    response = client.post(f"/units/{unit_id}/cards", headers=HEADERS, json=body)

    assert response.status_code == 422, response.text
    assert response.json()["status"] == 422
    assert "{{c1::" in response.json()["detail"]
    assert _cards_of(container, unit_id) == []
    assert _unit_row(container, unit_id).drop_resolution is None  # type: ignore[attr-defined]


def test_manual_card_with_blank_front_is_rejected_with_422(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    unit_id = _seed_one_drop(container)
    body = {"type": "qa", "front": "   ", "back": "An answer."}

    response = client.post(f"/units/{unit_id}/cards", headers=HEADERS, json=body)

    assert response.status_code == 422, response.text
    assert response.json()["status"] == 422
    assert _cards_of(container, unit_id) == []
    assert _unit_row(container, unit_id).drop_resolution is None  # type: ignore[attr-defined]


def test_manual_qa_with_blank_back_is_rejected_with_422(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    """Not on the ticket's named list; it covers the third validation rule the
    ticket states (`type='qa'` with blank `back` → 422)."""
    client, container = make_client()
    unit_id = _seed_one_drop(container)
    body = {"type": "qa", "front": "What do evals produce?", "back": " "}

    response = client.post(f"/units/{unit_id}/cards", headers=HEADERS, json=body)

    assert response.status_code == 422, response.text
    assert _cards_of(container, unit_id) == []


def test_manual_card_appears_in_pending_queue(
    make_client: Callable[..., tuple[TestClient, Container]],
) -> None:
    client, container = make_client()
    with container.session() as session:
        book = _book(session)
        unit = _unit(
            session,
            _run(session),
            [
                _highlight(session, book, text="First source.", position=1),
                _highlight(session, book, text="Second source.", position=2),
            ],
        )
        session.commit()
        unit_id = unit.id

    created = client.post(f"/units/{unit_id}/cards", headers=HEADERS, json=_manual_body())
    assert created.status_code == 200, created.text

    pending = client.get("/cards/pending", headers=HEADERS).json()
    queued = [card for card in pending["cards"] if card["id"] == created.json()["id"]]
    assert len(queued) == 1
    assert queued[0]["status"] == "pending_review"
    assert queued[0]["source_highlights"] == ["First source.", "Second source."]
    assert queued[0] == created.json()
    assert pending["counts"]["pending_review"] == 1
