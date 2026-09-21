"""`POST /ingest` (multipart upload) and `GET /ingest/status` (docs/api-spec.md, "Ingestion").

The upload route is a thin adapter over `Container.ingest_oreilly_export` — the
watcher's single path (docs/backend.md, "Wiring and entry points"). It streams the
multipart body to a temp file, hands the `Path` over, and deletes the temp file
however the call ends. Parse, dedupe and insert live in the ingest layer; the one
shared call is what keeps the upload and the watcher from diverging.
"""

import shutil
import tempfile
from pathlib import Path

from fastapi import APIRouter, UploadFile

from recally.api.auth import ApiKeyGuard
from recally.api.deps import ContainerDep, SessionDep
from recally.api.errors import ProblemDetail
from recally.ingest.adapters.oreilly_csv import OReillyCsvError
from recally.schemas.ingest import IngestStatusResponse
from recally.services.ingest_status import latest_ingest_run

router = APIRouter(prefix="/ingest", tags=["ingest"], dependencies=[ApiKeyGuard])


@router.post("", response_model=IngestStatusResponse)
def upload_oreilly_export(file: UploadFile, container: ContainerDep) -> IngestStatusResponse:
    """Ingest one uploaded O'Reilly export; respond with the `ingest_runs` row it made.

    The response arrives after the agent pipeline has run (minutes for a large
    export) by design: the client reports a real result, not a premature
    "accepted" (docs/api-spec.md, "Ingestion"). A body the adapter cannot parse is
    a 422 — judged on content, never on the filename or its MIME type, which
    providers report unreliably. The temp file is removed on every exit path.
    """
    temp_path = _save_to_temp_file(file)
    try:
        run = container.ingest_oreilly_export(temp_path)
    except OReillyCsvError as error:
        raise ProblemDetail(status=422, detail=str(error)) from error
    finally:
        shutil.rmtree(temp_path.parent, ignore_errors=True)
    return IngestStatusResponse.from_run(run)


def _save_to_temp_file(file: UploadFile) -> Path:
    """Stream the upload to a file named like the export inside a fresh temp dir.

    The container records `path.name` on the `ingest_runs` row, exactly as the
    watcher records the file it saw — so the upload's run row carries the export's
    real name, not a temp name. Only the basename is kept: the filename is client
    data and must not reach outside the temp dir. A copy failure removes the dir
    before propagating, so a failed upload also leaves nothing behind.
    """
    filename = Path(file.filename or "export.csv").name
    temp_dir = Path(tempfile.mkdtemp(prefix="recally-upload-"))
    temp_path = temp_dir / filename
    try:
        with temp_path.open(mode="wb") as handle:
            shutil.copyfileobj(file.file, handle)
    except BaseException:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise
    return temp_path


@router.get("/status", response_model=IngestStatusResponse)
def get_ingest_status(session: SessionDep) -> IngestStatusResponse:
    """The latest ingest run.

    404 before the first ingest: there is no run to report, and an empty object would
    make "never ingested" indistinguishable from "ingested nothing".
    """
    run = latest_ingest_run(session)
    if run is None:
        raise ProblemDetail(status=404, detail="No ingest run has been recorded yet.")
    return IngestStatusResponse.from_run(run)
