"""Validate the judge against the human's recorded verdicts (#244).

The judge (`evals/judge.py`) predicts whether Ming would keep a card. This module
runs it over the committed label set (`tests/fixtures/eval/writer_eval.jsonl`,
#242: 45 units / 71 labels — 48 approvals, 23 rejections) and reports how often
it agrees with the human. That measurement — not the judge — is the deliverable:
an unvalidated judge produces confident numbers with unknown bias.

Honesty rules baked into the report:

- Rejection agreement is broken out from approval agreement. A judge that
  approves everything scores 68% on this set, so the overall figure alone says
  nothing.
- Every rate carries n and a 95% Wilson score interval — at n=23 one flipped
  rejection moves agreement by ~4 points, and a bare point estimate launders a
  small sample into false precision. The per-axis buckets are smaller still.
- Each rejection reason is mapped to an axis by a deterministic keyword mapping
  (`axis_for_reason`); reasons that map to nothing land in an explicit
  "unmapped" bucket, reported like any other.

Safety mirrors the harness (#243): `run_validation` refuses the configured
production database (`_refuse_if_production`), because `llm_calls` rows are
always written (hard rule 3); the CLI runs against a throwaway copy made with
`_database_copy`. Card text is loaded read-only (`create_read_only_engine`) —
the fixture carries verdicts and card ids, not card text.

Run it (never from pytest or CI — it costs one real LLM call per label):

    uv run python -m recally.evals.validate
"""

from __future__ import annotations

import argparse
import json
import math
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from recally.config import Settings, get_settings
from recally.db import create_database_engine, create_session_factory
from recally.evals.extract import create_read_only_engine
from recally.evals.harness import (
    DEFAULT_EVAL_SET,
    EvalUnit,
    _database_copy,
    _load_eval_set,
    _refuse_if_production,
)
from recally.evals.judge import AXES, DefaultJudge, Judge, JudgeRequest
from recally.llm import LlmCaller
from recally.models import Card

UNMAPPED = "unmapped"

# Human rejection reason -> axis, the deterministic keyword mapping the ticket
# asks for. Lowercased substring keywords; the match earliest in the reason
# wins, ties break in tuple order. Applied to the 23 reasons of the 2026-09
# label set it buckets them: triviality 11, context_sufficiency 6,
# main_pointedness 4, unmapped 2 ("the critic is correct" and the
# questions-around-a-question reason map to nothing — reported honestly rather
# than force-fitted). Pinned by `test_every_real_rejection_reason_maps_as_documented`.
_AXIS_KEYWORDS: tuple[tuple[str, str], ...] = (
    ("main_pointedness", "main point"),
    ("main_pointedness", "why is more important"),
    ("main_pointedness", "losing important info"),
    ("context_sufficiency", "context"),
    ("context_sufficiency", "isolated"),
    ("context_sufficiency", "does not make sense"),
    ("context_sufficiency", "truncated"),
    ("triviality", "trivial"),
    ("triviality", "meaningless"),
    ("triviality", "meaningful"),
    ("triviality", "abstract"),
)


def axis_for_reason(reason: str | None) -> str:
    """The axis a human rejection reason maps to, or "unmapped".

    Keyword mapping (earliest occurrence in the reason wins):
    main_pointedness ← "main point", "why is more important", "losing important info";
    context_sufficiency ← "context", "isolated", "does not make sense", "truncated";
    triviality ← "trivial", "meaningless", "meaningful", "abstract".
    """
    if not reason:
        return UNMAPPED
    text = reason.casefold()
    best: tuple[int, int, str] | None = None
    for order, (axis, keyword) in enumerate(_AXIS_KEYWORDS):
        position = text.find(keyword)
        if position == -1:
            continue
        candidate = (position, order, axis)
        if best is None or candidate < best:
            best = candidate
    return best[2] if best is not None else UNMAPPED


def _wilson(count: int, n: int, z: float = 1.96) -> tuple[float, float] | None:
    """The 95% Wilson score interval for count/n; None when n == 0.

    The small-sample-honest interval: at n=23 it is wide, and the width is the
    point (#244) — differences smaller than a bin flip are noise.
    """
    if n == 0:
        return None
    proportion = count / n
    denominator = 1 + z * z / n
    centre = (proportion + z * z / (2 * n)) / denominator
    half = z * math.sqrt(proportion * (1 - proportion) / n + z * z / (4 * n * n)) / denominator
    return (round(max(0.0, centre - half), 3), round(min(1.0, centre + half), 3))


