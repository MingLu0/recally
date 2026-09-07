"""The step 2e gate for the default Critic variant (docs/agents.md, "4. Critic Agent").

Every test runs the real `LlmCaller` against an in-memory database with
`litellm.completion` monkeypatched to a recorded response; no test touches a
provider. The recorded responses exercise the agent-boundary validation: one
verdict per request card, `accept|revise|reject` only, and a non-empty critique
on `revise`/`reject`.
"""

import inspect
import json
from collections.abc import Iterator
from pathlib import Path
from types import SimpleNamespace
from typing import Any, get_args, get_type_hints

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from recally.agents.base import AgentContext, CardDraft, CardVerdict, CriticRequest, CriticResult
from recally.agents.critic import default as critic_module
from recally.agents.critic.default import DefaultCritic
from recally.config import Settings
from recally.llm import LlmCaller
from recally.models import Base

CARD_STATUSES = {"pending_review", "approved", "needs_human", "rejected"}

SOURCE_TEXT = (
    "Spaced repetition works because each review is scheduled just before the "
    "predicted moment of forgetting, so the memory is reconsolidated at peak "
    "retrieval effort."
)
CARD_ONE = CardDraft(
    type="qa",
    front="Why does spaced repetition schedule a review just before forgetting?",
    back="Retrieval at peak effort reconsolidates the memory.",
    rationale="Tests the mechanism, not the definition.",
    guidance_version=None,
)
CARD_TWO = CardDraft(
    type="cloze",
    front="Spaced repetition schedules each review just before the moment of {{c1::forgetting}}.",
    back="",
    rationale="Key term as a single deletion.",
    guidance_version=None,
)


class RecordingLlm:
    """Builds `LlmCaller` fixtures whose provider call returns a recorded response.

    `respond` sets the JSON text the next completion returns; `sent_messages`
    captures every message list the agent sent, so prompt-content assertions read
    what the model would actually receive.
    """

    def __init__(self, monkeypatch: pytest.MonkeyPatch, session_factory: sessionmaker[Session]):
        self.sent_messages: list[list[dict[str, Any]]] = []
        self._next_response = "[]"

        def fake_completion(*, model: str, messages: list[dict[str, Any]], **kwargs: Any) -> Any:
            self.sent_messages.append(messages)
            return SimpleNamespace(
                choices=[
                    SimpleNamespace(
                        message=SimpleNamespace(role="assistant", content=self._next_response)
                    )
                ],
                usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5),
            )

        monkeypatch.setattr("recally.llm.litellm.completion", fake_completion)
        monkeypatch.setattr("recally.llm.litellm.completion_cost", lambda **kwargs: 0.0)
        self.caller = LlmCaller(session_factory, log_payloads=True)

    def respond(self, verdicts: list[dict[str, str]]) -> None:
        self._next_response = json.dumps(verdicts)

    def last_prompt(self) -> str:
        assert self.sent_messages, "the Critic made no LLM call"
        return "\n".join(str(message["content"]) for message in self.sent_messages[-1])


@pytest.fixture
def session_factory() -> Iterator[sessionmaker[Session]]:
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    yield sessionmaker(bind=engine, expire_on_commit=False)


@pytest.fixture
def recording_llm(
    monkeypatch: pytest.MonkeyPatch, session_factory: sessionmaker[Session]
) -> RecordingLlm:
    return RecordingLlm(monkeypatch, session_factory)


@pytest.fixture
def ctx(recording_llm: RecordingLlm) -> AgentContext:
    return AgentContext(
        ingest_run_id=None,
        settings=Settings(RECALLY_API_KEY="test-key-not-a-real-secret"),
        llm=recording_llm.caller,
    )


def _request(source_truncated: bool = False) -> CriticRequest:
    return CriticRequest(
        cards=[CARD_ONE, CARD_TWO],
        source_text=SOURCE_TEXT,
        source_truncated=source_truncated,
    )


def test_verdict_per_card(recording_llm: RecordingLlm, ctx: AgentContext) -> None:
    """A two-card request returns exactly two verdicts, in request order."""
    recording_llm.respond(
        [
            {"verdict": "accept", "critique": ""},
            {"verdict": "revise", "critique": "Cloze front gives away the answer."},
        ]
    )

    result = DefaultCritic()(_request(), ctx)

    assert isinstance(result, CriticResult)
    assert len(result.verdicts) == 2
    assert [verdict.verdict for verdict in result.verdicts] == ["accept", "revise"]
    assert result.verdicts[1].critique == "Cloze front gives away the answer."


