"""Ingestion: adapters that read an export file, and the dedupe that persists it."""

from recally.ingest.dedupe import IngestCounts, ingest_file, ingest_highlights

__all__ = [
    "IngestCounts",
    "ingest_file",
    "ingest_highlights",
]
