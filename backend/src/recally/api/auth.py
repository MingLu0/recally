"""The `X-API-Key` dependency (docs/api-spec.md; `RECALLY_API_KEY` in docs/config.md).

Single-user auth for phase 1: one key from the environment, compared in constant time.
Phase 3 replaces this with real auth (docs/roadmap.md, "Productionization phases").
"""

import secrets
from typing import Annotated

from fastapi import Depends, Header

from recally.api.deps import ContainerDep
from recally.api.errors import ProblemDetail

API_KEY_HEADER = "X-API-Key"


def require_api_key(
    container: ContainerDep,
    x_api_key: Annotated[str | None, Header(alias=API_KEY_HEADER)] = None,
) -> None:
    """401 unless the request carries the configured key.

    A missing header and a wrong key give the same response: telling them apart would
    let a caller probe for whether the header name is right. `compare_digest` keeps the
    comparison time independent of how much of the key matched.
    """
    expected = container.settings.api_key
    if x_api_key is None or not secrets.compare_digest(x_api_key, expected):
        raise ProblemDetail(status=401, detail="Invalid or missing X-API-Key header.")


ApiKeyGuard = Depends(require_api_key)
