"""The review endpoints (docs/api-spec.md, "Reviews"; ADR-005, ADR-008).

Routers translate HTTP ↔ service calls and nothing else (docs/backend.md,
"Layering", rule 2): the due query and rating application live in
`services/reviews.py`, which the CLI (roadmap step 3f) also calls directly.
"""

from datetime import datetime
from typing import Any

from fastapi import APIRouter
from pydantic import ValidationError

from recally.api.auth import ApiKeyGuard
from recally.api.deps import ContainerDep, SessionDep
from recally.api.errors import ProblemDetail, _summarize_validation_errors
from recally.schemas.reviews import (
    DueCardResponse,
    DueListResponse,
    RateBatchItem,
    RateBatchRequest,
    RateBatchResponse,
    RateBatchResultItem,
    RateCardRequest,
    RateResultResponse,
)
from recally.services.reviews import (
    RatingCommand,
    RatingOutcome,
    ReviewError,
    apply_batch,
    list_due,
    rate_card,
)

router = APIRouter(prefix="/reviews", tags=["reviews"], dependencies=[ApiKeyGuard])


@router.get("/due", response_model=DueListResponse)
def get_due_reviews(session: SessionDep, container: ContainerDep) -> DueListResponse:
    """Cards due now plus today's new-card allotment (capped by NEW_CARDS_PER_DAY)."""
    due = list_due(
        session,
        container.fsrs_scheduler(session),
        new_cards_per_day=container.settings.new_cards_per_day,
    )
    return DueListResponse(
        due_count=due.due_count,
        new_count=due.new_count,
        learning_steps_minutes=due.learning_steps_minutes,
        cards=[DueCardResponse.from_due_card(card) for card in due.cards],
    )


@router.post("/{card_id}/rate", response_model=RateResultResponse)
def rate_card_endpoint(
    card_id: int, session: SessionDep, container: ContainerDep, body: RateCardRequest
) -> RateResultResponse:
    """Score one rating at its client `rated_at`, write the `review_logs` row."""
    command = RatingCommand(
        card_id=card_id,
        rating=body.rating,
        response_ms=body.response_ms,
        rated_at=body.rated_at,
        device_id=body.device_id,
    )
    try:
        result = rate_card(session, container.fsrs_scheduler(session), command)
    except ReviewError as error:
        raise ProblemDetail(status=error.status, detail=error.detail) from error
    return RateResultResponse.from_result(result)


@router.post("/rate-batch", response_model=RateBatchResponse)
def rate_batch_endpoint(
    session: SessionDep, container: ContainerDep, body: RateBatchRequest
) -> RateBatchResponse:
    """Flush the offline rating queue: each item applied independently.

    Items are validated one by one, so a malformed item becomes an `ok: false`
    result rather than a failed call; only a body that is not `{"ratings": [...]}`
    is a top-level 422 (docs/api-spec.md). Results align with the request by
    position — the client matches them to its queue that way.
    """
    results: list[RateBatchResultItem | None] = [None] * len(body.ratings)
    commands: list[RatingCommand] = []
    positions: list[int] = []
    for index, raw in enumerate(body.ratings):
        try:
            command = _validate_batch_item(raw)
        except ReviewError as error:
            results[index] = _failure_item(raw, error)
            continue
        commands.append(command)
        positions.append(index)

    outcomes = apply_batch(session, container.fsrs_scheduler(session), commands)
    for position, command, outcome in zip(positions, commands, outcomes, strict=True):
        results[position] = _outcome_item(command, outcome)

    return RateBatchResponse(results=[item for item in results if item is not None])


def _validate_batch_item(raw: Any) -> RatingCommand:
    """One batch item as a command, or the ReviewError its result entry carries."""
    try:
        parsed = RateBatchItem.model_validate(raw)
    except ValidationError as error:
        raise ReviewError(
            status=422, detail=_summarize_validation_errors(error.errors())
        ) from error
    return RatingCommand(
        card_id=parsed.card_id,
        rating=parsed.rating,
        response_ms=parsed.response_ms,
        rated_at=parsed.rated_at,
        device_id=parsed.device_id,
    )


def _failure_item(raw: Any, error: ReviewError) -> RateBatchResultItem:
    """A failed item's result, echoing `card_id`/`rated_at` when they parsed."""
    card_id = raw.get("card_id") if isinstance(raw, dict) else None
    rated_at = raw.get("rated_at") if isinstance(raw, dict) else None
    return RateBatchResultItem(
        card_id=card_id if isinstance(card_id, int) else None,
        rated_at=_parse_datetime(rated_at),
        ok=False,
        status=error.status,
        detail=error.detail,
    )


def _outcome_item(command: RatingCommand, outcome: RatingOutcome) -> RateBatchResultItem:
    if outcome.error is not None:
        return RateBatchResultItem(
            card_id=command.card_id,
            rated_at=command.rated_at,
            ok=False,
            status=outcome.error.status,
            detail=outcome.error.detail,
        )
    if outcome.result is None:  # pragma: no cover - RatingOutcome always sets one side
        raise RuntimeError("RatingOutcome with neither result nor error")
    return RateBatchResultItem(
        card_id=outcome.result.card_id,
        rated_at=outcome.result.rated_at,
        ok=True,
        next_due=outcome.result.next_due,
        state=outcome.result.state,
        step=outcome.result.step,
        lapsed=outcome.result.lapsed,
        duplicate=outcome.result.duplicate,
    )


def _parse_datetime(value: Any) -> datetime | None:
    """Best-effort echo of a raw `rated_at` from a malformed item; None if unparseable."""
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
