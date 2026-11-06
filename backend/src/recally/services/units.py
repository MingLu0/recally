"""The Curator's drops, listed and resolved by the human (#365, ADR-019).

Deterministic, no LLM (hard rule 2): this module lists unresolved drops, dismisses
one, and writes a hand-written card on one. Generating cards for a drop goes
through the Writer ⇄ Critic loop and so lives in `pipeline.py`
(`generate_cards_for_dropped_unit`), which calls `require_unresolved_drop` and
`mark_rescued` from here so both rescue routes share one gate.

**The Curator's decision is never rewritten.** `curated_units.decision` stays
`drop` on a rescued unit — it is the Curator's output and eval truth. The human's
answer is `drop_resolution` (`dismissed` / `rescued`) plus `drop_resolved_at`;
`rescued` is the Curator false-negative signal for evals and later tuning, which
nothing reads automatically yet.

A hand-written card still lands in `pending_review` (hard rule 1): the human typed
it, and still decides it in the approval queue.
"""

import re
from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from recally.models import Card, CuratedUnit, CuratedUnitHighlight, Highlight
from recally.models.base import utc_now
from recally.models.cards import HUMAN_AUTHORED_MODEL

DISMISSED = "dismissed"
RESCUED = "rescued"

# Exactly one `{{cN::...}}` deletion with a non-blank answer: the single-deletion
# rule the Writer enforces on its own clozes (docs/agents.md §3) holds for a
# hand-written one too, so the queue never shows a cloze the Review screen cannot.
_CLOZE_DELETION = re.compile(r"\{\{c\d+::(.*?)\}\}", flags=re.DOTALL)


class UnitActionError(Exception):
    """A dropped-unit action refused; the API layer maps the subclass to a status."""


class UnitNotFound(UnitActionError):
    pass


class UnitNotDropped(UnitActionError):
    """The unit is a `keep`: there is no drop to resolve."""


class UnitAlreadyResolved(UnitActionError):
    """Dismissed or rescued already; a second tap must not act twice."""


class HandWrittenCardInvalid(ValueError):
    """A hand-written card's text breaks a card rule (blank front, no deletion…)."""


@dataclass(frozen=True)
class DroppedUnit:
    """One dropped unit with every source highlight, in the API's shape.

    `first_export_position` backs the documented ordering (book, chapter, then the
    smallest export position among the unit's highlights); it is not in the payload.
    """

    id: int
    reason: str | None
    curated_text: str
    created_at: datetime
    drop_resolution: str | None
    drop_resolved_at: datetime | None
    source_highlights: list[str] = field(default_factory=list)
    truncated: bool = False
    book_id: int = 0
    book: str = ""
    chapter: str | None = None
    first_export_position: int = 0


def list_dropped_units(
    session: Session,
    *,
    book_id: int | None = None,
    chapter: str | None = None,
    user_id: int = 1,
) -> list[DroppedUnit]:
    """Unresolved drops from every ingest run, in the approval queue's order.

    Two kinds of drop are excluded because there is nothing left to rescue:

    - one whose highlights are **all** removed from the export (`removed_at`): the
      reader deleted them, so a card from them would describe nothing they keep;
    - a **stale** drop, any of whose highlights has since been curated into a newer
      unit. A changed re-import resets `processed=false` (hard rule 6) and the
      Curator writes a new unit over the current text, so the old drop no longer
      describes it. "Newer" is a higher `curated_units.id`.
    """
    units = session.scalars(
        select(CuratedUnit).where(
            CuratedUnit.user_id == user_id,
            CuratedUnit.decision == "drop",
            CuratedUnit.drop_resolution.is_(None),
        )
    ).all()
    newest_unit_by_highlight = _newest_unit_by_highlight(session)

    dropped: list[DroppedUnit] = []
    for unit in units:
        sources = _unit_sources(session, unit.id)
        if not sources:
            continue  # provenance is mandatory; a sourceless drop cannot be shown
        if all(source.removed_at is not None for source in sources):
            continue
        if any(newest_unit_by_highlight.get(source.id, unit.id) > unit.id for source in sources):
            continue
        listed = _dropped_unit(unit, sources)
        if book_id is not None and listed.book_id != book_id:
            continue
        if chapter is not None and listed.chapter != chapter:
            continue
        dropped.append(listed)

    # `id` breaks ties so the order is defined on every backend (issue #232).
    dropped.sort(
        key=lambda unit: (unit.book, unit.chapter or "", unit.first_export_position, unit.id)
    )
    return dropped


def dropped_count(session: Session, *, user_id: int = 1) -> int:
    """The collection-wide unresolved total, ignoring any list filter — the header
    must be right whatever the list shows, as with `GET /cards/pending`'s counts."""
    return len(list_dropped_units(session, user_id=user_id))


def dropped_unit_view(session: Session, unit: CuratedUnit) -> DroppedUnit:
    """The unit in the API's shape, resolved or not (the dismiss response)."""
    return _dropped_unit(unit, _unit_sources(session, unit.id))


