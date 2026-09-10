"""The step 3e gate: `GET /stats` and the aggregates behind it (docs/api-spec.md, "Stats").

The service takes `now` and the timezone explicitly so the day-boundary tests do not
race the clock (same pattern as `services.decks.list_decks`); the router passes the
real ones. Stored datetimes are naive UTC (models/base.py), so every expected value
below is derived from local Auckland wall-clock times through `_utc`.

A lapse is an Again (rating 1) on a card that was in FSRS `review` state when rated:
that is the specific FSRS meaning docs/design/design-system.md points at, and an Again
during learning is not one. The tests pin that definition.
"""

import uuid
from collections.abc import Iterator
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
from recally.container import Container
from recally.main import create_app
from recally.models import (
    Base,
    Book,
    Card,
    CardState,
    CuratedUnit,
    Highlight,
    IngestRun,
    ReviewLog,
    WriterGuidance,
)
from recally.services.stats import StatsSummary, get_stats

TEST_API_KEY = "test-key-not-a-real-secret"
AKL = ZoneInfo("Pacific/Auckland")  # UTC+12 in September (NZST), non-UTC as the gate asks

# Local Auckland time 2026-09-09 01:00, so "today" is 2026-09-09.
NOW_UTC = datetime(2026, 9, 8, 13, 0, 0)
TODAY = date(2026, 9, 9)


@pytest.fixture
def container() -> Iterator[Container]:
    """A container on a fresh in-memory database (same shape as test_api.py)."""
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    settings = Settings(RECALLY_DATABASE_URL="sqlite://", RECALLY_API_KEY=TEST_API_KEY)
    try:
        yield Container(settings, engine=engine)
    finally:
        engine.dispose()


