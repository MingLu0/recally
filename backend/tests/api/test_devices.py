"""The step 5a gate for `POST /devices` (docs/api-spec.md, "Devices").

Registration is idempotent on `fcm_token` (UNIQUE in `devices`): the app calls this
on every start and every token refresh, so a re-register must return the same
`device_id` and never surface the constraint as a 500.
"""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select
from sqlalchemy.pool import StaticPool

from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
from recally.container import Container
from recally.main import create_app
from recally.models import Base
from recally.models.scheduling import Device

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


def test_register_device_returns_device_id(client: TestClient, container: Container) -> None:
    """A first registration creates the row and returns its id."""
    response = client.post(
        "/devices",
        headers={"X-API-Key": TEST_API_KEY},
        json={"fcm_token": "fcm-token-aaa", "platform": "android"},
    )

    assert response.status_code == 200
    device_id = response.json()["device_id"]
    assert isinstance(device_id, int)

    with container.session() as session:
        device = session.get(Device, device_id)
    assert device is not None
    assert device.fcm_token == "fcm-token-aaa"
    assert device.platform == "android"
    assert device.last_push_at is None, "last_push_at belongs to the notifier (step 5b)"


def test_re_registering_same_token_returns_same_id_and_no_new_row(
    client: TestClient, container: Container
) -> None:
    """The idempotency contract: same token in, same id out, still one row."""
    first = client.post(
        "/devices",
        headers={"X-API-Key": TEST_API_KEY},
        json={"fcm_token": "fcm-token-aaa", "platform": "android"},
    )
    second = client.post(
        "/devices",
        headers={"X-API-Key": TEST_API_KEY},
        json={"fcm_token": "fcm-token-aaa", "platform": "android"},
    )

    assert first.status_code == 200
    assert second.status_code == 200
    assert second.json()["device_id"] == first.json()["device_id"]

    with container.session() as session:
        count = session.scalar(select(func.count()).select_from(Device))
    assert count == 1


def test_registration_never_raises_on_the_unique_constraint(
    client: TestClient, container: Container
) -> None:
    """The duplicate path is a 200, not a 500: the UNIQUE constraint on
    `fcm_token` must be detected and answered, never raised into the error handler."""
    client.post(
        "/devices",
        headers={"X-API-Key": TEST_API_KEY},
        json={"fcm_token": "fcm-token-aaa", "platform": "android"},
    )
    duplicate = client.post(
        "/devices",
        headers={"X-API-Key": TEST_API_KEY},
        json={"fcm_token": "fcm-token-aaa", "platform": "android"},
    )

    assert duplicate.status_code == 200, (
        f"a duplicate fcm_token surfaced as {duplicate.status_code}: "
        f"{duplicate.json()} — the IntegrityError escaped instead of being detected"
    )
    assert duplicate.json()["device_id"]


def test_devices_requires_api_key(client: TestClient) -> None:
    """Every route carries the guard, this one included."""
    response = client.post("/devices", json={"fcm_token": "fcm-token-aaa", "platform": "android"})

    assert response.status_code == 401
    assert response.json() == {
        "status": 401,
        "detail": "Invalid or missing X-API-Key header.",
    }
