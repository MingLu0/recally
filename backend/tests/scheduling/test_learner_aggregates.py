"""Step 6b-a gate: the Learner stage-B aggregate builder (`scheduling/learner.py`).

Spec: docs/agents.md, "7. Learner Agent", stage B — lapse rates by card type, book,
tag and guidance version, deletions-per-cloze, failure streaks and rating response
times, built from `review_logs` and `cards`. The builder is deterministic (hard rule
2 names only stage B's *guidance writing* as LLM) and caps input size: aggregates,
not raw logs (docs/agents.md, "Cost controls").

Two exclusions are spec and look like bugs without the doc in front of you:

- suspended cards (`suspended_until` in the future) are excluded from the lapse-rate
  aggregates — a card out of rotation stops generating reviews, so leaving it in
  would read as improved retention;
- post-approval edits (`edited_at` set, ADR-008) are segmented, not pooled — a card
  hand-fixed weeks later must not credit its lapse rate to the `guidance_version`
  that wrote the flawed original.

`recally.scheduling.learner` is imported inside each test rather than at module
scope: the module does not exist until the implementation lands, and a late import
turns "not written yet" into one red test per behaviour instead of a collection
error for the whole file (same shape as tests/scheduling/test_optimizer.py).
"""

import json
from collections.abc import Iterator
from datetime import timedelta
from typing import Any

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from recally.models import (
    Base,
    Book,
    Card,
    CuratedUnit,
    CuratedUnitHighlight,
    Highlight,
    IngestRun,
    ReviewLog,
)
from recally.models.base import utc_now

NOW = utc_now()
# `bury` sets the next day boundary, `suspend` a far-future sentinel (ADR-008); the
# aggregates only exclude values in the future.
SUSPENDED = NOW + timedelta(days=3650)
BURIED_YESTERDAY = NOW - timedelta(days=1)


@pytest.fixture
def session() -> Iterator[Session]:
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    try:
        with Session(engine) as session:
            yield session
    finally:
        engine.dispose()


_seed_counter = 0


