"""The issue-#234 gate for `POST /ingest` (docs/api-spec.md, "Ingestion").

The route is a thin adapter over `Container.ingest_oreilly_export`
(docs/backend.md, "Wiring and entry points"): it streams the multipart body to a
temp file, hands the `Path` to the container — the same entry point the watcher
drives — and deletes the temp file however the call ends. All parse, dedupe and
insert logic stays in the ingest layer; that shared call is what makes
`test_ingest_upload_matches_watcher_counts` meaningful rather than a tautology.

The agent pipeline never runs here: `Container.run_pipeline` is patched to a
no-op (class-wide, so a second container can stand in for the watcher), keeping
every LLM call out of the suite (backend/AGENTS.md).
"""

from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, create_engine, func, select
from sqlalchemy.pool import StaticPool

from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
from recally.container import Container
from recally.ingest import ingest_file
from recally.ingest.adapters import OReillyCsvAdapter
from recally.ingest.adapters.oreilly_csv import OReillyCsvError
from recally.main import create_app
from recally.models import Base, Highlight, IngestRun

TEST_API_KEY = "test-key-not-a-real-secret"
FIXTURE_A = Path(__file__).parent / "fixtures" / "oreilly-annotations-a.csv"
FIXTURE_B = Path(__file__).parent / "fixtures" / "oreilly-annotations-b.csv"


def make_container() -> tuple[Container, Engine]:
    """A container on a fresh in-memory database (same shape as tests/test_pipeline.py)."""
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    settings = Settings(RECALLY_DATABASE_URL="sqlite://", RECALLY_API_KEY=TEST_API_KEY)
    return Container(settings, engine=engine), engine


@pytest.fixture
def container(monkeypatch: pytest.MonkeyPatch) -> Iterator[Container]:
    """The upload target, with the pipeline stubbed so no LLM call is made."""
    monkeypatch.setattr(Container, "run_pipeline", lambda self, ingest_run_id: None)
    test_container, engine = make_container()
    try:
        yield test_container
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


def upload(client: TestClient, content: bytes, filename: str = "oreilly-annotations.csv") -> Any:
    """POST one file the way the Android import tile sends it."""
    return client.post(
        "/ingest",
        headers={"X-API-Key": TEST_API_KEY},
        files={"file": (filename, content, "text/csv")},
    )


def test_ingest_upload_matches_watcher_counts(client: TestClient, container: Container) -> None:
    """The endpoint and the watcher's path report identical counts for both fixtures.

    A second container plays the watcher: `container.ingest_oreilly_export` is the
    exact call the watcher makes (recally/ingest/watcher.py), so running fixture A
    then fixture B through both routes must produce the same new/updated/removed
    sequence — including B's updated and removed rows against A's database.
    """
    watcher_container, engine = make_container()
    try:
        for fixture in (FIXTURE_A, FIXTURE_B):
            response = upload(client, fixture.read_bytes(), filename=fixture.name)
            assert response.status_code == 200, response.text
            run = watcher_container.ingest_oreilly_export(fixture)
            body = response.json()
            assert body["rows_seen"] == run.rows_seen
            assert body["rows_new"] == run.rows_new
            assert body["rows_updated"] == run.rows_updated
            assert body["rows_removed"] == run.rows_removed
    finally:
        engine.dispose()


def test_ingest_upload_requires_api_key(client: TestClient) -> None:
    """The router's guard covers the upload exactly like every other route."""
    response = client.post(
        "/ingest",
        files={"file": ("oreilly-annotations.csv", FIXTURE_A.read_bytes(), "text/csv")},
    )

    assert response.status_code == 401
    assert response.json() == {
        "status": 401,
        "detail": "Invalid or missing X-API-Key header.",
    }


def test_ingest_upload_rejects_non_csv(client: TestClient) -> None:
    """A body the adapter cannot parse is a 422 — judged on content, not filename:
    the payload carries a `.csv` name so the guard cannot be filename-matching."""
    response = upload(client, b'{"definitely": "not a csv"}')

    assert response.status_code == 422
    assert response.json()["status"] == 422


def test_ingest_upload_is_idempotent(client: TestClient, container: Container) -> None:
    """Re-uploading an unchanged export reports 0/0/0 and writes no rows (hard rule 6)."""
    first = upload(client, FIXTURE_A.read_bytes())
    assert first.status_code == 200, first.text

    second = upload(client, FIXTURE_A.read_bytes())
    assert second.status_code == 200, second.text
    assert second.json()["rows_new"] == 0
    assert second.json()["rows_updated"] == 0
    assert second.json()["rows_removed"] == 0

    with container.session() as session:
        highlights = session.scalar(select(func.count()).select_from(Highlight))
    assert highlights == first.json()["rows_new"]


def test_ingest_upload_returns_the_ingest_run(client: TestClient) -> None:
    """The response body is the `ingest_runs` row, the same shape `GET /ingest/status`
    publishes, so the client can report rows_new/rows_updated/rows_removed."""
    response = upload(client, FIXTURE_A.read_bytes(), filename=FIXTURE_A.name)
    assert response.status_code == 200, response.text

    status = client.get("/ingest/status", headers={"X-API-Key": TEST_API_KEY})
    assert status.status_code == 200, status.text
    assert response.json() == status.json()
    assert response.json()["filename"] == FIXTURE_A.name


def test_ingest_upload_removes_the_temp_file_on_failure(
    client: TestClient, container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A failed ingest leaves no temp file behind: the container is driven with a
    path that must be gone once the route has turned the error into a response."""
    handed: list[Path] = []

    def failing(file: Path) -> IngestRun:
        handed.append(file)
        raise OReillyCsvError("not an O'Reilly export")

    monkeypatch.setattr(container, "ingest_oreilly_export", failing)

    response = upload(client, b"junk", filename="oreilly-annotations.csv")

    assert response.status_code == 422
    assert len(handed) == 1, "the route handed the container exactly one temp file"
    assert not handed[0].exists(), f"temp file survived the failure: {handed[0]}"


def test_ingest_upload_uses_the_container_entry_point(
    client: TestClient, container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The route drives `ingest_oreilly_export` and nothing else: a spy on the
    container method sees the upload, the bytes handed over are the bytes sent,
    and the response renders the run the container returned."""
    with container.session() as session:
        run = ingest_file(session, FIXTURE_A, OReillyCsvAdapter())
        session.commit()
        run_id = run.id

    seen: list[tuple[Path, bytes]] = []

    def spy(file: Path) -> IngestRun:
        seen.append((file, file.read_bytes()))
        with container.session() as session:
            result = session.get(IngestRun, run_id)
            assert result is not None
            return result

    monkeypatch.setattr(container, "ingest_oreilly_export", spy)
    content = FIXTURE_A.read_bytes()

    response = upload(client, content, filename=FIXTURE_A.name)

    assert response.status_code == 200, response.text
    assert len(seen) == 1, "the route must call the container entry point exactly once"
    assert seen[0][1] == content, "the container received the uploaded bytes verbatim"
    assert response.json()["rows_new"] == run.rows_new
