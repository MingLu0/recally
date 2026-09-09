"""Learner stage B: the aggregate builder and the nightly guidance job.

Spec: docs/agents.md, "7. Learner Agent" (stage B) and "Cost controls" ("Nightly
Learner caps input size (aggregates, not raw logs)"); docs/data-model.md,
`writer_guidance` and `llm_calls`; docs/config.md, `LEARNER_MIN_REVIEWS` /
`AGENT_LEARNER` / `LLM_MODEL_LEARNER`.

The split of labour is the agent contract (docs/backend.md):

- `build_review_aggregates` is deterministic — counts over `review_logs` and
  `cards`, no LLM anywhere in it (hard rule 2 names only stage B's guidance
  writing as LLM).
- `generate_guidance` is the job: it checks `LEARNER_MIN_REVIEWS` BEFORE resolving
  the agent (a discarded call still costs money and still writes an `llm_calls`
  row), calls the Learner variant the registry resolves (`AGENT_LEARNER`), and is
  the only writer — the versioned `writer_guidance` INSERT (hard rule 10: rows are
  never edited, so a v2 is an append at `max(version) + 1`).
- The Learner agent itself lives in `agents/learner/` and holds no DB session.

Two exclusions in the aggregates are spec (docs/agents.md, stage B), and both look
like bugs without the doc in front of you:

- **Suspended cards are excluded** (`suspended_until` in the future): a card taken
  out of rotation stops generating reviews, so leaving it in would read as
  improved retention. A past value is a bury whose day has passed — that card is
  back in rotation and counts (ADR-008).
- **Post-approval edits are segmented, not pooled** (`edited_at` set, ADR-008): a
  card hand-fixed weeks later would otherwise credit its lapse rate to the
  `guidance_version` that wrote the flawed original.

No FastAPI here (docs/backend.md, "Layering" rule 1): APScheduler, an external
cron and the `POST /jobs/run` router all converge on this module, and only one of
those is an HTTP request.
"""

import json
import logging
import re
import statistics
from collections import defaultdict
from datetime import datetime
from typing import TYPE_CHECKING, Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from recally.agents.base import AgentContext, LearnerRequest
from recally.models import (
    Book,
    Card,
    CuratedUnitHighlight,
    Highlight,
    ReviewLog,
    WriterGuidance,
)
from recally.models.base import utc_now

if TYPE_CHECKING:
    from recally.container import Container

logger = logging.getLogger(__name__)

# 1=Again is the lapse rating (docs/data-model.md, `review_logs`).
LAPSE_RATING = 1

Bucket = dict[str, Any]


def generate_guidance(container: "Container") -> WriterGuidance | None:
    """The nightly Learner stage-B job: maybe append one `writer_guidance` row.

    Returns the new row (detached), or `None` when nothing was written — below
    `LEARNER_MIN_REVIEWS` (no LLM call either: the threshold comes first), or the
    Learner answered `guidance=None` ("no new row is warranted", which is not a
    failure). Leech rewrites are 6b-b; this job stops at returning the result.
    """
    settings = container.settings
    with container.session() as session:
        review_count = session.scalar(select(func.count()).select_from(ReviewLog)) or 0
        if review_count < settings.learner_min_reviews:
            logger.info(
                "learner: %d review_logs below LEARNER_MIN_REVIEWS=%d; no LLM call, no row",
                review_count,
                settings.learner_min_reviews,
            )
            return None
        aggregates = build_review_aggregates(session)
        current_guidance_version, current_guidance = _latest_guidance(session)

    learner = container.agent("learner")
    ctx = AgentContext(
        # The nightly job serves no ingest run: llm_calls.ingest_run_id is null
        # for Learner calls (docs/data-model.md), and unit_id stays null too.
        ingest_run_id=None,
        settings=settings,
        llm=container.llm_caller,
    )
    result = learner(
        LearnerRequest(
            review_aggregates=aggregates,
            current_guidance_version=current_guidance_version,
            current_guidance=current_guidance,
        ),
        ctx,
    )
    if result.guidance is None:
        logger.info("learner: guidance=None — no new writer_guidance row is warranted")
        return None

    with container.session() as session:
        next_version = (session.scalar(select(func.max(WriterGuidance.version))) or 0) + 1
        row = WriterGuidance(
            version=next_version,
            guidance=result.guidance,
            # "basis: aggregates the Learner used to write it" (docs/data-model.md).
            basis=aggregates,
            user_id=1,
        )
        session.add(row)
        session.commit()
        session.refresh(row)
        session.expunge(row)
    logger.info(
        "learner: wrote writer_guidance v%d (%d chars; rationale: %s)",
        row.version,
        len(row.guidance),
        result.rationale,
    )
    return row


