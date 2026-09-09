"""The one-push-per-day notifier (hard rule 8; docs/architecture.md, "Notifier").

Deterministic — no LLM call, no FastAPI (hard rule 2; docs/backend.md, "Layering"
rule 1): the text is a format string, and the same tick runs from APScheduler,
external cron and `POST /jobs/run`.

The policy (hard rule 8) is four independent gates, all checked on every tick:

1. Inside `PUSH_WINDOW` — a local-time range in `RECALLY_TIMEZONE`, parsed from one
   env string. Outside it, nothing is sent.
2. At most one push per day, where the day boundary is `RECALLY_TIMEZONE`'s, not
   UTC's (docs/config.md). `devices.last_push_at` is the denormalised column for
   this check (docs/data-model.md, `devices`).
3. No push while the previous batch is unreviewed: the latest `push_runs` row is
   loaded and its `card_ids` looped over in Python, looking for a `review_logs`
   entry after `sent_at` for each (docs/data-model.md — a join table for this is
   deferred to the Postgres cutover, ADR-004).
4. No due cards, no push — the trigger fires "when due cards exist"
   (docs/agents.md, Notifier).

The batch is exactly what `GET /reviews/due` returns for the same clock — the shared
`services/reviews.list_due`, never a second copy of "is this card due" (ADR-008): a
notifier that disagrees with the app's Today screen is the visible symptom of that
drift. The message is a high-priority *data* message (FCM HTTP v1 via
`firebase-admin` — the only import site, the same rule `llm.py` follows for litellm);
the app renders it (docs/android.md, "Push notifications").

Only a successful send writes a `push_runs` row and stamps `devices.last_push_at`:
a row means "a push actually went out" (docs/data-model.md), and a phantom row from
a failed send would suppress tomorrow's push too. A failed send is logged, never
retried and never raised (single user, one device — token cleanup is scoped out).
"""

import logging
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING
from zoneinfo import ZoneInfo

import firebase_admin
from firebase_admin import credentials, exceptions, messaging
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from recally.models import Device, PushRun, ReviewLog
from recally.models.base import utc_now
from recally.services.reviews import DueCard, list_due

if TYPE_CHECKING:
    from recally.container import Container

logger = logging.getLogger(__name__)


def send_due_push(container: "Container", *, now: datetime | None = None) -> None:
    """One tick of the notify job: announce today's due cards to every registered
    device, subject to the hard-rule-8 gates above.

    `now` is the naive-UTC clock the whole tick runs on (the same injection pattern
    as `list_due(now=...)`); the APScheduler and jobs.py callers leave it to wall
    time.
    """
    settings = container.settings
    moment = now or utc_now()
    zone = ZoneInfo(settings.timezone)
    local_now = _local_date_or_time(moment, zone)
    window_start, window_end = settings.push_window_bounds
    if not window_start <= local_now.time() < window_end:
        logger.info(
            "notifier: %s local is outside PUSH_WINDOW %s; skipping",
            local_now.time().isoformat(timespec="minutes"),
            settings.push_window,
        )
        return
    if settings.firebase_credentials_file is None:
        logger.warning("notifier: FIREBASE_CREDENTIALS_FILE is not set; skipping the push")
        return
    with container.session() as session:
        due_list = list_due(
            session,
            container.fsrs_scheduler(session),
            new_cards_per_day=settings.new_cards_per_day,
            now=moment,
        )
        if not due_list.cards:
            logger.info("notifier: no due cards; nothing to announce")
            return
        if _previous_batch_unreviewed(session):
            logger.info("notifier: the previous batch is still unreviewed; skipping")
            return
        card_ids = [entry.id for entry in due_list.cards]
        body = _message_body(due_list.cards)
        today = local_now.date()
        for device in session.scalars(select(Device)).all():
            if device.last_push_at is not None and _local_date(device.last_push_at, zone) == today:
                continue
            if _send(device, body, len(card_ids), settings.firebase_credentials_file):
                session.add(
                    PushRun(
                        device_id=device.id,
                        sent_at=moment,
                        card_ids=card_ids,
                        due_count=len(card_ids),
                        user_id=device.user_id,
                    )
                )
                device.last_push_at = moment
                session.commit()


def _local_date_or_time(moment: datetime, zone: ZoneInfo) -> datetime:
    """The naive-UTC instant as a local wall time in `zone`."""
    return moment.replace(tzinfo=timezone.utc).astimezone(zone)


def _local_date(moment: datetime, zone: ZoneInfo) -> date:
    """The local calendar date of a stored naive-UTC instant — the day boundary for
    "one push per day" is RECALLY_TIMEZONE's, never UTC's (docs/config.md)."""
    return _local_date_or_time(moment, zone).date()


def _previous_batch_unreviewed(session: Session) -> bool:
    """True when any card in the latest push_runs row has no review_logs entry after
    that row's sent_at. The loop runs in Python over one small batch per day
    (docs/data-model.md, `push_runs`; the join table waits for Postgres, ADR-004)."""
    latest = session.scalar(
        select(PushRun).order_by(PushRun.sent_at.desc(), PushRun.id.desc()).limit(1)
    )
    if latest is None:
        return False
    for card_id in latest.card_ids:
        reviewed_count = session.scalar(
            select(func.count())
            .select_from(ReviewLog)
            .where(ReviewLog.card_id == card_id, ReviewLog.rated_at > latest.sent_at)
        )
        if not reviewed_count:
            return True
    return False


def _message_body(cards: list[DueCard]) -> str:
    """ "12 cards due from Evals for AI Engineers" (docs/android.md). The named book
    is the one contributing the most due cards — a tie breaks to the book whose card
    is due earliest, since the batch arrives due-sorted — and the count is the total
    across all books."""
    book = Counter(entry.book for entry in cards).most_common(1)[0][0]
    total = len(cards)
    noun = "card" if total == 1 else "cards"
    return f"{total} {noun} due from {book}"


def _send(device: Device, body: str, due_count: int, credentials_file: Path) -> bool:
    """One high-priority data message via FCM HTTP v1. False on a failed send —
    logged, never retried, never raised, and no push_runs row written (a row means
    a push actually went out; a phantom one would suppress tomorrow's push too)."""
    _ensure_firebase_app(credentials_file)
    message = messaging.Message(
        token=device.fcm_token,
        data={"title": "Recally", "body": body, "due_count": str(due_count)},
        android=messaging.AndroidConfig(priority="high"),
    )
    try:
        messaging.send(message)
    except exceptions.FirebaseError:
        logger.exception(
            "notifier: FCM send failed for device %d; writing no push_runs row", device.id
        )
        return False
    logger.info("notifier: sent %r to device %d", body, device.id)
    return True


def _ensure_firebase_app(credentials_file: Path) -> None:
    """Initialise the default firebase-admin app once per process (the SDK is
    process-global; `_apps` is its own registry of initialised apps)."""
    if not firebase_admin._apps:
        firebase_admin.initialize_app(credentials.Certificate(str(credentials_file)))
