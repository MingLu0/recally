"""The #243 gate: the replay harness never touches production data.

`recally.evals.harness` replays the Writer ⇄ Critic loop over the committed eval set
by resolving agents through the registry — never through `pipeline.run()` — so no
eval run can create a card or flip a `processed` flag. It must also refuse to start
when its database URL is the configured production path: `llm_calls` rows are always
written (hard rule 3), so the harness runs against a throwaway copy.
"""

import json
from collections.abc import Callable, Iterator
from datetime import date
from pathlib import Path
from typing import Any, Literal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

from recally.agents.base import (
    AgentContext,
    CardDraft,
    CardVerdict,
    CriticRequest,
    CriticResult,
    WriterRequest,
    WriterResult,
)
from recally.agents.registry import AgentRegistry
from recally.config import Settings
from recally.evals.harness import ProductionDatabaseError, run_harness
from recally.models import Base, Book, Card, CuratedUnit, CuratedUnitHighlight, Highlight, IngestRun

REAL_EVAL_SET = Path(__file__).parents[1] / "fixtures" / "eval" / "writer_eval.jsonl"


# --- stub agents -----------------------------------------------------------------


class StubWriter:
    """One fixed card per call; optionally raises for a chosen unit (see #219)."""

    def __init__(self, *, fail_on_text: str | None = None) -> None:
        self.requests: list[WriterRequest] = []
        self._fail_on_text = fail_on_text

    def __call__(self, request: WriterRequest, ctx: AgentContext) -> WriterResult:
        self.requests.append(request)
        if self._fail_on_text and self._fail_on_text in request.curated_text:
            raise ValueError("writer returned no cards")
        return WriterResult(
            cards=[
                CardDraft(
                    type="qa",
                    front="Why does the harness replay?",
                    back="To score against the human.",
                    rationale="stub",
                    guidance_version=None,
                )
            ]
        )


class StubCritic:
    """One fixed verdict for every card, recording each request."""

    def __init__(self, verdict: Literal["accept", "revise", "reject"] = "accept") -> None:
        self.requests: list[CriticRequest] = []
        self._verdict = verdict

    def __call__(self, request: CriticRequest, ctx: AgentContext) -> CriticResult:
        self.requests.append(request)
        critique = "" if self._verdict == "accept" else "not good enough"
        return CriticResult(
            verdicts=[CardVerdict(verdict=self._verdict, critique=critique) for _ in request.cards]
        )


def _registry(writer: StubWriter, critic: StubCritic) -> AgentRegistry:
    registry = AgentRegistry()
    registry.register("writer", "stub", writer)
    registry.register("critic", "stub", critic)
    return registry


def _settings(tmp_path: Path, **overrides: Any) -> Settings:
    kwargs: dict[str, Any] = {
        # The configured production path is a marker; the harness target is passed
        # separately, and the two must differ for a run to be allowed.
        "RECALLY_DATABASE_URL": f"sqlite:///{tmp_path}/production.db",
        "RECALLY_API_KEY": "test-key-not-a-real-secret",
        "AGENT_WRITER": "stub",
        "AGENT_CRITIC": "stub",
        "LLM_CONCURRENCY": 1,
    }
    kwargs.update(overrides)
    return Settings(**kwargs)


# --- database seeding ------------------------------------------------------------


def _add_unit(session: Session, *, curated_text: str, processed: bool = False) -> CuratedUnit:
    book = Book(
        title="Evals for AI Engineers",
        source="oreilly",
        external_id=f"9781098188283-{curated_text}",
    )
    run = IngestRun(filename="a-oreilly-annotations.csv")
    session.add_all([book, run])
    session.flush()
    highlight = Highlight(
        book_id=book.id,
        raw_text=curated_text,
        dedupe_key=f"uuid-{curated_text}",
        source="oreilly",
        highlighted_at=date(2026, 9, 12),
        export_position=0,
        processed=processed,
    )
    session.add(highlight)
    session.flush()
    unit = CuratedUnit(ingest_run_id=run.id, curated_text=curated_text, decision="keep", tags=[])
    session.add(unit)
    session.flush()
    session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id))
    session.flush()
    return unit


def _add_card(session: Session, unit: CuratedUnit, *, status: str) -> Card:
    card = Card(
        unit_id=unit.id,
        type="qa",
        front="Existing card.",
        back="Existing answer.",
        original_front="Existing card.",
        original_back="Existing answer.",
        tags=[],
        status=status,
        model="claude-sonnet-5",
    )
    session.add(card)
    session.flush()
    return card


@pytest.fixture
def seeded_url(tmp_path: Path) -> Iterator[Callable[[Callable[[Session], None]], str]]:
    """Build a file-backed database per seed function and hand out its URL."""

    def build(seed: Callable[[Session], None]) -> str:
        path = tmp_path / "eval.db"
        engine = create_engine(f"sqlite:///{path}")
        Base.metadata.create_all(engine)
        with Session(engine) as session:
            seed(session)
            session.commit()
        engine.dispose()
        return f"sqlite:///{path}"

    yield build


