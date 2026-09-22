"""The #243 gate: scoring a replay run against the human's recorded verdicts.

`recally.evals.score.score_run` turns the harness's per-unit results into the
summary metrics — agreement, false-keep, missed-keep, round-1 accept, rework calls —
each reported with its denominator so n stays visible next to every rate.
"""

import json

from recally.evals.score import score_run


def _unit(
    unit_id: int,
    *,
    labels: list[dict],
    cards: list[dict] | None = None,
    error: str | None = None,
) -> dict:
    cards = cards or []
    return {
        "unit_id": unit_id,
        "source_truncated": False,
        "labels": labels,
        "cards": cards,
        "error": error,
        "kept": any(card["status"] in ("pending_review", "approved") for card in cards),
    }


def _label(verdict: str, reason: str | None = None) -> dict:
    return {"card_id": 1, "verdict": verdict, "reason": reason}


def _card(status: str, *, rounds: int = 1, outcome: str = "accepted") -> dict:
    return {
        "type": "qa",
        "front": "Q?",
        "back": "A.",
        "status": status,
        "status_reason": None,
        "rounds": rounds,
        "outcome": outcome,
    }


def test_score_counts_a_false_keep() -> None:
    """A unit the human rejected where the pipeline now keeps a card is a false keep."""
    units = [
        _unit(1, labels=[_label("reject", "too trivial")], cards=[_card("pending_review")]),
        _unit(2, labels=[_label("reject", "meaningless")], cards=[]),
    ]

    summary = score_run(units)

    assert summary["false_keep"]["count"] == 1
    assert summary["false_keep"]["n"] == 2


def test_score_reports_denominators() -> None:
    units = [
        _unit(
            1,
            labels=[_label("approve"), _label("reject", "context lost")],
            cards=[_card("pending_review"), _card("needs_human", rounds=3, outcome="exhausted")],
        ),
        _unit(2, labels=[_label("approve")], cards=[]),
    ]

    summary = score_run(units)

    # Every rate carries its n; the denominators are the label and card populations.
    assert summary["agreement"]["n"] == 3
    assert summary["false_keep"]["n"] == 1
    assert summary["missed_keep"]["n"] == 2
    assert summary["round1_accept"]["n"] == 2
    for metric in ("agreement", "false_keep", "missed_keep", "round1_accept"):
        assert "count" in summary[metric]
        assert "rate" in summary[metric]
    assert summary["agreement"]["count"] == 1  # unit 1's approve label; the rest disagree
    assert summary["missed_keep"]["count"] == 1
    assert summary["round1_accept"]["count"] == 1
    assert summary["rework_calls"]["count"] == 3  # exhausted at round 3 = 3 revise verdicts


def test_score_is_stable_for_identical_input() -> None:
    units = [
        _unit(9, labels=[_label("reject", "x")], cards=[_card("pending_review", rounds=2)]),
        _unit(4, labels=[_label("approve")], cards=[_card("pending_review")]),
        _unit(7, labels=[_label("approve"), _label("reject", "y")], error="writer blew up"),
    ]

    first = score_run(units)
    second = score_run(units)
    reversed_order = score_run(list(reversed(units)))

    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)
    assert json.dumps(first, sort_keys=True) == json.dumps(reversed_order, sort_keys=True)
