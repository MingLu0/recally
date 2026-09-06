"""The O'Reilly annotations CSV adapter.

The export has nine columns and no header variation seen so far:

    Book Title, Chapter Title, Date of Highlight, Book URL, Chapter URL,
    Annotation URL, Highlight, Color, Personal Note

Two identifiers are dug out of URLs rather than given as columns:

- the **annotation UUID**, the fragment of the Annotation URL, which is the highlight
  dedupe key and is stable across exports (hard rule 6);
- the **ISBN**, the last path segment of the Book URL, which is the book dedupe key.

Rows are emitted in file order. The export is newest-first, and reverse creation order
within a day, so that index is the only positional signal the file carries
(docs/data-model.md, `export_position`).
"""

import csv
from collections.abc import Sequence
from datetime import date, datetime
from pathlib import Path
from urllib.parse import urlparse

from recally.ingest.adapters.base import NormalizedBook, NormalizedHighlight

SOURCE = "oreilly"

REQUIRED_COLUMNS = (
    "Book Title",
    "Chapter Title",
    "Date of Highlight",
    "Book URL",
    "Annotation URL",
    "Highlight",
)


class OReillyCsvError(ValueError):
    """The file is not a usable O'Reilly annotations export."""


class OReillyCsvAdapter:
    """Parses `*oreilly-annotations*.csv` exports. Satisfies `BaseAdapter`."""

    source = SOURCE

    def parse(self, file: Path) -> list[NormalizedHighlight]:
        """Read `file` into normalized highlights, in export order."""
        # The export is UTF-8 with a BOM when saved from some browsers; utf-8-sig
        # strips it if present and is a no-op otherwise, so the first header name
        # never arrives as "﻿Book Title".
        with file.open(newline="", encoding="utf-8-sig") as handle:
            reader = csv.DictReader(handle)
            _require_columns(reader.fieldnames, file)
            return [
                _normalize_row(row, export_position, file)
                for export_position, row in enumerate(reader)
            ]


def _require_columns(fieldnames: Sequence[str] | None, file: Path) -> None:
    """Fail on the whole file rather than per row, so a wrong file is one clear error."""
    present = set(fieldnames or ())
    missing = [column for column in REQUIRED_COLUMNS if column not in present]
    if missing:
        raise OReillyCsvError(f"{file.name}: missing column(s) {', '.join(missing)}")


def _normalize_row(row: dict[str, str], export_position: int, file: Path) -> NormalizedHighlight:
    missing = [column for column in REQUIRED_COLUMNS if row.get(column) is None]
    if missing:
        raise OReillyCsvError(
            f"{file.name} row {export_position}: short row, missing {', '.join(missing)}"
        )

    book_url = row["Book URL"].strip()
    isbn = _isbn_from_book_url(book_url)
    if not isbn:
        raise OReillyCsvError(
            f"{file.name} row {export_position}: no ISBN in Book URL {book_url!r}"
        )

    annotation_url = row["Annotation URL"].strip()
    annotation_uuid = _uuid_from_annotation_url(annotation_url)
    if not annotation_uuid:
        raise OReillyCsvError(
            f"{file.name} row {export_position}: no annotation UUID in {annotation_url!r}"
        )

    book = NormalizedBook(
        # The O'Reilly export carries no author column, so `author` stays None.
        title=row["Book Title"].strip(),
        source=SOURCE,
        external_id=isbn,
        url=book_url or None,
    )

    return NormalizedHighlight(
        dedupe_key=annotation_uuid,
        # Deliberately not stripped or repaired: `raw_text` is "as exported"
        # (docs/data-model.md) and clipped rows stay clipped (hard rule 7).
        raw_text=row["Highlight"],
        book=book,
        source=SOURCE,
        highlighted_at=_parse_highlight_date(row["Date of Highlight"], export_position, file),
        export_position=export_position,
        chapter=_optional(row.get("Chapter Title")),
        # O'Reilly has no location column; the Kindle adapter will fill this.
        location=None,
        personal_note=_optional(row.get("Personal Note")),
        color=_optional(row.get("Color")),
    )


def _optional(value: str | None) -> str | None:
    """Empty CSV cells are absent values, not empty strings."""
    if value is None:
        return None
    stripped = value.strip()
    return stripped or None


def _isbn_from_book_url(book_url: str) -> str | None:
    """`https://learning.oreilly.com/library/view/-/9781806109012/` -> `9781806109012`."""
    segments = [segment for segment in urlparse(book_url).path.split("/") if segment]
    if not segments:
        return None
    last_segment = segments[-1]
    # The slug segment is "-" for every export seen; guard anyway so a titled URL
    # (".../view/30-agents/9781806109012/") still yields the identifier, not the slug.
    return last_segment if last_segment.isdigit() else None


def _uuid_from_annotation_url(annotation_url: str) -> str | None:
    """The UUID is the URL fragment: `...Chapter_9.xhtml#efaf55cf-...` -> `efaf55cf-...`."""
    return urlparse(annotation_url).fragment or None


def _parse_highlight_date(value: str, export_position: int, file: Path) -> date:
    """The column is a plain ISO date; anything else means the file is not this export."""
    try:
        return datetime.strptime(value.strip(), "%Y-%m-%d").date()
    except ValueError as error:
        raise OReillyCsvError(
            f"{file.name} row {export_position}: bad Date of Highlight {value!r}"
        ) from error
