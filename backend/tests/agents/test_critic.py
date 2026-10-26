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
from recally.agents.critic.default import DefaultCritic, _render_prompt
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


# --- The #251 gate: judge the source before the card ------------------------
# On the #248 baseline the Critic scored missed-keep 0/48 with false-keep
# 20/23, and 13 of the 20 false-keeps were single cards on units where the
# human binned everything. The cause was the prompt: nothing asked whether the
# source can support a card before the card itself was judged. These tests pin
# the fixed prompt's contract, asserting on the *rendered* prompt via the
# module's own render path, so a placeholder that stops substituting fails too.


def _rendered_gate_prompt(source_truncated: bool = False) -> str:
    request = CriticRequest(
        cards=[CARD_ONE], source_text=SOURCE_TEXT, source_truncated=source_truncated
    )
    return _render_prompt(request)


def test_critic_prompt_asks_about_source_worthiness_first() -> None:
    """The rendered prompt directs the Critic to judge whether the source can
    support a card before judging the card itself (#251)."""
    prompt = _rendered_gate_prompt().lower()
    assert "can this source support" in prompt
    assert prompt.index("can this source support") < prompt.index("atomicity")


def test_critic_prompt_names_the_measured_reject_shapes() -> None:
    """The rubric names the shapes of the 12 pure-reject units — sentence
    fragments, connective prose and over-generic claims — and quotes the
    human's recorded reject reasons, the only written record of the standard."""
    prompt = _rendered_gate_prompt().lower()
    assert "sentence fragment" in prompt
    assert "connective prose" in prompt or "chapter transition" in prompt
    assert "true of any system" in prompt
    for reason in ("too trivial", "too abstract", "too isolated", "does not make much sense"):
        assert reason in prompt


def test_critic_prompt_routes_specific_fragments_to_revise_not_reject() -> None:
    """The source shapes are suspicion triggers, not verdicts: a fragment or
    generic-sounding source that still names something specific (a list, a
    named pair, a concrete mechanism) routes to `revise`; `reject` is reserved
    for sources with no nameable fact (#252 review — the v1 wording treated the
    shapes as sufficient for reject and cost 6 missed-keeps)."""
    prompt = _rendered_gate_prompt().lower()
    assert "names something specific" in prompt
    assert "no nameable fact" in prompt
    specific_idx = prompt.index("names something specific")
    assert "revise" in prompt[max(0, specific_idx - 200) : specific_idx + 600]
    empty_idx = prompt.index("no nameable fact")
    assert "reject" in prompt[max(0, empty_idx - 200) : empty_idx + 600]


def test_critic_prompt_keeps_all_five_criteria() -> None:
    """Atomicity, unambiguity, self-containedness, non-triviality and factual
    fidelity all survive the edit (regression guard)."""
    prompt = _rendered_gate_prompt().lower()
    for criterion in (
        "atomicity",
        "unambiguity",
        "self-containedness",
        "non-triviality",
        "factual fidelity",
    ):
        assert criterion in prompt


def test_critic_prompt_keeps_reject_distinct_from_revise() -> None:
    """`reject` stays for the unsalvageable, not the bad: a card that could be
    rewritten is `revise`. The distinction decides whether a human ever sees
    the card, so an edit that blurs it is a regression (regression guard)."""
    prompt = _rendered_gate_prompt().lower()
    assert "unsalvageable" in prompt
    assert "could be rewritten" in prompt


def test_critic_prompt_renders_all_placeholders() -> None:
    """`$source_text` and `$cards` substitute, the truncation block appears only
    for a clipped source, and no stray `$` survives rendering."""
    rendered = _rendered_gate_prompt()
    assert SOURCE_TEXT in rendered
    assert CARD_ONE.front in rendered
    assert "$" not in rendered
    assert "TRUNCATION_NOTE" not in rendered

    truncated = _rendered_gate_prompt(source_truncated=True)
    assert SOURCE_TEXT in truncated
    assert "$" not in truncated
    assert "TRUNCATION_NOTE" not in truncated
    assert "clipped" in truncated.lower()


# --- The #280 gate: revise advice must not introduce new violations ----------
# In the cycle-3 traces the Critic's own revise advice caused defects: card 166
# was told to bundle two capabilities into one card and was then flagged for
# exactly that bundling; card 110 was told to trim context the question needed.
# The prompt constrained verdicts but not the advice behind a `revise`.


