"""Seed a demo database so every Android screen can be reviewed with real content.

Not a test and not part of `src/recally/` — a developer tool for the manual UI
pass described in `docs/demo-seed.md`. It writes rows directly rather than
running the pipeline, so it makes **no LLM calls** (hard rule 2 is about the
pipeline; this bypasses the pipeline entirely).

What it does not do: derive FSRS values through the scheduler. `card_state`
rows here are plausible, hand-written values for display purposes. The server
stays authoritative for real scheduling (hard rule 5), so this database is for
looking at the UI, never for judging scheduling correctness.

Usage (from `backend/`):

    RECALLY_DATABASE_URL=sqlite:///../data/recally.db \
        uv run python scripts/seed_demo.py --reset
"""

from __future__ import annotations

import argparse
import random
import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from uuid import uuid4
from zoneinfo import ZoneInfo

# Import from the source tree without installing the script as a package.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from sqlalchemy.orm import Session  # noqa: E402

from recally.db import create_database_engine, create_session_factory  # noqa: E402
from recally.models.base import utc_now  # noqa: E402
from recally.models.cards import (  # noqa: E402
    Card,
    CuratedUnit,
    CuratedUnitHighlight,
    WriterGuidance,
)
from recally.models.ingest import Book, Highlight, IngestRun  # noqa: E402
from recally.models.scheduling import CardState, ReviewLog  # noqa: E402

USER_ID = 1
MODEL = "claude-sonnet-5"

# Every DateTime column stores naive UTC (models/base.py, `utc_now`). Aware
# datetimes would compare wrongly against the due predicate, so reuse the
# project's own helper rather than a second definition of "now".
NOW = utc_now()


def _local_midnight_utc(days_ago: int, timezone_name: str) -> datetime:
    """Naive-UTC instant that is midday *local* time `days_ago` days back.

    `/stats` computes day boundaries in `RECALLY_TIMEZONE`, so a review stamped
    at 20:00 UTC lands on tomorrow in Auckland and silently misses the streak.
    Midday local is comfortably inside the intended day either way.
    """
    zone = ZoneInfo(timezone_name)
    local_noon = (datetime.now(zone) - timedelta(days=days_ago)).replace(
        hour=12, minute=0, second=0, microsecond=0
    )
    return local_noon.astimezone(ZoneInfo("UTC")).replace(tzinfo=None)


