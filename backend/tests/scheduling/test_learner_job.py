"""Step 6b-a gate: the `learner` job — threshold, versioned write, wiring.

Spec: docs/agents.md, "7. Learner Agent", stage B; docs/data-model.md,
`writer_guidance` ("never edited in place", "basis: aggregates the Learner used to
write it") and `llm_calls` ("ingest_run_id null for Learner calls"); docs/config.md,
`LEARNER_MIN_REVIEWS` / `AGENT_LEARNER` / `LLM_MODEL_LEARNER`; ADR-006, ADR-007.

The job's halves are tested against their own seams:

- the threshold and the persistence run against a stub Learner registered in a
  test-local `AgentRegistry`, so no LLM is involved and the request the agent
  received can be read back;
- the `llm_calls` trace test runs the real `learner/default` variant with
  `litellm.completion` mocked (the same seam as test_llm.py), because the trace
  fields are the assertion;
- the HTTP test drives `POST /jobs/run` with the job entry point mocked, because
  the wiring — not the fit — is what retiring 5a's 501 placeholder is about.

`recally.scheduling.learner` is imported inside tests, not at module scope: the
module does not exist until the implementation lands (same shape as
tests/scheduling/test_optimizer.py).
"""

import json
from collections.abc import Callable, Iterator
from datetime import timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select
from sqlalchemy.pool import StaticPool

from recally.agents.base import LearnerRequest, LearnerResult
from recally.agents.registry import AgentRegistry
from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
from recally.container import Container
from recally.main import create_app
from recally.models import (
    Base,
    Card,
    CuratedUnit,
    IngestRun,
    LlmCall,
    ReviewLog,
    WriterGuidance,
)
from recally.models.base import utc_now

TEST_API_KEY = "test-key-not-a-real-secret"
NOW = utc_now()

V1_GUIDANCE = "v1: prefer application questions over definitions."
V1_BASIS = {
    "review_count": 120,
    "lapse_rate_by_card_type": {"qa": {"reviews": 120, "lapses": 30, "lapse_rate": 0.25}},
}
V1_CREATED_AT = NOW - timedelta(days=7)
V2_GUIDANCE = "v2: definition cards lapse at 40% — prefer application questions."


class StubLearner:
    """A registered `learner` variant that records its request and replays a result.

    Registered into a test-local `AgentRegistry`, so the job resolves it through the
    same `container.agent("learner")` path the real variant uses (ADR-007).
    """

    def __init__(self, result: LearnerResult) -> None:
        self.result = result
        self.requests: list[LearnerRequest] = []

    def __call__(self, request: LearnerRequest, ctx: Any) -> LearnerResult:
        self.requests.append(request)
        return self.result


@pytest.fixture
def make_container() -> Iterator[Callable[..., Container]]:
    """Container factory on fresh in-memory databases (same shape as
    tests/scheduling/test_optimizer.py). `learner=` installs a stub variant into a
    test-local registry; without it the container uses the default registry."""
    built: list[Container] = []

    def factory(learner: StubLearner | None = None, **settings_overrides: object) -> Container:
        engine = create_engine(
            "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
        )
        Base.metadata.create_all(engine)
        settings = Settings(
            RECALLY_DATABASE_URL="sqlite://",
            RECALLY_API_KEY=TEST_API_KEY,
            **settings_overrides,  # type: ignore[arg-type]
        )
        registry = None
        if learner is not None:
            registry = AgentRegistry()
            registry.register("learner", "default", learner)
        container = Container(settings, engine=engine, registry=registry)
        built.append(container)
        return container

    try:
        yield factory
    finally:
        for container in built:
            container.engine.dispose()


def _seed_reviews(container: Container, count: int) -> None:
    """`count` review_logs on one approved card, one per hour."""
    with container.session() as session:
        run = IngestRun(filename="learner-job-oreilly-annotations.csv", user_id=1)
        session.add(run)
        session.flush()
        unit = CuratedUnit(
            ingest_run_id=run.id, curated_text="…", tags=[], decision="keep", user_id=1
        )
        session.add(unit)
        session.flush()
        card = Card(
            unit_id=unit.id,
            type="qa",
            front="Why is the Learner nightly rather than per-review?",
            back="A lapse-rate bucket needs on the order of a hundred reviews.",
            original_front="Why is the Learner nightly rather than per-review?",
            original_back="A lapse-rate bucket needs on the order of a hundred reviews.",
            tags=["learner"],
            status="approved",
            approved_at=NOW - timedelta(days=30),
            model="claude-sonnet-5",
            guidance_version=1,
            user_id=1,
        )
        session.add(card)
        session.flush()
        for index in range(count):
            session.add(
                ReviewLog(
                    card_id=card.id,
                    rated_at=NOW - timedelta(hours=count - index),
                    rating=3,
                    response_ms=4200,
                    scheduled_days=0,
                    state_before="review",
                    user_id=1,
                )
            )
        session.commit()


