"""The default Critic variant (docs/agents.md, "4. Critic Agent").

One LLM call per request on the cheap tier (`LLM_MODEL_CRITIC`): the prompt is
rendered from `prompts/critic.md` (loaded from disk, never inline — ADR-003) with
the source text and the numbered candidate cards, and the JSON response is
validated at the agent boundary (docs/backend.md, "The agent contract"): exactly
one verdict per request card, `accept|revise|reject` only, and a non-empty
critique on `revise`/`reject` — the runner writes it to `status_reason` and the
Writer works from it, so an empty one is useless.

The Critic returns verdicts, never card statuses (hard rule 1); the runner maps
them (#35). When the source highlight is clipped (`source_truncated`), the prompt
marks it so fidelity is judged against the partial text and no card is penalised
for text the export lost (hard rule 7).
"""

import json
from pathlib import Path
from string import Template
from typing import Literal, cast

from recally.agents.base import AgentContext, CardVerdict, CriticRequest, CriticResult

PROMPT_PATH = Path(__file__).parent / "prompts" / "critic.md"
TRUNCATION_BEGIN = "[[TRUNCATION_NOTE]]"
TRUNCATION_END = "[[/TRUNCATION_NOTE]]"
VALID_VERDICTS = ("accept", "revise", "reject")


class DefaultCritic:
    """The default Critic: one cheap-tier call, verdicts validated at the boundary."""

    def __call__(self, request: CriticRequest, ctx: AgentContext) -> CriticResult:
        prompt = _render_prompt(request)
        response_text = ctx.llm(
            [{"role": "user", "content": prompt}],
            model=ctx.settings.llm_model_critic,
            agent="critic/default",
            ingest_run_id=ctx.ingest_run_id,
        )
        return CriticResult(verdicts=_parse_verdicts(response_text, expected=len(request.cards)))


def _render_prompt(request: CriticRequest) -> str:
    template = PROMPT_PATH.read_text(encoding="utf-8")
    if request.source_truncated:
        prompt = template.replace(TRUNCATION_BEGIN, "").replace(TRUNCATION_END, "")
    else:
        block_start = template.index(TRUNCATION_BEGIN)
        block_end = template.index(TRUNCATION_END) + len(TRUNCATION_END)
        prompt = template[:block_start] + template[block_end:]
    cards_block = "\n".join(
        f"{number}. [{card.type}] Front: {card.front} | Back: {card.back} "
        f"| Rationale: {card.rationale}"
        for number, card in enumerate(request.cards, start=1)
    )
    return Template(prompt).substitute(source_text=request.source_text, cards=cards_block)


def _parse_verdicts(response_text: str, *, expected: int) -> list[CardVerdict]:
    payload = _extract_json(response_text)
    if not isinstance(payload, list):
        raise ValueError(f"critic response must be a JSON list of verdicts, got {payload!r:.200}")
    if len(payload) != expected:
        raise ValueError(f"critic returned {len(payload)} verdicts for {expected} cards")
    return [_parse_verdict(entry, index) for index, entry in enumerate(payload, start=1)]


def _parse_verdict(entry: object, position: int) -> CardVerdict:
    if not isinstance(entry, dict):
        raise ValueError(f"verdict {position} must be a JSON object, got {entry!r:.200}")
    verdict = entry.get("verdict")
    if verdict not in VALID_VERDICTS:
        raise ValueError(
            f"verdict {position}: invalid verdict {verdict!r}; must be one of {VALID_VERDICTS}"
        )
    critique = entry.get("critique", "")
    if not isinstance(critique, str):
        raise ValueError(f"verdict {position}: critique must be a string, got {critique!r:.200}")
    critique = critique.strip()
    if verdict in ("revise", "reject") and not critique:
        raise ValueError(f"verdict {position}: {verdict} requires a non-empty critique")
    return CardVerdict(
        verdict=cast(Literal["accept", "revise", "reject"], verdict), critique=critique
    )


def _extract_json(response_text: str) -> object:
    """Parse the model's JSON, tolerating code fences around it."""
    text = response_text.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        text = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:]).strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"critic response is not valid JSON: {exc}") from exc
