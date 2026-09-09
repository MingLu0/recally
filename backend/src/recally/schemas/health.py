"""Response schema for the health probes (docs/api-spec.md, "Health")."""

from pydantic import BaseModel


class HealthResponse(BaseModel):
    """`{"status": "ok"}` — the whole documented body."""

    status: str
