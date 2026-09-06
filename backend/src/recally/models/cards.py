"""Curator output, the cards written from it, and the Learner guidance behind them."""

from datetime import datetime
from typing import Any

from sqlalchemy import JSON, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from recally.models.base import Base, UserScopedMixin, utc_now


class CuratedUnit(UserScopedMixin, Base):
    """One Curator output. Most units wrap a single highlight; a group wraps several.

    `curated_text` lives here rather than on `highlights` because a group has one
    curated text over several source rows. Whether any of those sources was truncated
    is derived through `curated_unit_highlights`, never stored (hard rule 7).
    """

    __tablename__ = "curated_units"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    # Lets units orphaned by a crashed run be found and removed.
    ingest_run_id: Mapped[int] = mapped_column(ForeignKey("ingest_runs.id"), nullable=False)
    curated_text: Mapped[str] = mapped_column(Text, nullable=False)
    tags: Mapped[list[str]] = mapped_column(JSON, nullable=False, default=list)
    decision: Mapped[str] = mapped_column(String(16), nullable=False)
    reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utc_now)

    highlight_links: Mapped[list["CuratedUnitHighlight"]] = relationship(back_populates="unit")
    cards: Mapped[list["Card"]] = relationship(back_populates="unit")


class CuratedUnitHighlight(UserScopedMixin, Base):
    """Unit ⇄ highlight provenance, so every card traces back to each source row."""

    __tablename__ = "curated_unit_highlights"

    unit_id: Mapped[int] = mapped_column(ForeignKey("curated_units.id"), primary_key=True)
    highlight_id: Mapped[int] = mapped_column(ForeignKey("highlights.id"), primary_key=True)

    unit: Mapped[CuratedUnit] = relationship(back_populates="highlight_links")


class WriterGuidance(UserScopedMixin, Base):
    """Versioned Learner guidance injected into the Writer prompt.

    Hard rule 10: rows are never edited in place. A new lesson is a new version, and
    cards record the version that generated them, so quality can be compared across
    versions later.
    """

    __tablename__ = "writer_guidance"

    version: Mapped[int] = mapped_column(Integer, primary_key=True)
    guidance: Mapped[str] = mapped_column(Text, nullable=False)
    basis: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utc_now)


class Card(UserScopedMixin, Base):
    """A flashcard. Nothing reaches FSRS scheduling until a human sets `approved`.

    `status` moves `pending_review` / `needs_human` → `approved` (hard rule 1); the
    pipeline itself never writes `approved` or `rejected` (hard rule 9). `original_*`
    keeps the Writer text the Critic accepted, so a human edit at approval time stays
    distinguishable from what the model produced.
    """

    __tablename__ = "cards"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    unit_id: Mapped[int] = mapped_column(ForeignKey("curated_units.id"), nullable=False)
    type: Mapped[str] = mapped_column(String(16), nullable=False)
    front: Mapped[str] = mapped_column(Text, nullable=False)
    back: Mapped[str] = mapped_column(Text, nullable=False)
    original_front: Mapped[str] = mapped_column(Text, nullable=False)
    original_back: Mapped[str] = mapped_column(Text, nullable=False)
    tags: Mapped[list[str]] = mapped_column(JSON, nullable=False, default=list)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    # Critic critique, human rejection reason, or `superseded by <id>` for the Learner.
    status_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    # `card_state` is created at the same moment this is set.
    approved_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    # Last post-approval edit of front/back/tags. FSRS state is untouched by an edit;
    # the stamp lets the Learner segment hand-fixed cards so they do not flatter the
    # guidance version that wrote the flawed original (ADR-008).
    edited_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    # NULL = in rotation. Bury sets the next day boundary, suspend a far-future
    # sentinel; `/reviews/due` excludes any future value and FSRS state is never
    # recomputed (ADR-008).
    suspended_until: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    # Writer ⇄ Critic rounds spent on this card, per card rather than per unit.
    generation_rounds: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    model: Mapped[str] = mapped_column(String(128), nullable=False)
    guidance_version: Mapped[int | None] = mapped_column(
        ForeignKey("writer_guidance.version"), nullable=True
    )
    # Integer micro-USD (1 USD = 1_000_000): cheap-model calls cost fractions of a
    # cent, which a `cents` column would round away.
    cost_microusd: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utc_now)

    unit: Mapped[CuratedUnit] = relationship(back_populates="cards")
