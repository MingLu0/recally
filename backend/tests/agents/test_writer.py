"""The step 2d gate for the Card Writer default variant (docs/agents.md §3).

Every test stubs `ctx.llm` with a recording fake returning a recorded response; no
test touches a real LLM provider. The negative tests (`*_raises`, the no-critique
half of the revision test, the no-round/no-status assertions) were confirmed to fail
against the not-yet-written implementation before `agents/writer/default.py`
existed — that red output is pasted in the PR.
"""

import dataclasses
import inspect
import json
from pathlib import Path
from typing import Any

import pytest

from recally.agents.base import AgentContext, CardDraft, WriterRequest, WriterResult
from recally.agents.writer import default as writer_default
from recally.agents.writer.default import DefaultWriter, WriterResponseError
from recally.config import Settings

INGEST_RUN_ID = 7
GUIDANCE_VERSION = 3
GUIDANCE_TEXT = "Definition cards lapse 40% — prefer application questions."
TRUNCATED_TEXT = "A WAL frame is a record of the change, written to the san"

QA_CARD = {
    "type": "qa",
    "front": "Why does SQLite write a WAL frame before applying a change?",
    "back": "So the change can be replayed or rolled back after a crash.",
    "rationale": "Why-question over the unit's core mechanism.",
}
CLOZE_CARD = {
    "type": "cloze",
    "front": "A WAL frame is written to {{c1::the log}} before the change is applied.",
    "back": "the log",
    "rationale": "Single-deletion cloze on the ordering guarantee.",
}
TWO_CARD_RESPONSE = json.dumps({"cards": [QA_CARD, CLOZE_CARD]})


class FakeLlm:
    """Records every call and returns the recorded response text."""

    def __init__(self, response: str) -> None:
        self.response = response
        self.calls: list[dict[str, Any]] = []

    def __call__(self, messages: list[dict[str, Any]], **kwargs: Any) -> str:
        self.calls.append({"messages": messages, "kwargs": kwargs})
        return self.response


def _ctx(fake_llm: FakeLlm) -> AgentContext:
    return AgentContext(
        ingest_run_id=INGEST_RUN_ID,
        settings=Settings(RECALLY_API_KEY="test-key-not-a-real-secret"),
        llm=fake_llm,  # type: ignore[arg-type]
    )


def _request(**overrides: Any) -> WriterRequest:
    fields: dict[str, Any] = {
        "curated_text": "WAL frames record changes before they are applied.",
        "tags": ["sqlite", "durability"],
        "guidance_version": GUIDANCE_VERSION,
        "guidance": GUIDANCE_TEXT,
        "critique": None,
    }
    fields.update(overrides)
    return WriterRequest(**fields)


def _response_with(cards: list[dict[str, Any]]) -> str:
    return json.dumps({"cards": cards})


def _rendered_prompt(fake_llm: FakeLlm) -> str:
    """The single user message the Writer sent, concatenated for assertions."""
    assert len(fake_llm.calls) == 1
    return "\n".join(message["content"] for message in fake_llm.calls[0]["messages"])


def test_returns_one_to_three_cards() -> None:
    """A recorded two-card response returns two typed results."""
    fake_llm = FakeLlm(TWO_CARD_RESPONSE)
    result = DefaultWriter()(_request(), _ctx(fake_llm))

    assert isinstance(result, WriterResult)
    assert len(result.cards) == 2
    assert all(isinstance(card, CardDraft) for card in result.cards)
    assert result.cards[0].type == "qa"
    assert result.cards[1].type == "cloze"


def test_cards_are_stamped_with_guidance_version() -> None:
    """The `guidance_version` in the request appears on every returned card."""
    fake_llm = FakeLlm(TWO_CARD_RESPONSE)
    result = DefaultWriter()(_request(guidance_version=GUIDANCE_VERSION), _ctx(fake_llm))

    assert result.cards, "expected at least one card"
    for card in result.cards:
        assert card.guidance_version == GUIDANCE_VERSION


def test_guidance_text_reaches_the_prompt() -> None:
    """The rendered request contains the `writer_guidance` text it was given."""
    fake_llm = FakeLlm(TWO_CARD_RESPONSE)
    DefaultWriter()(_request(guidance=GUIDANCE_TEXT), _ctx(fake_llm))

    assert GUIDANCE_TEXT in _rendered_prompt(fake_llm)


def test_multi_deletion_cloze_raises() -> None:
    """A `cloze` whose `front` has two deletion markers raises.

    The single-deletion rule is enforced in Python: the prompt alone will not hold it.
    """
    multi_deletion = dict(
        CLOZE_CARD, front="The {{c1::WAL}} is written to {{c2::the log}} before commit."
    )
    fake_llm = FakeLlm(_response_with([multi_deletion]))

    with pytest.raises(WriterResponseError):
        DefaultWriter()(_request(), _ctx(fake_llm))


