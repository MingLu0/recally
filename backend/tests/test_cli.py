"""Step 3f gate: the `recally` CLI (docs/backend.md, "Wiring and entry points").

The CLI is the validation-checkpoint client (docs/workflow.md, "Validation
checkpoint"): for two weeks it is the only way to use Recally. It calls container
services directly — never HTTP, never a DAO — so these tests drive `cli.main`
against an in-memory database through the container: no server, no network.

One test per command, asserting the underlying state change (the reads, `pending`
and `due`, assert what is listed, which is their state). The negatives assert a
non-zero exit code with no exception escaping — the CLI's problem+json-style error
surfacing, not a traceback.
"""

import ast
import os
import subprocess
import sys
from collections.abc import Iterator
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from recally import cli
from recally.config import Settings
from recally.container import Container
from recally.models import (
    Base,
    Book,
    Card,
    CardState,
    CuratedUnit,
    CuratedUnitHighlight,
    Highlight,
    IngestRun,
    ReviewLog,
)
from recally.models.base import utc_now
from recally.scheduling.fsrs import new_card_state
from recally.services.reviews import list_due

TEST_API_KEY = "test-key-not-a-real-secret"

BACKEND_DIR = Path(__file__).resolve().parents[1]


@pytest.fixture
def container() -> Iterator[Container]:
    """A container on a fresh in-memory database (see test_card_controls.py)."""
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    settings = Settings(RECALLY_DATABASE_URL="sqlite://", RECALLY_API_KEY=TEST_API_KEY)
    try:
        yield Container(settings, engine=engine)
    finally:
        engine.dispose()


def run_cli(container: Container, *argv: str) -> int:
    """Invoke the CLI in-process against the test container. Returns the exit code."""
    return cli.main(list(argv), container=container)


def _add_book(session: Session, *, title: str = "Evals for AI Engineers") -> Book:
    book = Book(title=title, source="oreilly", external_id="9781098188283", user_id=1)
    session.add(book)
    session.flush()
    return book


