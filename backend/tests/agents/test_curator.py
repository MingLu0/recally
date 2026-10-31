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


# --- the #253 prompt gate ---------------------------------------------------------
#
# The Curator under-groups (#253): thin, truncated and subject-less sources reach
# the Writer as single-highlight units. These tests pin the two prompt changes —
# the widened Group job and the cardability drop — plus the rules the edit must
# not break. Section sentinels are matched inside the job they belong to, so a
# mention in the wrong job does not count.

GROUP_SECTION_START = "2. **Group.**"
GROUP_SECTION_END = "3. **Flag truncation.**"
FILTER_SECTION_START = "1. **Filter.**"


def _prompt() -> str:
    return PROMPT_PATH.read_text(encoding="utf-8")


def _section(prompt: str, start: str, end: str) -> str:
    assert start in prompt, f"section start {start!r} missing from the prompt"
    assert end in prompt, f"section end {end!r} missing from the prompt"
    section = prompt.split(start, 1)[1].split(end, 1)[0]
    # Collapse line wrapping so a sentinel is found wherever the prose wraps.
    return " ".join(section.split())


def _group_section(prompt: str) -> str:
    return _section(prompt, GROUP_SECTION_START, GROUP_SECTION_END)


def _filter_section(prompt: str) -> str:
    return _section(prompt, FILTER_SECTION_START, GROUP_SECTION_START)


def test_curator_prompt_requires_grouping_subjectless_fragments() -> None:
    """The Group job directs folding mid-sentence / dangling-pronoun highlights
    into the adjacent highlight that supplies the subject (#253)."""
    group = _group_section(_prompt())
    assert "mid-sentence" in group
    assert "dangling pronoun" in group
    assert "supplies its subject" in group
    assert "must" in group  # a requirement, not a suggestion


def test_curator_prompt_requires_pairing_truncated_with_fuller_sibling() -> None:
    """The Group job directs pairing a truncated row with its fuller sibling
    when one exists in the batch (#253)."""
    group = _group_section(_prompt())
    assert "truncated" in group.lower()
    assert "fuller sibling" in group
    assert "in the batch" in group


def test_curator_prompt_never_reconstructs_truncated_text() -> None:
    """Hard rule 7 survives the grouping edit: pairing changes which unit a
    clipped row belongs to, never its text (negative assertion)."""
    prompt = _prompt()
    assert "never reconstruct or extend" in prompt
    group = _group_section(prompt)
    assert "never extend a truncated row" in group
    assert "never use the sibling to complete" in group


def test_curator_prompt_defines_cardability_drop() -> None:
    """A fragment that supports no self-contained card — alone or grouped — is
    dropped; the value filter's default-to-keep is untouched (#253)."""
    prompt = _prompt()
    filter_section = _filter_section(prompt)
    assert "Default to `keep`" in filter_section
    assert "no self-contained card" in filter_section
    assert "`drop`" in filter_section


def test_curator_prompt_keeps_output_contract() -> None:
    """The output rules the runner and validator rely on survive the edit."""
    prompt = _prompt()
    assert "exactly one unit" in prompt
    assert "`keep` or `drop`" in prompt
    assert "only contains ids that are also in `highlight_ids`" in prompt
    assert "non-empty for every `drop`" in prompt


# --- the #290 prompt gate ---------------------------------------------------------
#
# #290: the Group job gained positive fold requirements in #253 but no coherence
# test and no negative examples, so two bad-group shapes reached the Writer:
# a four-highlight bundle of two near-duplicate pairs (card 102) and an
# unrelated trio the Writer had to invent a bridge for (card 110), plus a
# meaningless topical group that should have been dropped whole (card 113).
# These tests pin the coherence test and the counterexamples, plus the rules
# the edit must not break.


def test_curator_prompt_states_the_group_coherence_test() -> None:
    """The Group job bounds every group — including mandatory-shaped folds —
    with a coherence test: one proposition statable in a single sentence.
    Adjacency in export order is not evidence (#290)."""
    group = _group_section(_prompt())
    assert "Coherence test (bounds every group)" in group
    assert "single sentence" in group
    assert "Adjacency in export order is not evidence" in group
    assert "separate single-highlight units" in group
    assert "even when a fold rule" in group


def test_curator_prompt_includes_negative_grouping_examples() -> None:
    """The prompt names the two measured counterexamples: the duplicate-pair
    bundle (card 102) and the unrelated trio whose Writer invented a bridge
    (card 110), plus the meaningless topical group dropped whole (card 113)."""
    prompt = _prompt()
    assert "Duplicate-pair bundle" in prompt
    assert "near-duplicates" in prompt
    assert "Unrelated trio" in prompt
    assert "invent" in prompt  # the Writer-invented bridge the group forced
    assert "single proposition connects" in prompt


def test_curator_prompt_keeps_fold_and_cardability_rules() -> None:
    """#290 bounds the mandatory folds with coherence; it does not remove them.
    The subjectless and truncated folds, the truncation non-reconstruction
    rule and the cardability drop all survive the edit."""
    prompt = _prompt()
    group = _group_section(prompt)
    assert "Subjectless fragments" in group
    assert "dangling pronoun" in group
    assert "supplies its subject" in group
    assert "truncated" in group.lower()
    assert "fuller sibling" in group
    assert "never extend a truncated row" in group
    assert "never use the sibling to complete" in group
    assert "never reconstruct or extend" in prompt
    filter_section = _filter_section(prompt)
    assert "no self-contained card" in filter_section
    assert "Default to `keep`" in filter_section


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


# --- the #307 prompt gate ---------------------------------------------------------
#
# Section previews and chapter transitions reached the Writer because "navigation"
# in the value bullet reads as UI navigation (#307). Sentinels are matched inside
# the value bullet, so a mention under cardability or grouping does not count.

VALUE_BULLET_START = "**Value.**"
CARDABILITY_BULLET_START = "**Cardability.**"


def _value_bullet(prompt: str) -> str:
    return _section(prompt, VALUE_BULLET_START, CARDABILITY_BULLET_START)


def test_curator_prompt_names_section_previews_and_transitions_as_drops() -> None:
    """The value bullet names prose that only announces or links sections as
    junk to drop (#307)."""
    value_bullet = _value_bullet(_prompt())
    assert "section previews" in value_bullet
    assert "chapter transitions" in value_bullet
    assert "In this section, we explore" in value_bullet
    assert "carries no fact" in value_bullet


def test_curator_prompt_drops_claims_true_of_any_system() -> None:
    """The value bullet names a claim so generic it is true of any system as
    junk to drop, with the Critic's own example (#325)."""
    value_bullet = _value_bullet(_prompt())
    assert "so generic" in value_bullet
    assert "true of any system" in value_bullet
    assert "central supervisor or operate as decentralized nodes" in value_bullet


def test_curator_prompt_keeps_default_to_keep() -> None:
    """Naming the new drop case leaves default-to-keep and the cardability rule
    intact (#307 preservation)."""
    filter_section = _filter_section(_prompt())
    assert "Default to `keep`: junk is a small minority of real exports." in filter_section
    assert "Drop only clear junk" in filter_section
    assert "no self-contained card" in filter_section
    assert "however valuable the words look" in filter_section
