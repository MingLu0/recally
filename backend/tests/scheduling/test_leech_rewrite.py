"""Step 6b-b gate: the leech rewrite, through the normal Writer ⇄ Critic path.

Spec: docs/agents.md §7 ("Rewrites go through the normal Writer ⇄ Critic → human
approval path as new cards; the leech keeps its FSRS state until the human
approves the rewrite"); docs/data-model.md, `cards.supersedes_card_id` and
`llm_calls.card_id` ("Set for calls that serve an existing card (e.g.
Learner-driven leech rewrites)"); hard rules 1, 2 and 9.

Every test drives the real `learner.generate_guidance` job with a test-local
registry of stubs, so the wiring — detection, the pipeline loop, the queue
statuses — is what is exercised, not a mock of it. The rewrite path does not
exist until the implementation lands, so the negatives are red first (ADR-012).
"""

from collections.abc import Callable, Iterator
from datetime import timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.pool import StaticPool

from recally.agents.base import (
    CardDraft,
    CardVerdict,
    CriticRequest,
    CriticResult,
    LearnerRequest,
    LearnerResult,
    WriterRequest,
    WriterResult,
)
from recally.agents.registry import AgentRegistry
from recally.config import Settings
from recally.container import Container
from recally.models import Base, Card, CardState, CuratedUnit, IngestRun, LlmCall, ReviewLog
from recally.models.base import utc_now

TEST_API_KEY = "test-key-not-a-real-secret"
NOW = utc_now()

LEECH_FRONT = "Why is a leech card hard to recall?"
LEECH_BACK = "Its explanation never matched how the reader thinks about it."
REWRITE_DRAFT = CardDraft(
    type="qa",
    front="Which everyday analogy explains the leech's idea?",
    back="A rewritten explanation through an alternative analogy.",
    rationale="Alternative analogy for a card that keeps failing.",
    guidance_version=None,
)


class StubLearner:
    """Replays a fixed LearnerResult and records its request."""

    def __init__(self, result: LearnerResult) -> None:
        self.result = result
        self.requests: list[LearnerRequest] = []

    def __call__(self, request: LearnerRequest, ctx: Any) -> LearnerResult:
        self.requests.append(request)
        return self.result


class StubWriter:
    """Replays fixed drafts; calls `ctx.llm` so the trace rows exist."""

    def __init__(self, drafts: list[CardDraft]) -> None:
        self.drafts = drafts
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
    """Replays one verdict for every card it is shown; calls `ctx.llm`."""

    def __init__(self, verdict: str, critique: str = "stub critique") -> None:
        self.verdict = verdict
        self.critique = critique
        self.requests: list[CriticRequest] = []

    def __call__(self, request: CriticRequest, ctx: Any) -> CriticResult:
        self.requests.append(request)
        ctx.llm(
            [{"role": "user", "content": "critic stub"}],
            model="test-critic-model",
            agent="critic/stub",
            ingest_run_id=ctx.ingest_run_id,
        )
        return CriticResult(
            verdicts=[CardVerdict(verdict=self.verdict, critique=self.critique)]  # type: ignore[arg-type]
        )


@pytest.fixture
def make_container(monkeypatch: pytest.MonkeyPatch) -> Iterator[Callable[..., Container]]:
    """Container factory on fresh in-memory databases, with `litellm` mocked so
    the stubs' `ctx.llm` calls write real `llm_calls` rows without a provider."""

    def fake_completion(*, model: str, messages: list[dict[str, Any]], **kwargs: Any) -> Any:
        return SimpleNamespace(
            model=model,
            choices=[SimpleNamespace(message=SimpleNamespace(role="assistant", content="{}"))],
            usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5),
        )

    monkeypatch.setattr("recally.llm.litellm.completion", fake_completion)
    monkeypatch.setattr("recally.llm.litellm.completion_cost", lambda **kwargs: 0.0)

    built: list[Container] = []

    def factory(
        *,
        learner: StubLearner | None = None,
        writer: StubWriter | None = None,
        critic: StubCritic | None = None,
        **settings_overrides: object,
    ) -> Container:
        engine = create_engine(
            "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
        )
        Base.metadata.create_all(engine)
        settings = Settings(
            RECALLY_DATABASE_URL="sqlite://",
            RECALLY_API_KEY=TEST_API_KEY,
            **settings_overrides,  # type: ignore[arg-type]
        )
        registry = AgentRegistry()
        if learner is not None:
            registry.register("learner", "default", learner)
        if writer is not None:
            registry.register("writer", "default", writer)
        if critic is not None:
            registry.register("critic", "default", critic)
        container = Container(settings, engine=engine, registry=registry)
        built.append(container)
        return container

    try:
        yield factory
    finally:
        for container in built:
            container.engine.dispose()