def _add_card(
    session: Session,
    book_id: int,
    *,
    status: str = "approved",
    front: str = "Why evaluate traces rather than individual steps?",
    back: str = "An LLM pipeline's behaviour only makes sense end-to-end.",
    chapter: str | None = "1. Introduction",
) -> Card:
    """A card plus its whole provenance chain, so the queue and due queries reach it."""
    highlight = Highlight(
        book_id=book_id,
        raw_text=back,
        dedupe_key=f"uuid-{book_id}-{front[:12]}-{status}",
        source="oreilly",
        chapter=chapter,
        highlighted_at=utc_now().date(),
        export_position=0,
        user_id=1,
    )
    run = IngestRun(filename="a-oreilly-annotations.csv", user_id=1)
    session.add_all([highlight, run])
    session.flush()
    unit = CuratedUnit(ingest_run_id=run.id, curated_text="…", decision="keep", tags=[], user_id=1)
    session.add(unit)
    session.flush()
    session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
    card = Card(
        unit_id=unit.id,
        type="qa",
        front=front,
        back=back,
        original_front=front,
        original_back=back,
        tags=[],
        status=status,
        approved_at=utc_now() if status == "approved" else None,
        model="claude-sonnet-5",
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def _add_due_state(session: Session, card_id: int, *, due: datetime) -> CardState:
    """The row approval would have created, moved to a past due date so the card is
    due no matter when the suite runs."""
    state = new_card_state(card_id=card_id, due=due, user_id=1)
    session.add(state)
    session.flush()
    return state


def _due_ids(container: Container) -> list[int]:
    """The due list through the same service the CLI uses."""
    with container.session() as session:
        scheduler = container.fsrs_scheduler(session)
        return [card.id for card in list_due(session, scheduler, new_cards_per_day=10).cards]


def _review_logs(session: Session, card_id: int) -> list[ReviewLog]:
    return list(session.scalars(select(ReviewLog).where(ReviewLog.card_id == card_id)).all())


# --- pending --------------------------------------------------------------------


def test_cli_pending_lists_queued_cards(
    container: Container, capsys: pytest.CaptureFixture
) -> None:
    """`pending_review` and `needs_human`, nothing else (services/cards.py QUEUE_STATUSES)."""
    with container.session() as session:
        book = _add_book(session)
        pending = _add_card(session, book.id, status="pending_review", front="Pending card front")
        needs_human = _add_card(session, book.id, status="needs_human", front="Needs-human front")
        approved = _add_card(session, book.id, status="approved", front="Approved card front")
        rejected = _add_card(session, book.id, status="rejected", front="Rejected card front")
        session.commit()
        pending_id, needs_human_id = pending.id, needs_human.id
        approved_id, rejected_id = approved.id, rejected.id

    assert run_cli(container, "pending") == 0

    out = capsys.readouterr().out
    assert f"#{pending_id} " in out
    assert f"#{needs_human_id} " in out
    assert f"#{approved_id} " not in out
    assert f"#{rejected_id} " not in out


# --- approve / reject -------------------------------------------------------------


def test_cli_approve_sets_status_and_creates_card_state(container: Container) -> None:
    """The 3a behaviour reached through the CLI: approval enters FSRS with one
    `card_state` row, `learning` at step 0, `due` at the approval time."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="pending_review")
        session.commit()
        card_id = card.id

    assert run_cli(container, "approve", str(card_id)) == 0

    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.status == "approved"
        assert persisted.approved_at is not None
        states = list(session.scalars(select(CardState).where(CardState.card_id == card_id)))
        assert len(states) == 1
        assert states[0].state == "learning"
        assert states[0].step == 0
        assert states[0].due == persisted.approved_at


def test_cli_reject_sets_status_and_reason(container: Container) -> None:
    """Rejection records the reason for the Learner and — hard rule 1 works in one
    direction only — never creates a `card_state` row."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="needs_human")
        session.commit()
        card_id = card.id

    assert run_cli(container, "reject", str(card_id), "--reason", "Too trivial.") == 0

    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.status == "rejected"
        assert persisted.status_reason == "Too trivial."
        assert list(session.scalars(select(CardState).where(CardState.card_id == card_id))) == []


# --- due --------------------------------------------------------------------------


def test_cli_due_lists_due_cards_with_state_and_step(
    container: Container, capsys: pytest.CaptureFixture
) -> None:
    with container.session() as session:
        book = _add_book(session)
        due_card = _add_card(session, book.id, status="approved", front="Due card front")
        _add_due_state(session, due_card.id, due=utc_now() - timedelta(days=1))
        future_card = _add_card(session, book.id, status="approved", front="Future card front")
        _add_due_state(session, future_card.id, due=utc_now() + timedelta(days=30))
        session.commit()
        due_id, future_id = due_card.id, future_card.id

    assert run_cli(container, "due") == 0

    out = capsys.readouterr().out
    assert f"#{due_id} " in out
    assert "learning" in out, "the FSRS state is printed"
    assert "step=0" in out, "the FSRS step is printed"
    assert f"#{future_id} " not in out


# --- rate -------------------------------------------------------------------------


def test_cli_rate_writes_a_review_log_and_advances_due(container: Container) -> None:
    due_before = utc_now() - timedelta(days=1)
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="approved")
        _add_due_state(session, card.id, due=due_before)
        session.commit()
        card_id = card.id

    assert run_cli(container, "rate", str(card_id), "3", "--response-ms", "1200") == 0

    with container.session() as session:
        logs = _review_logs(session, card_id)
        assert len(logs) == 1
        assert logs[0].rating == 3
        assert logs[0].response_ms == 1200
        assert logs[0].rated_at is not None
        state = session.get(CardState, card_id)
        assert state is not None
        assert state.last_review == logs[0].rated_at
        assert state.due > due_before, "a Good rating advances the due date"


def test_cli_rate_rejects_a_rating_outside_one_to_four(container: Container) -> None:
    """Negative: rating 7 exits non-zero and writes no `review_logs` row; the card's
    scheduling state is untouched."""
    due_before = utc_now() - timedelta(days=1)
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="approved")
        _add_due_state(session, card.id, due=due_before)
        session.commit()
        card_id = card.id

    exit_code = run_cli(container, "rate", str(card_id), "7")

    assert exit_code != 0
    with container.session() as session:
        assert _review_logs(session, card_id) == []
        state = session.get(CardState, card_id)
        assert state is not None
        assert state.due == due_before
        assert state.last_review is None


