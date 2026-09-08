"""The default Curator variant (docs/agents.md §2).

One LLM call per batch of at most `CURATOR_MAX_BATCH` highlights, in export order.
Grouping cannot span a batch boundary because each call sees exactly one batch and
the response is validated against that batch's ids. The prompt is loaded from
`prompts/curator.md` and the structured output is requested as JSON in the prompt
and parsed here — no provider-specific features (ADR-003).

Validation at the agent boundary (docs/backend.md, "The agent contract"): a
well-formed response with invalid *content* — a hallucinated highlight id, a
`decision` outside keep|drop, a `truncated_highlight_ids` entry not covered by the
unit — raises `CuratorValidationError` here, so the runner's failure path
(docs/architecture.md, "Failure handling") records it and the next run retries.
"""

import json
from pathlib import Path
from typing import Any, Literal, cast

from recally.agents.base import (
    AgentContext,
    CuratedUnitDraft,
    CuratorRequest,
    CuratorResult,
    HighlightInput,
)
from recally.agents.registry import register

PROMPT_PATH = Path(__file__).parent / "prompts" / "curator.md"
AGENT_NAME = "curator/default"


class CuratorValidationError(ValueError):
    """The LLM response is invalid for the batch it was given."""


def load_prompt() -> str:
    """The Curator system prompt, read from disk on each run (ADR-003)."""
    return PROMPT_PATH.read_text(encoding="utf-8")


class DefaultCurator:
    """Filter, group, flag truncation and tag a chapter's highlights."""

    def __call__(self, request: CuratorRequest, ctx: AgentContext) -> CuratorResult:
        max_batch = ctx.settings.curator_max_batch
        prompt = load_prompt()
        units: list[CuratedUnitDraft] = []
        for start in range(0, len(request.highlights), max_batch):
            batch = request.highlights[start : start + max_batch]
            units.extend(self._curate_batch(request, batch, prompt, ctx))
        return CuratorResult(units=units)

    def _curate_batch(
        self,
        request: CuratorRequest,
        batch: list[HighlightInput],
        prompt: str,
        ctx: AgentContext,
    ) -> list[CuratedUnitDraft]:
        messages = [
            {"role": "system", "content": prompt},
            {
                "role": "user",
                "content": _render_user_message(request.book_title, request.chapter, batch),
            },
        ]
        response_text = ctx.llm(
            messages,
            model=ctx.settings.llm_model_curator,
            agent=AGENT_NAME,
            ingest_run_id=ctx.ingest_run_id,
        )
        return _parse_units(response_text, batch)


def _render_user_message(book_title: str, chapter: str | None, batch: list[HighlightInput]) -> str:
    payload = {
        "book_title": book_title,
        "chapter": chapter,
        "highlights": [
            {"id": highlight.id, "text": highlight.text, "personal_note": highlight.personal_note}
            for highlight in batch
        ],
    }
    return json.dumps(payload, ensure_ascii=False)


def _extract_json(response_text: str) -> Any:
    """Parse the response as JSON, tolerating a markdown code fence around it."""
    stripped = response_text.strip()
    if stripped.startswith("```"):
        lines = stripped.splitlines()
        lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        stripped = "\n".join(lines)
    try:
        return json.loads(stripped)
    except json.JSONDecodeError as error:
        raise CuratorValidationError(f"response is not valid JSON: {error}") from error


def _parse_units(response_text: str, batch: list[HighlightInput]) -> list[CuratedUnitDraft]:
    parsed = _extract_json(response_text)
    if not isinstance(parsed, dict) or not isinstance(parsed.get("units"), list):
        raise CuratorValidationError("response must be a JSON object with a 'units' list")
    batch_ids = {highlight.id for highlight in batch}
    return [_parse_unit(raw_unit, batch_ids) for raw_unit in parsed["units"]]


def _parse_unit(raw_unit: Any, batch_ids: set[int]) -> CuratedUnitDraft:
    if not isinstance(raw_unit, dict):
        raise CuratorValidationError(f"unit must be a JSON object, got {type(raw_unit).__name__}")

    highlight_ids = _parse_id_list(raw_unit.get("highlight_ids"), "highlight_ids")
    unknown_ids = sorted(set(highlight_ids) - batch_ids)
    if unknown_ids:
        raise CuratorValidationError(f"unit names highlight ids outside the batch: {unknown_ids}")

    truncated_ids = _parse_id_list(
        raw_unit.get("truncated_highlight_ids", []), "truncated_highlight_ids"
    )
    orphaned_truncated_ids = sorted(set(truncated_ids) - set(highlight_ids))
    if orphaned_truncated_ids:
        raise CuratorValidationError(
            f"truncated_highlight_ids not covered by the unit's highlight_ids: "
            f"{orphaned_truncated_ids}"
        )

    decision = raw_unit.get("decision")
    if decision not in ("keep", "drop"):
        raise CuratorValidationError(f"decision must be 'keep' or 'drop', got {decision!r}")

    reason = raw_unit.get("reason")
    if not isinstance(reason, str):
        raise CuratorValidationError("reason must be a string")
    if decision == "drop" and not reason.strip():
        raise CuratorValidationError("a drop decision must carry a non-empty reason")

    curated_text = raw_unit.get("curated_text")
    if not isinstance(curated_text, str) or not curated_text.strip():
        raise CuratorValidationError("curated_text must be a non-empty string")

    tags = raw_unit.get("tags", [])
    if not isinstance(tags, list) or not all(isinstance(tag, str) for tag in tags):
        raise CuratorValidationError("tags must be a list of strings")

    return CuratedUnitDraft(
        highlight_ids=highlight_ids,
        curated_text=curated_text,
        tags=tags,
        truncated_highlight_ids=truncated_ids,
        decision=cast(Literal["keep", "drop"], decision),
        reason=reason,
    )


def _parse_id_list(raw_ids: Any, field_name: str) -> list[int]:
    if not isinstance(raw_ids, list) or not all(isinstance(id_, int) for id_ in raw_ids):
        raise CuratorValidationError(f"{field_name} must be a list of integers")
    if field_name == "highlight_ids" and not raw_ids:
        raise CuratorValidationError("highlight_ids must not be empty")
    return list(raw_ids)


register("curator", "default", DefaultCurator())
