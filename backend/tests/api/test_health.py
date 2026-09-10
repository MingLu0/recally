"""The version gate for `GET /health/auth` (issue #195, docs/api-spec.md, "Health").

A backend process left running across a deploy serves the old code from memory,
and the app's only symptom is a decoding failure on a field the stale server
never sends. The Settings connection test already calls this endpoint, so the
running build's version rides along on the answer and version skew becomes
something the human can read rather than something they have to diagnose.
"""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool

from recally import __version__
from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
from recally.container import Container
from recally.main import create_app
from recally.models import Base

TEST_API_KEY = "test-key-not-a-real-secret"


@pytest.fixture
def container() -> Iterator[Container]:
    """A container on a fresh in-memory database (same shape as tests/api/test_devices.py)."""
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


def test_health_auth_reports_the_backend_version(client: TestClient) -> None:
    """The authenticated probe carries the running build's non-empty version."""
    response = client.get("/health/auth", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert "version" in body, "the connection test must be able to read the backend version"
    assert isinstance(body["version"], str)
    assert body["version"], "an empty version is no better than an absent one"
    assert body["version"] == __version__
