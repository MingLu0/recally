"""The step 2a gate for `llm.py`, the sole LiteLLM wrapper (hard rule 3, ADR-003).

Every test monkeypatches `litellm.completion`; no test touches a real provider.
The fake completion returns a `SimpleNamespace` shaped like the bits of LiteLLM's
`ModelResponse` the wrapper reads (`choices[0].message.content`, `usage`), and
`litellm.completion_cost` is patched per test so costs are deterministic.
"""

from collections.abc import Iterator
from types import SimpleNamespace
from typing import Any

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from recally.llm import LlmCaller
from recally.models import Base, Card, CuratedUnit, IngestRun, LlmCall

MESSAGES = [{"role": "user", "content": "Summarise this highlight."}]
MODEL = "writer-model-x"
AGENT = "writer/default"
FAKE_COMPLETION = "fake completion text"


@pytest.fixture
def session_factory() -> Iterator[sessionmaker[Session]]:
    """A session factory over one shared in-memory database.

    `StaticPool` keeps a single connection so the wrapper's own session (it opens one
    per call to write the `llm_calls` row) and the test's assertion session see the
    same database.
    """
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    yield sessionmaker(bind=engine, expire_on_commit=False)


@pytest.fixture
def fake_completion(monkeypatch: pytest.MonkeyPatch) -> None:
    """Replace the provider call; default `completion_cost` is zero.

    Tests that care about cost re-patch `completion_cost` themselves.
    """

    def fake(*, model: str, messages: list[dict[str, Any]], **kwargs: Any) -> Any:
        return SimpleNamespace(
            model=model,
            choices=[
                SimpleNamespace(message=SimpleNamespace(role="assistant", content=FAKE_COMPLETION))
            ],
            usage=SimpleNamespace(prompt_tokens=11, completion_tokens=7),
        )

    monkeypatch.setattr("recally.llm.litellm.completion", fake)
    monkeypatch.setattr("recally.llm.litellm.completion_cost", lambda **kwargs: 0.0)


def _only_row(session_factory: sessionmaker[Session]) -> LlmCall:
    with session_factory() as session:
        rows = session.query(LlmCall).all()
        assert len(rows) == 1, f"expected exactly one llm_calls row, got {len(rows)}"
        return rows[0]


def test_call_writes_llm_call_row(
    session_factory: sessionmaker[Session], fake_completion: None
) -> None:
    caller = LlmCaller(session_factory, log_payloads=True)
    result = caller(MESSAGES, model=MODEL, agent=AGENT)

    assert result == FAKE_COMPLETION
    row = _only_row(session_factory)
    assert row.request == MESSAGES
    assert row.response is not None
    assert row.model == MODEL
    assert row.latency_ms is not None and row.latency_ms >= 0


def test_correlation_fields_are_persisted(
    session_factory: sessionmaker[Session], fake_completion: None
) -> None:
    """unit_id/card_id/round come from the caller's arguments, not the agent."""
    with session_factory() as session:
        run = IngestRun(filename="export.csv")
        session.add(run)
        session.flush()
        unit = CuratedUnit(ingest_run_id=run.id, curated_text="text", decision="keep")
        session.add(unit)
        session.flush()
        card = Card(
            unit_id=unit.id,
            type="qa",
            front="f",
            back="b",
            original_front="f",
            original_back="b",
            status="pending_review",
            model=MODEL,
        )
        session.add(card)
        session.commit()

        caller = LlmCaller(session_factory, log_payloads=True)
        caller(
            MESSAGES,
            model=MODEL,
            agent=AGENT,
            ingest_run_id=run.id,
            unit_id=unit.id,
            card_id=card.id,
            round=2,
        )

    row = _only_row(session_factory)
    assert row.agent == AGENT
    assert row.ingest_run_id == run.id
    assert row.unit_id == unit.id
    assert row.card_id == card.id
    assert row.round == 2


def test_agent_field_is_role_slash_variant(
    session_factory: sessionmaker[Session], fake_completion: None
) -> None:
    """ADR-007: the trace names the implementation (`writer/default`), not the role."""
    caller = LlmCaller(session_factory, log_payloads=True)
    caller(MESSAGES, model=MODEL, agent="writer/default")

    row = _only_row(session_factory)
    assert row.agent == "writer/default"


def test_cost_is_int_microusd(
    session_factory: sessionmaker[Session], fake_completion: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """$0.0001842 is stored as 184 integer micro-USD — no cents, no float dollars."""
    monkeypatch.setattr("recally.llm.litellm.completion_cost", lambda **kwargs: 0.0001842)

    caller = LlmCaller(session_factory, log_payloads=True)
    caller(MESSAGES, model=MODEL, agent=AGENT)

    row = _only_row(session_factory)
    assert row.cost_microusd == 184
    assert type(row.cost_microusd) is int


def test_payloads_suppressed_when_flag_off(
    session_factory: sessionmaker[Session], fake_completion: None
) -> None:
    """LLM_LOG_PAYLOADS=false nulls request/response; every other column is kept."""
    caller = LlmCaller(session_factory, log_payloads=False)
    result = caller(MESSAGES, model=MODEL, agent=AGENT)

    assert result == FAKE_COMPLETION
    row = _only_row(session_factory)
    assert row.request is None
    assert row.response is None
    assert row.agent == AGENT
    assert row.model == MODEL
    assert row.input_tokens == 11
    assert row.output_tokens == 7
    assert row.latency_ms is not None and row.latency_ms >= 0


def test_ollama_cost_is_zero(
    session_factory: sessionmaker[Session], fake_completion: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A local Ollama model records cost 0 and never asks LiteLLM to price it."""
    monkeypatch.setattr(
        "recally.llm.litellm.completion_cost",
        lambda **kwargs: pytest.fail("completion_cost must not be called for Ollama"),
    )

    caller = LlmCaller(session_factory, log_payloads=True)
    caller(MESSAGES, model="ollama/llama3.1", agent=AGENT)

    row = _only_row(session_factory)
    assert row.cost_microusd == 0


def test_provider_error_propagates_and_still_logs(
    session_factory: sessionmaker[Session], monkeypatch: pytest.MonkeyPatch
) -> None:
    """A provider exception propagates to the caller, and the call is still logged.

    The wrapper writes the `llm_calls` row (response null, zero tokens/cost) before
    re-raising, so the trace survives; the runner's failure path then records the error
    on `ingest_runs.error` and the next run retries (docs/architecture.md, "Failure
    handling").
    """

    def failing(*, model: str, messages: list[dict[str, Any]], **kwargs: Any) -> Any:
        raise RuntimeError("provider down")

    monkeypatch.setattr("recally.llm.litellm.completion", failing)

    caller = LlmCaller(session_factory, log_payloads=True)
    with pytest.raises(RuntimeError, match="provider down"):
        caller(MESSAGES, model=MODEL, agent=AGENT, unit_id=1)

    row = _only_row(session_factory)
    assert row.request == MESSAGES
    assert row.response is None
    assert row.model == MODEL
    assert row.unit_id == 1
    assert row.input_tokens == 0
    assert row.output_tokens == 0
    assert row.cost_microusd == 0
    assert row.latency_ms is not None and row.latency_ms >= 0
