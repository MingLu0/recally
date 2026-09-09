"""Device registration behind `POST /devices` (docs/api-spec.md, "Devices").

Deterministic, no LLM (hard rule 2), and no FastAPI (docs/backend.md, "Layering",
rule 1). Idempotent on `fcm_token`: the UNIQUE constraint on the column is the
backstop, never the mechanism — an existing token is detected and its id returned,
so re-registering can never surface an `IntegrityError` as a 500.
"""

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from recally.models.scheduling import Device


def register_device(session: Session, *, fcm_token: str, platform: str, user_id: int = 1) -> Device:
    """Return the device for `fcm_token`, creating the row on first registration.

    `last_push_at` is left NULL: it is denormalised from `push_runs` by the
    notifier (step 5b), not set at registration.
    """
    existing = session.scalar(select(Device).where(Device.fcm_token == fcm_token))
    if existing is not None:
        return existing
    device = Device(fcm_token=fcm_token, platform=platform, user_id=user_id)
    session.add(device)
    try:
        session.commit()
    except IntegrityError:
        # Lost a race with a concurrent registration of the same token: the row
        # now exists, so the idempotent answer is still "the existing id".
        session.rollback()
        raced = session.scalar(select(Device).where(Device.fcm_token == fcm_token))
        if raced is None:  # pragma: no cover - the constraint fired on this token
            raise
        return raced
    return device
