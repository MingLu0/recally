"""Step 6a-a gate: the nightly FSRS optimizer (Learner stage A).

Spec: docs/agents.md, "7. Learner Agent" (stage A is deterministic — "parameter
fitting is math", hard rule 2); docs/data-model.md, `fsrs_params` ("latest row is
active; defaults are used until the first fit"); docs/config.md,
`OPTIMIZER_MIN_REVIEWS`; docs/architecture.md, "Optimizer" (the optional
torch/pandas extra); ADR-002.

The fit needs the `fsrs[optimizer]` extra (torch + pandas), so the tests that run
a real fit are marked `optimizer` and skip when the extra is absent — the default
`uv sync` suite and CI exercise everything else: the threshold, the missing-extra
no-op, and the layering rules.

`recally.scheduling.optimizer` is imported inside each test rather than at module
scope: the module does not exist until the implementation lands, and a late import
turns "not written yet" into one red test per behaviour instead of a collection
error for the whole file.
"""

import ast
import itertools
import logging
from collections.abc import Callable, Iterator
from datetime import timedelta
from pathlib import Path

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

import recally.scheduling
from recally.config import Settings
from recally.container import Container
from recally.models import Base, Card, CuratedUnit, FsrsParams, IngestRun, ReviewLog
from recally.models.base import utc_now

TEST_API_KEY = "test-key-not-a-real-secret"
NOW = utc_now()

_PROVENANCE_COUNTER = itertools.count(1)

# Mostly Good with a lapse and an Easy mixed in; variety keeps the fit honest
# without depending on any particular schedule.
RATINGS = (3, 3, 2, 4, 1)


def _optimizer_extra_available() -> bool:
    """True when `fsrs[optimizer]` (torch + pandas) is installed.

    `from fsrs import Optimizer` always succeeds — py-fsrs lazy-loads a stub that
    raises ImportError only on instantiation — so the probe has to construct one.
    """
    try:
        from fsrs import Optimizer

        Optimizer(())
    except ImportError:
        return False
    return True


requires_optimizer_extra = pytest.mark.skipif(
    not _optimizer_extra_available(),
    reason="fsrs[optimizer] extra not installed (uv sync --extra optimizer)",
)


@pytest.fixture
def make_container() -> Iterator[Callable[..., Container]]:
    """Container factory on fresh in-memory databases, with settings overrides
    (same shape as tests/api/test_jobs.py)."""
    built: list[Container] = []

    def factory(**settings_overrides: object) -> Container:
        engine = create_engine(
            "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
        )
        Base.metadata.create_all(engine)
        settings = Settings(
            RECALLY_DATABASE_URL="sqlite://",
            RECALLY_API_KEY=TEST_API_KEY,
            **settings_overrides,  # type: ignore[arg-type]
        )
        container = Container(settings, engine=engine)
        built.append(container)
        return container

    try:
        yield factory
    finally:
        for container in built:
            container.engine.dispose()


@pytest.fixture
def container(make_container: Callable[..., Container]) -> Container:
    return make_container()


