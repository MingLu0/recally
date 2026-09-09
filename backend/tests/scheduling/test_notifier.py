"""Step 5b gate: the one-push-per-day notifier (hard rule 8; hard rule 2).

Spec: docs/agents.md, "Notifier"; docs/architecture.md, "Notifier"; docs/data-model.md,
`devices` and `push_runs`; docs/config.md, "Scheduling and push"; docs/android.md,
"Push notifications".

`firebase-admin` is mocked in every test — no test sends a real push, the same rule
as "tests never call a real LLM provider" (AGENTS.md). The default firebase app is
pre-initialised with a placeholder so no credentials file is ever read; `send` is
replaced by a capture (or a raise) while the real `Message` classes stay in use, so
the tests can assert on the exact payload 5c renders.

Ticks are expressed in local wall time (`Pacific/Auckland`, the Settings default) and
converted to the naive UTC the `DateTime` columns store, so the window and day
boundary being exercised are the timezone's, never UTC's by accident.
"""

import ast
import itertools
from collections.abc import Callable, Iterator
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import firebase_admin
import pytest
from firebase_admin import exceptions, messaging
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

import recally.scheduling
from recally.config import Settings
from recally.container import Container
from recally.models import (
    Base,
    Book,
    Card,
    CardState,
    CuratedUnit,
    CuratedUnitHighlight,
    Device,
    Highlight,
    IngestRun,
    PushRun,
    ReviewLog,
)
from recally.scheduling import notifier
from recally.services import reviews

TEST_API_KEY = "test-key-not-a-real-secret"
LOCAL_TZ = ZoneInfo("Pacific/Auckland")  # the Settings default for RECALLY_TIMEZONE

_PROVENANCE_COUNTER = itertools.count(1)


@pytest.fixture
def make_container() -> Iterator[Callable[..., Container]]:
    """Container factory on fresh in-memory databases, with settings overrides
    (same shape as tests/scheduling/test_optimizer.py)."""
    built: list[Container] = []

    def factory(**settings_overrides: object) -> Container:
        engine = create_engine(
            "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
        )
        Base.metadata.create_all(engine)
        settings = Settings(
            RECALLY_DATABASE_URL="sqlite://",
            RECALLY_API_KEY=TEST_API_KEY,
            FIREBASE_CREDENTIALS_FILE="/nonexistent/test-service-account.json",
            **settings_overrides,  # type: ignore[arg-type]
        )
        container = Container(settings, engine=engine)
        built.append(container)
        return container

    try:
        yield factory
    finally:
        for container in built:
            container.engine.dispose()


@pytest.fixture
def container(make_container: Callable[..., Container]) -> Container:
    return make_container()


@pytest.fixture
def _firebase_app(monkeypatch: pytest.MonkeyPatch) -> None:
    """A pre-initialised default firebase-admin app, so the notifier never reads the
    credentials file. `firebase_admin._apps` is process-global; monkeypatch restores it."""
    monkeypatch.setitem(firebase_admin._apps, firebase_admin._DEFAULT_APP_NAME, object())


@pytest.fixture
def fcm_sends(monkeypatch: pytest.MonkeyPatch, _firebase_app: None) -> list[messaging.Message]:
    """Capture every `messaging.send` call. No test sends a real push."""
    sent: list[messaging.Message] = []

    def fake_send(message: messaging.Message, **_kwargs: object) -> str:
        sent.append(message)
        return "projects/test/messages/fake-message-id"

    monkeypatch.setattr(messaging, "send", fake_send)
    return sent


def _utc(local: datetime) -> datetime:
    """A wall time in RECALLY_TIMEZONE as the naive UTC the columns store."""
    return local.replace(tzinfo=LOCAL_TZ).astimezone(timezone.utc).replace(tzinfo=None)


def _seed_book(session: Session, *, title: str, isbn: str) -> Book:
    book = Book(title=title, source="oreilly", external_id=isbn, user_id=1)
    session.add(book)
    session.flush()
    return book