def test_cli_rate_sends_no_device_id(container: Container) -> None:
    """Negative: `review_logs.device_id` is null — a CLI rating must not be
    attributed to a phone, or the step 4 gate stops meaning anything."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="approved")
        _add_due_state(session, card.id, due=utc_now() - timedelta(days=1))
        session.commit()
        card_id = card.id

    assert run_cli(container, "rate", str(card_id), "3") == 0

    with container.session() as session:
        logs = _review_logs(session, card_id)
        assert len(logs) == 1
        assert logs[0].device_id is None


# --- edit -------------------------------------------------------------------------


def test_cli_edit_changes_front_and_sets_edited_at(container: Container) -> None:
    """The 3d edit through the CLI: front/back change, `edited_at` is stamped,
    `original_*` keeps the Writer's text and FSRS state is untouched (ADR-008)."""
    due_before = utc_now() - timedelta(days=1)
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="approved")
        _add_due_state(session, card.id, due=due_before)
        session.commit()
        card_id = card.id
        original_front = card.front

    assert (
        run_cli(container, "edit", str(card_id), "--front", "Edited front", "--back", "Edited back")
        == 0
    )

    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.front == "Edited front"
        assert persisted.back == "Edited back"
        assert persisted.edited_at is not None
        assert persisted.original_front == original_front
        state = session.get(CardState, card_id)
        assert state is not None
        assert state.due == due_before


def test_cli_edit_on_an_unapproved_card_fails(container: Container) -> None:
    """Negative: the 3d 409 surfaces as a non-zero exit, not a traceback; the card
    is unchanged (hard rule 1 — edits before approval belong to approve)."""
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="pending_review")
        session.commit()
        card_id = card.id
        original_front = card.front

    exit_code = run_cli(container, "edit", str(card_id), "--front", "Edited front")

    assert exit_code != 0
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.front == original_front
        assert persisted.edited_at is None


# --- bury / suspend / unsuspend ----------------------------------------------------


def test_cli_bury_removes_the_card_from_due(container: Container) -> None:
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="approved")
        _add_due_state(session, card.id, due=utc_now() - timedelta(days=1))
        session.commit()
        card_id = card.id

    assert _due_ids(container) == [card_id]

    assert run_cli(container, "bury", str(card_id)) == 0

    assert _due_ids(container) == []
    with container.session() as session:
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.suspended_until is not None


def test_cli_suspend_then_unsuspend_restores_the_same_due(container: Container) -> None:
    """Suspend takes the card out; unsuspend brings it back at the `due` it already
    held — nothing is recomputed (ADR-005, ADR-008)."""
    due_before = utc_now() - timedelta(days=1)
    with container.session() as session:
        book = _add_book(session)
        card = _add_card(session, book.id, status="approved")
        _add_due_state(session, card.id, due=due_before)
        session.commit()
        card_id = card.id

    assert run_cli(container, "suspend", str(card_id)) == 0
    assert _due_ids(container) == []

    assert run_cli(container, "unsuspend", str(card_id)) == 0

    assert _due_ids(container) == [card_id]
    with container.session() as session:
        state = session.get(CardState, card_id)
        assert state is not None
        assert state.due == due_before
        persisted = session.get(Card, card_id)
        assert persisted is not None
        assert persisted.suspended_until is None


# --- clean-failure negatives -------------------------------------------------------


def test_cli_approve_on_an_unknown_id_fails_cleanly(container: Container) -> None:
    """Negative: non-zero exit, no traceback, and nothing is written."""
    exit_code = run_cli(container, "approve", "999")

    assert exit_code != 0
    with container.session() as session:
        assert list(session.scalars(select(CardState))) == []


# --- layering negatives ------------------------------------------------------------