def _seed_v1(container: Container) -> None:
    with container.session() as session:
        session.add(
            WriterGuidance(
                version=1,
                guidance=V1_GUIDANCE,
                basis=V1_BASIS,
                created_at=V1_CREATED_AT,
                user_id=1,
            )
        )
        session.commit()


def _guidance_rows(container: Container) -> list[WriterGuidance]:
    with container.session() as session:
        return list(session.scalars(select(WriterGuidance).order_by(WriterGuidance.version)).all())


def _llm_call_count(container: Container) -> int:
    with container.session() as session:
        return session.scalar(select(func.count()).select_from(LlmCall)) or 0


# --- The threshold (docs/config.md, LEARNER_MIN_REVIEWS) -------------------------


def test_below_min_reviews_writes_no_guidance_row(
    make_container: Callable[..., Container],
) -> None:
    """With fewer than LEARNER_MIN_REVIEWS review_logs the job writes nothing:
    'a bucket needs on the order of a hundred reviews to mean anything'
    (docs/config.md) — confident guidance from noise is the failure mode."""
    from recally.scheduling import learner

    stub = StubLearner(LearnerResult(guidance=V2_GUIDANCE, rationale="r"))
    container = make_container(learner=stub, LEARNER_MIN_REVIEWS=5)
    _seed_reviews(container, 4)

    learner.generate_guidance(container)

    assert _guidance_rows(container) == []


def test_below_min_reviews_makes_no_llm_call(
    make_container: Callable[..., Container],
) -> None:
    """The threshold is checked BEFORE the agent: a call that is made and then
    discarded still costs money and still writes an `llm_calls` row. Below the
    threshold the agent is never invoked and the trace table stays empty."""
    from recally.scheduling import learner

    stub = StubLearner(LearnerResult(guidance=V2_GUIDANCE, rationale="r"))
    container = make_container(learner=stub, LEARNER_MIN_REVIEWS=5)
    _seed_reviews(container, 4)

    learner.generate_guidance(container)

    assert stub.requests == [], f"the Learner was called below LEARNER_MIN_REVIEWS: {stub.requests}"
    assert _llm_call_count(container) == 0


# --- The versioned write (hard rule 10; docs/data-model.md, writer_guidance) ------


def test_above_min_reviews_writes_one_guidance_row(
    make_container: Callable[..., Container],
) -> None:
    """Above the threshold, with an existing v1, the job appends exactly one row
    at version=2 carrying the Learner's new guidance text."""
    from recally.scheduling import learner

    stub = StubLearner(
        LearnerResult(guidance=V2_GUIDANCE, rationale="lapses cluster on definitions")
    )
    container = make_container(learner=stub, LEARNER_MIN_REVIEWS=5)
    _seed_v1(container)
    _seed_reviews(container, 5)

    learner.generate_guidance(container)

    rows = _guidance_rows(container)
    assert len(rows) == 2, f"expected v1 + one new row, got versions {[r.version for r in rows]}"
    assert rows[1].version == 2
    assert rows[1].guidance == V2_GUIDANCE


def test_writing_v2_never_edits_v1(
    make_container: Callable[..., Container],
) -> None:
    """Hard rule 10: a v2 is an INSERT. v1's `guidance`, `basis` and `created_at`
    are byte-identical before and after the job — cards stamped v1 must keep
    pointing at the text that generated them."""
    from recally.scheduling import learner

    stub = StubLearner(LearnerResult(guidance=V2_GUIDANCE, rationale="r"))
    container = make_container(learner=stub, LEARNER_MIN_REVIEWS=5)
    _seed_v1(container)
    _seed_reviews(container, 5)
    before = _guidance_rows(container)[0]
    snapshot = (before.guidance, before.basis, before.created_at)

    learner.generate_guidance(container)

    after = _guidance_rows(container)[0]
    assert after.version == 1
    assert (after.guidance, after.basis, after.created_at) == snapshot, (
        "v1 changed when v2 was written — writer_guidance rows are never edited"
    )