def require_unresolved_drop(session: Session, unit_id: int) -> CuratedUnit:
    """The unit, freshly read, or the refusal: unknown, a keep, or already resolved.

    `populate_existing` because the session keeps objects across commits
    (`expire_on_commit=False`, `db.py`): the generate path calls this again after
    its LLM calls, and must see a resolution written meanwhile by another request.
    """
    unit = session.get(CuratedUnit, unit_id, populate_existing=True)
    if unit is None:
        raise UnitNotFound(f"Unit {unit_id} not found.")
    if unit.decision != "drop":
        raise UnitNotDropped(
            f"Unit {unit_id} was kept by the Curator; there is no drop to resolve."
        )
    if unit.drop_resolution is not None:
        raise UnitAlreadyResolved(f"Unit {unit_id} is already resolved ({unit.drop_resolution}).")
    return unit


def dismiss_drop(unit: CuratedUnit) -> None:
    """The human agrees with the Curator. The caller validated and commits."""
    unit.drop_resolution = DISMISSED
    unit.drop_resolved_at = utc_now()


def mark_rescued(unit: CuratedUnit) -> None:
    """The human made a card from the drop: the Curator false-negative signal.

    `decision` is left as `drop` on purpose — it is the Curator's output.
    """
    unit.drop_resolution = RESCUED
    unit.drop_resolved_at = utc_now()


def validate_hand_written_card(card_type: str, front: str, back: str) -> None:
    """The card rules a typed card must meet; raises `HandWrittenCardInvalid`.

    A blank front is never a card; a QA needs an answer; a cloze needs exactly one
    `{{c1::...}}` deletion with a non-blank answer, and its back may be empty.
    """
    if not front.strip():
        raise HandWrittenCardInvalid("front must not be blank")
    if card_type == "qa" and not back.strip():
        raise HandWrittenCardInvalid("a Q&A card needs a back")
    if card_type == "cloze":
        deletions = _CLOZE_DELETION.findall(front)
        if len(deletions) != 1 or not deletions[0].strip():
            raise HandWrittenCardInvalid(
                "a cloze front needs exactly one {{c1::…}} deletion, e.g. "
                "'The {{c1::Gulf of Specification}} is the gap…'"
            )


def create_hand_written_card(
    session: Session, unit: CuratedUnit, *, card_type: str, front: str, back: str
) -> Card:
    """One human-authored card on a dropped unit, queued for approval.

    `pending_review`, never `approved` (hard rule 1). `original_*` hold the typed
    text — the text the card was born with — so an approval-time edit stays
    measurable. `model='human'`, `generation_rounds=0` and `guidance_version=NULL`
    mark it as no Writer's work, which keeps it out of the Learner's guidance
    attribution (docs/agents.md §7). No LLM call. The caller commits.
    """
    validate_hand_written_card(card_type, front, back)
    card = Card(
        unit_id=unit.id,
        type=card_type,
        front=front,
        back=back,
        original_front=front,
        original_back=back,
        tags=list(unit.tags),
        status="pending_review",
        status_reason=None,
        generation_rounds=0,
        draft_index=None,
        model=HUMAN_AUTHORED_MODEL,
        guidance_version=None,
        cost_microusd=0,
        user_id=unit.user_id,
    )
    session.add(card)
    mark_rescued(unit)
    session.flush()
    return card


def _newest_unit_by_highlight(session: Session) -> dict[int, int]:
    """Each highlight's most recent unit id: the unit that describes its current text."""
    return {
        highlight_id: newest_unit_id
        for highlight_id, newest_unit_id in session.execute(
            select(
                CuratedUnitHighlight.highlight_id, func.max(CuratedUnitHighlight.unit_id)
            ).group_by(CuratedUnitHighlight.highlight_id)
        )
        .tuples()
        .all()
    }


def _unit_sources(session: Session, unit_id: int) -> list[Highlight]:
    """A unit's source highlights, in export order."""
    return list(
        session.scalars(
            select(Highlight)
            .join(CuratedUnitHighlight, CuratedUnitHighlight.highlight_id == Highlight.id)
            .where(CuratedUnitHighlight.unit_id == unit_id)
            .order_by(Highlight.export_position)
        ).all()
    )


def _dropped_unit(unit: CuratedUnit, sources: list[Highlight]) -> DroppedUnit:
    """Grouping never spans a chapter, so the first source stands in for placement."""
    first = sources[0] if sources else None
    return DroppedUnit(
        id=unit.id,
        reason=unit.reason,
        curated_text=unit.curated_text,
        created_at=unit.created_at,
        drop_resolution=unit.drop_resolution,
        drop_resolved_at=unit.drop_resolved_at,
        source_highlights=[source.raw_text for source in sources],
        truncated=any(source.truncated for source in sources),
        book_id=first.book_id if first else 0,
        book=first.book.title if first else "",
        chapter=first.chapter if first else None,
        first_export_position=first.export_position if first else 0,
    )
