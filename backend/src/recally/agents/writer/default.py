"""The default Card Writer variant (docs/agents.md §3).

One LLM call per curated unit: renders `prompts/writer.md` (loaded from disk,
ADR-003) with the unit text, the current `writer_guidance` and — on a revision
round — the Critic's critique, then parses the JSON response into `CardDraft`s
stamped with the request's `guidance_version`.

Round counting and the 3-round cap live in `pipeline.py` (hard rule 9 is
structural): this agent sees one request and returns one result, holding no state
between calls.
"""

import json
import re
from pathlib import Path
from string import Template
from typing import Any

from recally.agents.base import AgentContext, CardDraft, WriterRequest, WriterResult
from recally.agents.registry import register

AGENT_NAME = "writer/default"
VARIANT = "default"

_PROMPT_TEMPLATE = Template(
    (Path(__file__).parent / "prompts" / "writer.md").read_text(encoding="utf-8")
)

# Anki-style `{{cN::...}}` deletion markers; the single-deletion rule counts these.
CLOZE_MARKER = re.compile(r"\{\{c\d+::")


class WriterResponseError(ValueError):
    """The Writer's response failed validation at the agent boundary."""


class DefaultWriter:
    """The `Writer` protocol implementation registered as `("writer", "default")`."""

    def __call__(self, request: WriterRequest, ctx: AgentContext) -> WriterResult:
        prompt = _PROMPT_TEMPLATE.substitute(
            guidance=request.guidance or "No Learner guidance yet; follow the card rules above.",
            tags=", ".join(request.tags) if request.tags else "(none)",
            curated_text=request.curated_text,
            critique_section=_critique_section(request.critique),
        )
        raw = ctx.llm(
            [{"role": "user", "content": prompt}],
            model=ctx.settings.llm_model_writer,
            agent=AGENT_NAME,
            ingest_run_id=ctx.ingest_run_id,
        )
        return WriterResult(cards=_parse_cards(raw, request.guidance_version))


def _critique_section(critique: str | None) -> str:
    if critique is None:
        return ""
    return (
        "## Critic feedback from the previous round\n\n"
        f"{critique}\n\n"
        "Rewrite the card to resolve this feedback while keeping every rule above.\n"
    )


def _parse_cards(raw: str, guidance_version: int | None) -> list[CardDraft]:
    payload = _loads(raw)
    cards = payload.get("cards") if isinstance(payload, dict) else None
    if not isinstance(cards, list):
        raise WriterResponseError(f"response is not a JSON object with a cards list: {raw!r}")
    return [_parse_card(card, guidance_version) for card in cards]


def _parse_card(item: Any, guidance_version: int | None) -> CardDraft:
    """Validate one response card and stamp it with the request's guidance version.

    These checks live in Python because the prompt alone will not hold them: an
    unknown `type`, an empty `front`/`back`, or a multi-deletion cloze raises here
    at the agent boundary rather than landing in the approval queue.
    """
    if not isinstance(item, dict):
        raise WriterResponseError(f"card is not a JSON object: {item!r}")
    card_type = item.get("type")
    if card_type not in ("qa", "cloze"):
        raise WriterResponseError(f"card type must be qa|cloze, got {card_type!r}")
    front, back, rationale = (
        str(item.get(field) or "") for field in ("front", "back", "rationale")
    )
    if not front.strip() or not back.strip():
        raise WriterResponseError("card front and back must be non-empty")
    if card_type == "cloze" and len(CLOZE_MARKER.findall(front)) != 1:
        raise WriterResponseError(
            f"cloze front must hold exactly one deletion marker, got: {front!r}"
        )
    return CardDraft(
        type=card_type,
        front=front,
        back=back,
        rationale=rationale,
        guidance_version=guidance_version,
    )


def _loads(raw: str) -> Any:
    """Parse the response, tolerating a ```json fence around the JSON object."""
    text = raw.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        text = "\n".join(lines[1:-1] if lines[-1].startswith("```") else lines[1:])
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise WriterResponseError(f"response is not valid JSON: {exc}") from exc


register("writer", VARIANT, DefaultWriter())