def test_single_deletion_cloze_accepted() -> None:
    """The one-marker case passes."""
    fake_llm = FakeLlm(_response_with([CLOZE_CARD]))
    result = DefaultWriter()(_request(), _ctx(fake_llm))

    assert len(result.cards) == 1
    assert result.cards[0].front.count("{{c1::") == 1


def test_invalid_card_type_raises() -> None:
    """`type: "truefalse"` raises at the agent boundary."""
    fake_llm = FakeLlm(_response_with([dict(QA_CARD, type="truefalse")]))

    with pytest.raises(WriterResponseError):
        DefaultWriter()(_request(), _ctx(fake_llm))


@pytest.mark.parametrize("card_count", [0, 4])
def test_zero_or_four_cards_raises(card_count: int) -> None:
    """Both bounds of the 1-3 card rule raise."""
    fake_llm = FakeLlm(_response_with([QA_CARD] * card_count))

    with pytest.raises(ValueError, match="card"):
        DefaultWriter()(_request(), _ctx(fake_llm))


def test_critique_is_included_on_a_revision_request() -> None:
    """A request carrying a critique renders it into the prompt; without one the
    prompt has no critique section."""
    critique = "Card 1 bundles two ideas; split the crash-replay guarantee out."

    with_critique = FakeLlm(TWO_CARD_RESPONSE)
    DefaultWriter()(_request(critique=critique), _ctx(with_critique))
    assert critique in _rendered_prompt(with_critique)

    without_critique = FakeLlm(TWO_CARD_RESPONSE)
    DefaultWriter()(_request(critique=None), _ctx(without_critique))
    prompt = _rendered_prompt(without_critique)
    assert critique not in prompt
    assert "Critic feedback" not in prompt


def test_writer_does_not_count_rounds() -> None:
    """The result carries no round number and no status; the agent passes no round
    to the LLM call and keeps no state between calls (hard rule 9 is structural)."""
    for result_type in (WriterResult, CardDraft):
        field_names = {field.name for field in dataclasses.fields(result_type)}
        assert "round" not in field_names
        assert "status" not in field_names

    fake_llm = FakeLlm(TWO_CARD_RESPONSE)
    writer = DefaultWriter()
    writer(_request(), _ctx(fake_llm))
    llm_kwargs = fake_llm.calls[0]["kwargs"]
    assert "round" not in llm_kwargs or llm_kwargs["round"] is None

    writer(_request(), _ctx(fake_llm))
    assert len(fake_llm.calls) == 2
    assert not hasattr(writer, "round")


def test_truncated_source_is_not_extended() -> None:
    """The clipped text reaches the prompt verbatim, alongside an instruction
    against reconstructing the missing words (hard rule 7)."""
    fake_llm = FakeLlm(TWO_CARD_RESPONSE)
    DefaultWriter()(_request(curated_text=TRUNCATED_TEXT), _ctx(fake_llm))

    prompt = _rendered_prompt(fake_llm)
    assert TRUNCATED_TEXT in prompt
    assert "never reconstruct" in prompt.lower()


def test_prompt_is_loaded_from_file() -> None:
    """The prompt text comes from `prompts/writer.md`, not an inline literal (ADR-003)."""
    prompt_file = Path(writer_default.__file__).parent / "prompts" / "writer.md"
    file_text = prompt_file.read_text()
    distinctive_line = next(
        line for line in file_text.splitlines() if "Minimum information" in line
    )

    fake_llm = FakeLlm(TWO_CARD_RESPONSE)
    DefaultWriter()(_request(), _ctx(fake_llm))

    assert distinctive_line in _rendered_prompt(fake_llm)
    assert distinctive_line not in inspect.getsource(writer_default)


def test_writer_llm_call_uses_writer_model_and_agent_name() -> None:
    """The call is traced as `writer/default` on the writer model tier (ADR-007)."""
    settings = Settings(RECALLY_API_KEY="test-key-not-a-real-secret")
    fake_llm = FakeLlm(TWO_CARD_RESPONSE)
    ctx = AgentContext(ingest_run_id=INGEST_RUN_ID, settings=settings, llm=fake_llm)  # type: ignore[arg-type]

    DefaultWriter()(_request(), ctx)

    kwargs = fake_llm.calls[0]["kwargs"]
    assert kwargs["model"] == settings.llm_model_writer
    assert kwargs["agent"] == "writer/default"
    assert kwargs["ingest_run_id"] == INGEST_RUN_ID
