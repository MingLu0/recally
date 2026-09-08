"""Watch completed browser downloads and ingest matching O'Reilly exports.

Browsers first write a partial file and then rename it into place.  Watching that
rename, rather than file creation, keeps the CSV adapter from reading a download in
progress.  A per-destination debounce absorbs duplicate filesystem notifications.
"""

from __future__ import annotations

import logging
from fnmatch import fnmatch
from os import fsdecode
from pathlib import Path
from threading import Event, Lock, Timer
from typing import Protocol

from watchdog.events import DirMovedEvent, FileMovedEvent, FileSystemEventHandler
from watchdog.observers import Observer

EXPORT_PATTERN = "*oreilly-annotations*.csv"

logger = logging.getLogger(__name__)


class OReillyIngestor(Protocol):
    """The container operation the watcher needs, independent of HTTP."""

    def ingest_oreilly_export(self, file: Path) -> object:
        """Persist one completed O'Reilly CSV export."""
        ...


class _MovedExportHandler(FileSystemEventHandler):
    """Forward filesystem rename events to an :class:`ExportWatcher`."""

    def __init__(self, watcher: ExportWatcher) -> None:
        self._watcher = watcher

    def on_moved(self, event: DirMovedEvent | FileMovedEvent) -> None:
        if not event.is_directory:
            self._watcher.handle_moved(Path(fsdecode(event.dest_path)))


class ExportWatcher:
    """A watchdog observer for fully downloaded O'Reilly CSV exports."""

    def __init__(
        self,
        ingestor: OReillyIngestor,
        *,
        watch_dir: Path,
        debounce_ms: int,
    ) -> None:
        if debounce_ms <= 0:
            raise ValueError("debounce_ms must be greater than zero")

        self._ingestor = ingestor
        self._watch_dir = watch_dir.expanduser()
        self._debounce_seconds = debounce_ms / 1000
        self._observer = Observer()
        self._handler = _MovedExportHandler(self)
        self._lock = Lock()
        self._pending: dict[Path, tuple[int, Timer]] = {}
        self._next_token = 0
        self._started = False

    def start(self) -> None:
        """Start watching the configured directory for completed downloads."""
        if self._started:
            return
        self._watch_dir.mkdir(parents=True, exist_ok=True)
        self._observer.schedule(self._handler, str(self._watch_dir), recursive=False)
        self._observer.start()
        self._started = True

    def stop(self) -> None:
        """Cancel delayed work and stop the observer cleanly."""
        with self._lock:
            for _, timer in self._pending.values():
                timer.cancel()
            self._pending.clear()
        if self._started:
            self._observer.stop()
            self._observer.join()
            self._started = False

    def handle_moved(self, destination: Path) -> None:
        """Debounce a rename into the watched folder.

        This public method is intentionally small so the actual watchdog event can
        be simulated without an OS-specific observer in tests.
        """
        if not fnmatch(destination.name, EXPORT_PATTERN):
            return

        path = destination.resolve()
        try:
            path.relative_to(self._watch_dir.resolve())
        except ValueError:
            return

        with self._lock:
            self._next_token += 1
            token = self._next_token
            timer = Timer(self._debounce_seconds, self._ingest_if_current, args=(path, token))
            previous = self._pending.get(path)
            if previous is not None:
                previous[1].cancel()
            self._pending[path] = (token, timer)

        timer.daemon = True
        timer.start()

    def _ingest_if_current(self, file: Path, token: int) -> None:
        with self._lock:
            scheduled = self._pending.get(file)
            if scheduled is None or scheduled[0] != token:
                return
            del self._pending[file]

        if not file.is_file():
            logger.warning("Skipping moved export because it no longer exists: %s", file)
            return

        try:
            self._ingestor.ingest_oreilly_export(file)
        except Exception:
            # An invalid export must not kill watchdog's observer thread. A later
            # rename triggers another deterministic ingestion attempt.
            logger.exception("Failed to ingest moved O'Reilly export: %s", file)


def create_export_watcher(
    ingestor: OReillyIngestor,
    *,
    watch_dir: Path,
    debounce_ms: int,
) -> ExportWatcher:
    """Build the watcher; callers obtain ``ingestor`` from ``container.py``."""
    return ExportWatcher(ingestor, watch_dir=watch_dir, debounce_ms=debounce_ms)


def create_configured_export_watcher() -> ExportWatcher:
    """Resolve the process-wide dependencies from the composition root."""
    from recally.container import get_container

    container = get_container()
    return create_export_watcher(
        container,
        watch_dir=container.settings.watch_dir,
        debounce_ms=container.settings.watch_debounce_ms,
    )


def main() -> None:
    """Run the watcher until interrupted; useful before the app lifespan owns it."""
    watcher = create_configured_export_watcher()
    watcher.start()
    try:
        Event().wait()
    except KeyboardInterrupt:
        pass
    finally:
        watcher.stop()


if __name__ == "__main__":
    main()
