"""The step 5a gate for `POST /jobs/run` (docs/api-spec.md, "Jobs").

The router is a thin adapter over `recally.scheduling.jobs` (docs/backend.md,
"Wiring and entry points"): `notify` invokes the notifier seam step 5b fills,
`learner` and `optimizer` are known-but-unbuilt names that must answer 501 — a
silent success for an unbuilt job is the failure mode to avoid — and an unknown
name is a plain 422.
"""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool

from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
from recally.container import Container
from recally.main import create_app
from recally.models import Base

TEST_API_KEY = "test-key-not-a-real-secret"


@pytest.fixture
def container() -> Iterator[Container]:
    """A container on a fresh in-memory database (same shape as tests/test_api.py)."""
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


def test_run_notify_invokes_the_notifier_seam(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """`{"job": "notify"}` calls the notifier exactly once; the seam is what step
    5b fills with the one-push-per-day policy, so the test mocks it rather than
    asserting any push behaviour of its own."""
    from recally.scheduling import notifier

    calls: list[object] = []
    monkeypatch.setattr(notifier, "send_due_push", lambda container: calls.append(container))

    response = client.post("/jobs/run", headers={"X-API-Key": TEST_API_KEY}, json={"job": "notify"})

    assert response.status_code == 200
    assert len(calls) == 1, f"the notifier seam ran {len(calls)} times, expected exactly 1"


def test_unimplemented_jobs_return_501_not_success(client: TestClient) -> None:
    """`learner` and `optimizer` are registered names whose jobs belong to step
    6a/6b: they must fail loudly (501 problem+json), never pretend to have run."""
    for job in ("learner", "optimizer"):
        response = client.post("/jobs/run", headers={"X-API-Key": TEST_API_KEY}, json={"job": job})

        assert response.status_code == 501, (
            f"{job!r} returned {response.status_code}: an unimplemented job must not report success"
        )
        assert response.json()["status"] == 501
        assert response.json()["detail"]
        assert response.headers["content-type"].startswith("application/problem+json")


def test_unknown_job_name_is_422(client: TestClient) -> None:
    """A name the system does not know is rejected at validation, never run."""
    response = client.post("/jobs/run", headers={"X-API-Key": TEST_API_KEY}, json={"job": "sweep"})

    assert response.status_code == 422
    assert response.json()["status"] == 422


def test_jobs_requires_api_key(client: TestClient) -> None:
    """Every route carries the guard, this one included."""
    response = client.post("/jobs/run", json={"job": "notify"})

    assert response.status_code == 401
    assert response.json() == {
        "status": 401,
        "detail": "Invalid or missing X-API-Key header.",
    }