def test_guidance_none_writes_no_row(
    make_container: Callable[..., Container],
) -> None:
    """`guidance=None` means 'no new row is warranted' (the LearnerResult
    docstring): the job writes nothing and does not treat it as a failure."""
    from recally.scheduling import learner

    stub = StubLearner(LearnerResult(guidance=None, rationale="no clear signal yet"))
    container = make_container(learner=stub, LEARNER_MIN_REVIEWS=5)
    _seed_v1(container)
    _seed_reviews(container, 5)

    result = learner.generate_guidance(container)

    assert result is None
    assert [row.version for row in _guidance_rows(container)] == [1]
    assert len(stub.requests) == 1


def test_basis_records_the_aggregates_the_fit_used(
    make_container: Callable[..., Container],
) -> None:
    """`basis` is 'the aggregates the Learner used to write it'
    (docs/data-model.md): the persisted value is exactly the `review_aggregates`
    the agent was handed (modulo the JSON round-trip the column applies)."""
    from recally.scheduling import learner

    stub = StubLearner(LearnerResult(guidance=V2_GUIDANCE, rationale="r"))
    container = make_container(learner=stub, LEARNER_MIN_REVIEWS=5)
    _seed_reviews(container, 5)

    learner.generate_guidance(container)

    assert len(stub.requests) == 1
    sent_aggregates = stub.requests[0].review_aggregates
    row = _guidance_rows(container)[0]
    assert row.version == 1  # first ever row: max(version)+1 over an empty table
    assert row.basis == json.loads(json.dumps(sent_aggregates))


# --- The llm_calls trace (docs/data-model.md; ADR-006) ---------------------------


def test_learner_llm_call_has_null_ingest_run_id(
    make_container: Callable[..., Container], monkeypatch: pytest.MonkeyPatch
) -> None:
    """A Learner call is traced as `agent="learner/default"` (ADR-007) with
    `ingest_run_id` and `unit_id` null — the nightly job serves no ingest run and
    no unit (docs/data-model.md, `llm_calls`). Runs the real variant with
    `litellm.completion` mocked, because the trace fields are the assertion."""
    response_text = json.dumps(
        {
            "guidance": V2_GUIDANCE,
            "rationale": "lapses cluster on definitions",
            "leech_card_ids": [],
        }
    )

    def fake_completion(*, model: str, messages: list[dict[str, Any]], **kwargs: Any) -> Any:
        return SimpleNamespace(
            model=model,
            choices=[
                SimpleNamespace(message=SimpleNamespace(role="assistant", content=response_text))
            ],
            usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5),
        )

    monkeypatch.setattr("recally.llm.litellm.completion", fake_completion)
    monkeypatch.setattr("recally.llm.litellm.completion_cost", lambda **kwargs: 0.0)

    from recally.scheduling import learner

    # No stub: the default registry resolves the real learner/default variant.
    container = make_container(LEARNER_MIN_REVIEWS=5)
    _seed_reviews(container, 5)

    learner.generate_guidance(container)

    with container.session() as session:
        rows = list(session.scalars(select(LlmCall)).all())
    assert len(rows) == 1, f"expected exactly one llm_calls row, got {len(rows)}"
    row = rows[0]
    assert row.agent == "learner/default"
    assert row.ingest_run_id is None
    assert row.unit_id is None


# --- POST /jobs/run wiring (retires 5a's 501 placeholder) ------------------------


@pytest.fixture
def api_container() -> Iterator[Container]:
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
def client(api_container: Container, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    app = create_app()
    app.dependency_overrides[container_dependency] = lambda: api_container
    monkeypatch.setenv("RECALLY_API_KEY", TEST_API_KEY)
    get_settings.cache_clear()
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        get_settings.cache_clear()


def test_run_learner_no_longer_returns_501(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Step 6b-a retires the placeholder: `{"job": "learner"}` runs the guidance
    job. The job itself is mocked — its behaviour is the gate above; here only
    the wiring matters (same shape as the optimizer's retirement test)."""
    from recally.scheduling import learner

    calls: list[object] = []
    monkeypatch.setattr(learner, "generate_guidance", lambda container: calls.append(container))

    response = client.post(
        "/jobs/run", headers={"X-API-Key": TEST_API_KEY}, json={"job": "learner"}
    )

    assert response.status_code == 200, (
        f"learner returned {response.status_code}: the 501 placeholder survived"
    )
    assert len(calls) == 1, f"the learner job ran {len(calls)} times, expected exactly 1"