def _seed_card(
    session: Session,
    book: Book,
    *,
    due: datetime,
    last_review: datetime | None = None,
    suspended_until: datetime | None = None,
) -> Card:
    """One approved card with the full provenance chain, due at the given instant.

    `last_review=None` is a never-reviewed (new) card; a value makes it a graduated
    review card. Mirrors the 3a approval gate: an approved card carries `card_state`.
    """
    suffix = next(_PROVENANCE_COUNTER)
    run = IngestRun(filename=f"seed-{suffix}-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    highlight = Highlight(
        book_id=book.id,
        chapter="3. Error Analysis",
        raw_text="An LLM pipeline's behaviour only makes sense end-to-end.",
        dedupe_key=f"00000000-0000-0000-0000-{suffix:012d}",
        source="oreilly",
        highlighted_at=due.date(),
        export_position=suffix,
        user_id=1,
    )
    session.add(highlight)
    session.flush()
    unit = CuratedUnit(
        ingest_run_id=run.id, curated_text="…", tags=["evals"], decision="keep", user_id=1
    )
    session.add(unit)
    session.flush()
    session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
    is_new = last_review is None
    card = Card(
        unit_id=unit.id,
        type="qa",
        front="Why evaluate traces rather than individual steps?",
        back="An LLM pipeline's behaviour only makes sense end-to-end.",
        original_front="Why evaluate traces rather than individual steps?",
        original_back="An LLM pipeline's behaviour only makes sense end-to-end.",
        tags=["evals"],
        status="approved",
        suspended_until=suspended_until,
        approved_at=due - timedelta(days=1),
        model="claude-sonnet-5",
        user_id=1,
    )
    session.add(card)
    session.flush()
    session.add(
        CardState(
            card_id=card.id,
            state="learning" if is_new else "review",
            step=0 if is_new else None,
            stability=None if is_new else 12.5,
            difficulty=None if is_new else 4.2,
            due=due,
            last_review=last_review,
            user_id=1,
        )
    )
    session.commit()
    return card


def _seed_device(session: Session, *, token: str = "fcm-token-aaa") -> Device:
    device = Device(fcm_token=token, platform="android", user_id=1)
    session.add(device)
    session.commit()
    return device


def _seed_push_run(
    session: Session, device: Device, *, sent_at: datetime, card_ids: list[int], due_count: int
) -> PushRun:
    push_run = PushRun(
        device_id=device.id, sent_at=sent_at, card_ids=card_ids, due_count=due_count, user_id=1
    )
    session.add(push_run)
    session.commit()
    return push_run


def _seed_review_log(session: Session, card: Card, *, rated_at: datetime) -> None:
    """A review_logs row written directly: the unreviewed-batch gate reads this table
    and nothing else (docs/data-model.md, `push_runs`), so the card's FSRS state is
    deliberately left alone."""
    session.add(
        ReviewLog(
            card_id=card.id,
            rated_at=rated_at,
            rating=3,
            response_ms=5000,
            scheduled_days=1,
            state_before="review",
            user_id=1,
        )
    )
    session.commit()


def _push_runs(container: Container) -> list[PushRun]:
    with container.session() as session:
        return list(session.scalars(select(PushRun).order_by(PushRun.id)))


def _last_push_at(container: Container, device_id: int) -> datetime | None:
    with container.session() as session:
        device = session.get(Device, device_id)
        assert device is not None
        return device.last_push_at


# --- The roadmap gate (the merge requirement) -------------------------------------