def _snapshot(url: str, table: Any) -> list[Any]:
    engine = create_engine(url)
    try:
        with engine.connect() as connection:
            return connection.execute(select(table).order_by(table.c.id)).all()
    finally:
        engine.dispose()


def _write_eval_set(tmp_path: Path, entries: list[dict[str, Any]]) -> Path:
    path = tmp_path / "eval_set.jsonl"
    path.write_text(
        "".join(json.dumps(entry, ensure_ascii=False) + "\n" for entry in entries),
        encoding="utf-8",
    )
    return path


def _entry(unit_id: int, curated_text: str, verdict: str = "approve") -> dict[str, Any]:
    return {
        "unit_id": unit_id,
        "curated_text": curated_text,
        "tags": [],
        "source_truncated": False,
        "labels": [
            {
                "card_id": unit_id * 10,
                "verdict": verdict,
                "reason": "too trivial" if verdict == "reject" else None,
            }
        ],
        "review_signal": {"reviews": 0, "again_hard": 0, "good_easy": 0},
    }


# --- the gate --------------------------------------------------------------------


def test_harness_refuses_to_run_against_the_production_database(
    seeded_url: Callable[[Callable[[Session], None]], str], tmp_path: Path
) -> None:
    url = seeded_url(lambda session: None)
    eval_set = _write_eval_set(tmp_path, [_entry(1, "unit text")])
    writer = StubWriter()
    # The harness's target URL *is* the configured production path: refuse before any
    # agent call, loudly, rather than rely on the operator remembering.
    settings = _settings(tmp_path, RECALLY_DATABASE_URL=url)

    with pytest.raises(ProductionDatabaseError):
        run_harness(eval_set, url, settings, _registry(writer, StubCritic()))

    assert writer.requests == []


def test_harness_never_creates_cards(
    seeded_url: Callable[[Callable[[Session], None]], str], tmp_path: Path
) -> None:
    def seed(session: Session) -> None:
        unit = _add_unit(session, curated_text="unit text")
        _add_card(session, unit, status="pending_review")

    url = seeded_url(seed)
    eval_set = _write_eval_set(
        tmp_path, [_entry(1, "unit text"), _entry(2, "other text", "reject")]
    )
    before = _snapshot(url, Card.__table__)

    run_harness(eval_set, url, _settings(tmp_path), _registry(StubWriter(), StubCritic()))

    assert _snapshot(url, Card.__table__) == before


def test_harness_never_flips_highlight_processed(
    seeded_url: Callable[[Callable[[Session], None]], str], tmp_path: Path
) -> None:
    def seed(session: Session) -> None:
        _add_unit(session, curated_text="unit text", processed=False)
        _add_unit(session, curated_text="done text", processed=True)

    url = seeded_url(seed)
    eval_set = _write_eval_set(tmp_path, [_entry(1, "unit text")])
    before = _snapshot(url, Highlight.__table__)

    run_harness(eval_set, url, _settings(tmp_path), _registry(StubWriter(), StubCritic()))

    assert _snapshot(url, Highlight.__table__) == before


def test_harness_respects_llm_max_rounds(
    seeded_url: Callable[[Callable[[Session], None]], str], tmp_path: Path
) -> None:
    url = seeded_url(lambda session: None)
    eval_set = _write_eval_set(tmp_path, [_entry(1, "unit text")])
    writer = StubWriter()
    critic = StubCritic(verdict="revise")
    settings = _settings(tmp_path, LLM_MAX_ROUNDS=3)

    artifact = run_harness(eval_set, url, settings, _registry(writer, critic))

    # A Critic that always says `revise` stops at LLM_MAX_ROUNDS (hard rule 9):
    # initial Writer call plus two revisions, three Critic verdicts, never a hang.
    assert len(writer.requests) == 3
    assert len(critic.requests) == 3
    (unit,) = artifact["units"]
    (card,) = unit["cards"]
    assert card["rounds"] == 3
    assert card["outcome"] == "exhausted"
    assert card["status"] == "needs_human"


def test_harness_emits_one_result_per_unit(
    seeded_url: Callable[[Callable[[Session], None]], str], tmp_path: Path
) -> None:
    entries = [json.loads(line) for line in REAL_EVAL_SET.read_text().splitlines()]
    failing_text = entries[0]["curated_text"]
    writer = StubWriter(fail_on_text=failing_text)

    artifact = run_harness(
        REAL_EVAL_SET,
        seeded_url(lambda session: None),
        _settings(tmp_path),
        _registry(writer, StubCritic()),
    )

    assert len(artifact["units"]) == 45
    (failed,) = [unit for unit in artifact["units"] if unit["error"] is not None]
    assert failed["unit_id"] == entries[0]["unit_id"]
    assert failed["cards"] == []
    assert artifact["summary"]["agreement"]["n"] == 71
