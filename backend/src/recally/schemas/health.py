"""Response schema for the health probes (docs/api-spec.md, "Health")."""

from pydantic import BaseModel


class HealthResponse(BaseModel):
    """`{"status": "ok"}` — the unauthenticated probe's whole body."""

    status: str


class AuthedHealthResponse(HealthResponse):
    """`GET /health/auth` — the probe plus the running build's version.

    The version rides on the authenticated probe alone: it is what the Android
    Settings connection test already calls, and the unauthenticated one is a
    load-balancer target that should disclose nothing (issue #195).
    """

    version: str
