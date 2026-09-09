"""Shared field types for the API schemas.

Every timestamp in docs/api-spec.md is UTC with a `Z` suffix
(`"2026-09-05T07:55:00Z"`). The database columns are *naive* UTC
(`models/base.py`, `utc_now`), and Pydantic renders a naive datetime with no
offset at all — which `java.time.Instant.parse` on the Android client rejects,
so a whole response fails to map. [UtcDatetime] is the single place that adds
the offset back on the way out.
"""

from datetime import datetime, timezone
from typing import Annotated

from pydantic import PlainSerializer


def _to_utc_z(value: datetime) -> str:
    """Render as RFC 3339 UTC. A naive value is assumed to already be UTC."""
    as_utc = (
        value.replace(tzinfo=timezone.utc)
        if value.tzinfo is None
        else value.astimezone(timezone.utc)
    )
    return as_utc.isoformat().replace("+00:00", "Z")


UtcDatetime = Annotated[datetime, PlainSerializer(_to_utc_z, return_type=str, when_used="json")]
