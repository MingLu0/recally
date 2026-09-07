"""The step 2c gate for the default Curator variant (docs/agents.md §2).

Every test runs against a fake `llm` callable with recorded responses; no test
touches a provider or a database.

Two places where this file deliberately diverges from the ticket text, because the
docs are the spec (AGENTS.md):

- `test_group_of_three_becomes_one_unit` uses synthetic "Stage 1/2/3" heading
  highlights: docs/agents.md and docs/roadmap.md both record that this heading run
  is not in the export we have, so no fixture row can supply it. The LLM is mocked,
  so the inputs only need to exercise the grouping path.
- `test_truncated_row_is_flagged_not_reconstructed` uses the real clipped fixture
  row `efaf55cf-…` ("…executed and tested in isolation.", a 149-character exact
  prefix of `bf9830d8-…`). The ticket's "written to the san" ending matches no
  committed fixture; docs/roadmap.md step 1 names `efaf55cf-…` as the clipped row.
"""

import csv
import json
from collections import deque
from pathlib import Path
from typing import Any

import pytest

from recally.agents.base import AgentContext, CuratorRequest, HighlightInput
from recally.agents.curator.default import (
    PROMPT_PATH,
    CuratorValidationError,
    DefaultCurator,
)
from recally.config import Settings

FIXTURES_DIR = Path(__file__).parent.parent / "fixtures"

STAGE_HEADINGS = [
    "Stage 1: Task assignment",
    "Stage 2: Code synthesis",
    "Stage 3: Test synthesis",
]

PROMPT_SENTINEL = "missing text exists nowhere else"


def _clipped_fixture_text() -> str:
    """The Highlight column of the clipped Chapter 9 row `efaf55cf-…`."""
    with open(FIXTURES_DIR / "oreilly-annotations-a.csv", newline="") as fixture_file:
        for row in csv.DictReader(fixture_file):
            if "efaf55cf" in row["Annotation URL"]:
                return row["Highlight"]
    raise AssertionError("clipped fixture row efaf55cf-… not found")


def _units_payload(units: list[dict[str, Any]]) -> str:
    return json.dumps({"units": units})


def _keep_unit(
    highlight_ids: list[int],
    curated_text: str,
    truncated_highlight_ids: list[int] | None = None,
) -> dict[str, Any]:
    return {
        "highlight_ids": highlight_ids,
        "curated_text": curated_text,
        "tags": ["agents"],
        "truncated_highlight_ids": truncated_highlight_ids or [],
        "decision": "keep",
        "reason": "",
    }


class FakeLlm:
    """A stand-in for `LlmCaller` that replays recorded responses and records calls."""

    def __init__(self, responses: list[str]) -> None:
        self._responses = deque(responses)
        self.calls: list[dict[str, Any]] = []

    def __call__(
        self,
        messages: list[dict[str, Any]],
        *,
        model: str,
        agent: str,
        ingest_run_id: int | None = None,
        **kwargs: Any,
    ) -> str:
        self.calls.append({"messages": messages, "model": model, "agent": agent})
        return self._responses.popleft()


def _ctx(fake_llm: FakeLlm, curator_max_batch: int = 40) -> AgentContext:
    settings = Settings(
        RECALLY_API_KEY="test-key-not-a-real-secret", CURATOR_MAX_BATCH=curator_max_batch
    )
    return AgentContext(ingest_run_id=None, settings=settings, llm=fake_llm)  # type: ignore[arg-type]


def _request(highlights: list[HighlightInput]) -> CuratorRequest:
    return CuratorRequest(
        book_title="30 Agents Every AI Engineer Must Build",
        chapter="Chapter 9: Software Development Agents",
        highlights=highlights,
    )


def _highlight(id_: int, text: str) -> HighlightInput:
    return HighlightInput(id=id_, text=text, personal_note=None)


def _batch_ids(call: dict[str, Any]) -> list[int]:
    payload = json.loads(call["messages"][1]["content"])
    return [highlight["id"] for highlight in payload["highlights"]]


def test_group_of_three_becomes_one_unit() -> None:
    """Three sibling headings fold into one unit with three highlight_ids."""
    fake_llm = FakeLlm([_units_payload([_keep_unit([1, 2, 3], "\n".join(STAGE_HEADINGS))])])
    request = _request([_highlight(index + 1, text) for index, text in enumerate(STAGE_HEADINGS)])

    result = DefaultCurator()(request, _ctx(fake_llm))

    assert len(result.units) == 1
    assert result.units[0].highlight_ids == [1, 2, 3]
    assert result.units[0].decision == "keep"


