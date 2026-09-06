"""The adapter contract every ingestion source implements.

An adapter is a pure reader: it turns one export file into normalized rows and does
nothing else. It opens no database session, makes no LLM call (hard rule 2) and never
rewrites the text it read (hard rule 7). Everything that touches the database —
inserting, updating, marking `removed_at` — belongs to `ingest/dedupe.py`.

Each adapter also owns one contract that the rest of the system depends on: what its
`dedupe_key` is. For O'Reilly it is the annotation UUID (hard rule 6); a Kindle
adapter would document its own. That contract is why a new source is a new module
here rather than a branch in the pipeline (docs/backend.md, "Layering").
"""

from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Protocol, runtime_checkable


@dataclass(frozen=True)
class NormalizedBook:
    """The book an export's rows belong to.

    `external_id` is the source's own stable identifier — the ISBN for O'Reilly
    (hard rule 6) — and pairs with `source` as the book's dedupe key.
    """

    title: str
    source: str
    external_id: str
    url: str | None = None
    author: str | None = None


@dataclass(frozen=True)
class NormalizedHighlight:
    """One exported highlight, carried through verbatim.

    `raw_text` is exactly what the file contained. Some O'Reilly rows are clipped
    mid-word and the lost text exists nowhere else, so no adapter repairs, re-joins or
    trims one (hard rule 7). `truncated` is not set here either: docs/data-model.md
    calls it a Curator flag, and the Curator sets it in roadmap step 2. The adapter's
    job is to preserve the evidence, not to judge it.
    """

    dedupe_key: str
    raw_text: str
    book: NormalizedBook
    source: str
    highlighted_at: date
    export_position: int
    chapter: str | None = None
    location: str | None = None
    personal_note: str | None = None
    color: str | None = None


@runtime_checkable
class BaseAdapter(Protocol):
    """Reads one export file into normalized highlights.

    A `Protocol` rather than an ABC, for the same reason the agent roles are (ADR-007):
    the seam is checked by mypy at the call site, so an adapter needs no import of this
    module to satisfy it.
    """

    #: Value written to `highlights.source` / `books.source`, e.g. `oreilly`.
    source: str

    def parse(self, file: Path) -> list[NormalizedHighlight]:
        """Return every highlight in `file`, in the order the file lists them."""
        ...