@pytest.fixture
def client(container: Container, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    app = create_app()
    app.dependency_overrides[container_dependency] = lambda: container
    monkeypatch.setenv("RECALLY_API_KEY", TEST_API_KEY)
    get_settings.cache_clear()
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        get_settings.cache_clear()


def _utc(local_dt: datetime) -> datetime:
    """A naive-UTC stamp for an Auckland wall-clock time, as the columns store it."""
    return local_dt.replace(tzinfo=AKL).astimezone(timezone.utc).replace(tzinfo=None)


def _local(day_offset: int, hour: int, minute: int = 0) -> datetime:
    """Naive-UTC stamp for `hour:minute` Auckland time, `day_offset` days from TODAY."""
    return _utc(
        datetime.combine(TODAY + timedelta(days=day_offset), datetime.min.time())
        + timedelta(hours=hour, minutes=minute)
    )


def _highlight(session: Session, *, text: str = "A highlight.") -> Highlight:
    book = session.scalars(select(Book)).first()
    if book is None:
        book = Book(
            title="Evals for AI Engineers", source="oreilly", external_id="9781098188283", user_id=1
        )
        session.add(book)
        session.flush()
    highlight = Highlight(
        book_id=book.id,
        raw_text=text,
        dedupe_key=str(uuid.uuid4()),
        source="oreilly",
        highlighted_at=TODAY,
        export_position=0,
        user_id=1,
    )
    session.add(highlight)
    session.flush()
    return highlight


def _card(
    session: Session,
    *,
    card_type: str = "qa",
    status: str = "approved",
    guidance_version: int | None = None,
    suspended_until: datetime | None = None,
) -> Card:
    highlight = _highlight(session)
    run = IngestRun(filename=f"{uuid.uuid4()}-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    unit = CuratedUnit(
        ingest_run_id=run.id, curated_text=highlight.raw_text, decision="keep", tags=[], user_id=1
    )
    session.add(unit)
    session.flush()
    card = Card(
        unit_id=unit.id,
        type=card_type,
        front="Q?",
        back="A.",
        original_front="Q?",
        original_back="A.",
        tags=[],
        status=status,
        suspended_until=suspended_until,
        model="claude-sonnet-5",
        guidance_version=guidance_version,
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def _review(
    session: Session,
    card: Card,
    rated_at: datetime,
    *,
    rating: int = 3,
    state_before: str = "review",
) -> ReviewLog:
    log = ReviewLog(
        card_id=card.id,
        rated_at=rated_at,
        rating=rating,
        response_ms=2500,
        scheduled_days=4,
        state_before=state_before,
        user_id=1,
    )
    session.add(log)
    session.flush()
    return log


def _stats(session: Session) -> StatsSummary:
    return get_stats(session, tz=AKL, now=NOW_UTC)


def test_streak_days_uses_configured_timezone_day_boundaries(
    container: Container,
) -> None:
    """A review just after local midnight counts for the new local day, not the old one.

    Both reviews fall on 2026-09-08 in UTC (11:30 and 12:30), but Auckland midnight
    splits them onto Sep 8 and Sep 9. Computed in UTC the streak is 1; in
    `RECALLY_TIMEZONE` it is 2 — that difference is the whole test.
    """
    with container.session() as session:
        card = _card(session)
        _review(session, card, _local(-1, 23, 30))  # Sep 8, 23:30 local
        _review(session, card, _local(0, 0, 30))  # Sep 9, 00:30 local — just after midnight
        session.commit()
        assert _stats(session).streak_days == 2


def test_streak_days_breaks_on_a_missed_day(container: Container) -> None:
    with container.session() as session:
        card = _card(session)
        _review(session, card, _local(0, 9))  # today
        _review(session, card, _local(-2, 9))  # two days ago…
        _review(session, card, _local(-3, 9))  # …and three, but yesterday was missed
        session.commit()
        assert _stats(session).streak_days == 1


def test_reviews_today_counts_only_todays_logs(container: Container) -> None:
    """Same UTC day, different local days: only the post-midnight one is today's."""
    with container.session() as session:
        card = _card(session)
        _review(session, card, _local(-1, 23, 30))  # yesterday local
        _review(session, card, _local(0, 0, 30))  # today local (same UTC day)
        session.commit()
        assert _stats(session).reviews_today == 1


def test_retention_30d_from_review_logs(container: Container) -> None:
    """One lapse in five; the Again during learning is not a lapse (FSRS meaning)."""
    with container.session() as session:
        card = _card(session)
        _review(session, card, _local(-1, 8), rating=1, state_before="review")  # the lapse
        _review(session, card, _local(-2, 8), rating=1, state_before="learning")  # not a lapse
        _review(session, card, _local(-3, 8), rating=2)
        _review(session, card, _local(-4, 8), rating=3)
        _review(session, card, _local(-5, 8), rating=4)
        session.commit()
        assert _stats(session).retention_30d == pytest.approx(0.8)


def test_retention_30d_ignores_reviews_older_than_thirty_days(container: Container) -> None:
    """Negative: a 31-day-old lapse would drag the share to 2/3 if it were counted."""
    with container.session() as session:
        card = _card(session)
        _review(session, card, _local(-30, 8), rating=1, state_before="review")
        _review(session, card, _local(0, 8), rating=3)
        _review(session, card, _local(-1, 8), rating=4)
        session.commit()
        assert _stats(session).retention_30d == 1.0


def test_lapse_rate_by_type_splits_qa_and_cloze(container: Container) -> None:
    with container.session() as session:
        qa_card = _card(session, card_type="qa")
        cloze_card = _card(session, card_type="cloze")
        _review(session, qa_card, _local(-1, 8), rating=1, state_before="review")
        _review(session, qa_card, _local(-2, 8), rating=3)
        _review(session, qa_card, _local(-3, 8), rating=3)
        for day in range(1, 5):
            _review(
                session,
                cloze_card,
                _local(-day, 9),
                rating=1 if day == 1 else 3,
                state_before="review",
            )
        session.commit()
        lapse_rate_by_type = _stats(session).lapse_rate_by_type
        assert lapse_rate_by_type["qa"] == pytest.approx(1 / 3)
        assert lapse_rate_by_type["cloze"] == pytest.approx(0.25)


def test_lapse_rate_by_guidance_version_omits_null_guidance(container: Container) -> None:
    """Negative: null guidance is omitted — `{}`, not `{"null": ...}` — per api-spec.md.

    This is also the real response until step 6b writes the first `writer_guidance`
    row: the field exists and is correctly empty.
    """
    with container.session() as session:
        card = _card(session, guidance_version=None)
        _review(session, card, _local(0, 8), rating=1, state_before="review")
        session.commit()
        assert _stats(session).lapse_rate_by_guidance_version == {}


def test_lapse_rate_by_guidance_version_keys_are_strings(container: Container) -> None:
    with container.session() as session:
        session.add(WriterGuidance(version=2, guidance="Prefer one idea per card.", user_id=1))
        card = _card(session, guidance_version=2)
        _review(session, card, _local(0, 8), rating=1, state_before="review")
        _review(session, card, _local(-1, 8), rating=3)
        session.commit()
        lapse_rate_by_guidance_version = _stats(session).lapse_rate_by_guidance_version
        assert lapse_rate_by_guidance_version == {"2": pytest.approx(0.5)}
        assert all(isinstance(key, str) for key in lapse_rate_by_guidance_version)


def test_curation_yield_is_approved_cards_over_highlights_ingested(container: Container) -> None:
    with container.session() as session:
        # 4 highlights that produced nothing, plus 4 cards (one highlight each):
        # 3 approved ÷ 8 ingested.
        for _ in range(4):
            _highlight(session)
        for _ in range(3):
            _card(session, status="approved")
        _card(session, status="pending_review")
        session.commit()
        assert _stats(session).curation_yield == pytest.approx(3 / 8)


def test_forecast_is_day_granularity_from_card_state_due(container: Container) -> None:
    """Two cards due at different times on one day are one entry with `due: 2`."""
    with container.session() as session:
        morning_card = _card(session)
        evening_card = _card(session)
        later_card = _card(session)
        session.add_all(
            [
                CardState(card_id=morning_card.id, state="review", due=_local(1, 8), user_id=1),
                CardState(card_id=evening_card.id, state="review", due=_local(1, 22), user_id=1),
                CardState(card_id=later_card.id, state="review", due=_local(3, 8), user_id=1),
            ]
        )
        session.commit()
        forecast = _stats(session).forecast
        assert [(entry.date, entry.due) for entry in forecast] == [
            (TODAY + timedelta(days=1), 2),
            (TODAY + timedelta(days=3), 1),
        ]


def test_forecast_excludes_suspended_cards(container: Container) -> None:
    """Negative: ADR-008's exclusion is at the query; buried cards over-promise.

    The expired bury (`suspended_until` in the past) clears itself and stays in.
    """
    with container.session() as session:
        buried_card = _card(session, suspended_until=NOW_UTC + timedelta(hours=6))
        in_rotation_card = _card(session)
        cleared_card = _card(session, suspended_until=NOW_UTC - timedelta(hours=1))
        session.add_all(
            [
                CardState(card_id=buried_card.id, state="review", due=_local(1, 8), user_id=1),
                CardState(card_id=in_rotation_card.id, state="review", due=_local(1, 8), user_id=1),
                CardState(card_id=cleared_card.id, state="review", due=_local(1, 8), user_id=1),
            ]
        )
        session.commit()
        forecast = _stats(session).forecast
        assert [(entry.date, entry.due) for entry in forecast] == [
            (TODAY + timedelta(days=1), 2),
        ]


def test_stats_on_empty_database_returns_zeros_not_errors(client: TestClient) -> None:
    """Negative: no division by zero anywhere; every key of the documented shape is present.

    Retention is the one figure that is null rather than zero: with no review in
    the window there is nothing to report, and "0%" would be a claim about recall
    that the data does not make (issue #190).
    """
    response = client.get("/stats", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    assert response.json() == {
        "streak_days": 0,
        "reviews_today": 0,
        "retention_30d": None,
        "retention_30d_reviews": 0,
        "lapse_rate_by_type": {},
        "lapse_rate_by_guidance_version": {},
        "curation_yield": 0.0,
        "next_due_at": None,
        "forecast": [],
    }


def test_next_due_at_is_the_earliest_future_due(container: Container) -> None:
    """Two approved cards due at two future times: the earlier one is the answer.

    A `pending_review` card due sooner still does not win — nothing is
    scheduled before approval (hard rule 1) — and an overdue card is due now,
    not next, so it is not the figure either.
    """
    with container.session() as session:
        earlier_card = _card(session)
        later_card = _card(session)
        unapproved_card = _card(session, status="pending_review")
        overdue_card = _card(session)
        session.add_all(
            [
                CardState(card_id=earlier_card.id, state="review", due=_local(1, 8), user_id=1),
                CardState(card_id=later_card.id, state="review", due=_local(2, 8), user_id=1),
                CardState(card_id=unapproved_card.id, state="new", due=_local(0, 6), user_id=1),
                CardState(card_id=overdue_card.id, state="review", due=_local(-1, 8), user_id=1),
            ]
        )
        session.commit()
        assert _stats(session).next_due_at == _local(1, 8)


def test_next_due_at_is_null_when_nothing_is_scheduled(container: Container) -> None:
    """Negative: an approved card with no `card_state` row is unscheduled, so
    there is no next due — null, never a made-up instant."""
    with container.session() as session:
        _card(session)
        session.commit()
        assert _stats(session).next_due_at is None


def test_next_due_at_ignores_suspended_cards(container: Container) -> None:
    """Negative: the buried card is due sooner than every other card, but a
    suspended card is out of rotation (ADR-008), so it does not become the answer."""
    with container.session() as session:
        buried_card = _card(session, suspended_until=NOW_UTC + timedelta(hours=6))
        in_rotation_card = _card(session)
        session.add_all(
            [
                CardState(card_id=buried_card.id, state="review", due=_local(0, 6), user_id=1),
                CardState(card_id=in_rotation_card.id, state="review", due=_local(1, 8), user_id=1),
            ]
        )
        session.commit()
        assert _stats(session).next_due_at == _local(1, 8)


def test_stats_requires_the_api_key(client: TestClient) -> None:
    response = client.get("/stats")

    assert response.status_code == 401
    assert response.json() == {
        "status": 401,
        "detail": "Invalid or missing X-API-Key header.",
    }


def test_retention_is_none_when_no_reviews_in_the_window(container: Container) -> None:
    """Negative: "never reviewed" is not "0% retention" (issue #190).

    `0.0` for an empty window renders as "0%", which reads as catastrophic
    recall when the truth is that there is nothing to report. The absence is
    the answer, so the field is null and the app renders the no-data treatment.
    """
    with container.session() as session:
        card = _card(session)
        _review(session, card, _local(-40, 8), rating=3)  # outside the 30-day window
        session.commit()
        assert _stats(session).retention_30d is None


def test_retention_excludes_an_again_from_learning(container: Container) -> None:
    """An Again during learning is not a lapse, so retention stays 1.0.

    This is the definition docs/design/design-system.md pins and the reason the
    figure reads 100% on a young collection (issue #190). Locking it here keeps
    a well-meaning "fix" that redefines a lapse from landing silently.
    """
    with container.session() as session:
        card = _card(session)
        _review(session, card, _local(-1, 8), rating=1, state_before="learning")
        _review(session, card, _local(-2, 8), rating=1, state_before="relearning")
        _review(session, card, _local(-3, 8), rating=3)
        session.commit()
        assert _stats(session).retention_30d == 1.0


def test_retention_counts_an_again_from_review(container: Container) -> None:
    """The other half of the definition: an Again from `review` state does lapse."""
    with container.session() as session:
        card = _card(session)
        _review(session, card, _local(-1, 8), rating=1, state_before="review")
        _review(session, card, _local(-2, 8), rating=3)
        _review(session, card, _local(-3, 8), rating=3)
        _review(session, card, _local(-4, 8), rating=3)
        session.commit()
        assert _stats(session).retention_30d == pytest.approx(0.75)


def test_retention_window_excludes_a_review_older_than_30_local_days(
    container: Container,
) -> None:
    """Day −31 is outside the window; only the in-window lapse counts.

    Day −29 is the oldest day still inside it, so a lapse there halves the
    figure while the day −31 lapse leaves it alone — if the boundary were
    wrong the share would be 1/3, not 1/2.
    """
    with container.session() as session:
        card = _card(session)
        _review(session, card, _local(-31, 8), rating=1, state_before="review")  # outside
        _review(session, card, _local(-29, 8), rating=1, state_before="review")  # inside
        _review(session, card, _local(0, 8), rating=3)
        session.commit()
        assert _stats(session).retention_30d == pytest.approx(0.5)