def _revise_advice_section(prompt: str) -> str:
    """The body of the rendered prompt's `## Revise advice` section, lowercased
    with whitespace collapsed so a reflowed line break cannot split a phrase."""
    marker = "## revise advice"
    lowered = prompt.lower()
    assert marker in lowered, "the rendered prompt has no `## Revise advice` section"
    section_start = lowered.index(marker) + len(marker)
    next_heading = lowered.find("\n## ", section_start)
    section = lowered[section_start:] if next_heading == -1 else lowered[section_start:next_heading]
    return " ".join(section.split())


def test_critic_prompt_constrains_revise_advice() -> None:
    """The rendered prompt states that a revise suggestion must not introduce a
    new violation of any check (#280)."""
    advice_section = _revise_advice_section(_rendered_gate_prompt())
    assert "must not introduce a new violation" in advice_section
    assert "revise" in advice_section


def test_critic_prompt_advice_preserves_atomicity_and_context() -> None:
    """The advice rule explicitly forbids advising two-recall bundling, stripping
    context the question needs, and wording the source does not support (#280)."""
    advice_section = _revise_advice_section(_rendered_gate_prompt())
    assert "never advise bundling two recalls" in advice_section
    assert "never advise stripping" in advice_section
    assert "context" in advice_section
    assert "the source does not support" in advice_section


def test_critic_prompt_keeps_five_criteria_and_verdict_rules() -> None:
    """The five checks and the unsalvageable-vs-rewrite distinction survive the
    advice rule, which sits alongside them rather than replacing them (#280)."""
    prompt = _rendered_gate_prompt()
    lowered = prompt.lower()
    for criterion in (
        "atomicity",
        "unambiguity",
        "self-containedness",
        "non-triviality",
        "factual fidelity",
    ):
        assert criterion in lowered
    assert "unsalvageable" in lowered
    assert "could be rewritten" in lowered
    for heading in ("## The five checks", "## Verdicts"):
        assert heading in prompt


# --- The #306 gate: the Critic sees its previous critique ---------------------
# In the cycle-4 traces the Critic flagged wording it had suggested itself in 7
# of 7 multi-critique chains: each round judged the revised card with no memory
# of the critique that produced it. From round 2 the request carries that
# critique, and the prompt tells the Critic to keep its advice consistent.

PREVIOUS_CRITIQUE = (
    "Not self-contained: replace 'it' with 'spaced repetition' and ask about the "
    "timing of the review."
)


def _rendered_revision_round_prompt() -> str:
    request = CriticRequest(
        cards=[CARD_ONE], source_text=SOURCE_TEXT, previous_critique=PREVIOUS_CRITIQUE
    )
    return _render_prompt(request)


def test_previous_critique_reaches_the_prompt_on_a_revision_round(
    recording_llm: RecordingLlm, ctx: AgentContext
) -> None:
    """With `previous_critique` set, the prompt the model receives contains it."""
    recording_llm.respond([{"verdict": "accept", "critique": ""}])
    request = CriticRequest(
        cards=[CARD_ONE], source_text=SOURCE_TEXT, previous_critique=PREVIOUS_CRITIQUE
    )

    DefaultCritic()(request, ctx)

    assert PREVIOUS_CRITIQUE in recording_llm.last_prompt()


def test_critic_prompt_forbids_faulting_its_own_advice() -> None:
    """The revision-round prompt says not to fault wording the Critic itself
    suggested unless the source contradicts it, and to own a wrong earlier
    suggestion rather than raise it as a new defect."""
    prompt = " ".join(_rendered_revision_round_prompt().lower().split())
    assert "do not fault wording you suggested" in prompt
    assert "unless the source contradicts it" in prompt
    assert "say so explicitly" in prompt


def test_round_one_prompt_has_no_previous_critique_block() -> None:
    """With `previous_critique=None` the block is stripped: no previous-critique
    text, no leftover markers and no unsubstituted `$` (preservation)."""
    prompt = _rendered_gate_prompt()
    assert "PREVIOUS_CRITIQUE" not in prompt
    assert "previous critique" not in prompt.lower()
    assert "do not fault wording you suggested" not in prompt.lower()
    assert "$" not in prompt
