"""`GET /decks` (docs/api-spec.md, "Decks & browsing")."""

from fastapi import APIRouter

from recally.api.auth import ApiKeyGuard
from recally.api.deps import SessionDep
from recally.schemas.decks import Deck, DeckListResponse
from recally.services.decks import list_decks

router = APIRouter(prefix="/decks", tags=["decks"], dependencies=[ApiKeyGuard])


@router.get("", response_model=DeckListResponse)
def get_decks(session: SessionDep) -> DeckListResponse:
    """Books with card counts and due counts."""
    return DeckListResponse(decks=[Deck.from_summary(summary) for summary in list_decks(session)])