def _seed_card(
    session: Session,
    *,
    card_type: str = "qa",
    front: str = "Why are aggregates deterministic?",
    tags: list[str] | None = None,
    book_title: str = "Designing Agents",
    guidance_version: int | None = 1,
    suspended_until: Any = None,
    edited_at: Any = None,
) -> Card:
    """One approved card with the full book → highlight → unit provenance chain,
    so the by-book lapse bucket has something to read."""
    global _seed_counter
    _seed_counter += 1
    suffix = _seed_counter
    run = IngestRun(filename=f"aggregates-{suffix}-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    book = Book(
        title=book_title,
        source="oreilly",
        external_id=f"978000000{suffix:04d}",
        user_id=1,
    )
    session.add(book)
    session.flush()
    highlight = Highlight(
        book_id=book.id,
        raw_text=f"Highlight text {suffix}.",
        dedupe_key=f"uuid-{suffix}",
        source="oreilly",
        highlighted_at=NOW.date(),
        export_position=1,
        user_id=1,
    )
    session.add(highlight)
    session.flush()
    unit = CuratedUnit(
        ingest_run_id=run.id,
        curated_text=f"Curated {suffix}.",
        tags=tags or [],
        decision="keep",
        user_id=1,
    )
    session.add(unit)
    session.flush()
    session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
    card = Card(
        unit_id=unit.id,
        type=card_type,
        front=front,
        back=f"Answer {suffix}.",
        original_front=front,
        original_back=f"Answer {suffix}.",
        tags=tags or [],
        status="approved",
        approved_at=NOW - timedelta(days=30),
        edited_at=edited_at,
        suspended_until=suspended_until,
        model="claude-sonnet-5",
        guidance_version=guidance_version,
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def _seed_reviews(session: Session, card_id: int, ratings: list[int]) -> None:
    """One review per rating, oldest first, one per hour so (card_id, rated_at)
    stays unique."""
    for index, rating in enumerate(ratings):
        session.add(
            ReviewLog(
                card_id=card_id,
                rated_at=NOW - timedelta(hours=len(ratings) - index),
                rating=rating,
                response_ms=4200,
                scheduled_days=0,
                state_before="review",
                user_id=1,
            )
        )
    session.commit()


def _lapse_bucket(aggregates: dict[str, Any], section: str, key: str) -> dict[str, Any] | None:
    """The bucket `section[key]`, or None when the key never accumulated a review."""
    return aggregates[section].get(key)


# --- The roadmap gate (docs/roadmap.md, step 6b) --------------------------------


def test_suspended_cards_are_excluded_from_lapse_rates(session: Session) -> None:
    """A card with `suspended_until` in the future contributes nothing to the
    lapse-rate aggregates (docs/agents.md, stage B): out of rotation means no new
    reviews, so leaving its old ones in would read as improved retention."""
    from recally.scheduling import learner

    active = _seed_card(session, guidance_version=1)
    suspended = _seed_card(session, guidance_version=1, suspended_until=SUSPENDED)
    _seed_reviews(session, active.id, [3, 1])  # 2 reviews, 1 lapse
    _seed_reviews(session, suspended.id, [1, 1, 1])  # 3 lapses that must vanish

    aggregates = learner.build_review_aggregates(session, now=NOW)

    bucket = _lapse_bucket(aggregates, "lapse_rate_by_guidance_version", "1")
    assert bucket == {"reviews": 2, "lapses": 1, "lapse_rate": 0.5}, (
        f"the suspended card's 3 lapses leaked into the v1 bucket: {bucket}"
    )
    type_bucket = _lapse_bucket(aggregates, "lapse_rate_by_card_type", "qa")
    assert type_bucket == {"reviews": 2, "lapses": 1, "lapse_rate": 0.5}, (
        f"the suspended card's reviews leaked into the by-type bucket: {type_bucket}"
    )
    assert aggregates["review_count"] == 2
    assert aggregates["suspended_card_count"] == 1


def test_bury_does_not_exclude_a_card_from_aggregates(session: Session) -> None:
    """The mirror of the suspension rule: `suspended_until` in the PAST is a bury
    whose day has passed — the card is back in rotation and its reviews count.
    Without this test, 'exclude everything with a non-null value' passes the
    suspension test above."""
    from recally.scheduling import learner

    buried = _seed_card(session, guidance_version=1, suspended_until=BURIED_YESTERDAY)
    _seed_reviews(session, buried.id, [3, 1])

    aggregates = learner.build_review_aggregates(session, now=NOW)

    bucket = _lapse_bucket(aggregates, "lapse_rate_by_guidance_version", "1")
    assert bucket == {"reviews": 2, "lapses": 1, "lapse_rate": 0.5}, (
        f"a past suspended_until (a bury) wrongly excluded the card: {bucket}"
    )
    assert aggregates["review_count"] == 2
    assert aggregates["suspended_card_count"] == 0


def test_post_approval_edits_are_segmented_not_pooled(session: Session) -> None:
    """A card with `edited_at` set does not contribute its lapses to the
    `guidance_version` that wrote the flawed original (ADR-008): edited cards are
    a separate segment, so a hand-fix weeks later cannot flatter the guidance."""
    from recally.scheduling import learner

    pristine = _seed_card(session, guidance_version=1)
    edited = _seed_card(session, guidance_version=1, edited_at=NOW - timedelta(days=2))
    _seed_reviews(session, pristine.id, [3, 3])
    _seed_reviews(session, edited.id, [1, 1])

    aggregates = learner.build_review_aggregates(session, now=NOW)

    pooled = _lapse_bucket(aggregates, "lapse_rate_by_guidance_version", "1")
    assert pooled == {"reviews": 2, "lapses": 0, "lapse_rate": 0.0}, (
        f"the edited card's lapses were pooled into the v1 bucket: {pooled}"
    )
    segment = aggregates["post_approval_edits"]
    assert segment["edited_card_count"] == 1
    edited_bucket = segment["lapse_rate_by_guidance_version"].get("1")
    assert edited_bucket == {"reviews": 2, "lapses": 2, "lapse_rate": 1.0}, (
        f"the edited card's lapses belong in the segmented bucket: {edited_bucket}"
    )


def test_aggregates_carry_no_raw_review_rows(session: Session) -> None:
    """The payload is aggregate counts, not per-review records (docs/agents.md,
    "Cost controls": 'Nightly Learner caps input size (aggregates, not raw logs)').
    No key a raw `review_logs` row would carry may appear anywhere in the tree."""
    from recally.scheduling import learner

    card = _seed_card(session, guidance_version=1)
    _seed_reviews(session, card.id, [3, 1, 3])

    aggregates = learner.build_review_aggregates(session, now=NOW)

    # Must be JSON-serialisable: it is stored as `writer_guidance.basis` (JSON).
    json.dumps(aggregates)

    raw_row_keys = {
        "id",
        "rated_at",
        "received_at",
        "response_ms",
        "scheduled_days",
        "state_before",
        "device_id",
    }

    def walk(node: Any, path: str) -> None:
        if isinstance(node, dict):
            assert not raw_row_keys & node.keys(), (
                f"raw review_log columns at {path}: {sorted(raw_row_keys & node.keys())}"
            )
            # A per-review record carries a rating next to a timestamp; aggregates
            # never do.
            assert "rating" not in node, f"per-review record leaked at {path}: {node!r}"
            for key, value in node.items():
                walk(value, f"{path}.{key}")
        elif isinstance(node, list):
            for index, item in enumerate(node):
                walk(item, f"{path}[{index}]")
        else:
            assert node is None or isinstance(node, str | int | float | bool), (
                f"non-scalar leaf at {path}: {node!r}"
            )

    walk(aggregates, "$")

    # Sanity: the aggregates did summarise the seeded reviews — the walk above is
    # meaningless on an empty payload.
    assert aggregates["review_count"] == 3
    bucket = _lapse_bucket(aggregates, "lapse_rate_by_card_type", "qa")
    assert bucket == {"reviews": 3, "lapses": 1, "lapse_rate": round(1 / 3, 4)}