def test_invalid_verdict_raises(recording_llm: RecordingLlm, ctx: AgentContext) -> None:
    """`verdict: "maybe"` raises at the agent boundary (backend.md, the agent contract)."""
    recording_llm.respond(
        [
            {"verdict": "maybe", "critique": "unsure"},
            {"verdict": "accept", "critique": ""},
        ]
    )

    with pytest.raises(ValueError, match="maybe"):
        DefaultCritic()(_request(), ctx)


def test_verdict_count_mismatch_raises(recording_llm: RecordingLlm, ctx: AgentContext) -> None:
    """One verdict for a two-card request raises — verdicts must cover every card."""
    recording_llm.respond([{"verdict": "accept", "critique": ""}])

    with pytest.raises(ValueError, match="2"):
        DefaultCritic()(_request(), ctx)


def test_revise_requires_a_critique(recording_llm: RecordingLlm, ctx: AgentContext) -> None:
    """`revise` with an empty critique is useless to the Writer and must raise."""
    recording_llm.respond(
        [
            {"verdict": "revise", "critique": "  "},
            {"verdict": "accept", "critique": ""},
        ]
    )

    with pytest.raises(ValueError, match="critique"):
        DefaultCritic()(_request(), ctx)


def test_reject_requires_a_critique(recording_llm: RecordingLlm, ctx: AgentContext) -> None:
    """`reject` with an empty critique gives the runner nothing for `status_reason`."""
    recording_llm.respond(
        [
            {"verdict": "accept", "critique": ""},
            {"verdict": "reject", "critique": ""},
        ]
    )

    with pytest.raises(ValueError, match="critique"):
        DefaultCritic()(_request(), ctx)


def test_accept_needs_no_critique(recording_llm: RecordingLlm, ctx: AgentContext) -> None:
    """`accept` with an empty critique is a complete verdict."""
    recording_llm.respond(
        [
            {"verdict": "accept", "critique": ""},
            {"verdict": "accept"},
        ]
    )

    result = DefaultCritic()(_request(), ctx)

    assert [verdict.verdict for verdict in result.verdicts] == ["accept", "accept"]
    assert all(verdict.critique == "" for verdict in result.verdicts)


def test_result_carries_no_card_status() -> None:
    """No `CriticResult`/`CardVerdict` field can hold a `cards.status` value.

    This is the executable form of hard rule 1: the Critic returns a verdict,
    never a status; mapping verdicts to statuses is the runner's job (#35).
    """
    for result_type in (CriticResult, CardVerdict):
        hints = get_type_hints(result_type)
        assert "status" not in hints, f"{result_type.__name__} carries a status field"
        for hint in hints.values():
            literal_strings = {arg for arg in get_args(hint) if isinstance(arg, str)}
            assert CARD_STATUSES.isdisjoint(literal_strings), (
                f"{result_type.__name__} can express a cards.status value"
            )


def test_source_highlight_reaches_the_prompt(
    recording_llm: RecordingLlm, ctx: AgentContext
) -> None:
    """The rendered request contains the source text the verdict is judged against."""
    recording_llm.respond(
        [
            {"verdict": "accept", "critique": ""},
            {"verdict": "accept", "critique": ""},
        ]
    )

    DefaultCritic()(_request(), ctx)

    assert SOURCE_TEXT in recording_llm.last_prompt()


def test_truncated_source_is_marked_in_the_prompt(
    recording_llm: RecordingLlm, ctx: AgentContext
) -> None:
    """A clipped source is marked as clipped, so fidelity is judged against the
    partial text (hard rule 7); an untruncated request carries no such marker."""
    recording_llm.respond(
        [
            {"verdict": "accept", "critique": ""},
            {"verdict": "accept", "critique": ""},
        ]
    )

    DefaultCritic()(_request(source_truncated=True), ctx)
    truncated_prompt = recording_llm.last_prompt()
    assert "clipped" in truncated_prompt.lower() or "truncated" in truncated_prompt.lower()

    DefaultCritic()(_request(source_truncated=False), ctx)
    untruncated_prompt = recording_llm.last_prompt()
    assert "clipped" not in untruncated_prompt.lower()
    assert "truncated" not in untruncated_prompt.lower()


def test_prompt_is_loaded_from_file() -> None:
    """The prompt lives in `prompts/critic.md` on disk (ADR-003), not inline."""
    prompt_path = Path(critic_module.__file__).parent / "prompts" / "critic.md"
    assert prompt_path.is_file(), "agents/critic/prompts/critic.md is missing"
    prompt_text = prompt_path.read_text(encoding="utf-8")
    assert len(prompt_text) > 500, "the prompt file should hold the full prompt"

    module_source = inspect.getsource(critic_module)
    for line in prompt_text.splitlines():
        stripped = line.strip()
        if len(stripped) > 40:
            assert stripped not in module_source, (
                f"prompt text is inline in default.py: {stripped[:60]!r}"
            )