def _metric(count: int, n: int) -> dict[str, Any]:
    """One reported rate: count, n, point estimate and its 95% Wilson interval."""
    return {
        "count": count,
        "n": n,
        "rate": round(count / n, 3) if n else None,
        "ci95": _wilson(count, n),
    }


def _load_cards(database_url: str, card_ids: list[int]) -> dict[int, tuple[str, str, str]]:
    """card_id -> (type, front, back) for the labelled ids, loaded read-only.

    A labelled card missing from the database fails loudly: silently scoring a
    smaller set would misreport n.
    """
    engine = create_read_only_engine(database_url)
    try:
        with Session(engine) as session:
            rows = session.execute(
                select(Card.id, Card.type, Card.front, Card.back).where(Card.id.in_(card_ids))
            ).all()
    finally:
        engine.dispose()
    cards = {row.id: (row.type, row.front, row.back) for row in rows}
    missing = sorted(set(card_ids) - cards.keys())
    if missing:
        raise ValueError(
            f"eval-set labels name {len(missing)} card ids absent from the database: {missing}"
        )
    return cards


def _judge_label(
    judge: Judge,
    cards: dict[int, tuple[str, str, str]],
    unit: EvalUnit,
    label: dict[str, Any],
) -> dict[str, Any]:
    """One label's record: the human's verdict and mapped axis, the judge's, and agree."""
    card_id: int = label["card_id"]
    card_type, front, back = cards[card_id]
    record: dict[str, Any] = {
        "unit_id": unit.unit_id,
        "card_id": card_id,
        "human_verdict": label["verdict"],
        "human_reason": label["reason"],
        "human_axis": axis_for_reason(label["reason"]) if label["verdict"] == "reject" else None,
    }
    try:
        verdict = judge(
            JudgeRequest(
                card_type=card_type,
                front=front,
                back=back,
                curated_text=unit.curated_text,
                tags=unit.tags,
                source_truncated=unit.source_truncated,
            ),
            unit_id=unit.unit_id,
            card_id=card_id,
        )
    except Exception as exc:
        # A malformed judge response is a per-label outcome, not a crashed run:
        # the label is excluded from every rate and listed under `errors`.
        return {
            **record,
            "judge_verdict": None,
            "judge_axis": None,
            "judge_rationale": None,
            "agree": None,
            "error": f"{type(exc).__name__}: {exc}",
        }
    return {
        **record,
        "judge_verdict": verdict.verdict,
        "judge_axis": verdict.axis,
        "judge_rationale": verdict.rationale,
        "agree": verdict.verdict == record["human_verdict"],
        "error": None,
    }


def _summarize(records: list[dict[str, Any]]) -> dict[str, Any]:
    """The validation summary. Pure: the same records summarize identically twice."""
    scored = [record for record in records if record["error"] is None]
    errors = [
        {"card_id": record["card_id"], "error": record["error"]}
        for record in records
        if record["error"] is not None
    ]
    approvals = [record for record in scored if record["human_verdict"] == "approve"]
    rejections = [record for record in scored if record["human_verdict"] == "reject"]

    def agreed(rows: list[dict[str, Any]]) -> int:
        return sum(1 for record in rows if record["agree"])

    return {
        # The label set size, recorded so a later reader knows how much to trust
        # the rates; per-metric n is the scored subset (smaller if judge errors).
        "n": len(records),
        "scored": len(scored),
        "errors": errors,
        "agreement": _metric(agreed(scored), len(scored)),
        "rejection_agreement": _metric(agreed(rejections), len(rejections)),
        "approval_agreement": _metric(agreed(approvals), len(approvals)),
        "per_axis": {
            axis: _metric(
                agreed([record for record in rejections if record["human_axis"] == axis]),
                sum(1 for record in rejections if record["human_axis"] == axis),
            )
            for axis in (*AXES, UNMAPPED)
        },
        "confusion": {
            human: {
                judge: sum(
                    1
                    for record in scored
                    if record["human_verdict"] == human.removeprefix("human_")
                    and record["judge_verdict"] == judge.removeprefix("judge_")
                )
                for judge in ("judge_approve", "judge_reject")
            }
            for human in ("human_approve", "human_reject")
        },
    }


