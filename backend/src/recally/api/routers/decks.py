"""`GET /decks` and `GET /decks/{book_id}/cards` (docs/api-spec.md, "Decks & browsing")."""

from fastapi import APIRouter

from recally.api.auth import ApiKeyGuard
from recally.api.deps import SessionDep
from recally.schemas.decks import Deck, DeckCard, DeckCardsResponse, DeckListResponse
from recally.services.decks import list_deck_cards, list_decks

router = APIRouter(prefix="/decks", tags=["decks"], dependencies=[ApiKeyGuard])


@router.get("", response_model=DeckListResponse)
def get_decks(session: SessionDep) -> DeckListResponse:
    """Books with card counts and due counts."""
    return DeckListResponse(decks=[Deck.from_summary(summary) for summary in list_decks(session)])


@router.get("/{book_id}/cards", response_model=DeckCardsResponse)
def get_deck_cards(
    book_id: int, session: SessionDep, chapter: str | None = None
) -> DeckCardsResponse:
    """Browse a book's approved cards, suspended ones included (ADR-008).

    Unlike `/reviews/due` this list does not filter suspended cards out — it is
    where unsuspend is reached — so each card carries `suspended_until` (null when
    in rotation).
    """
    records = list_deck_cards(session, book_id, chapter=chapter)
    return DeckCardsResponse(cards=[DeckCard.from_record(record) for record in records])
