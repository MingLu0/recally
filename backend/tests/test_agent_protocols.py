"""The step 2b protocol gate: a conforming stub satisfies each role `Protocol`.

The real assertion is mypy strict on `src/recally/` plus this file's annotations: a
stub whose `__call__` signature diverges from the role protocol fails type-checking.
The runtime invocations exist so the test documents the intent and the assignments
are not dead code.
"""

from collections.abc import Iterator

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from recally.agents.base import (
    AgentContext,
    CardDraft,
    CardVerdict,
    Critic,
    CriticRequest,
    CriticResult,
    Curator,
    CuratorRequest,
    CuratorResult,
    HighlightInput,
    Learner,
    LearnerRequest,
    LearnerResult,
    Writer,
    WriterRequest,
    WriterResult,
)
from recally.config import Settings
from recally.llm import LlmCaller
from recally.models import Base


class StubCurator:
    def __call__(self, request: CuratorRequest, ctx: AgentContext) -> CuratorResult:
        return CuratorResult(units=[])


class StubWriter:
    def __call__(self, request: WriterRequest, ctx: AgentContext) -> WriterResult:
        return WriterResult(cards=[CardDraft(type="qa", front="f", back="b", rationale="r")])


class StubCritic:
    def __call__(self, request: CriticRequest, ctx: AgentContext) -> CriticResult:
        return CriticResult(
            verdicts=[CardVerdict(verdict="accept", critique="ok") for _ in request.cards]
        )


class StubLearner:
    def __call__(self, request: LearnerRequest, ctx: AgentContext) -> LearnerResult:
        return LearnerResult(guidance=None, rationale="cold start", leech_card_ids=[])


@pytest.fixture
def ctx() -> Iterator[AgentContext]:
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    try:
        yield AgentContext(
            ingest_run_id=None,
            settings=Settings(RECALLY_API_KEY="test-key-not-a-real-secret"),
            llm=LlmCaller(sessionmaker(bind=engine), log_payloads=False),
        )
    finally:
        engine.dispose()


def test_conforming_stub_satisfies_protocol(ctx: AgentContext) -> None:
    """Each stub assigns to its role `Protocol` (the mypy assertion) and runs."""
    curator: Curator = StubCurator()
    writer: Writer = StubWriter()
    critic: Critic = StubCritic()
    learner: Learner = StubLearner()

    curator_result = curator(
        CuratorRequest(
            book_title="T",
            chapter="C",
            highlights=[HighlightInput(id=1, text="text", personal_note=None)],
        ),
        ctx,
    )
    assert curator_result.units == []

    writer_result = writer(
        WriterRequest(
            curated_text="text",
            tags=[],
            guidance_version=None,
            guidance=None,
            critique=None,
        ),
        ctx,
    )
    critic_result = critic(
        CriticRequest(cards=writer_result.cards, source_text="text"),
        ctx,
    )
    assert [v.verdict for v in critic_result.verdicts] == ["accept"]

    learner_result = learner(
        LearnerRequest(
            review_aggregates={},
            current_guidance_version=None,
            current_guidance=None,
        ),
        ctx,
    )
    assert learner_result.guidance is None
