"""Score a replay run against the human's recorded verdicts (#243).

The harness replays the Writer ⇄ Critic loop per eval unit; this module turns the
per-unit results into the summary metrics. Scoring is per recorded label: a unit
where the pipeline keeps at least one card *agrees with* that unit's `approve`
labels and *false-keeps* against its `reject` labels (mixed units carry both). Every
rate is reported with its denominator — at n=23 one unit moves the false-keep rate
by ~4 points, and a summary that hides n invites reading noise as signal.

Baselines from the 2026-09-12 live run: round-1 accept 64%, rework calls 391.
"""

from typing import Any

_KEPT_STATUSES = ("pending_review", "approved")


def _rate(count: int, n: int) -> float | None:
    return round(count / n, 3) if n else None


def _rework_calls(card: dict[str, Any]) -> int:
    """The Critic `revise` verdicts one card absorbed.

    An exhausted card's terminal verdict was itself a `revise` (the round cap cut it
    off, pipeline.py), so it took `rounds` rework calls; accepted and rejected cards
    took `rounds - 1`.
    """
    rounds: int = card["rounds"]
    return rounds if card["outcome"] == "exhausted" else rounds - 1


def score_run(units: list[dict[str, Any]]) -> dict[str, Any]:
    """The run summary: agreement, false-keep, missed-keep, round-1 accept, rework.

    Pure and order-independent — the same results file scores identically twice.
    """
    labels = [label for unit in units for label in unit["labels"]]
    cards = [card for unit in units for card in unit["cards"]]
    kept = [any(card["status"] in _KEPT_STATUSES for card in unit["cards"]) for unit in units]

    approvals = [label for label in labels if label["verdict"] == "approve"]
    rejections = [label for label in labels if label["verdict"] == "reject"]

    false_keeps = sum(
        1
        for unit, unit_kept in zip(units, kept, strict=True)
        for label in unit["labels"]
        if label["verdict"] == "reject" and unit_kept
    )
    missed_keeps = sum(
        1
        for unit, unit_kept in zip(units, kept, strict=True)
        for label in unit["labels"]
        if label["verdict"] == "approve" and not unit_kept
    )
    agreements = len(labels) - false_keeps - missed_keeps

    round1_accepts = sum(
        1 for card in cards if card["outcome"] == "accepted" and card["rounds"] == 1
    )

    return {
        "agreement": {
            "count": agreements,
            "n": len(labels),
            "rate": _rate(agreements, len(labels)),
        },
        "false_keep": {
            "count": false_keeps,
            "n": len(rejections),
            "rate": _rate(false_keeps, len(rejections)),
        },
        "missed_keep": {
            "count": missed_keeps,
            "n": len(approvals),
            "rate": _rate(missed_keeps, len(approvals)),
        },
        "round1_accept": {
            "count": round1_accepts,
            "n": len(cards),
            "rate": _rate(round1_accepts, len(cards)),
        },
        "rework_calls": {"count": sum(_rework_calls(card) for card in cards)},
    }