class DemoBuilder:
    """Builds the provenance rows every query traverses.

    `Book → Highlight` and `IngestRun → CuratedUnit` are separate parents; the
    `curated_unit_highlights` row is what joins them. A card missing that join
    row is dropped from the pending queue and makes `GET /reviews/due` return
    HTTP 500, so it is never optional.
    """

    def __init__(self, session: Session, timezone_name: str) -> None:
        self.session = session
        self.timezone_name = timezone_name
        self.export_position = 0
        self.ingest_run = IngestRun(filename="seed-demo-oreilly-annotations.csv", user_id=USER_ID)
        session.add(self.ingest_run)
        session.flush()

    def add_book(self, title: str, author: str, external_id: str) -> Book:
        book = Book(
            title=title,
            author=author,
            source="oreilly",
            external_id=external_id,
            url=f"https://learning.oreilly.com/library/view/-/{external_id}/",
            user_id=USER_ID,
        )
        self.session.add(book)
        self.session.flush()
        return book

    def add_card(
        self,
        book: Book,
        chapter: str,
        highlight_text: str,
        front: str,
        back: str,
        *,
        status: str,
        card_type: str = "qa",
        tags: list[str] | None = None,
        truncated: bool = False,
        status_reason: str | None = None,
        guidance_version: int | None = None,
        due: datetime | None = None,
        state: str = "learning",
        step: int | None = 0,
        stability: float | None = None,
        difficulty: float | None = None,
        last_review: datetime | None = None,
        suspended_until: datetime | None = None,
    ) -> Card:
        """One card with its full provenance, and `card_state` iff approved."""
        self.export_position += 1
        highlight = Highlight(
            book_id=book.id,
            chapter=chapter,
            raw_text=highlight_text,
            dedupe_key=str(uuid4()),
            source="oreilly",
            highlighted_at=date.today() - timedelta(days=self.export_position % 30),
            export_position=self.export_position,
            truncated=truncated,
            processed=True,
            user_id=USER_ID,
        )
        self.session.add(highlight)
        self.session.flush()

        unit = CuratedUnit(
            ingest_run_id=self.ingest_run.id,
            curated_text=highlight_text,
            tags=tags or [],
            decision="keep",
            user_id=USER_ID,
        )
        self.session.add(unit)
        self.session.flush()
        self.session.add(
            CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=USER_ID)
        )

        approved = status == "approved"
        card = Card(
            unit_id=unit.id,
            type=card_type,
            front=front,
            back=back,
            original_front=front,
            original_back=back,
            tags=tags or [],
            status=status,
            status_reason=status_reason,
            approved_at=NOW - timedelta(days=3) if approved else None,
            suspended_until=suspended_until,
            model=MODEL,
            guidance_version=guidance_version,
            user_id=USER_ID,
        )
        self.session.add(card)
        self.session.flush()

        # Only approved cards carry scheduling state — that is the 3a approval gate.
        if approved:
            self.session.add(
                CardState(
                    card_id=card.id,
                    state=state,
                    step=step,
                    stability=stability,
                    difficulty=difficulty,
                    due=due if due is not None else NOW - timedelta(hours=1),
                    last_review=last_review,
                    user_id=USER_ID,
                )
            )
        return card

    def add_review_history(self, cards: list[Card], days: int) -> None:
        """Consecutive-day review history, so streak and retention are non-zero.

        A lapse is `rating == 1` with `state_before == "review"`; a handful of
        those against mostly-3 ratings gives a believable retention near 0.9.
        `review_logs` is UNIQUE on (card_id, rated_at), so each row is offset.
        """
        shuffler = random.Random(20260909)
        for day in range(days):
            rated_day = _local_midnight_utc(day, self.timezone_name)
            for index, card in enumerate(shuffler.sample(cards, min(4, len(cards)))):
                is_lapse = (day + index) % 9 == 0
                self.session.add(
                    ReviewLog(
                        card_id=card.id,
                        rated_at=rated_day + timedelta(minutes=index * 7),
                        received_at=rated_day + timedelta(minutes=index * 7),
                        rating=1 if is_lapse else shuffler.choice([3, 3, 3, 4]),
                        response_ms=shuffler.randint(1800, 9000),
                        scheduled_days=0 if is_lapse else shuffler.choice([1, 3, 7, 14]),
                        state_before="review",
                        user_id=USER_ID,
                    )
                )


# Book 1 is the committed fixture (backend/tests/fixtures/oreilly-annotations-*.csv):
# real title, real chapter, real highlight text. Books 2 and 3 are written for
# this seed — the fixtures are a single book and a single chapter, which would
# leave the Decks list and the chapter grouping untested.
AGENTS_BOOK = ("30 Agents Every AI Engineer Must Build", "Vincent Koc", "9781806109012")
EVALS_BOOK = ("Evals for AI Engineers", "Hamel Husain", "9781098188283")
FSRS_BOOK = ("Spaced Repetition in Practice", "Jarrett Ye", "9781098152741")

