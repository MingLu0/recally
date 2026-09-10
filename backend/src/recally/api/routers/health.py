"""`GET /health` and `GET /health/auth` (docs/api-spec.md, "Health").

Two routers rather than one, because the guard is per-router: the plain probe
must stay reachable without the key so a load balancer never needs one, while
the authenticated twin is what the Android Settings screen's connection test
calls — 200 means the base URL and the key are both right, 401 means the key is
wrong, and a transport error means the server was not reachable at all.

Neither touches the database: a liveness probe that fails when the database is
slow reports the wrong thing.

The authenticated twin also reports the running build's version. A backend left
running across a deploy serves the old code from memory, and the app's only
symptom was a decoding failure on a field the stale process never sent; the
version turns that into something the connection test can read (issue #195).
"""

from fastapi import APIRouter

from recally import __version__
from recally.api.auth import ApiKeyGuard
from recally.schemas.health import AuthedHealthResponse, HealthResponse

router = APIRouter(tags=["health"])
authed_router = APIRouter(tags=["health"], dependencies=[ApiKeyGuard])


@router.get("/health", response_model=HealthResponse)
def get_health() -> HealthResponse:
    """Unauthenticated liveness check."""
    return HealthResponse(status="ok")


@authed_router.get("/health/auth", response_model=AuthedHealthResponse)
def get_health_auth() -> AuthedHealthResponse:
    """Liveness, a valid `X-API-Key`, and the running build's version."""
    return AuthedHealthResponse(status="ok", version=__version__)