def run_validation(
    eval_set_path: Path,
    database_url: str,
    settings: Settings,
    *,
    judge: Judge | None = None,
) -> dict[str, Any]:
    """Judge every labelled card in `eval_set_path` and summarize the agreement.

    Refuses first, before any engine or judge work, when `database_url` is the
    configured production database. The only writes the run can ever make are
    `llm_calls` rows (hard rule 3), which is why the CLI feeds this a copy.
    """
    _refuse_if_production(database_url, settings)
    units = _load_eval_set(eval_set_path)
    labelled = [(unit, label) for unit in units for label in unit.labels]
    cards = _load_cards(database_url, [label["card_id"] for _, label in labelled])

    engine = None
    if judge is None:
        engine = create_database_engine(database_url)
        judge = DefaultJudge(
            LlmCaller(create_session_factory(engine), log_payloads=settings.llm_log_payloads),
            model=settings.llm_model_judge,
        )
    try:
        with ThreadPoolExecutor(max_workers=settings.llm_concurrency) as pool:
            records = list(
                pool.map(lambda item: _judge_label(judge, cards, item[0], item[1]), labelled)
            )
    finally:
        if engine is not None:
            engine.dispose()

    records.sort(key=lambda record: (record["unit_id"], record["card_id"]))
    return {
        "eval_set": eval_set_path.name,
        "model": settings.llm_model_judge,
        "labels": records,
        "summary": _summarize(records),
    }


def _format_metric(name: str, metric: dict[str, Any]) -> str:
    """One report line; a rate is never printed without its n and interval."""
    if metric["n"] == 0:
        return f"{name}: n=0"
    lo, hi = metric["ci95"]
    return (
        f"{name}: {metric['count']}/{metric['n']} = {metric['rate'] * 100:.1f}% "
        f"(95% Wilson CI {lo * 100:.1f}-{hi * 100:.1f}%)"
    )


def format_report(artifact: dict[str, Any]) -> str:
    """The human-readable validation report: agreement, per-axis, confusion, n."""
    summary = artifact["summary"]
    confusion = summary["confusion"]
    lines = [
        f"judge model: {artifact['model']} — eval set {artifact['eval_set']}",
        f"label set size n = {summary['n']} "
        f"({summary['approval_agreement']['n']} approvals, "
        f"{summary['rejection_agreement']['n']} rejections); "
        f"scored {summary['scored']}, judge errors {len(summary['errors'])}",
        "",
        _format_metric("overall agreement   ", summary["agreement"]),
        _format_metric("rejection agreement ", summary["rejection_agreement"]),
        _format_metric("approval agreement  ", summary["approval_agreement"]),
        "",
        "per-axis agreement (rejections, grouped by the human's mapped axis):",
        *(f"  {_format_metric(axis, metric)}" for axis, metric in summary["per_axis"].items()),
        "",
        "confusion matrix:",
        "                  judge approve  judge reject",
        f"  human approve       {confusion['human_approve']['judge_approve']:>5}        "
        f"  {confusion['human_approve']['judge_reject']:>5}",
        f"  human reject        {confusion['human_reject']['judge_approve']:>5}        "
        f"  {confusion['human_reject']['judge_reject']:>5}",
    ]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--eval-set", type=Path, default=DEFAULT_EVAL_SET)
    parser.add_argument(
        "--database-url",
        default=None,
        help="source database to copy; defaults to RECALLY_DATABASE_URL. "
        "The run always happens against the throwaway copy, never the source.",
    )
    parser.add_argument("--output", type=Path, default=Path("eval-validation.json"))
    args = parser.parse_args(argv)

    settings = get_settings()
    source_url = args.database_url or settings.database_url
    with _database_copy(source_url) as run_url:
        artifact = run_validation(args.eval_set, run_url, settings)
    args.output.write_text(
        json.dumps(artifact, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(format_report(artifact))
    print(f"\nwrote {len(artifact['labels'])} judged labels to {args.output}")


if __name__ == "__main__":
    main()
