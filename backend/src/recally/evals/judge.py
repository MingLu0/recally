"""The LLM judge of the human's card standard (#244).

Given a card (type/front/back) plus its unit context, the judge predicts whether
*Ming* would keep the card, against the standard in `prompts/judge.md` — the 23
hand-written rejection reasons, quoted — which is not the Critic's standard
(factual fidelity is the Critic's job and is explicitly out of the judge's
brief). `evals/validate.py` measures how often the prediction agrees with the
human's recorded verdicts before the judge is trusted for anything.

Why this lives under `evals/` and not `agents/`: the judge plays no part in the
pipeline. Anything registered in `agents/registry.py` can be resolved into a
real run, and the judge must never reach production card generation — so it is
not a role, not a variant, and never registered
(`test_judge_is_never_registered_as_a_pipeline_agent` keeps it that way).

Like every LLM call in the repo this goes through `llm.py` (hard rule 3): the
caller is injected, the prompt is a file loaded from disk (ADR-003), and the
JSON response is validated at the boundary. The result carries both the verdict
and the axis that drove it — a bare verdict cannot be validated per-axis.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from string import Template
from typing import Literal, Protocol, cast

from recally.llm import LlmCaller

PROMPT_PATH = Path(__file__).parent / "prompts" / "judge.md"

VERDICTS = ("approve", "reject")
AXES = ("triviality", "context_sufficiency", "main_pointedness")
# `none` is for approve verdicts only; a reject must name the axis that drove it.
VALID_AXES = AXES + ("none",)

TRUNCATION_NOTE = (
    "NOTE: the source text below was clipped mid-word by the export, so part of it is "
    "missing and exists nowhere else. Judge whether what remains still makes a "
    "worthwhile, self-sufficient card — this reader has rejected cards for exactly "
    'this ("truncated source to a point that it no longer meaningful").'
)


@dataclass(frozen=True)
class JudgeRequest:
    """One card plus the unit context it was written from."""

    card_type: str
    front: str
    back: str
    curated_text: str
    tags: list[str]
    source_truncated: bool


@dataclass(frozen=True)
class JudgeVerdict:
    """What the judge decided and which of the human's axes drove it."""

    verdict: Literal["approve", "reject"]
    axis: Literal["triviality", "context_sufficiency", "main_pointedness", "none"]
    rationale: str


class Judge(Protocol):
    """The shape `evals/validate.py` depends on; tests inject a stub of it."""

    def __call__(
        self, request: JudgeRequest, *, unit_id: int | None = None, card_id: int | None = None
    ) -> JudgeVerdict: ...


class DefaultJudge:
    """The judge: one call per card on `LLM_MODEL_JUDGE`, traced as `judge/default`."""

    def __init__(self, llm: LlmCaller, model: str) -> None:
        self._llm = llm
        self._model = model

    def __call__(
        self, request: JudgeRequest, *, unit_id: int | None = None, card_id: int | None = None
    ) -> JudgeVerdict:
        response_text = self._llm(
            [{"role": "user", "content": _render_prompt(request)}],
            model=self._model,
            agent="judge/default",
            unit_id=unit_id,
            card_id=card_id,
        )
        return _parse_verdict(response_text)


def _render_prompt(request: JudgeRequest) -> str:
    template = PROMPT_PATH.read_text(encoding="utf-8")
    return Template(template).substitute(
        card_type=request.card_type,
        front=request.front,
        back=request.back,
        source_text=request.curated_text,
        tags=", ".join(request.tags) if request.tags else "(none)",
        truncation_note=TRUNCATION_NOTE if request.source_truncated else "",
    )


def _parse_verdict(response_text: str) -> JudgeVerdict:
    payload = _extract_json(response_text)
    if not isinstance(payload, dict):
        raise ValueError(f"judge response must be a JSON object, got {payload!r:.200}")
    verdict = payload.get("verdict")
    if verdict not in VERDICTS:
        raise ValueError(f"judge: invalid verdict {verdict!r}; must be one of {VERDICTS}")
    axis = payload.get("axis")
    if axis not in VALID_AXES:
        raise ValueError(f"judge: invalid axis {axis!r}; must be one of {VALID_AXES}")
    if verdict == "reject" and axis == "none":
        raise ValueError("judge: a reject verdict must name the axis that drove it")
    rationale = payload.get("rationale", "")
    if not isinstance(rationale, str):
        raise ValueError(f"judge: rationale must be a string, got {rationale!r:.200}")
    return JudgeVerdict(
        verdict=cast(Literal["approve", "reject"], verdict),
        axis=cast(Literal["triviality", "context_sufficiency", "main_pointedness", "none"], axis),
        rationale=rationale.strip(),
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
        raise ValueError(f"judge response is not valid JSON: {exc}") from exc
