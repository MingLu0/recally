"""The #249 gate: the Writer prompt makes one card the default.

The eval baseline (#248) measured the fan-out this file guards against: 1.67
cards per unit, with the extra card on mixed-label units so often a weaker twin
of the keeper that the human binned it (false-keep 19/23). The fix is option 1
from the ticket — prompt guidance, not code — so every test here asserts on the
prompt as the LLM actually receives it, rendered through `DefaultWriter` with a
recording fake; no test touches a real LLM provider.

The two new-behaviour tests (`test_writer_prompt_states_the_one_card_default`,
`test_writer_prompt_names_the_redundant_sibling_cases`) were confirmed to fail
against the unedited `prompts/writer.md` before the prompt change was made —
that red output is pasted in the PR. The other two are regression guards that
must pass both before and after: the existing card rules must survive the edit,
and the template placeholders must still substitute.
"""

import json
from typing import Any

from recally.agents.base import AgentContext, WriterRequest
from recally.agents.writer.default import DefaultWriter
from recally.config import Settings

INGEST_RUN_ID = 7
GUIDANCE_TEXT = "Definition cards lapse 40% — prefer application questions."
CURATED_TEXT = "WAL frames record changes before they are applied."
CRITIQUE_TEXT = "Card 1 bundles two ideas; split the crash-replay guarantee out."

ONE_CARD_RESPONSE = json.dumps(
    {
        "cards": [
            {
                "type": "qa",
                "front": "Why does SQLite write a WAL frame before applying a change?",
                "back": "So the change can be replayed or rolled back after a crash.",
                "rationale": "Why-question over the unit's core mechanism.",
            }
        ]
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


def _rendered_prompt(**request_overrides: Any) -> str:
    """The single user message the Writer sends for a fully-populated request."""
    fields: dict[str, Any] = {
        "curated_text": CURATED_TEXT,
        "tags": ["sqlite", "durability"],
        "guidance_version": 3,
        "guidance": GUIDANCE_TEXT,
        "critique": CRITIQUE_TEXT,
    }
    fields.update(request_overrides)
    fake_llm = FakeLlm(ONE_CARD_RESPONSE)
    ctx = AgentContext(
        ingest_run_id=INGEST_RUN_ID,
        settings=Settings(RECALLY_API_KEY="test-key-not-a-real-secret"),
        llm=fake_llm,  # type: ignore[arg-type]
    )
    DefaultWriter()(WriterRequest(**fields), ctx)
    assert len(fake_llm.calls) == 1
    return "\n".join(message["content"] for message in fake_llm.calls[0]["messages"])


def test_writer_prompt_states_the_one_card_default() -> None:
    """The rendered prompt says a single card is the default, so the 1–3 range in
    `WriterResult` is filled only when the source earns it."""
    assert "one card is the default" in _rendered_prompt().lower()


def test_writer_prompt_names_the_redundant_sibling_cases() -> None:
    """The four disqualified second-card shapes from the ticket's evidence all
    appear: restatement in another format, a same-clause cloze, an answer-leaking
    stem, and "what" where "why" is available."""
    prompt = _rendered_prompt().lower()
    assert "restates the first" in prompt and "another format" in prompt
    assert "same clause" in prompt
    assert "stem contains its own answer" in prompt
    assert '"what" when "why" is available' in prompt


def test_writer_prompt_keeps_the_existing_card_rules() -> None:
    """The pre-#249 rules survive the edit: atomicity, self-containedness, the
    single cloze deletion and prefer-why/how over trivia."""
    prompt = _rendered_prompt()
    assert "Minimum information principle" in prompt
    assert "answerable without the book open" in prompt
    assert "single deletion" in prompt
    assert "{{c1::" in prompt
    assert 'Prefer "why" and "how" questions over trivia' in prompt


def test_writer_prompt_renders_all_placeholders() -> None:
    """`$guidance`, `$tags`, `$curated_text` and `$critique_section` all substitute
    and no stray `$` survives rendering."""
    prompt = _rendered_prompt()
    assert GUIDANCE_TEXT in prompt
    assert "sqlite, durability" in prompt
    assert CURATED_TEXT in prompt
    assert CRITIQUE_TEXT in prompt
    assert "$" not in prompt
