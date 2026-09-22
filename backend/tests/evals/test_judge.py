"""The #244 gate: the judge is an eval tool that predicts the human's verdict.

`recally.evals.judge` scores a card against *the human's* standard — the 23
hand-written rejection reasons — never the Critic's. It is deliberately not a
pipeline agent: anything registered in `agents/registry.py` can be resolved into
a real run, and the judge must never reach production card generation. Every
call still goes through `llm.py` (hard rule 3); tests never touch a real LLM.
"""

import re
from pathlib import Path
from typing import Any

import pytest

import recally.evals
from recally.agents import registry as agents_registry
from recally.evals.judge import DefaultJudge, JudgeRequest, JudgeVerdict

# Provider SDKs the evals package must never import (hard rule 3): `llm.py` is
# the only module in the repo that may (docs/backend.md, layering rule 5).
_SDK_IMPORT = re.compile(
    r"^\s*(?:from|import)\s+(litellm|openai|anthropic|ollama|cohere|boto3|google)\b"
)


class StubLlm:
    """Stands in for `LlmCaller`: records the call, returns a canned response."""

    def __init__(self, response: str) -> None:
        self.response = response
        self.calls: list[dict[str, Any]] = []

    def __call__(
        self,
        messages: list[dict[str, Any]],
        *,
        model: str,
        agent: str,
        unit_id: int | None = None,
        card_id: int | None = None,
        **kwargs: Any,
    ) -> str:
        self.calls.append(
            {
                "messages": messages,
                "model": model,
                "agent": agent,
                "unit_id": unit_id,
                "card_id": card_id,
            }
        )
        return self.response


def _request() -> JudgeRequest:
    return JudgeRequest(
        card_type="qa",
        front="Why does the bounded loop stop?",
        back="Because three rounds without an accept sends the card to a human.",
        curated_text="the Writer ⇄ Critic loop is bounded at three rounds",
        tags=["agents"],
        source_truncated=False,
    )


def _prompt_from(call: dict[str, Any]) -> str:
    (message,) = call["messages"]
    assert message["role"] == "user"
    return message["content"]


def test_judge_goes_through_the_llm_wrapper() -> None:
    # Grep-level (hard rule 3): nothing under evals/ may import a provider SDK.
    evals_dir = Path(recally.evals.__file__).parent
    offenders = []
    for path in sorted(evals_dir.rglob("*.py")):
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if _SDK_IMPORT.search(line):
                offenders.append(f"{path.name}:{lineno}: {line.strip()}")
    assert offenders == []

    # Behavioural: the judge's one call goes through the injected LlmCaller,
    # traced as `judge/default` and correlated to the unit/card it judged.
    llm = StubLlm('{"verdict": "approve", "axis": "none", "rationale": "worth knowing"}')
    judge = DefaultJudge(llm, model="test-judge-model")

    judge(_request(), unit_id=7, card_id=42)

    (call,) = llm.calls
    assert call["agent"] == "judge/default"
    assert call["model"] == "test-judge-model"
    assert call["unit_id"] == 7
    assert call["card_id"] == 42


def test_judge_prompt_names_all_three_axes() -> None:
    llm = StubLlm('{"verdict": "approve", "axis": "none", "rationale": "fine"}')
    judge = DefaultJudge(llm, model="test-judge-model")

    judge(_request(), unit_id=7, card_id=42)

    prompt = _prompt_from(llm.calls[0])
    # The three axes of the human's standard (#244) and the card + unit context.
    assert "triviality" in prompt
    assert "context_sufficiency" in prompt
    assert "main_pointedness" in prompt
    assert "Why does the bounded loop stop?" in prompt
    assert "the Writer ⇄ Critic loop is bounded at three rounds" in prompt
    # The rubric quotes the human's own rejection reasons — they are the only
    # written record of the standard.
    assert "too trivial" in prompt
    assert "context Lost" in prompt
    assert "missing the main point" in prompt


def test_judge_returns_a_verdict_and_an_axis() -> None:
    # A bare verdict cannot be validated per-axis: the parsed result carries both.
    llm = StubLlm('{"verdict": "reject", "axis": "triviality", "rationale": "not worth knowing"}')
    judge = DefaultJudge(llm, model="test-judge-model")

    verdict = judge(_request(), unit_id=7, card_id=42)

    assert isinstance(verdict, JudgeVerdict)
    assert verdict.verdict == "reject"
    assert verdict.axis == "triviality"
    assert verdict.rationale == "not worth knowing"

    # An approve verdict may carry `none`, and code fences around the JSON are tolerated.
    llm = StubLlm('```json\n{"verdict": "approve", "axis": "none", "rationale": "kept"}\n```')
    verdict = DefaultJudge(llm, model="test-judge-model")(_request(), unit_id=7, card_id=42)
    assert verdict.verdict == "approve"
    assert verdict.axis == "none"

    # A reject with no axis is malformed: the axis is what per-axis validation reads.
    llm = StubLlm('{"verdict": "reject", "axis": "none", "rationale": "bad"}')
    with pytest.raises(ValueError, match="axis"):
        DefaultJudge(llm, model="test-judge-model")(_request(), unit_id=7, card_id=42)

    llm = StubLlm('{"verdict": "reject", "axis": "fidelity", "rationale": "bad"}')
    with pytest.raises(ValueError, match="axis"):
        DefaultJudge(llm, model="test-judge-model")(_request(), unit_id=7, card_id=42)


def test_judge_is_never_registered_as_a_pipeline_agent() -> None:
    # Registered agents can be resolved into a real pipeline run; the judge must
    # never reach production card generation, so registry.py must not name it.
    source = Path(agents_registry.__file__).read_text(encoding="utf-8")
    assert "judge" not in source.lower()
    assert "judge" not in agents_registry.ROLES