# (chapter, highlight, front, back, tags) — approved cards, due now.
DUE_CARDS = [
    (
        "Chapter 9: Software Development Agents",
        "This execution produces observable outcomes that flow into the sensing layer.",
        "In a closed-loop agent, where do execution outcomes go?",
        "Back into the sensing layer, which is what closes the loop.",
        ["agents"],
    ),
    (
        "Chapter 9: Software Development Agents",
        "Self-Improving agent architecture can be viewed as a closed-loop control system, "
        "mirroring feedback mechanisms used in robotics and industrial automation but applied "
        "to cognitive processes within LLM-powered software systems.",
        "What existing engineering discipline does self-improving agent architecture mirror?",
        "Closed-loop control from robotics and industrial automation, applied to cognitive "
        "processes in LLM-powered systems rather than to physical actuators.",
        ["agents", "architecture"],
    ),
    (
        "Chapter 9: Software Development Agents",
        "Compliance validation, by contrast, operates on an entirely different knowledge domain.",
        "Why can't one agent handle both code review and compliance validation well?",
        "They operate on entirely different knowledge domains.",
        ["agents"],
    ),
    (
        "Chapter 3: Error Analysis",
        "An LLM pipeline's behaviour only makes sense end-to-end.",
        "Why evaluate traces rather than individual steps?",
        "An LLM pipeline's behaviour only makes sense end-to-end; a step that looks correct "
        "in isolation can still produce a wrong final answer.",
        ["evals"],
    ),
    (
        "Chapter 3: Error Analysis",
        "Open coding means reading traces and writing down what went wrong in your own words.",
        "What is open coding in error analysis?",
        "Reading traces and writing down what went wrong in your own words, before imposing "
        "any fixed taxonomy.",
        ["evals", "method"],
    ),
    (
        "Chapter 4: Building an Eval Set",
        "A benchmark you cannot regenerate is a benchmark you cannot trust.",
        "Why must an eval set be regenerable?",
        "If you cannot regenerate it you cannot audit how it was built, so you cannot trust "
        "what a score on it means.",
        ["evals"],
    ),
    (
        "Chapter 4: Building an Eval Set",
        "Binary judgments are easier to agree on than five-point scales.",
        "Why prefer binary judgments over a five-point scale in an eval rubric?",
        "Annotators agree with each other far more often on a binary call.",
        ["evals", "method"],
    ),
    (
        "Chapter 2: The Forgetting Curve",
        "Retrievability falls exponentially with time; stability sets how slowly.",
        "What do retrievability and stability each describe?",
        "Retrievability is the current probability of recall; stability is how slowly that "
        "probability decays.",
        ["fsrs"],
    ),
    (
        "Chapter 2: The Forgetting Curve",
        "Reviewing too early wastes time; reviewing too late loses the memory.",
        "Why does spacing have an optimum rather than a minimum?",
        "Too early wastes review time on something still known; too late means the memory is "
        "already lost and must be relearned.",
        ["fsrs"],
    ),
    (
        "Chapter 5: Scheduling",
        "Difficulty is a property of the item; stability is a property of your memory of it.",
        "Difficulty and stability differ in what they describe — how?",
        "Difficulty belongs to the item itself; stability belongs to your memory of that item.",
        ["fsrs"],
    ),
]

# Cloze cards — `front` needs exactly one `{{cN::…}}` marker.
CLOZE_CARDS = [
    (
        "Chapter 5: Scheduling",
        "The optimal review interval is the point where retrievability crosses the desired "
        "retention threshold.",
        "The optimal review interval is where retrievability crosses the "
        "{{c1::desired retention}} threshold.",
        "desired retention",
        ["fsrs"],
    ),
    (
        "Chapter 3: Error Analysis",
        "Axial coding groups the open codes into a taxonomy of failure modes.",
        "{{c1::Axial coding}} groups open codes into a taxonomy of failure modes.",
        "Axial coding",
        ["evals", "method"],
    ),
]

# Never-reviewed approved cards — these land in `new_count`, not `due_count`.
NEW_CARDS = [
    (
        "Chapter 9: Software Development Agents",
        "interpreting ambiguous policy documents, tracing data flow across services, and making "
        "enforcement decisions",
        "What three capabilities does a compliance agent need?",
        "Interpreting ambiguous policy documents, tracing data flow across services, and making "
        "enforcement decisions.",
        ["agents"],
    ),
    (
        "Chapter 6: Deployment",
        "Ship the smallest agent that does one job completely.",
        "What is the recommended first agent to ship?",
        "The smallest one that does a single job completely, rather than a broad one that does "
        "many jobs partially.",
        ["agents"],
    ),
    (
        "Chapter 4: Building an Eval Set",
        "Sample from production, not from your imagination.",
        "Where should eval-set inputs come from?",
        "Production traffic, not invented examples — imagined inputs miss the failure modes "
        "real users produce.",
        ["evals"],
    ),
    (
        "Chapter 2: The Forgetting Curve",
        "A lapse resets stability but leaves difficulty largely intact.",
        "What does a lapse do to stability and difficulty?",
        "It resets stability while leaving difficulty largely intact.",
        ["fsrs"],
    ),
    (
        "Chapter 5: Scheduling",
        "Learning steps exist to get a brand-new card to its first real interval.",
        "What are learning steps for?",
        "Getting a brand-new card to its first real scheduled interval.",
        ["fsrs"],
    ),
]

