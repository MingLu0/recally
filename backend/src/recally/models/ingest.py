"""Ingestion-side tables: books, highlights and the per-file run record."""

from datetime import date, datetime

from sqlalchemy import Boolean, Date, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from recally.models.base import Base, UserScopedMixin, utc_now


class Book(UserScopedMixin, Base):
    """A source book. `external_id` is the ISBN for O'Reilly, the ASIN for Kindle."""

    __tablename__ = "books"
    __table_args__ = (UniqueConstraint("source", "external_id"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    # The O'Reilly CSV has no author column; Kindle exports do.
    author: Mapped[str | None] = mapped_column(Text, nullable=True)
    source: Mapped[str] = mapped_column(String(32), nullable=False)
    external_id: Mapped[str] = mapped_column(String(128), nullable=False)
    url: Mapped[str | None] = mapped_column(Text, nullable=True)

    highlights: Mapped[list["Highlight"]] = relationship(back_populates="book")


class Highlight(UserScopedMixin, Base):
    """One exported highlight, as written by the reader.

    `raw_text` is stored exactly as exported. Some O'Reilly rows are clipped mid-word
    and the lost text exists nowhere else, so nothing ever reconstructs them (hard rule
    7); the Curator only flags them via `truncated`, which lives here and nowhere else.
    """

    __tablename__ = "highlights"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    book_id: Mapped[int] = mapped_column(ForeignKey("books.id"), nullable=False)
    chapter: Mapped[str | None] = mapped_column(Text, nullable=True)
    location: Mapped[str | None] = mapped_column(Text, nullable=True)
    raw_text: Mapped[str] = mapped_column(Text, nullable=False)
    personal_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Currently always YELLOW; kept so highlights can be filtered by colour later.
    color: Mapped[str | None] = mapped_column(String(32), nullable=True)
    # Hard rule 6: the O'Reilly annotation UUID, stable across exports. UNIQUE is what
    # makes a re-import an update of this row rather than a second one.
    dedupe_key: Mapped[str] = mapped_column(String(255), nullable=False, unique=True)
    source: Mapped[str] = mapped_column(String(32), nullable=False)
    highlighted_at: Mapped[date] = mapped_column(Date, nullable=False)
    # Export order is newest first, and reverse creation order within a day, so the row
    # index is the only positional signal the file carries.
    export_position: Mapped[int] = mapped_column(Integer, nullable=False)
    truncated: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # Set once the unit covering this highlight reached a terminal outcome, not when
    # the Curator merely ran (docs/agents.md, "Pipeline runner and handoffs").
    processed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # Set when the UUID is absent from a later export; the cards it produced are kept.
    removed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)

    book: Mapped[Book] = relationship(back_populates="highlights")


class IngestRun(UserScopedMixin, Base):
    """One run over one export file. Backs `GET /ingest/status`.

    `rows_unchanged` is not stored; it is `rows_seen - rows_new - rows_updated`.
    """

    __tablename__ = "ingest_runs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    filename: Mapped[str] = mapped_column(Text, nullable=False)
    rows_seen: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    rows_new: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    # Same UUID, changed text or note. Expected to stay 0 in practice.
    rows_updated: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    rows_removed: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    # The pipeline's counters (step 2). `units_dropped`/`highlights_dropped` are the
    # Curator's filter output: a dropped highlight produces no card, so without these
    # a wrongly-dropped highlight is invisible (docs/api-spec.md, GET /ingest/status).
    units_kept: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    units_dropped: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    highlights_dropped: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    # May include leftover highlights from an earlier run: the runner processes every
    # `processed=false` row, not just the ones this file introduced.
    cards_generated: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    cost_microusd: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    started_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utc_now)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
