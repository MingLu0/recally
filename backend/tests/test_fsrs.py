"""Step 3a gate: the py-fsrs wrapper in `scheduling/fsrs.py`.

The wrapper is the only module that imports `fsrs` (same seam as `llm.py` for
LiteLLM), is server-authoritative for scheduling (hard rule 5: every rating is
scored at its explicit client `review_datetime`, never at "now"), and converts
between `card_state` rows and the library `Card` through the documented
`to_dict`/`from_dict` round-trip (docs/data-model.md, `card_state`).
"""

import ast
from collections.abc import Iterator
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from fsrs import Rating, Scheduler, State
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from recally.config import Settings
from recally.models import Base, CardState, FsrsParams
from recally.scheduling.fsrs import (
    FsrsScheduler,
    apply_library_card,
    card_to_library,
    new_card_state,
)

NOW = datetime(2026, 9, 8, 12, 0, 0)


def _fitted_weights(first_weight: float) -> list[float]:
    """A plausible optimizer fit: the library defaults with w[0] perturbed, so the
    weights stay inside the bounds the library validates."""
    weights = list(Scheduler().parameters)
    weights[0] = first_weight
    return weights


FITTED_OLD = _fitted_weights(0.3)
FITTED_NEW = _fitted_weights(0.4)


@pytest.fixture
def session() -> Iterator[Session]:
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    try:
        with session_factory() as test_session:
            yield test_session
    finally:
        engine.dispose()


def _settings(**overrides: object) -> Settings:
    return Settings(RECALLY_API_KEY="test-key-not-a-real-secret", **overrides)  # type: ignore[arg-type]


def test_new_card_is_learning_step_zero() -> None:
    """FSRS 6 has no `new` state: a fresh card is `learning` at step 0, with
    stability/difficulty NULL until the first review (docs/data-model.md)."""
    state = new_card_state(card_id=1, due=NOW)

    assert state.state == "learning"
    assert state.step == 0
    assert state.stability is None
    assert state.difficulty is None
    assert state.last_review is None

    library_card = card_to_library(state)
    assert library_card.state == State.Learning
    assert library_card.step == 0
    assert library_card.stability is None
    assert library_card.difficulty is None


def test_state_round_trips_through_from_dict() -> None:
    """Every `card_state` column survives row → Card.from_dict → to_dict → row,
    including a card mid-`review` with `step` NULL (docs/data-model.md: the table
    mirrors the library object so the conversion is a mapping, not a translation)."""
    rows = [
        new_card_state(card_id=1, due=NOW),
        CardState(
            card_id=2,
            state="review",
            step=None,
            stability=12.5,
            difficulty=4.2,
            due=NOW + timedelta(days=9),
            last_review=NOW - timedelta(days=1),
            user_id=1,
        ),
        CardState(
            card_id=3,
            state="relearning",
            step=1,
            stability=0.5,
            difficulty=7.7,
            due=NOW + timedelta(minutes=10),
            last_review=NOW,
            user_id=1,
        ),
    ]

    for original in rows:
        library_card = card_to_library(original)
        restored = CardState(card_id=original.card_id, state="learning", due=NOW, user_id=1)
        apply_library_card(restored, library_card)

        assert restored.state == original.state
        assert restored.step == original.step
        assert restored.stability == original.stability
        assert restored.difficulty == original.difficulty
        assert restored.due == original.due
        assert restored.last_review == original.last_review


def test_review_card_honours_explicit_review_datetime(session: Session) -> None:
    """Hard rule 5: the wrapper never substitutes the current time. The same rating
    an hour earlier produces a `due` an hour earlier."""
    scheduler = FsrsScheduler.build(_settings(), session)
    an_hour_ago = NOW - timedelta(hours=1)
    rated_now = new_card_state(card_id=1, due=NOW)
    rated_earlier = new_card_state(card_id=2, due=an_hour_ago)

    scheduler.review_card(rated_now, Rating.Good, review_datetime=NOW)
    scheduler.review_card(rated_earlier, Rating.Good, review_datetime=an_hour_ago)

    assert rated_now.due - rated_earlier.due == timedelta(hours=1)


def test_learning_steps_come_from_config(session: Session) -> None:
    """FSRS_LEARNING_STEPS_MINUTES=2,20 puts an Again-rated new card due 2 minutes
    out, not the library default of 1 (docs/config.md)."""
    scheduler = FsrsScheduler.build(_settings(FSRS_LEARNING_STEPS_MINUTES="2,20"), session)
    state = new_card_state(card_id=1, due=NOW)

    scheduler.review_card(state, Rating.Again, review_datetime=NOW)

    assert state.due == NOW + timedelta(minutes=2)


def test_desired_retention_comes_from_config(session: Session) -> None:
    """A non-default FSRS_DESIRED_RETENTION reaches the `Scheduler`."""
    scheduler = FsrsScheduler.build(_settings(FSRS_DESIRED_RETENTION=0.8), session)

    assert scheduler.desired_retention == 0.8


def test_latest_fsrs_params_row_is_used_when_present(session: Session) -> None:
    """The latest `fsrs_params` row is active (docs/data-model.md); its 21 weights
    and retention override the config defaults."""
    session.add(
        FsrsParams(
            parameters=FITTED_OLD,
            desired_retention=0.7,
            review_count=500,
            created_at=NOW - timedelta(days=7),
            user_id=1,
        )
    )
    session.add(
        FsrsParams(
            parameters=FITTED_NEW,
            desired_retention=0.95,
            review_count=900,
            created_at=NOW,
            user_id=1,
        )
    )
    session.commit()

    scheduler = FsrsScheduler.build(_settings(FSRS_DESIRED_RETENTION=0.8), session)

    assert list(scheduler.parameters) == pytest.approx(FITTED_NEW)
    assert scheduler.desired_retention == 0.95


def test_defaults_used_when_no_fsrs_params_row(session: Session) -> None:
    """An empty `fsrs_params` table is not an error: the library defaults and the
    configured retention are in effect until the first fit."""
    scheduler = FsrsScheduler.build(_settings(), session)

    assert scheduler.desired_retention == 0.9
    assert list(scheduler.parameters) == list(Scheduler().parameters)


def test_again_on_new_card_is_due_within_the_first_learning_step(session: Session) -> None:
    """The roadmap step 3 gate's first clause at the wrapper level: an Again rating
    on a new card lands inside the first learning step (default 1 minute)."""
    scheduler = FsrsScheduler.build(_settings(), session)
    state = new_card_state(card_id=1, due=NOW)

    scheduler.review_card(state, Rating.Again, review_datetime=NOW)

    assert NOW < state.due <= NOW + timedelta(minutes=1)


def test_fsrs_wrapper_imports_no_fastapi() -> None:
    """Negative layering check (docs/backend.md, "Layering", rule 1): scheduling/
    is called from the CLI and APScheduler too, so no FastAPI/Starlette type may
    leak into it."""
    scheduling_dir = Path(__file__).resolve().parents[1] / "src" / "recally" / "scheduling"
    assert scheduling_dir.is_dir(), "the scheduling/ package does not exist yet"

    offenders: list[str] = []
    for path in sorted(scheduling_dir.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                offenders.extend(
                    f"{path.name}: import {alias.name}"
                    for alias in node.names
                    if alias.name.split(".")[0] in ("fastapi", "starlette")
                )
            elif (
                isinstance(node, ast.ImportFrom)
                and node.module
                and node.module.split(".")[0] in ("fastapi", "starlette")
            ):
                offenders.append(f"{path.name}: from {node.module} import ...")

    assert not offenders, "scheduling/ must not import FastAPI/Starlette: " + "; ".join(offenders)
