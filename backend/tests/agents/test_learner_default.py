"""Step 6b-a gate for the default Learner variant (docs/agents.md, "7. Learner
Agent", stage B).

Every test stubs `ctx.llm` with a recording fake returning a recorded response; no
test touches a real LLM provider (same shape as tests/agents/test_writer.py). The
negative test (`test_learner_raises_on_malformed_response`) was confirmed to fail
against the not-yet-written implementation before `agents/learner/default.py`
existed — that red output is pasted in the PR under `## TDD evidence`.

`recally.agents.learner.default` is imported inside tests, not at module scope: the
module does not exist until the implementation lands, and a late import turns
"not written yet" into one red test per behaviour instead of a collection error
for the whole file.
"""

import dataclasses
import inspect
import json
from pathlib import Path
from typing import Any

import pytest

from recally.agents.base import AgentContext, LearnerRequest, LearnerResult
from recally.config import Settings

GUIDANCE_TEXT = "Definition cards lapse at 40% — prefer application questions."
VALID_RESPONSE = json.dumps(
    {
        "guidance": GUIDANCE_TEXT,
        "rationale": "QA-definition cards lapse at twice the rate of application cards.",
        "leech_card_ids": [3, 7],
    }
)


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
        ingest_run_id=None,  # the nightly Learner serves no ingest run
        settings=Settings(RECALLY_API_KEY="test-key-not-a-real-secret"),
        llm=fake_llm,  # type: ignore[arg-type]
    )


def _request() -> LearnerRequest:
    return LearnerRequest(
        review_aggregates={
            "review_count": 240,
            "lapse_rate_by_card_type": {"qa": {"reviews": 240, "lapses": 60, "lapse_rate": 0.25}},
        },
        current_guidance_version=1,
        current_guidance="v1: prefer application questions over definitions.",
    )


def _rendered_prompt(fake_llm: FakeLlm) -> str:
    """The single user message the Learner sent, concatenated for assertions."""
    assert len(fake_llm.calls) == 1
    return "\n".join(message["content"] for message in fake_llm.calls[0]["messages"])


def test_learner_parses_a_valid_response_into_learner_result() -> None:
    """guidance, rationale and `leech_card_ids` round-trip from a recorded response."""
    from recally.agents.learner.default import DefaultLearner

    fake_llm = FakeLlm(VALID_RESPONSE)

    result = DefaultLearner()(_request(), _ctx(fake_llm))

    assert isinstance(result, LearnerResult)
    assert result.guidance == GUIDANCE_TEXT
    assert result.rationale == "QA-definition cards lapse at twice the rate of application cards."
    assert result.leech_card_ids == [3, 7]


def test_learner_raises_on_malformed_response() -> None:
    """Invalid content raises at the agent boundary (docs/backend.md, "The agent
    contract") rather than returning a half-built result the job would persist."""
    from recally.agents.learner.default import DefaultLearner

    fake_llm = FakeLlm("this is not JSON, and no fence will save it")

    with pytest.raises(ValueError):
        DefaultLearner()(_request(), _ctx(fake_llm))


def test_learner_holds_no_db_session() -> None:
    """The implementation takes only `(request, ctx)`, and `AgentContext` carries
    no session (docs/backend.md, "The agent contract"): every write is the job's."""
    from recally.agents.learner.default import DefaultLearner

    parameters = list(inspect.signature(DefaultLearner.__call__).parameters)
    assert parameters == ["self", "request", "ctx"]

    context_fields = {field.name for field in dataclasses.fields(AgentContext)}
    assert context_fields == {"ingest_run_id", "settings", "llm"}
    assert not any("session" in name or "db" in name for name in context_fields)


def test_learner_prompt_is_loaded_from_a_file() -> None:
    """The prompt text comes from `prompts/learner.md`, not an inline literal
    (ADR-003): a distinctive line from the file reaches the model and appears
    nowhere in the module source."""
    from recally.agents.learner import default as learner_module
    from recally.agents.learner.default import DefaultLearner

    prompt_file = Path(learner_module.__file__).parent / "prompts" / "learner.md"
    file_text = prompt_file.read_text(encoding="utf-8")
    distinctive_line = next(line for line in file_text.splitlines() if "writer_guidance" in line)

    fake_llm = FakeLlm(VALID_RESPONSE)
    DefaultLearner()(_request(), _ctx(fake_llm))

    assert distinctive_line in _rendered_prompt(fake_llm)
    assert distinctive_line not in inspect.getsource(learner_module)
