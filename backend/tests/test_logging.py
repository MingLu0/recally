"""Issue #211 gate: the backend configures logging, so the nightly jobs are audible.

Five modules create `recally.*` loggers, but nothing in the backend ever called
`basicConfig`/`dictConfig`, and uvicorn configures only its own `uvicorn.*` loggers.
So `recally.*` records propagated to a handlerless root: WARNING+ leaked bare to
stderr through Python's last-resort handler, and INFO — the jobs' entire operational
output — went nowhere. #93's decisions table promises the optimizer "no-ops with a
clear log line"; the line existed in code and no process emitted it.

`configure_logging()` is scoped to the `recally` namespace logger rather than the
root deliberately: no SQLAlchemy/APScheduler INFO noise, no root-logger pollution,
and no silent no-op when something else already configured the root.

`recally.logging_config` is imported inside each test rather than at module scope so
"not written yet" is one red test per behaviour instead of a collection error for the
whole file.
"""

import logging
from collections.abc import Callable, Iterator

import pytest
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool

from recally.config import Settings
from recally.container import Container
from recally.models import Base

TEST_API_KEY = "test-key-not-a-real-secret"
NAMESPACE = "recally"


@pytest.fixture(autouse=True)
def restore_namespace_logger() -> Iterator[None]:
    """Undo whatever a test did to the `recally` logger.

    The namespace logger is process-wide state: leaving a handler attached would make
    the idempotence test pass for the wrong reason, and leaking one into the rest of
    the suite would duplicate every later line.
    """
    namespace_logger = logging.getLogger(NAMESPACE)
    original_handlers = list(namespace_logger.handlers)
    original_level = namespace_logger.level
    original_propagate = namespace_logger.propagate
    try:
        yield
    finally:
        namespace_logger.handlers = original_handlers
        namespace_logger.setLevel(original_level)
        namespace_logger.propagate = original_propagate


def test_configure_logging_attaches_one_handler_to_the_recally_namespace() -> None:
    """The namespace logger owns exactly one handler, at INFO.

    INFO is the point: every line this issue is about — the optimizer's skip line, the
    notifier's one-push-per-day decisions, the Learner's stage B lines — is INFO.
    """
    from recally.logging_config import configure_logging

    namespace_logger = logging.getLogger(NAMESPACE)
    namespace_logger.handlers = []
    namespace_logger.setLevel(logging.NOTSET)

    configure_logging()

    assert len(namespace_logger.handlers) == 1
    assert isinstance(namespace_logger.handlers[0], logging.StreamHandler)
    assert namespace_logger.level == logging.INFO


def test_configure_logging_is_idempotent() -> None:
    """A second call attaches nothing, so no line is ever duplicated.

    Both entry points can re-enter it: the lifespan runs again under `--reload`, and
    the tests build the app repeatedly in one process.
    """
    from recally.logging_config import configure_logging

    namespace_logger = logging.getLogger(NAMESPACE)
    namespace_logger.handlers = []
    namespace_logger.setLevel(logging.NOTSET)

    configure_logging()
    handlers_after_first_call = list(namespace_logger.handlers)
    configure_logging()

    assert namespace_logger.handlers == handlers_after_first_call
    assert len(namespace_logger.handlers) == 1


@pytest.fixture
def make_container() -> Iterator[Callable[..., Container]]:
    """Container factory on fresh in-memory databases (same shape as the job tests)."""
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


def test_optimizer_log_lines_reach_the_stream(
    make_container: Callable[..., Container],
    capsys: pytest.CaptureFixture[str],
) -> None:
    """The optimizer's below-threshold skip line lands on the configured stream.

    This is the defect the #93 gate caught, end to end: not "the code calls
    `logger.info`" (it always did) but "a process emits the line". An empty database
    is below any positive threshold, so the fit is skipped without needing the
    `fsrs[optimizer]` extra.
    """
    from recally.logging_config import configure_logging
    from recally.scheduling.optimizer import fit_parameters

    namespace_logger = logging.getLogger(NAMESPACE)
    namespace_logger.handlers = []
    namespace_logger.setLevel(logging.NOTSET)
    configure_logging()

    container = make_container(OPTIMIZER_MIN_REVIEWS=400)
    assert fit_parameters(container) is None

    logged_output = capsys.readouterr().err
    assert "optimizer: 0 review_logs below OPTIMIZER_MIN_REVIEWS=400" in logged_output
    assert "skipping the fit" in logged_output


def test_configure_logging_is_called_by_the_job_running_entry_points() -> None:
    """Both entry points that run jobs configure logging; the CLI stays print-only.

    The handler has to be attached by the *process*, which is what the FastAPI
    lifespan and the watcher's `main()` are. Asserting on the source keeps the wiring
    from being deleted without a failing test, without starting a real server or a
    real filesystem observer.
    """
    import inspect

    from recally import main as main_module
    from recally.ingest import watcher

    assert "configure_logging" in inspect.getsource(main_module.lifespan)
    assert "configure_logging" in inspect.getsource(watcher.main)
