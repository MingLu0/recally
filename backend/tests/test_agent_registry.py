"""The step 2b registry gate: (role, variant) resolution driven by `AGENT_*` env vars.

Variants register under a `(role, variant)` key and the active variant per role comes
from `AGENT_CURATOR` / `AGENT_WRITER` / `AGENT_CRITIC` / `AGENT_LEARNER` (default
`default`, ADR-007). An unknown configured variant must fail at container build —
startup — not at the first LLM call half a chapter into a run.

No test touches a real LLM provider; the registered "implementations" here are
sentinel objects, because this ticket ships no agent implementations.
"""

import dataclasses
from collections.abc import Iterator
from typing import get_args, get_type_hints

import pytest
from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from recally.agents.base import (
    AgentContext,
    CardDraft,
    CardVerdict,
    CriticResult,
    CuratedUnitDraft,
    CuratorResult,
    LearnerResult,
    WriterResult,
)
from recally.agents.registry import AgentRegistry, UnknownAgentVariantError
from recally.config import Settings
from recally.container import Container
from recally.llm import LlmCaller
from recally.models import Base

ROLES = ("curator", "writer", "critic", "learner")
CARD_STATUSES = {"pending_review", "approved", "needs_human", "rejected"}


def _settings() -> Settings:
    return Settings(RECALLY_API_KEY="test-key-not-a-real-secret")


@pytest.fixture
def registry() -> AgentRegistry:
    """A fresh registry per test; the module-global one stays untouched."""
    return AgentRegistry()


@pytest.fixture
def engine() -> Iterator[Engine]:
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    try:
        yield engine
    finally:
        engine.dispose()


def test_resolves_default_variant(registry: AgentRegistry, monkeypatch: pytest.MonkeyPatch) -> None:
    """With no `AGENT_*` set, each role resolves to its `default` variant."""
    for role in ROLES:
        monkeypatch.delenv(f"AGENT_{role.upper()}", raising=False)
    registered = {role: object() for role in ROLES}
    for role, implementation in registered.items():
        registry.register(role, "default", implementation)

    for role, implementation in registered.items():
        assert registry.resolve(role, _settings()) is implementation


def test_env_var_selects_variant(registry: AgentRegistry, monkeypatch: pytest.MonkeyPatch) -> None:
    """`AGENT_WRITER=strict` swaps the Writer with no change to any calling module."""
    default_writer = object()
    strict_writer = object()
    registry.register("writer", "default", default_writer)
    registry.register("writer", "strict", strict_writer)
    monkeypatch.setenv("AGENT_WRITER", "strict")

    assert registry.resolve("writer", _settings()) is strict_writer


def test_unknown_variant_fails_at_startup(
    registry: AgentRegistry, engine: Engine, monkeypatch: pytest.MonkeyPatch
) -> None:
    """`AGENT_CRITIC=nope` raises on container build.

    The message must name the role and the registered variants, so a typo points at
    the fix instead of surfacing as a missing key mid-run.
    """
    registry.register("critic", "default", object())
    monkeypatch.setenv("AGENT_CRITIC", "nope")

    with pytest.raises(UnknownAgentVariantError) as excinfo:
        Container(_settings(), engine=engine, registry=registry)

    message = str(excinfo.value)
    assert "critic" in message
    assert "nope" in message
    assert "default" in message


def test_agent_context_has_no_session(engine: Engine) -> None:
    """`AgentContext` exposes no attribute that is a Session or a session factory.

    This is the executable form of hard rule 1: with no database handle in the
    context, no agent implementation can approve a card, flip `processed`, or extend
    the Writer ⇄ Critic loop — those are all the runner's writes (ADR-007).
    """
    caller = LlmCaller(sessionmaker(bind=engine), log_payloads=False)
    ctx = AgentContext(ingest_run_id=1, settings=_settings(), llm=caller)

    for field in dataclasses.fields(AgentContext):
        assert "session" not in field.name.lower()
        value = getattr(ctx, field.name)
        assert not isinstance(value, Session)
        assert not isinstance(value, sessionmaker)


def test_result_types_carry_no_card_status() -> None:
    """No result field accepts `approved`/`pending_review`; verdicts are accept|revise|reject.

    Agents return verdicts and decisions; `cards.status` transitions belong to the
    runner and the human queue, so the status vocabulary must be inexpressible in the
    result types (hard rules 1 and 9).
    """
    result_types = (
        CuratorResult,
        CuratedUnitDraft,
        WriterResult,
        CardDraft,
        CriticResult,
        CardVerdict,
        LearnerResult,
    )
    for result_type in result_types:
        hints = get_type_hints(result_type)
        assert "status" not in hints, f"{result_type.__name__} carries a status field"
        for hint in hints.values():
            literal_strings = {arg for arg in get_args(hint) if isinstance(arg, str)}
            assert CARD_STATUSES.isdisjoint(literal_strings), (
                f"{result_type.__name__} can express a cards.status value"
            )

    verdict_values = set(get_args(get_type_hints(CardVerdict)["verdict"]))
    assert verdict_values == {"accept", "revise", "reject"}
    decision_values = set(get_args(get_type_hints(CuratedUnitDraft)["decision"]))
    assert decision_values == {"keep", "drop"}
