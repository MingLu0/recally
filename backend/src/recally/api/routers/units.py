"""Dropped units: the Curator's drops, listed and resolved (docs/api-spec.md,
"Dropped units"; #365, ADR-019).

A drop produces no card, so before this route it was visible only as a count on
`GET /ingest/status`. The human can now dismiss one (the Curator was right),
generate cards for it through the normal Writer ⇄ Critic loop, or write a card by
hand. Both rescues land in the approval queue — never `approved` (hard rule 1) —
and neither rewrites `curated_units.decision`, which stays the Curator's output.

Generate answers only after the cards are written, like `POST /ingest`: the
client reports a real result, not "accepted".
"""

from fastapi import APIRouter
from sqlalchemy.orm import Session

from recally.api.auth import ApiKeyGuard
from recally.api.deps import ContainerDep, SessionDep
from recally.api.errors import ProblemDetail
from recally.models import Card, CuratedUnit
from recally.schemas.cards import PendingCardResponse
from recally.schemas.units import (
    DroppedUnitResponse,
    DroppedUnitsResponse,
    GeneratedCardsResponse,
    HandWrittenCardRequest,
)
from recally.services.cards import pending_card
from recally.services.units import (
    UnitActionError,
    UnitNotFound,
    create_hand_written_card,
    dismiss_drop,
    dropped_count,
    dropped_unit_view,
    list_dropped_units,
    require_unresolved_drop,
)

router = APIRouter(prefix="/units", tags=["units"], dependencies=[ApiKeyGuard])


@router.get("/dropped", response_model=DroppedUnitsResponse)
def get_dropped_units(
    session: SessionDep, book_id: int | None = None, chapter: str | None = None
) -> DroppedUnitsResponse:
    """Unresolved drops from every ingest run, ordered book, chapter, then the
    smallest `export_position` among the unit's highlights."""
    units = list_dropped_units(session, book_id=book_id, chapter=chapter)
    return DroppedUnitsResponse(
        count=dropped_count(session),
        units=[DroppedUnitResponse.from_dropped(unit) for unit in units],
    )


@router.post("/{unit_id}/dismiss", response_model=DroppedUnitResponse)
def dismiss_unit(unit_id: int, session: SessionDep) -> DroppedUnitResponse:
    """The Curator was right: hide the drop. No undo (out of scope for #365)."""
    unit = _unresolved_drop(session, unit_id)
    dismiss_drop(unit)
    session.commit()
    return DroppedUnitResponse.from_dropped(dropped_unit_view(session, unit))


@router.post("/{unit_id}/generate", response_model=GeneratedCardsResponse)
def generate_unit_cards(
    unit_id: int, session: SessionDep, container: ContainerDep
) -> GeneratedCardsResponse:
    """Run the Writer ⇄ Critic loop on the drop; respond with the cards it wrote.

    A loop failure is a 502 and writes nothing: the unit stays unresolved and
    listed, so the human can try again.
    """
    try:
        card_ids = container.generate_cards_for_dropped_unit(unit_id)
    except UnitActionError as error:
        raise _refusal(error) from error
    except Exception as error:
        raise ProblemDetail(
            status=502,
            detail=(
                f"Card generation failed ({type(error).__name__}); "
                "the highlight is still in Dropped."
            ),
        ) from error
    return GeneratedCardsResponse(cards=[_pending_shape(session, card_id) for card_id in card_ids])


@router.post("/{unit_id}/cards", response_model=PendingCardResponse)
def create_unit_card(
    unit_id: int, body: HandWrittenCardRequest, session: SessionDep
) -> PendingCardResponse:
    """A hand-written card on the drop, queued as `pending_review`. No LLM call."""
    unit = _unresolved_drop(session, unit_id)
    card = create_hand_written_card(
        session, unit, card_type=body.type, front=body.front, back=body.back
    )
    session.commit()
    return _pending_shape(session, card.id)


def _unresolved_drop(session: Session, unit_id: int) -> CuratedUnit:
    """The drop to act on, or the problem+json refusal."""
    try:
        return require_unresolved_drop(session, unit_id)
    except UnitActionError as error:
        raise _refusal(error) from error


def _refusal(error: UnitActionError) -> ProblemDetail:
    """404 for an unknown unit; 409 for a keep or an already-resolved drop."""
    return ProblemDetail(status=404 if isinstance(error, UnitNotFound) else 409, detail=str(error))


def _pending_shape(session: Session, card_id: int) -> PendingCardResponse:
    card = session.get(Card, card_id)
    queued = pending_card(session, card) if card is not None else None
    if queued is None:  # pragma: no cover - the unit's sources were checked above
        raise ProblemDetail(status=500, detail="Internal server error.")
    return PendingCardResponse.from_pending(queued)
