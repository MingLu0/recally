"""Upserts one parsed export into the database (hard rule 6).

The whole of ingestion is deterministic — no LLM call reaches this path (hard rule 2).
The rules, per `docs/data-model.md` and AGENTS.md hard rule 6:

- Same UUID, same text and note        -> skip; the row is untouched.
- Same UUID, different text or note    -> update that row in place and reset
                                          `processed=false` so the pipeline redoes it.
                                          Never a second row; `dedupe_key` is UNIQUE.
- UUID absent from a later export      -> set `removed_at`. The cards it already
                                          produced are kept. Cleared again if the
                                          UUID reappears in a later export.
- New UUID                             -> insert.

Scope is per book: a file covering one book says nothing about highlights of another,
so only the books present in this file are considered for removal.
"""

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from recally.ingest.adapters.base import BaseAdapter, NormalizedBook, NormalizedHighlight
from recally.models import Book, Highlight, IngestRun
from recally.models.base import utc_now


@dataclass(frozen=True)
class IngestCounts:
    """What one file did to the database. Mirrors the `ingest_runs` columns."""

    rows_seen: int = 0
    rows_new: int = 0
    rows_updated: int = 0
    rows_removed: int = 0

    @property
    def rows_unchanged(self) -> int:
        """Not stored; derived exactly as docs/data-model.md defines it."""
        return self.rows_seen - self.rows_new - self.rows_updated


def ingest_highlights(
    session: Session,
    highlights: list[NormalizedHighlight],
    *,
    filename: str,
    user_id: int = 1,
) -> IngestRun:
    """Apply `highlights` to the database and record the run.

    Returns the persisted `IngestRun`. The caller commits; keeping the transaction
    boundary outside lets the watcher, `POST /ingest` and the tests each own it.
    """
    run = IngestRun(filename=filename, user_id=user_id, started_at=utc_now())
    session.add(run)

    counts = _apply_highlights(session, highlights, user_id=user_id)

    run.rows_seen = counts.rows_seen
    run.rows_new = counts.rows_new
    run.rows_updated = counts.rows_updated
    run.rows_removed = counts.rows_removed
    run.finished_at = utc_now()
    session.flush()
    return run


def _apply_highlights(
    session: Session,
    highlights: list[NormalizedHighlight],
    *,
    user_id: int = 1,
) -> IngestCounts:
    """The upsert itself, without the `ingest_runs` bookkeeping."""
    rows_new = 0
    rows_updated = 0
    seen_keys_by_book: dict[int, set[str]] = {}

    for normalized in highlights:
        book = _get_or_create_book(session, normalized.book, user_id=user_id)
        seen_keys_by_book.setdefault(book.id, set()).add(normalized.dedupe_key)

        existing = session.scalar(
            select(Highlight).where(Highlight.dedupe_key == normalized.dedupe_key)
        )
        if existing is None:
            session.add(_new_highlight(normalized, book_id=book.id, user_id=user_id))
            rows_new += 1
        elif _update_in_place(existing, normalized):
            rows_updated += 1

    rows_removed = _mark_removed(session, seen_keys_by_book, removed_at=utc_now())

    return IngestCounts(
        rows_seen=len(highlights),
        rows_new=rows_new,
        rows_updated=rows_updated,
        rows_removed=rows_removed,
    )


def _get_or_create_book(session: Session, normalized: NormalizedBook, *, user_id: int) -> Book:
    """The book dedupe key is (source, external_id) — the ISBN for O'Reilly."""
    book = session.scalar(
        select(Book).where(
            Book.source == normalized.source,
            Book.external_id == normalized.external_id,
        )
    )
    if book is None:
        book = Book(
            title=normalized.title,
            author=normalized.author,
            source=normalized.source,
            external_id=normalized.external_id,
            url=normalized.url,
            user_id=user_id,
        )
        session.add(book)
        # The book id is needed to attach highlights and to scope the removal sweep.
        session.flush()
    return book


def _new_highlight(normalized: NormalizedHighlight, *, book_id: int, user_id: int) -> Highlight:
    return Highlight(
        book_id=book_id,
        chapter=normalized.chapter,
        location=normalized.location,
        raw_text=normalized.raw_text,
        personal_note=normalized.personal_note,
        color=normalized.color,
        dedupe_key=normalized.dedupe_key,
        source=normalized.source,
        highlighted_at=normalized.highlighted_at,
        export_position=normalized.export_position,
        # `truncated` stays at its default: it is a Curator flag (docs/data-model.md),
        # set in roadmap step 2, not something the deterministic path guesses at.
        processed=False,
        user_id=user_id,
    )


def _update_in_place(existing: Highlight, normalized: NormalizedHighlight) -> bool:
    """Refresh `existing` from `normalized`; return whether it counts as an update.

    Only `raw_text` and `personal_note` make a row "updated" — those are what hard
    rule 6 names, and they are what invalidate the cards already generated from it.
    Positional and presentational fields are refreshed alongside but do not, on their
    own, mean the reader changed the highlight: `export_position` in particular shifts
    on every export as newer highlights are added above.
    """
    content_changed = (
        existing.raw_text != normalized.raw_text
        or existing.personal_note != normalized.personal_note
    )

    existing.chapter = normalized.chapter
    existing.location = normalized.location
    existing.color = normalized.color
    existing.highlighted_at = normalized.highlighted_at
    existing.export_position = normalized.export_position
    # Present in this export, so it is no longer gone — a highlight the reader deleted
    # and later restored comes back into rotation rather than staying removed.
    existing.removed_at = None

    if content_changed:
        existing.raw_text = normalized.raw_text
        existing.personal_note = normalized.personal_note
        # Hard rule 6: the text the cards were written from has changed, so the
        # pipeline has to see this row again.
        existing.processed = False

    return content_changed


def _mark_removed(
    session: Session,
    seen_keys_by_book: dict[int, set[str]],
    *,
    removed_at: datetime,
) -> int:
    """Flag highlights of the books in this file whose UUIDs it no longer lists.

    Cards already generated from them are kept (hard rule 6); only the highlight is
    marked, and only within the books this export actually covered.
    """
    rows_removed = 0
    for book_id, seen_keys in seen_keys_by_book.items():
        stored = session.scalars(
            select(Highlight).where(
                Highlight.book_id == book_id,
                Highlight.removed_at.is_(None),
            )
        ).all()
        for highlight in stored:
            if highlight.dedupe_key not in seen_keys:
                highlight.removed_at = removed_at
                rows_removed += 1
    return rows_removed


def ingest_file(
    session: Session,
    file: Path,
    adapter: BaseAdapter,
    *,
    user_id: int = 1,
) -> IngestRun:
    """Parse `file` with `adapter` and apply it. The single entry point for a file."""
    return ingest_highlights(
        session,
        adapter.parse(file),
        filename=file.name,
        user_id=user_id,
    )