def _seed_card(session: Session) -> Card:
    """One approved card with the minimal provenance chain review_logs hang off."""
    suffix = next(_PROVENANCE_COUNTER)
    run = IngestRun(filename=f"seed-{suffix}-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    unit = CuratedUnit(ingest_run_id=run.id, curated_text="…", tags=[], decision="keep", user_id=1)
    session.add(unit)
    session.flush()
    card = Card(
        unit_id=unit.id,
        type="qa",
        front="Why is parameter fitting math rather than an LLM call?",
        back="It minimises a loss over review history; there is nothing to reason about.",
        original_front="Why is parameter fitting math rather than an LLM call?",
        original_back="It minimises a loss over review history; there is nothing to reason about.",
        tags=["fsrs"],
        status="approved",
        approved_at=NOW - timedelta(days=500),
        model="claude-sonnet-5",
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def _seed_reviews(session: Session, card_id: int, count: int) -> None:
    """`count` review_logs for one card, one per day, newest yesterday.

    Day spacing is what the optimizer's training-data count keys on (only
    non-same-day reviews count), and keeps every (card_id, rated_at) pair unique.
    """
    for index in range(count):
        session.add(
            ReviewLog(
                card_id=card_id,
                rated_at=NOW - timedelta(days=count - index),
                rating=RATINGS[index % len(RATINGS)],
                response_ms=4200,
                scheduled_days=0,
                state_before="review",
                user_id=1,
            )
        )
    session.commit()


def _seeded_card_with_reviews(container: Container, count: int) -> None:
    with container.session() as session:
        card = _seed_card(session)
        _seed_reviews(session, card.id, count)


def _fsrs_params_rows(container: Container) -> list[FsrsParams]:
    with container.session() as session:
        return list(session.scalars(select(FsrsParams).order_by(FsrsParams.id)).all())


def _fsrs_params_count(container: Container) -> int:
    with container.session() as session:
        return session.scalar(select(func.count()).select_from(FsrsParams)) or 0


# --- The roadmap gate (docs/roadmap.md, step 6a) -------------------------------


def test_below_min_reviews_writes_no_fsrs_params_row(
    make_container: Callable[..., Container],
) -> None:
    """With OPTIMIZER_MIN_REVIEWS - 1 reviews the job writes nothing: not a row
    with defaults, not a row with a flag — no row (docs/data-model.md: "Defaults
    are used until the first fit"; a premature row would end that state)."""
    from recally.scheduling import optimizer

    container = make_container()  # default OPTIMIZER_MIN_REVIEWS=400
    _seeded_card_with_reviews(container, container.settings.optimizer_min_reviews - 1)

    result = optimizer.fit_parameters(container)

    assert result is None
    assert _fsrs_params_count(container) == 0


@pytest.mark.optimizer
@requires_optimizer_extra
def test_above_min_reviews_writes_one_row_with_21_parameters_and_review_count(
    make_container: Callable[..., Container],
) -> None:
    """Above the threshold the job appends one fsrs_params row carrying the 21
    FSRS weights and the number of reviews the fit read."""
    from recally.scheduling import optimizer

    container = make_container()
    review_total = container.settings.optimizer_min_reviews + 1
    _seeded_card_with_reviews(container, review_total)

    row = optimizer.fit_parameters(container)

    assert row is not None
    rows = _fsrs_params_rows(container)
    assert len(rows) == 1
    assert len(rows[0].parameters) == 21, (
        f"fsrs_params.parameters holds the 21 FSRS weights, got {len(rows[0].parameters)}"
    )
    assert rows[0].review_count == review_total


# --- The threshold, exactly -----------------------------------------------------


@pytest.mark.optimizer
@requires_optimizer_extra
def test_threshold_is_inclusive_at_min_reviews(
    make_container: Callable[..., Container],
) -> None:
    """Exactly OPTIMIZER_MIN_REVIEWS reviews fits: "below" (docs/config.md,
    docs/roadmap.md) means strictly below. The boundary is where an off-by-one
    hides."""
    from recally.scheduling import optimizer

    container = make_container(OPTIMIZER_MIN_REVIEWS=10)
    _seeded_card_with_reviews(container, 10)

    row = optimizer.fit_parameters(container)

    assert row is not None
    assert _fsrs_params_count(container) == 1


def test_below_threshold_does_not_write_a_defaults_row(
    make_container: Callable[..., Container], monkeypatch: pytest.MonkeyPatch
) -> None:
    """No row *of any kind* below the threshold — the failure mode is a fit on
    noise writing library defaults over tuned-by-omission defaults. The Optimizer
    spy proves the fit is never even attempted."""
    import fsrs

    from recally.scheduling import optimizer

    constructed_with: list[object] = []

    class OptimizerSpy:
        def __init__(self, review_logs: object) -> None:
            constructed_with.append(review_logs)

    monkeypatch.setattr(fsrs, "Optimizer", OptimizerSpy)
    container = make_container(OPTIMIZER_MIN_REVIEWS=10)
    _seeded_card_with_reviews(container, 3)

    result = optimizer.fit_parameters(container)

    assert result is None
    assert constructed_with == [], "the optimizer was constructed below the threshold"
    assert _fsrs_params_count(container) == 0


# --- The optional extra ----------------------------------------------------------


class _MissingOptimizer:
    """What `fsrs.Optimizer` is without the extra: a stub whose constructor raises."""

    def __init__(self, *_args: object, **_kwargs: object) -> None:
        raise ImportError(
            'Optimizer is not installed.\nInstall it with: pip install "fsrs[optimizer]"'
        )


def test_missing_optimizer_extra_is_a_no_op_not_a_raise(
    make_container: Callable[..., Container],
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """With the extra unavailable the job returns cleanly and logs the install
    command (docs/architecture.md: the torch/pandas extra is optional). A raise
    would page a nightly cron for a dependency that is deliberately absent."""
    import fsrs

    from recally.scheduling import optimizer

    monkeypatch.setattr(fsrs, "Optimizer", _MissingOptimizer)
    container = make_container(OPTIMIZER_MIN_REVIEWS=3)
    _seeded_card_with_reviews(container, 3)

    with caplog.at_level(logging.WARNING, logger="recally.scheduling.optimizer"):
        result = optimizer.fit_parameters(container)

    assert result is None
    assert "uv sync --extra optimizer" in caplog.text


def test_missing_optimizer_extra_does_not_write_a_row(
    make_container: Callable[..., Container], monkeypatch: pytest.MonkeyPatch
) -> None:
    """An unavailable dependency must never look like a completed fit: no row,
    so the scheduler keeps using library defaults (docs/data-model.md)."""
    import fsrs

    from recally.scheduling import optimizer

    monkeypatch.setattr(fsrs, "Optimizer", _MissingOptimizer)
    container = make_container(OPTIMIZER_MIN_REVIEWS=3)
    _seeded_card_with_reviews(container, 3)

    optimizer.fit_parameters(container)

    assert _fsrs_params_count(container) == 0


# --- The row ---------------------------------------------------------------------


@pytest.mark.optimizer
@requires_optimizer_extra
def test_rerunning_appends_a_row_and_never_edits_the_previous_one(
    make_container: Callable[..., Container],
) -> None:
    """Two runs give two rows, and the first row's parameters and created_at are
    byte-identical afterwards. "Latest row is active" (docs/data-model.md) only
    holds if history is immutable."""
    from recally.scheduling import optimizer

    container = make_container(OPTIMIZER_MIN_REVIEWS=10)
    _seeded_card_with_reviews(container, 12)

    first = optimizer.fit_parameters(container)
    assert first is not None
    first_parameters = list(first.parameters)
    first_created_at = first.created_at

    second = optimizer.fit_parameters(container)

    assert second is not None
    rows = _fsrs_params_rows(container)
    assert len(rows) == 2
    assert rows[0].id != rows[1].id
    assert list(rows[0].parameters) == first_parameters
    assert rows[0].created_at == first_created_at


@pytest.mark.optimizer
@requires_optimizer_extra
def test_desired_retention_is_recorded_on_the_row(
    make_container: Callable[..., Container],
) -> None:
    """desired_retention comes from FSRS_DESIRED_RETENTION (docs/config.md) — the
    scheduler wrapper reads it off the latest row, so the fit must record it."""
    from recally.scheduling import optimizer

    container = make_container(OPTIMIZER_MIN_REVIEWS=10, FSRS_DESIRED_RETENTION=0.85)
    _seeded_card_with_reviews(container, 10)

    row = optimizer.fit_parameters(container)

    assert row is not None
    assert row.desired_retention == 0.85


@pytest.mark.optimizer
@requires_optimizer_extra
def test_review_count_matches_the_rows_the_fit_actually_read(
    make_container: Callable[..., Container],
) -> None:
    """review_count is the number of review_logs the fit was based on
    (docs/data-model.md), so a glance at the row says how much history shaped it."""
    from recally.scheduling import optimizer

    container = make_container(OPTIMIZER_MIN_REVIEWS=10)
    _seeded_card_with_reviews(container, 12)

    row = optimizer.fit_parameters(container)

    assert row is not None
    assert row.review_count == 12


# --- Hard rules and layering ------------------------------------------------------


@pytest.mark.optimizer
@requires_optimizer_extra
def test_optimizer_makes_no_llm_call(
    make_container: Callable[..., Container], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Hard rule 2: stage A is math (docs/agents.md, "What is LLM vs
    deterministic"). With the LLM caller rigged to explode, a full fit still
    completes and writes its row."""
    from recally.llm import LlmCaller
    from recally.scheduling import optimizer

    def explode(*_args: object, **_kwargs: object) -> None:
        raise AssertionError("the optimizer made an LLM call (hard rule 2)")

    monkeypatch.setattr(LlmCaller, "__call__", explode)
    container = make_container(OPTIMIZER_MIN_REVIEWS=10)
    _seeded_card_with_reviews(container, 10)

    row = optimizer.fit_parameters(container)

    assert row is not None


def test_optimizer_does_not_import_fastapi() -> None:
    """Nothing below api/ imports FastAPI (docs/backend.md, "Layering" rule 1):
    APScheduler, an external cron and the jobs router all call this module, and
    only one of those is an HTTP request. AST-walked like the other import tests
    so formatting cannot defeat it. Fails while the module does not exist yet."""
    module_path = Path(recally.scheduling.__file__).resolve().parent / "optimizer.py"
    tree = ast.parse(module_path.read_text(encoding="utf-8"), filename=str(module_path))

    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module.split(".")[0])

    assert "fastapi" not in imported and "starlette" not in imported, (
        f"scheduling/optimizer.py imports {sorted(imported & {'fastapi', 'starlette'})}: "
        "nothing below api/ imports FastAPI (docs/backend.md, 'Layering' rule 1)"
    )