# The ticket's sys.modules reading of these two probes is unimplementable: litellm
# (`llm.py` -> the container -> `cli.py`) transitively imports fastapi/starlette and
# httpx at module import, so a clean-interpreter `sys.modules` check fails no matter
# how clean cli.py is. What layering rule 1 (docs/backend.md) actually governs is
# the project's own import direction, so these tests walk the recally-internal
# import graph reachable from cli.py with `ast` — the same technique as
# test_design_invariants.py — and assert no reachable module imports a web
# framework, plus no HTTP-client import in cli.py itself. The subprocess half still
# runs real commands with no server up, which is the operational claim.

PACKAGE_ROOT = Path(cli.__file__).resolve().parent


def _module_path(module: str) -> Path | None:
    """`recally.services.cards` -> src/recally/services/cards.py (or a package)."""
    parts = module.split(".")[1:]  # drop the leading "recally"
    file = PACKAGE_ROOT.joinpath(*parts).with_suffix(".py")
    if file.exists():
        return file
    package = PACKAGE_ROOT.joinpath(*parts, "__init__.py")
    return package if package.exists() else None


def _imports_of(path: Path) -> Iterator[str]:
    """Every absolute dotted name a file imports, relative imports resolved."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    package_parts = ["recally", *path.relative_to(PACKAGE_ROOT).with_suffix("").parts]
    if path.name != "__init__.py":
        package_parts = package_parts[:-1]
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                yield alias.name
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                keep = len(package_parts) - (node.level - 1)
                base_parts = package_parts[:keep]
            else:
                base_parts = []
            if node.module:
                base_parts.extend(node.module.split("."))
            base = ".".join(base_parts)
            if base:
                yield base
            for alias in node.names:
                if base:
                    yield f"{base}.{alias.name}"


def _reachable_recally_modules(start: str) -> set[str]:
    """The transitive closure of recally.* modules `start` imports."""
    seen: set[str] = set()
    stack = [start]
    while stack:
        module = stack.pop()
        if module in seen:
            continue
        seen.add(module)
        path = _module_path(module)
        if path is None:
            continue
        stack.extend(
            imported
            for imported in _imports_of(path)
            if imported == "recally" or imported.startswith("recally.")
        )
    return seen


def test_cli_imports_no_fastapi() -> None:
    """Negative (docs/backend.md, "Layering", rule 1): no recally module reachable
    from cli.py imports FastAPI/Starlette — the CLI runs without the HTTP layer."""
    offenders: dict[str, list[str]] = {}
    for module in sorted(_reachable_recally_modules("recally.cli")):
        path = _module_path(module)
        if path is None:
            continue
        matches = [
            imported
            for imported in _imports_of(path)
            if imported.split(".")[0] in ("fastapi", "starlette")
        ]
        if matches:
            offenders[module] = matches
    assert not offenders, (
        f"modules reachable from recally.cli import a web framework: {offenders} — "
        "nothing below api/ may import FastAPI (docs/backend.md, 'Layering', rule 1)"
    )


def test_cli_makes_no_http_call(tmp_path: Path) -> None:
    """Negative: cli.py imports no HTTP client, and real commands run against the
    database with no server up — if the CLI shelled out to its own server, the
    subprocess half would fail to connect."""
    cli_path = PACKAGE_ROOT / "cli.py"
    assert cli_path is not None
    http_imports = [
        imported
        for imported in _imports_of(cli_path)
        if imported.split(".")[0] in ("httpx", "requests", "urllib")
    ]
    assert not http_imports, f"cli.py imports HTTP clients: {http_imports}"

    database_url = f"sqlite:///{tmp_path}/cli.db"
    probe = (
        "import sys\n"
        "from sqlalchemy import create_engine\n"
        "from recally.models import Base\n"
        f"Base.metadata.create_all(create_engine({database_url!r}))\n"
        "from recally.cli import main\n"
        "assert main(['pending']) == 0\n"
        "assert main(['due']) == 0\n"
        "print('no-server-ok')\n"
    )
    env = {
        **os.environ,
        "RECALLY_DATABASE_URL": database_url,
        "RECALLY_API_KEY": TEST_API_KEY,
    }
    result = subprocess.run(
        [sys.executable, "-c", probe],
        capture_output=True,
        text=True,
        cwd=BACKEND_DIR,
        env=env,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    assert "no-server-ok" in result.stdout