def _seed_leech(container: Container, *, again_count: int = 3) -> int:
    """One approved card with a real `card_state` row and `again_count` Again
    ratings — the leech the Learner will name. Returns the card id."""
    with container.session() as session:
        run = IngestRun(filename="leech-rewrite-oreilly-annotations.csv", user_id=1)
        session.add(run)
        session.flush()
        unit = CuratedUnit(
            ingest_run_id=run.id,
            curated_text="The curated text behind the leech.",
            tags=["leech"],
            decision="keep",
            user_id=1,
        )
        session.add(unit)
        session.flush()
        leech = Card(
            unit_id=unit.id,
            type="qa",
            front=LEECH_FRONT,
            back=LEECH_BACK,
            original_front=LEECH_FRONT,
            original_back=LEECH_BACK,
            tags=["leech"],
            status="approved",
            approved_at=NOW - timedelta(days=60),
            model="test-model",
            user_id=1,
        )
        session.add(leech)
        session.flush()
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
        for index in range(again_count):
            session.add(
                ReviewLog(
                    card_id=leech.id,
                    rated_at=NOW - timedelta(hours=again_count - index),
                    rating=1,
                    response_ms=9000,
                    scheduled_days=0,
                    state_before="review",
                    user_id=1,
                )
            )
        session.commit()
        return leech.id


def _cards(container: Container) -> list[Card]:
    with container.session() as session:
        return list(session.scalars(select(Card).order_by(Card.id)).all())


def _rewrite_of(container: Container, leech_id: int) -> Card | None:
    """The queued rewrite pointing at `leech_id`, if the job created one."""
    return next((card for card in _cards(container) if card.supersedes_card_id == leech_id), None)


def _card_state_snapshot(container: Container, card_id: int) -> tuple[Any, ...]:
    with container.session() as session:
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


def _run_job(container: Container) -> None:
    from recally.scheduling import learner

    learner.generate_guidance(container)


def test_rewrite_enters_the_queue_not_approved(
    make_container: Callable[..., Container],
) -> None:
    """Negative, hard rule 1: the job never writes `approved`. The rewrite lands
    in the human queue (`pending_review` or `needs_human`) like any other card."""
    stub_learner = StubLearner(LearnerResult(guidance=None, rationale="r", leech_card_ids=[]))
    container = make_container(
        learner=stub_learner,
        writer=StubWriter([REWRITE_DRAFT]),
        critic=StubCritic("accept"),
        LEARNER_MIN_REVIEWS=3,
    )
    leech_id = _seed_leech(container)
    stub_learner.result = LearnerResult(
        guidance=None, rationale="rewrite the leech", leech_card_ids=[leech_id]
    )

    _run_job(container)

    rewrite = _rewrite_of(container, leech_id)
    assert rewrite is not None, "the job created no rewrite for the named leech"
    assert rewrite.status in ("pending_review", "needs_human"), (
        f"rewrite status is {rewrite.status!r}: only a human sets `approved` (hard rule 1)"
    )
    statuses = {card.id: card.status for card in _cards(container)}
    assert statuses[leech_id] == "approved"  # the leech itself was approved by a human


def test_rewrite_shares_the_leeches_unit_id(make_container: Callable[..., Container]) -> None:
    """Provenance is unchanged: the rewrite is a new `cards` row on the same
    `unit_id`, so it traces back to the same source highlights."""
    stub_learner = StubLearner(LearnerResult(guidance=None, rationale="r"))
    container = make_container(
        learner=stub_learner,
        writer=StubWriter([REWRITE_DRAFT]),
        critic=StubCritic("accept"),
        LEARNER_MIN_REVIEWS=3,
    )
    leech_id = _seed_leech(container)
    stub_learner.result = LearnerResult(guidance=None, rationale="r", leech_card_ids=[leech_id])

    _run_job(container)

    rewrite = _rewrite_of(container, leech_id)
    assert rewrite is not None
    leech = next(card for card in _cards(container) if card.id == leech_id)
    assert rewrite.unit_id == leech.unit_id