def test_truncated_row_is_flagged_not_reconstructed() -> None:
    """The clipped row comes back flagged, with its text ending where the source ends."""
    clipped_text = _clipped_fixture_text()
    response = _units_payload([_keep_unit([1], clipped_text, truncated_highlight_ids=[1])])
    fake_llm = FakeLlm([response])

    result = DefaultCurator()(_request([_highlight(1, clipped_text)]), _ctx(fake_llm))

    unit = result.units[0]
    assert unit.truncated_highlight_ids == [1]
    assert unit.curated_text == clipped_text
    # The fuller fixture row continues "…in isolation. The file path and content…";
    # the curated text must not have been extended toward it (hard rule 7).
    assert "The file path" not in unit.curated_text
    assert len(unit.curated_text) <= len(clipped_text)


def test_drop_decision_carries_a_reason() -> None:
    drop_unit = {
        "highlight_ids": [1],
        "curated_text": "planner-coder-critic",
        "tags": [],
        "truncated_highlight_ids": [],
        "decision": "drop",
        "reason": "Isolated short phrase with no idea of its own.",
    }
    fake_llm = FakeLlm([_units_payload([drop_unit])])

    result = DefaultCurator()(_request([_highlight(1, "planner-coder-critic")]), _ctx(fake_llm))

    assert result.units[0].decision == "drop"
    assert result.units[0].reason.strip()


def test_hallucinated_highlight_id_raises() -> None:
    """An id outside the batch is an error at the agent boundary, not a warning."""
    fake_llm = FakeLlm([_units_payload([_keep_unit([1, 999], "text")])])

    with pytest.raises(CuratorValidationError):
        DefaultCurator()(_request([_highlight(1, "text"), _highlight(2, "more")]), _ctx(fake_llm))


def test_invalid_decision_raises() -> None:
    maybe_unit = _keep_unit([1], "text") | {"decision": "maybe"}
    fake_llm = FakeLlm([_units_payload([maybe_unit])])

    with pytest.raises(CuratorValidationError):
        DefaultCurator()(_request([_highlight(1, "text")]), _ctx(fake_llm))


def test_truncated_ids_must_be_subset_of_highlight_ids() -> None:
    fake_llm = FakeLlm([_units_payload([_keep_unit([1], "text", truncated_highlight_ids=[2])])])

    with pytest.raises(CuratorValidationError):
        DefaultCurator()(_request([_highlight(1, "text"), _highlight(2, "more")]), _ctx(fake_llm))


def test_batch_is_split_at_curator_max_batch() -> None:
    """90 highlights at CURATOR_MAX_BATCH=40 make 3 calls of 40/40/10, in export order."""
    highlights = [_highlight(id_, f"highlight {id_}") for id_ in range(1, 91)]
    responses = [
        _units_payload([_keep_unit([1], "highlight 1")]),
        _units_payload([_keep_unit([41], "highlight 41")]),
        _units_payload([_keep_unit([81], "highlight 81")]),
    ]
    fake_llm = FakeLlm(responses)

    result = DefaultCurator()(_request(highlights), _ctx(fake_llm, curator_max_batch=40))

    assert len(fake_llm.calls) == 3
    call_ids = [_batch_ids(call) for call in fake_llm.calls]
    assert [len(ids) for ids in call_ids] == [40, 40, 10]
    assert call_ids[0] == list(range(1, 41))
    assert call_ids[1] == list(range(41, 81))
    assert call_ids[2] == list(range(81, 91))

    batch_id_sets = [set(ids) for ids in call_ids]
    for unit in result.units:
        assert any(set(unit.highlight_ids) <= batch_ids for batch_ids in batch_id_sets), (
            f"unit spans a batch boundary: {unit.highlight_ids}"
        )


def test_agent_writes_nothing_to_the_database() -> None:
    """The agent runs with no session anywhere in reach and returns normally."""
    fake_llm = FakeLlm([_units_payload([_keep_unit([1], "text")])])

    result = DefaultCurator()(_request([_highlight(1, "text")]), _ctx(fake_llm))

    assert len(result.units) == 1


def test_prompt_is_loaded_from_file() -> None:
    """The rendered request carries prompt-file text the module does not contain."""
    prompt_text = PROMPT_PATH.read_text(encoding="utf-8")
    assert PROMPT_SENTINEL in prompt_text

    fake_llm = FakeLlm([_units_payload([_keep_unit([1], "text")])])
    DefaultCurator()(_request([_highlight(1, "text")]), _ctx(fake_llm))

    system_message = fake_llm.calls[0]["messages"][0]["content"]
    assert PROMPT_SENTINEL in system_message

    import recally.agents.curator.default as default_module

    module_source = Path(default_module.__file__).read_text(encoding="utf-8")  # type: ignore[arg-type]
    assert PROMPT_SENTINEL not in module_source
