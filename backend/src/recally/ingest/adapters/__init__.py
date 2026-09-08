"""Source adapters. A new source is a new module here, never a branch in the pipeline."""

from recally.ingest.adapters.base import BaseAdapter, NormalizedBook, NormalizedHighlight
from recally.ingest.adapters.oreilly_csv import OReillyCsvAdapter

__all__ = [
    "BaseAdapter",
    "NormalizedBook",
    "NormalizedHighlight",
    "OReillyCsvAdapter",
]