def test_rewrite_records_supersedes_card_id(make_container: Callable[..., Container]) -> None:
    """The link is stored on the rewrite, pointing at the leech — without it
    `POST /cards/{id}/approve` has no way to know the card in front of it
    replaces anything."""
    stub_learner = StubLearner(LearnerResult(guidance=None, rationale="r"))
    container = make_container(
        learner=stub_learner,
        writer=StubWriter([REWRITE_DRAFT]),
        critic=StubCritic("accept"),
        LEARNER_MIN_REVIEWS=3,
    )
    leech_id = _seed_leech(container)
    stub_learner.result = LearnerResult(guidance=None, rationale="r", leech_card_ids=[leech_id])

    _run_job(container)

    rewrite = next(
        (card for card in _cards(container) if card.id != leech_id),
        None,
    )
    assert rewrite is not None, "the job created no rewrite card"
    assert rewrite.supersedes_card_id == leech_id


def test_rewrite_llm_calls_carry_the_card_id(make_container: Callable[..., Container]) -> None:
    """Unlike initial generation (`card_id` null), the rewrite's Writer/Critic
    calls serve an existing card, so every one of their `llm_calls` rows names
    the leech (docs/data-model.md, `llm_calls.card_id`)."""
    stub_learner = StubLearner(LearnerResult(guidance=None, rationale="r"))
    container = make_container(
        learner=stub_learner,
        writer=StubWriter([REWRITE_DRAFT]),
        critic=StubCritic("accept"),
        LEARNER_MIN_REVIEWS=3,
    )
    leech_id = _seed_leech(container)
    stub_learner.result = LearnerResult(guidance=None, rationale="r", leech_card_ids=[leech_id])

    _run_job(container)

    with container.session() as session:
        rows = list(
            session.scalars(
                select(LlmCall).where(LlmCall.agent.in_(["writer/stub", "critic/stub"]))
            ).all()
        )
    assert rows, "the rewrite made no Writer/Critic llm_calls"
    assert all(row.card_id == leech_id for row in rows), (
        f"expected card_id={leech_id} on every rewrite call, "
        f"got {[(row.agent, row.card_id) for row in rows]}"
    )


def test_rewrite_respects_the_three_round_cap(make_container: Callable[..., Container]) -> None:
    """Hard rule 9: a Critic that never accepts yields `needs_human` after
    LLM_MAX_ROUNDS rounds, not a fourth round."""
    stub_learner = StubLearner(LearnerResult(guidance=None, rationale="r"))
    writer = StubWriter([REWRITE_DRAFT])
    critic = StubCritic("revise", critique="still not an alternative explanation")
    container = make_container(
        learner=stub_learner,
        writer=writer,
        critic=critic,
        LEARNER_MIN_REVIEWS=3,
        LLM_MAX_ROUNDS=3,
    )
    leech_id = _seed_leech(container)
    stub_learner.result = LearnerResult(guidance=None, rationale="r", leech_card_ids=[leech_id])

    _run_job(container)

    rewrite = _rewrite_of(container, leech_id)
    assert rewrite is not None
    assert rewrite.status == "needs_human"
    assert len(critic.requests) == 3, f"the Critic ran {len(critic.requests)} rounds, cap is 3"
    assert len(writer.requests) == 3, f"the Writer ran {len(writer.requests)} rounds, cap is 3"
    assert rewrite.generation_rounds == 3


def test_leech_keeps_its_fsrs_state_while_the_rewrite_is_queued(
    make_container: Callable[..., Container],
) -> None:
    """Negative, ADR-008: the leech's `card_state` row is byte-identical after
    the job runs — the rewrite sitting in the queue changes nothing until a
    human approves it."""
    stub_learner = StubLearner(LearnerResult(guidance=None, rationale="r"))
    container = make_container(
        learner=stub_learner,
        writer=StubWriter([REWRITE_DRAFT]),
        critic=StubCritic("accept"),
        LEARNER_MIN_REVIEWS=3,
    )
    leech_id = _seed_leech(container)
    stub_learner.result = LearnerResult(guidance=None, rationale="r", leech_card_ids=[leech_id])
    before = _card_state_snapshot(container, leech_id)

    _run_job(container)

    rewrite = _rewrite_of(container, leech_id)
    assert rewrite is not None, "no rewrite was queued, so there is nothing to wait behind"
    assert _card_state_snapshot(container, leech_id) == before, (
        "the leech's FSRS state changed while its rewrite was still in the queue"
    )