def test_sends_once_inside_the_push_window(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """Due cards, a registered device, no prior push_runs row: one tick inside the
    window sends exactly one message and writes exactly one push_runs row."""
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        card = _seed_card(
            session,
            book,
            due=_utc(datetime(2026, 1, 10, 9, 30)),
            last_review=_utc(datetime(2026, 1, 8, 9, 0)),
        )
        device = _seed_device(session)
    tick = _utc(datetime(2026, 1, 10, 10, 0))

    notifier.send_due_push(container, now=tick)

    assert len(fcm_sends) == 1, f"expected exactly one send, got {len(fcm_sends)}"
    message = fcm_sends[0]
    assert message.token == "fcm-token-aaa"
    assert message.data is not None
    assert message.data["body"] == "1 card due from Evals for AI Engineers"
    runs = _push_runs(container)
    assert len(runs) == 1
    assert runs[0].sent_at == tick
    assert runs[0].card_ids == [card.id]
    assert _last_push_at(container, device.id) == tick


def test_does_not_send_twice_in_the_same_day(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """NEGATIVE: a second tick later the same local day, still inside the window,
    sends nothing. The first batch is reviewed between the ticks so only the
    one-push-per-day gate (`devices.last_push_at`, the denormalised column for this
    check — docs/data-model.md) can be doing the suppressing."""
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        card = _seed_card(
            session,
            book,
            due=_utc(datetime(2026, 1, 10, 9, 30)),
            last_review=_utc(datetime(2026, 1, 8, 9, 0)),
        )
        _seed_device(session)
    first_tick = _utc(datetime(2026, 1, 10, 10, 0))
    notifier.send_due_push(container, now=first_tick)
    assert len(fcm_sends) == 1
    with container.session() as session:
        _seed_review_log(session, card, rated_at=first_tick + timedelta(minutes=30))

    notifier.send_due_push(container, now=_utc(datetime(2026, 1, 10, 15, 0)))

    assert len(fcm_sends) == 1, "a second push went out on the same local day (hard rule 8)"
    assert len(_push_runs(container)) == 1


def test_skips_while_previous_batch_is_unreviewed(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """NEGATIVE: the latest push_runs row holds a card with no review_logs entry
    after its sent_at, so the next day's tick inside the window sends nothing
    (hard rule 8; docs/data-model.md, `push_runs`)."""
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        card = _seed_card(
            session,
            book,
            due=_utc(datetime(2026, 1, 9, 9, 30)),
            last_review=_utc(datetime(2026, 1, 7, 9, 0)),
        )
        device = _seed_device(session)
        _seed_push_run(
            session,
            device,
            sent_at=_utc(datetime(2026, 1, 9, 10, 0)),
            card_ids=[card.id],
            due_count=1,
        )

    notifier.send_due_push(container, now=_utc(datetime(2026, 1, 10, 10, 0)))

    assert fcm_sends == [], "pushed while the previous batch is still unreviewed (hard rule 8)"
    assert len(_push_runs(container)) == 1, "a push_runs row means a push actually went out"


def test_sends_nothing_outside_the_push_window(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """NEGATIVE: 07:00 and 22:00 local are both outside the default 08:00-21:00
    window; each tick sends nothing even with due cards and no prior push."""
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        _seed_card(
            session,
            book,
            due=_utc(datetime(2026, 1, 10, 6, 0)),
            last_review=_utc(datetime(2026, 1, 8, 6, 0)),
        )
        _seed_device(session)

    for hour in (7, 22):
        notifier.send_due_push(container, now=_utc(datetime(2026, 1, 10, hour, 0)))

    assert fcm_sends == [], "pushed outside PUSH_WINDOW (hard rule 8)"
    assert _push_runs(container) == []


# --- The policy in detail ----------------------------------------------------------


def test_sends_again_once_the_previous_batch_is_reviewed(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """The mirror of the skip test — without it a notifier that never sends passes
    the skip test: a review_logs row after sent_at for every card_id unblocks the
    next day's push."""
    sent_yesterday = _utc(datetime(2026, 1, 9, 10, 0))
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        card = _seed_card(
            session,
            book,
            due=_utc(datetime(2026, 1, 9, 9, 30)),
            last_review=_utc(datetime(2026, 1, 7, 9, 0)),
        )
        device = _seed_device(session)
        _seed_push_run(session, device, sent_at=sent_yesterday, card_ids=[card.id], due_count=1)
        device.last_push_at = sent_yesterday
        _seed_review_log(session, card, rated_at=sent_yesterday + timedelta(hours=1))

    notifier.send_due_push(container, now=_utc(datetime(2026, 1, 10, 10, 0)))

    assert len(fcm_sends) == 1, "the reviewed previous batch should have unblocked today's push"
    assert len(_push_runs(container)) == 2


def test_a_review_before_sent_at_does_not_count_as_reviewed(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """NEGATIVE: a review_logs row *earlier* than sent_at leaves the card unreviewed
    — "no review_logs entry after sent_at" (docs/data-model.md) — and the push stays
    suppressed."""
    sent_yesterday = _utc(datetime(2026, 1, 9, 10, 0))
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        card = _seed_card(
            session,
            book,
            due=_utc(datetime(2026, 1, 9, 9, 30)),
            last_review=_utc(datetime(2026, 1, 7, 9, 0)),
        )
        device = _seed_device(session)
        _seed_push_run(session, device, sent_at=sent_yesterday, card_ids=[card.id], due_count=1)
        _seed_review_log(session, card, rated_at=sent_yesterday - timedelta(hours=1))

    notifier.send_due_push(container, now=_utc(datetime(2026, 1, 10, 10, 0)))

    assert fcm_sends == [], "a review older than the push must not unblock the next one"
    assert len(_push_runs(container)) == 1


def test_day_boundary_uses_recally_timezone_not_utc(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """Two ticks on the same UTC day but different Pacific/Auckland days: the second
    one sends, because the one-push-per-day boundary is RECALLY_TIMEZONE's
    (docs/config.md). January is NZDT (UTC+13): 20:30 on the 10th and 09:00 on the
    11th local are 07:30 and 20:00 on the 10th UTC."""
    first_tick = _utc(datetime(2026, 1, 10, 20, 30))
    second_tick = _utc(datetime(2026, 1, 11, 9, 0))
    assert first_tick.date() == second_tick.date(), "the trap: both ticks share one UTC day"
    assert (
        first_tick.replace(tzinfo=timezone.utc).astimezone(LOCAL_TZ).date()
        != second_tick.replace(tzinfo=timezone.utc).astimezone(LOCAL_TZ).date()
    ), "but they fall on different local days"
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        card = _seed_card(
            session,
            book,
            due=first_tick - timedelta(minutes=30),
            last_review=first_tick - timedelta(days=2),
        )
        _seed_device(session)
    notifier.send_due_push(container, now=first_tick)
    assert len(fcm_sends) == 1
    with container.session() as session:
        _seed_review_log(session, card, rated_at=first_tick + timedelta(minutes=30))

    notifier.send_due_push(container, now=second_tick)

    assert len(fcm_sends) == 2, "a UTC day boundary suppressed a push on a new local day"
    assert len(_push_runs(container)) == 2


def test_no_due_cards_sends_nothing_and_writes_no_push_run(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """NEGATIVE: an empty batch inside the window is silent — the trigger fires
    "when due cards exist" (docs/agents.md, Notifier). The control second tick proves
    the first tick's silence is the empty-batch gate, not a dead notifier."""
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        # Not due until tomorrow, so today's batch is empty.
        _seed_card(session, book, due=_utc(datetime(2026, 1, 11, 10, 0)))
        _seed_device(session)

    notifier.send_due_push(container, now=_utc(datetime(2026, 1, 10, 10, 0)))

    assert fcm_sends == [], "pushed with no due cards"
    assert _push_runs(container) == []
    # Control: once a card is due, the same notifier on the same day... would be the
    # per-day gate, so the control ticks the next day to isolate the batch check.
    with container.session() as session:
        _seed_card(session, book, due=_utc(datetime(2026, 1, 11, 9, 0)))
    notifier.send_due_push(container, now=_utc(datetime(2026, 1, 11, 10, 0)))
    assert len(fcm_sends) == 1, "control: with due cards the notifier sends"


# --- The row and the message -------------------------------------------------------


def test_push_run_records_the_card_ids_it_announced(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """`card_ids` equals the ids in the batch (the unreviewed gate loops over them)
    and `due_count` equals the number the message text shows."""
    tick = _utc(datetime(2026, 1, 10, 10, 0))
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        first = _seed_card(session, book, due=tick - timedelta(hours=2))
        second = _seed_card(
            session, book, due=tick - timedelta(hours=1), last_review=tick - timedelta(days=2)
        )
        _seed_device(session)

    notifier.send_due_push(container, now=tick)

    runs = _push_runs(container)
    assert len(runs) == 1
    assert set(runs[0].card_ids) == {first.id, second.id}
    assert runs[0].due_count == 2
    assert len(fcm_sends) == 1
    body = fcm_sends[0].data["body"] if fcm_sends[0].data else ""
    assert body.startswith("2 cards due from"), f"due_count disagrees with the copy: {body!r}"


def test_failed_send_writes_no_push_run_row_and_no_last_push_at(
    container: Container, monkeypatch: pytest.MonkeyPatch, _firebase_app: None
) -> None:
    """NEGATIVE: a failed send writes nothing — a push_runs row means "a push
    actually went out" (docs/data-model.md), and a phantom row would suppress
    tomorrow's push too. The failure is logged, never raised."""
    tick = _utc(datetime(2026, 1, 10, 10, 0))

    def failing_send(message: messaging.Message, **_kwargs: object) -> str:
        raise exceptions.FirebaseError("unavailable", "FCM is down")

    monkeypatch.setattr(messaging, "send", failing_send)
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        _seed_card(session, book, due=tick - timedelta(hours=1))
        device = _seed_device(session)

    notifier.send_due_push(container, now=tick)  # must not raise

    assert _push_runs(container) == [], "a failed send wrote a phantom push_runs row"
    assert _last_push_at(container, device.id) is None


def test_message_names_the_book_with_the_most_due_cards(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """Due cards across two books: the text names the book contributing the most
    due cards, and the count is the total across all books, not that book's share
    (docs/android.md, "Push notifications")."""
    tick = _utc(datetime(2026, 1, 10, 10, 0))
    with container.session() as session:
        evals = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        dmls = _seed_book(session, title="Designing Machine Learning Systems", isbn="9781098107963")
        _seed_card(session, evals, due=tick - timedelta(hours=3))
        _seed_card(session, evals, due=tick - timedelta(hours=2))
        _seed_card(session, dmls, due=tick - timedelta(hours=1))
        _seed_device(session)

    notifier.send_due_push(container, now=tick)

    assert len(fcm_sends) == 1
    message = fcm_sends[0]
    assert message.data is not None
    assert message.data["body"] == "3 cards due from Evals for AI Engineers"


def test_message_is_a_data_message_not_a_notification_payload(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """The app renders the notification itself (docs/android.md): the FCM call must
    be a high-priority *data* message — a `data` block and no `notification` block."""
    tick = _utc(datetime(2026, 1, 10, 10, 0))
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        _seed_card(session, book, due=tick - timedelta(hours=1))
        _seed_device(session)

    notifier.send_due_push(container, now=tick)

    assert len(fcm_sends) == 1
    message = fcm_sends[0]
    assert message.notification is None, "5c renders the notification; do not send a payload"
    assert message.data, "expected a non-empty data block"
    assert message.android is not None and message.android.priority == "high"


# --- Hard rules --------------------------------------------------------------------


def test_notifier_makes_no_llm_call(
    container: Container, fcm_sends: list[messaging.Message], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Hard rule 2: the notifier is deterministic. With the LLM caller rigged to
    explode, a full notify tick — one that actually sends — still completes."""
    from recally.llm import LlmCaller

    def explode(*_args: object, **_kwargs: object) -> None:
        raise AssertionError("the notifier made an LLM call (hard rule 2)")

    monkeypatch.setattr(LlmCaller, "__call__", explode)
    tick = _utc(datetime(2026, 1, 10, 10, 0))
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        _seed_card(session, book, due=tick - timedelta(hours=1))
        _seed_device(session)

    notifier.send_due_push(container, now=tick)

    assert len(fcm_sends) == 1, "a full tick should have sent the push"
    assert len(_push_runs(container)) == 1


def test_notifier_does_not_import_fastapi() -> None:
    """Nothing below api/ imports FastAPI (docs/backend.md, "Layering" rule 1):
    APScheduler, an external cron and the jobs router all call this module, and
    only one of those is an HTTP request. AST-walked like the other import tests
    (the #31 shape) so formatting cannot defeat it."""
    module_path = Path(recally.scheduling.__file__).resolve().parent / "notifier.py"
    tree = ast.parse(module_path.read_text(encoding="utf-8"), filename=str(module_path))

    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module.split(".")[0])

    assert "fastapi" not in imported and "starlette" not in imported, (
        f"scheduling/notifier.py imports {sorted(imported & {'fastapi', 'starlette'})}: "
        "nothing below api/ imports FastAPI (docs/backend.md, 'Layering' rule 1)"
    )


def test_notifier_reuses_the_shared_due_predicate(
    container: Container, fcm_sends: list[messaging.Message]
) -> None:
    """The notifier's batch is exactly what the reviews service returns for the same
    clock (ADR-008 drift rule) — asserted against `list_due` itself, so a second copy
    of "is this card due" (buried/suspended handling, the new-card cap) cannot drift
    from what the app's Today screen shows."""
    tick = _utc(datetime(2026, 1, 10, 10, 0))
    with container.session() as session:
        book = _seed_book(session, title="Evals for AI Engineers", isbn="9781098188283")
        due_reviewed = _seed_card(
            session, book, due=tick - timedelta(hours=2), last_review=tick - timedelta(days=2)
        )
        new_card = _seed_card(session, book, due=tick - timedelta(hours=1))
        _seed_card(session, book, due=tick + timedelta(days=1))  # not due yet
        _seed_card(  # due but suspended (ADR-008): never announced
            session,
            book,
            due=tick - timedelta(hours=1),
            suspended_until=tick + timedelta(days=1),
        )
        _seed_device(session)

    with container.session() as session:
        expected = reviews.list_due(
            session,
            container.fsrs_scheduler(session),
            new_cards_per_day=container.settings.new_cards_per_day,
            now=tick,
        )

    notifier.send_due_push(container, now=tick)

    runs = _push_runs(container)
    assert len(runs) == 1
    assert runs[0].card_ids == [entry.id for entry in expected.cards]
    assert set(runs[0].card_ids) == {due_reviewed.id, new_card.id}
    assert len(fcm_sends) == 1
    body = fcm_sends[0].data["body"] if fcm_sends[0].data else ""
    assert body.startswith("2 cards due from")
