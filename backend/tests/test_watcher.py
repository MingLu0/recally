"""The filesystem watcher reacts only to completed, debounced rename events."""

from pathlib import Path
from threading import Event

from watchdog.events import FileMovedEvent

from recally.ingest.watcher import ExportWatcher, _MovedExportHandler


class RecordingIngestor:
    """A composition-root stand-in that records calls from the watcher thread."""

    def __init__(self) -> None:
        self.files: list[Path] = []
        self.called = Event()

    def ingest_oreilly_export(self, file: Path) -> None:
        self.files.append(file)
        self.called.set()


def test_rename_into_watched_folder_triggers_one_ingest(tmp_path: Path) -> None:
    source = tmp_path / "oreilly-annotations.csv.crdownload"
    destination = tmp_path / "oreilly-annotations.csv"
    source.write_text("completed export")
    source.rename(destination)
    ingestor = RecordingIngestor()
    watcher = ExportWatcher(ingestor, watch_dir=tmp_path, debounce_ms=10)

    _MovedExportHandler(watcher).on_moved(FileMovedEvent(str(source), str(destination)))

    assert ingestor.called.wait(timeout=1)
    assert ingestor.files == [destination.resolve()]


def test_burst_of_rename_events_debounces_to_one_ingest(tmp_path: Path) -> None:
    destination = tmp_path / "oreilly-annotations.csv"
    destination.write_text("completed export")
    ingestor = RecordingIngestor()
    watcher = ExportWatcher(ingestor, watch_dir=tmp_path, debounce_ms=25)
    handler = _MovedExportHandler(watcher)

    for number in range(3):
        handler.on_moved(
            FileMovedEvent(str(tmp_path / f"download-{number}.crdownload"), str(destination))
        )

    assert ingestor.called.wait(timeout=1)
    assert ingestor.files == [destination.resolve()]
