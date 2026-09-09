"""The default Learner stage-B variant (docs/agents.md, "7. Learner Agent").

One LLM call per nightly run on the learner tier (`LLM_MODEL_LEARNER`): the prompt
is rendered from `prompts/learner.md` (loaded from disk, never inline — ADR-003)
with the aggregated review statistics and the current `writer_guidance`, and the
JSON response is validated at the agent boundary (docs/backend.md, "The agent
contract") into a `LearnerResult` before it returns.

The agent holds no DB session: it gets aggregates in and returns a result; the job
(`scheduling/learner.py`) does every write — the threshold check, the versioned
`writer_guidance` INSERT (hard rule 10) and, from 6b-b, the leech rewrites. The
call is traced as `learner/default` with `ingest_run_id` null — the nightly job
serves no ingest run (docs/data-model.md, `llm_calls`).
"""

import json
from pathlib import Path
from string import Template
from typing import Any

from recally.agents.base import AgentContext, LearnerRequest, LearnerResult
from recally.agents.registry import register

AGENT_NAME = "learner/default"
VARIANT = "default"

PROMPT_PATH = Path(__file__).parent / "prompts" / "learner.md"

_PROMPT_TEMPLATE = Template(PROMPT_PATH.read_text(encoding="utf-8"))


class LearnerResponseError(ValueError):
    """The Learner's response failed validation at the agent boundary."""


class DefaultLearner:
    """The `Learner` protocol implementation registered as `("learner", "default")`."""

    def __call__(self, request: LearnerRequest, ctx: AgentContext) -> LearnerResult:
        prompt = _PROMPT_TEMPLATE.substitute(
            aggregates_json=json.dumps(request.review_aggregates, indent=2, sort_keys=True),
            current_guidance_version=(
                str(request.current_guidance_version)
                if request.current_guidance_version is not None
                else "(none yet)"
            ),
            current_guidance=request.current_guidance or "No guidance row exists yet.",
        )
        raw = ctx.llm(
            [{"role": "user", "content": prompt}],
            model=ctx.settings.llm_model_learner,
            agent=AGENT_NAME,
            ingest_run_id=ctx.ingest_run_id,
        )
        return _parse_result(raw)


def _parse_result(raw: str) -> LearnerResult:
    payload = _loads(raw)
    if not isinstance(payload, dict):
        raise LearnerResponseError(f"response is not a JSON object: {raw!r:.200}")
    guidance = payload.get("guidance")
    if guidance is not None and not (isinstance(guidance, str) and guidance.strip()):
        raise LearnerResponseError(
            f"guidance must be a non-empty string or null: {guidance!r:.200}"
        )
    rationale = payload.get("rationale")
    if not isinstance(rationale, str) or not rationale.strip():
        raise LearnerResponseError(f"rationale must be a non-empty string: {rationale!r:.200}")
    leech_card_ids = payload.get("leech_card_ids", [])
    if not isinstance(leech_card_ids, list) or not all(
        isinstance(card_id, int) and not isinstance(card_id, bool) for card_id in leech_card_ids
    ):
        raise LearnerResponseError(
            f"leech_card_ids must be a list of integer card ids: {leech_card_ids!r:.200}"
        )
    return LearnerResult(
        guidance=guidance.strip() if isinstance(guidance, str) else None,
        rationale=rationale.strip(),
        leech_card_ids=list(leech_card_ids),
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
        raise LearnerResponseError(f"response is not valid JSON: {exc}") from exc


register("learner", VARIANT, DefaultLearner())