def build_review_aggregates(session: Session, *, now: datetime | None = None) -> dict[str, Any]:
    """The `LearnerRequest.review_aggregates` payload: counts, never raw rows.

    Every review-derived section excludes suspended cards; lapse attribution by
    guidance version pools only unedited cards, with the edited segment reported
    separately (ADR-008).
    """
    now = now or utc_now()
    cards = {card.id: card for card in session.scalars(select(Card)).all()}
    book_titles = _book_titles_by_card(session)
    reviews = session.scalars(
        select(ReviewLog).order_by(ReviewLog.card_id, ReviewLog.rated_at)
    ).all()

    suspended_card_ids = {
        card.id
        for card in cards.values()
        if card.suspended_until is not None and card.suspended_until > now
    }
    counted = [review for review in reviews if review.card_id not in suspended_card_ids]

    by_type: dict[str, Bucket] = defaultdict(_new_bucket)
    by_book: dict[str, Bucket] = defaultdict(_new_bucket)
    by_tag: dict[str, Bucket] = defaultdict(_new_bucket)
    by_deletions: dict[str, Bucket] = defaultdict(_new_bucket)
    by_guidance_version: dict[str, Bucket] = defaultdict(_new_bucket)
    edited_by_guidance_version: dict[str, Bucket] = defaultdict(_new_bucket)

    for review in counted:
        card = cards.get(review.card_id)
        if card is None:
            continue
        _accumulate(by_type[card.type], review)
        for title in book_titles.get(card.id, []):
            _accumulate(by_book[title], review)
        for tag in card.tags:
            _accumulate(by_tag[tag], review)
        if card.type == "cloze":
            deletions = str(_cloze_deletion_count(card.front))
            _accumulate(by_deletions[deletions], review)
        version_key = str(card.guidance_version) if card.guidance_version is not None else "none"
        if card.edited_at is not None:
            # Segmented, not pooled (ADR-008): a hand-fixed card must not credit
            # its lapses to the guidance version that wrote the flawed original.
            _accumulate(edited_by_guidance_version[version_key], review)
        else:
            _accumulate(by_guidance_version[version_key], review)

    edited_cards = [card for card in cards.values() if card.edited_at is not None]

    aggregates: dict[str, Any] = {
        "review_count": len(counted),
        "suspended_card_count": len(suspended_card_ids),
        "lapse_rate_by_card_type": _sorted_buckets(by_type),
        "lapse_rate_by_book": _sorted_buckets(by_book),
        "lapse_rate_by_tag": _sorted_buckets(by_tag),
        "lapse_rate_by_deletions_per_cloze": _sorted_buckets(by_deletions),
        "lapse_rate_by_guidance_version": _sorted_buckets(by_guidance_version),
        "post_approval_edits": {
            "edited_card_count": len(edited_cards),
            "lapse_rate_by_guidance_version": _sorted_buckets(edited_by_guidance_version),
        },
        "failure_streaks": _failure_streaks(counted),
        "response_times_ms": _response_times(counted),
        "approval_time_edits": _approval_time_edits(list(cards.values())),
        "rejection_reasons": _rejection_reasons(list(cards.values())),
    }
    # The payload is persisted as `writer_guidance.basis` (JSON) and rendered into
    # the prompt; fail loudly here rather than write a row that cannot round-trip.
    json.dumps(aggregates)
    return aggregates


def _latest_guidance(session: Session) -> tuple[int | None, str | None]:
    """The newest `writer_guidance` row, or (None, None) before the Learner's first."""
    guidance = session.scalar(
        select(WriterGuidance).order_by(WriterGuidance.version.desc()).limit(1)
    )
    if guidance is None:
        return None, None
    return guidance.version, guidance.guidance