# Awaiting human approval.
PENDING_CARDS = [
    (
        "Chapter 6: Deployment",
        "Observability is the difference between an agent you can debug and one you can only "
        "restart.",
        "What distinguishes a debuggable agent from one you can only restart?",
        "Observability.",
        ["agents"],
    ),
    (
        "Chapter 6: Deployment",
        "Every tool call is a place the agent can fail in a new way.",
        "Why does each additional tool call increase risk?",
        "Each one introduces a new, distinct failure mode.",
        ["agents"],
    ),
    (
        "Chapter 3: Error Analysis",
        "Stop adding eval criteria once new traces stop surprising you.",
        "When should you stop adding eval criteria?",
        "Once new traces stop surprising you — that is theoretical saturation.",
        ["evals", "method"],
    ),
    (
        "Chapter 4: Building an Eval Set",
        "An LLM judge needs the same rubric a human annotator was given.",
        "What rubric should an LLM judge receive?",
        "The same one the human annotators used.",
        ["evals"],
    ),
    (
        "Chapter 7: Cost",
        "The cheapest model that passes your evals is the correct model.",
        "How should a model tier be chosen?",
        "The cheapest model that still passes your evals.",
        ["evals", "cost"],
    ),
    (
        "Chapter 2: The Forgetting Curve",
        "Cramming produces high retrievability and almost no stability.",
        "Why does cramming fail over the long run?",
        "It produces high retrievability but almost no stability, so the memory decays quickly.",
        ["fsrs"],
    ),
    (
        "Chapter 5: Scheduling",
        "Desired retention trades review volume against how much you forget.",
        "What trade-off does desired retention control?",
        "Review volume against how much you forget — higher retention means more reviews.",
        ["fsrs"],
    ),
    (
        "Chapter 9: Software Development Agents",
        "A sensing layer without an acting layer is just monitoring.",
        "What separates an agent from a monitoring system?",
        "An acting layer — sensing alone is only monitoring.",
        ["agents", "architecture"],
    ),
]

# Bounced to a human by the Critic — `status_reason` is the critique the UI shows.
NEEDS_HUMAN_CARDS = [
    (
        "Chapter 7: Cost",
        "Token cost scales with context, and context scales with ambition.",
        "What does token cost scale with?",
        "Context length.",
        "The answer restates the highlight without explaining the second clause; a reader "
        "cannot tell why ambition drives context.",
        ["cost"],
    ),
    (
        "Chapter 6: Deployment",
        "Retries hide latency problems until they become outages.",
        "What do retries hide?",
        "Latency problems.",
        "Too shallow to be worth reviewing — the card tests recall of one word rather than "
        "the causal claim.",
        ["agents"],
    ),
    (
        "Chapter 2: The Forgetting Curve",
        "The spacing effect was documented by Ebbinghaus in 1885 and has survived every",
        "Who documented the spacing effect and when?",
        "Ebbinghaus, in 1885.",
        "Source highlight is truncated mid-sentence, so the claim after 'survived every' "
        "cannot be verified (hard rule 7 — not reconstructed).",
        ["fsrs"],
    ),
]


def _book_for_chapter(chapter: str, books: dict[str, Book]) -> Book:
    """Route a chapter to its book, so chapters group under the right deck."""
    if chapter.startswith(("Chapter 9", "Chapter 6", "Chapter 7")):
        return books["agents"]
    if chapter.startswith(("Chapter 3", "Chapter 4")):
        return books["evals"]
    return books["fsrs"]


