"""Build the committed writer eval set from deliberate human judgments (#242).

Every label in the set is an approve or reject the human performed deliberately; no
new labelling happens here. The eval subject is the curated unit — the harness (#243)
replays the Writer ⇄ Critic loop per unit — so each entry carries one or more
card-level labels.

Two guards are structural, not conventional:

- The database is opened **read-only** (`create_read_only_engine`), so eval work can
  never write to production data.
- Cards approved in the 2026-09-11 10:43:33 bulk tap (#240) are excluded by
  predicate (`_is_deliberate`), not by a hardcoded id list, so the rule survives
  future bulk taps. The predicate can be deleted once an `approval_source` column
  exists (#240 follow-up) and the filter can move onto it.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from sqlalchemy import Engine, create_engine, make_url, select
from sqlalchemy.orm import Session

from recally.config import get_settings
from recally.models import Card, CuratedUnit, CuratedUnitHighlight, Highlight, ReviewLog

# The accidental "Approve all clean" tap (#240): 116 cards approved in this one
# second, none of them a considered judgment.
BULK_APPROVAL_SECOND = datetime(2026, 9, 11, 10, 43, 33)

DEFAULT_OUTPUT = Path("tests/fixtures/eval/writer_eval.jsonl")


def create_read_only_engine(database_url: str) -> Engine:
    """An engine that refuses writes, for any database the eval set is extracted from.

    SQLite gets `mode=ro` on the file itself; Postgres gets a session-level
    `default_transaction_read_only`. Extraction therefore cannot mutate the source
    even if a future change adds a write by mistake.
    """
    url = make_url(database_url)
    backend = url.get_backend_name()
    if backend == "sqlite":
        database = url.database
        if not database or database == ":memory:":
            raise ValueError("read-only extraction needs a file-backed SQLite database")
        # Accept both plain paths and the `file:` URI form; the `mode=ro` is always
        # (re)applied here so the read-only guarantee never depends on the caller.
        path = database.removeprefix("file:")
        return create_engine(f"sqlite:///file:{path}?mode=ro&uri=true")
    if backend == "postgresql":
        return create_engine(url, connect_args={"options": "-c default_transaction_read_only=on"})
    raise ValueError(f"read-only extraction does not support the {backend!r} backend")


def _is_deliberate(card: Card) -> bool:
    """True unless the card was approved inside the 2026-09-11 10:43:33 bulk tap."""
    if card.status != "approved" or card.approved_at is None:
        return True
    bulk_end = BULK_APPROVAL_SECOND + timedelta(seconds=1)
    return not (BULK_APPROVAL_SECOND <= card.approved_at < bulk_end)


def extract_eval_set(database_url: str) -> list[dict[str, Any]]:
    """One entry per curated unit behind at least one deliberately judged card.

    Cards whose `curated_units` row is gone are dropped rather than emitted with a
    null unit. `review_signal` aggregates the unit's labelled cards' review history:
    a card rated Good/Easy weeks later was kept *and* recalled, the least corruptible
    quality signal available.
    """
    engine = create_read_only_engine(database_url)
    try:
        with Session(engine) as session:
            judged = [
                card
                for card in session.scalars(
                    select(Card).where(Card.status.in_(["approved", "rejected"])).order_by(Card.id)
                )
                if _is_deliberate(card)
            ]
            unit_ids = {card.unit_id for card in judged}
            if not unit_ids:
                return []
            units = {
                unit.id: unit
                for unit in session.scalars(select(CuratedUnit).where(CuratedUnit.id.in_(unit_ids)))
            }
            truncated_unit_ids = set(
                session.scalars(
                    select(CuratedUnitHighlight.unit_id)
                    .join(Highlight, CuratedUnitHighlight.highlight_id == Highlight.id)
                    .where(
                        CuratedUnitHighlight.unit_id.in_(unit_ids), Highlight.truncated.is_(True)
                    )
                )
            )
            ratings_by_card: dict[int, list[int]] = {}
            for card_id, rating in session.execute(
                select(ReviewLog.card_id, ReviewLog.rating).where(
                    ReviewLog.card_id.in_([card.id for card in judged])
                )
            ):
                ratings_by_card.setdefault(card_id, []).append(rating)
    finally:
        engine.dispose()

    by_unit: dict[int, list[Card]] = {}
    for card in judged:
        if card.unit_id in units:
            by_unit.setdefault(card.unit_id, []).append(card)

    entries: list[dict[str, Any]] = []
    for unit_id in sorted(by_unit):
        unit = units[unit_id]
        cards = sorted(by_unit[unit_id], key=lambda card: card.id)
        ratings = [rating for card in cards for rating in ratings_by_card.get(card.id, [])]
        entries.append(
            {
                "unit_id": unit_id,
                "curated_text": unit.curated_text,
                "tags": unit.tags,
                "source_truncated": unit_id in truncated_unit_ids,
                "labels": [
                    {
                        "card_id": card.id,
                        "verdict": "approve" if card.status == "approved" else "reject",
                        "reason": card.status_reason if card.status == "rejected" else None,
                    }
                    for card in cards
                ],
                "review_signal": {
                    "reviews": len(ratings),
                    "again_hard": sum(1 for rating in ratings if rating <= 2),
                    "good_easy": sum(1 for rating in ratings if rating >= 3),
                },
            }
        )
    return entries


def write_eval_set(entries: list[dict[str, Any]], output: Path) -> None:
    """One JSON object per unit, sorted by unit id — the set is fixture data and must diff."""
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as file:
        for entry in entries:
            file.write(json.dumps(entry, ensure_ascii=False) + "\n")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--database-url",
        default=None,
        help="defaults to RECALLY_DATABASE_URL; opened read-only either way",
    )
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)

    database_url = args.database_url or get_settings().database_url
    entries = extract_eval_set(database_url)
    write_eval_set(entries, args.output)
    label_count = sum(len(entry["labels"]) for entry in entries)
    print(f"wrote {len(entries)} units / {label_count} labels to {args.output}")


if __name__ == "__main__":
    main()