def _book_titles_by_card(session: Session) -> dict[int, list[str]]:
    """Card id → titles of the books its unit's highlights came from."""
    titles: dict[int, list[str]] = defaultdict(list)
    rows = session.execute(
        select(Card.id, Book.title)
        .join(CuratedUnitHighlight, CuratedUnitHighlight.unit_id == Card.unit_id)
        .join(Highlight, Highlight.id == CuratedUnitHighlight.highlight_id)
        .join(Book, Book.id == Highlight.book_id)
        .distinct()
    ).all()
    for card_id, title in rows:
        titles[card_id].append(title)
    return titles


def _cloze_deletion_count(front: str) -> int:
    """How many `{{cN::...}}` deletions a cloze front carries."""
    return len(re.findall(r"\{\{c\d+::", front))


def _new_bucket() -> Bucket:
    return {"reviews": 0, "lapses": 0, "lapse_rate": 0.0}


def _accumulate(bucket: Bucket, review: ReviewLog) -> None:
    bucket["reviews"] += 1
    if review.rating == LAPSE_RATING:
        bucket["lapses"] += 1
    bucket["lapse_rate"] = round(bucket["lapses"] / bucket["reviews"], 4)


def _sorted_buckets(buckets: dict[str, Bucket]) -> dict[str, Bucket]:
    """Buckets by key, sorted, with empty keys never present (defaultdict hygiene)."""
    return {key: buckets[key] for key in sorted(buckets)}


def _failure_streaks(reviews: list[ReviewLog]) -> dict[str, Any]:
    """Longest run of consecutive Again ratings per card, over counted reviews.

    `reviews` must arrive ordered by (card_id, rated_at) — the caller's query
    guarantees it. Streaks of 3+ are the leech signal stage B names ("failed 3+
    times"); which ids to rewrite is the Learner's call, from 6b-b.
    """
    longest_by_card: dict[int, int] = defaultdict(int)
    current_card_id: int | None = None
    current_streak = 0
    for review in reviews:
        if review.card_id != current_card_id:
            current_card_id = review.card_id
            current_streak = 0
        current_streak = current_streak + 1 if review.rating == LAPSE_RATING else 0
        longest_by_card[review.card_id] = max(longest_by_card[review.card_id], current_streak)
    return {
        "max_consecutive_failures": max(longest_by_card.values(), default=0),
        "cards_with_3_plus_consecutive_failures": sum(
            1 for streak in longest_by_card.values() if streak >= 3
        ),
    }


def _response_times(reviews: list[ReviewLog]) -> dict[str, Any]:
    """Flip-to-rate durations (docs/data-model.md: `response_ms` is captured on
    every rating), overall and per rating value."""
    durations = [review.response_ms for review in reviews]
    if not durations:
        return {"mean": 0.0, "median": 0.0, "p90": 0.0, "mean_by_rating": {}}
    ordered = sorted(durations)
    p90_index = min(len(ordered) - 1, int(0.9 * (len(ordered) - 1)))
    durations_by_rating: dict[str, list[int]] = defaultdict(list)
    for review in reviews:
        durations_by_rating[str(review.rating)].append(review.response_ms)
    return {
        "mean": round(statistics.fmean(durations), 1),
        "median": float(statistics.median(durations)),
        "p90": float(ordered[p90_index]),
        "mean_by_rating": {
            rating: round(statistics.fmean(values), 1)
            for rating, values in sorted(durations_by_rating.items())
        },
    }


def _approval_time_edits(cards: list[Card]) -> dict[str, Any]:
    """Cards whose text the human changed at approval (`front`/`back` differ from
    `original_*`): 'the most direct quality signal available' (docs/agents.md)."""
    approved = [card for card in cards if card.approved_at is not None]
    edited = [
        card
        for card in approved
        if card.front != card.original_front or card.back != card.original_back
    ]
    return {
        "approved_card_count": len(approved),
        "edited_at_approval_count": len(edited),
    }


def _rejection_reasons(cards: list[Card]) -> dict[str, Any]:
    """Why the human rejected cards — the other half of the direct quality signal."""
    reasons = sorted(
        card.status_reason for card in cards if card.status == "rejected" and card.status_reason
    )
    return {"rejected_card_count": len(reasons), "reasons": reasons}