def seed(session: Session, timezone_name: str, *, include_due: bool) -> dict[str, int]:
    builder = DemoBuilder(session, timezone_name)

    # Guidance versions, so /stats lapse_rate_by_guidance_version is non-empty:
    # it drops cards whose guidance_version is NULL.
    session.add_all(
        [
            WriterGuidance(
                version=1, guidance="Prefer one fact per card.", basis={}, user_id=USER_ID
            ),
            WriterGuidance(
                version=2,
                guidance="Prefer one fact per card; avoid yes/no questions.",
                basis={},
                user_id=USER_ID,
            ),
        ]
    )
    session.flush()

    books = {
        "agents": builder.add_book(*AGENTS_BOOK),
        "evals": builder.add_book(*EVALS_BOOK),
        "fsrs": builder.add_book(*FSRS_BOOK),
    }

    reviewed_cards: list[Card] = []

    if include_due:
        # Graduated cards mid-review: `last_review` set, so they count as due
        # rather than new, and `due_count` is not capped by NEW_CARDS_PER_DAY.
        for offset, (chapter, highlight, front, back, tags) in enumerate(DUE_CARDS):
            reviewed_cards.append(
                builder.add_card(
                    _book_for_chapter(chapter, books),
                    chapter,
                    highlight,
                    front,
                    back,
                    status="approved",
                    tags=tags,
                    guidance_version=2 if offset % 2 else 1,
                    due=NOW - timedelta(hours=offset + 1),
                    state="review",
                    step=None,
                    stability=8.0 + offset,
                    difficulty=4.0 + (offset % 4) * 0.6,
                    last_review=NOW - timedelta(days=offset % 5 + 1),
                )
            )
        for offset, (chapter, highlight, front, back, tags) in enumerate(CLOZE_CARDS):
            reviewed_cards.append(
                builder.add_card(
                    _book_for_chapter(chapter, books),
                    chapter,
                    highlight,
                    front,
                    back,
                    status="approved",
                    card_type="cloze",
                    tags=tags,
                    guidance_version=2,
                    due=NOW - timedelta(minutes=30 * (offset + 1)),
                    state="review",
                    step=None,
                    stability=11.0,
                    difficulty=5.1,
                    last_review=NOW - timedelta(days=2),
                )
            )

    # Never reviewed: last_review stays None, so these are the new-card allotment.
    for offset, (chapter, highlight, front, back, tags) in enumerate(NEW_CARDS):
        builder.add_card(
            _book_for_chapter(chapter, books),
            chapter,
            highlight,
            front,
            back,
            status="approved",
            tags=tags,
            guidance_version=2,
            # The first two are due now; the rest sit days ahead so the /stats
            # forecast spans a range instead of collapsing onto today.
            due=NOW - timedelta(minutes=offset + 1)
            if offset < 2
            else NOW + timedelta(days=offset * 2, hours=3),
        )

    # Suspended: excluded from /reviews/due, still listed on Book detail so the
    # unsuspend control has something to act on (ADR-008).
    for chapter, highlight, front, back, tags in DUE_CARDS[:2]:
        builder.add_card(
            _book_for_chapter(chapter, books),
            chapter,
            highlight + " (suspended copy)",
            front,
            back,
            status="approved",
            tags=tags,
            guidance_version=1,
            due=NOW - timedelta(hours=2),
            state="review",
            step=None,
            stability=6.0,
            difficulty=7.2,
            last_review=NOW - timedelta(days=4),
            suspended_until=NOW + timedelta(days=30),
        )

    for chapter, highlight, front, back, tags in PENDING_CARDS:
        builder.add_card(
            _book_for_chapter(chapter, books),
            chapter,
            highlight,
            front,
            back,
            status="pending_review",
            tags=tags,
        )

    # One truncated source highlight, flagged and never reconstructed (hard rule 7).
    for index, (chapter, highlight, front, back, reason, tags) in enumerate(NEEDS_HUMAN_CARDS):
        builder.add_card(
            _book_for_chapter(chapter, books),
            chapter,
            highlight,
            front,
            back,
            status="needs_human",
            tags=tags,
            status_reason=reason,
            truncated=index == len(NEEDS_HUMAN_CARDS) - 1,
        )

    session.flush()
    if reviewed_cards:
        builder.add_review_history(reviewed_cards, days=6)
    session.commit()

    return {
        "books": len(books),
        "due_cards": len(DUE_CARDS) + len(CLOZE_CARDS) if include_due else 0,
        "new_cards": len(NEW_CARDS),
        "suspended": 2,
        "pending_review": len(PENDING_CARDS),
        "needs_human": len(NEEDS_HUMAN_CARDS),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--reset",
        action="store_true",
        help="delete existing demo rows first, so re-running is idempotent",
    )
    parser.add_argument(
        "--no-due",
        action="store_true",
        help="seed no due cards, to review Today's nothing-due state",
    )
    parser.add_argument(
        "--timezone",
        default=None,
        help="IANA zone for review-history day boundaries (default: RECALLY_TIMEZONE)",
    )
    args = parser.parse_args()

    import os

    timezone_name = args.timezone or os.environ.get("RECALLY_TIMEZONE", "Pacific/Auckland")

    engine = create_database_engine()
    session_factory = create_session_factory(engine)

    with session_factory() as session:
        if args.reset:
            # Children before parents; no ON DELETE CASCADE is declared.
            for model in (
                ReviewLog,
                CardState,
                Card,
                CuratedUnitHighlight,
                CuratedUnit,
                Highlight,
                IngestRun,
                Book,
                WriterGuidance,
            ):
                session.query(model).delete()
            session.commit()

        counts = seed(session, timezone_name, include_due=not args.no_due)

    print(f"Seeded {engine.url} (timezone {timezone_name}):")
    for name, count in counts.items():
        print(f"  {count:3d}  {name}")
    print("\nStart the API with:  uv run uvicorn recally.main:app --host 0.0.0.0 --port 8000")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
