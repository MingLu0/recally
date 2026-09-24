"""The #253 gate: the Curator replay never writes to its source database.

`recally.evals.curator_replay` re-curates a database's highlights so a Curator
prompt change becomes measurable: the source is opened read-only and every write
(the replay's `curated_units`, the `llm_calls` trace) lands on a throwaway copy
— the same safety pattern as `evals/harness.py` (#243). The report carries the
structural metrics the ticket names, computed the same way on the pre-existing
units ("before") and the re-curated ones ("after").
"""

import hashlib
from collections.abc import Callable, Iterator
from datetime import date
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from recally.agents.base import (
    AgentContext,
    CuratedUnitDraft,
    CuratorRequest,
    CuratorResult,
)
from recally.agents.registry import AgentRegistry
from recally.config import Settings
from recally.evals.curator_replay import run_replay
from recally.models import Base, Book, Card, CuratedUnit, CuratedUnitHighlight, Highlight, IngestRun

METRIC_KEYS = {
    "single_highlight_rate",
    "lowercase_start_rate",
    "dangling_pronoun_rate",
    "truncated_alone_count",
    "avg_unit_source_length",
}

# The stub flags a row as truncated when its text ends on this clipping marker.
CLIP = "MID-WO"

HIGHLIGHT_TEXTS = [
    # A subject-less fragment and the sibling that supplies its subject.
    "it closes the loop by feeding outcomes back into sensing.",
    "A self-improving agent is a closed-loop control system.",
    # A truncated row and a plain one.
    f"The spacing effect was documented by Ebbinghaus in 1885 and has survived {CLIP}",
    "Retrievability falls exponentially with time.",
]


class StubCurator:
    """A deterministic Curator: folds each chapter's first two highlights into
    one group, keeps the rest singly, and flags any text ending on the clipping
    marker as truncated. No LLM anywhere near the test suite (backend/AGENTS.md)."""

    def __init__(self) -> None:
        self.requests: list[CuratorRequest] = []

    def __call__(self, request: CuratorRequest, ctx: AgentContext) -> CuratorResult:
        self.requests.append(request)
        highlights = request.highlights
        units: list[CuratedUnitDraft] = [
            CuratedUnitDraft(
                highlight_ids=[highlight.id for highlight in highlights[:2]],
                curated_text="\n".join(highlight.text for highlight in highlights[:2]),
                tags=["agents"],
                truncated_highlight_ids=[],
                decision="keep",
                reason="",
            )
        ]
        for highlight in highlights[2:]:
            truncated = highlight.text.endswith(CLIP)
            units.append(
                CuratedUnitDraft(
                    highlight_ids=[highlight.id],
                    curated_text=highlight.text,
                    tags=[],
                    truncated_highlight_ids=[highlight.id] if truncated else [],
                    decision="keep",
                    reason="",
                )
            )
        return CuratorResult(units=units)


def _settings(**overrides: Any) -> Settings:
    kwargs: dict[str, Any] = {
        "RECALLY_API_KEY": "test-key-not-a-real-secret",
        "AGENT_CURATOR": "stub",
        "LLM_CONCURRENCY": 1,
    }
    kwargs.update(overrides)
    return Settings(**kwargs)


def _registry(curator: StubCurator) -> AgentRegistry:
    registry = AgentRegistry()
    registry.register("curator", "stub", curator)
    return registry


def _seed_source(session: Session) -> None:
    """One book, one chapter, four highlights — plus the production-shaped
    rows the "before" metrics are computed over: single-highlight keep units,
    one of them covering a highlight already flagged truncated."""
    book = Book(
        title="Evals for AI Engineers",
        source="oreilly",
        external_id="9781098188283",
    )
    run = IngestRun(filename="a-oreilly-annotations.csv")
    session.add_all([book, run])
    session.flush()
    for position, text in enumerate(HIGHLIGHT_TEXTS):
        session.add(
            Highlight(
                book_id=book.id,
                chapter="Chapter 3: Error Analysis",
                raw_text=text,
                dedupe_key=f"uuid-{position}",
                source="oreilly",
                highlighted_at=date(2026, 9, 12),
                export_position=position,
                truncated=text.endswith(CLIP),
                processed=True,
            )
        )
    session.flush()
    for position, text in enumerate(HIGHLIGHT_TEXTS):
        unit = CuratedUnit(
            ingest_run_id=run.id,
            curated_text=text,
            decision="keep",
            tags=[],
        )
        session.add(unit)
        session.flush()
        session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=position + 1))
    session.flush()
    # A card on one unit, so the copy carries the full production shape.
    session.add(
        Card(
            unit_id=1,
            type="qa",
            front="Existing card.",
            back="Existing answer.",
            original_front="Existing card.",
            original_back="Existing answer.",
            tags=[],
            status="pending_review",
            model="claude-sonnet-5",
        )
    )


@pytest.fixture
def source_url(tmp_path: Path) -> Iterator[Callable[[], str]]:
    """Build the seeded source database once per test and hand out its URL."""

    def build() -> str:
        path = tmp_path / "source.db"
        engine = create_engine(f"sqlite:///{path}")
        Base.metadata.create_all(engine)
        with Session(engine) as session:
            _seed_source(session)
            session.commit()
        engine.dispose()
        return f"sqlite:///{path}"

    yield build


def _sha256(url: str) -> str:
    path = Path(url.removeprefix("sqlite:///"))
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_curator_replay_never_writes_to_source_db(source_url: Callable[[], str]) -> None:
    """The replay opens its source read-only: the file is byte-identical after
    a full re-curation, units and `llm_calls` having landed on the copy only."""
    url = source_url()
    before_hash = _sha256(url)

    run_replay(url, _settings(), _registry(StubCurator()))

    assert _sha256(url) == before_hash


def test_curator_replay_reports_the_structural_metrics(
    source_url: Callable[[], str],
) -> None:
    """The report carries exactly the ticket's "numbers to move" set, before
    and after, with values that match hand computation over the stub's units."""
    report = run_replay(source_url(), _settings(), _registry(StubCurator()))

    assert set(report["before"].keys()) == METRIC_KEYS
    assert set(report["after"].keys()) == METRIC_KEYS

    # Before: the seeded production shape — four single-highlight keep units.
    before = report["before"]
    assert before["single_highlight_rate"] == {"count": 4, "total": 4, "rate": 1.0}
    # "it closes the loop…" starts lowercase and with a dangling pronoun.
    assert before["lowercase_start_rate"] == {"count": 1, "total": 4, "rate": 0.25}
    assert before["dangling_pronoun_rate"] == {"count": 1, "total": 4, "rate": 0.25}
    # The one truncated highlight sits alone in its unit.
    assert before["truncated_alone_count"] == {"count": 1, "total": 1}
    assert before["avg_unit_source_length"] == pytest.approx(
        sum(len(text) for text in HIGHLIGHT_TEXTS) / 4
    )

    # After: the stub groups highlights 1+2, so three keep units, one grouped.
    after = report["after"]
    assert after["single_highlight_rate"] == {"count": 2, "total": 3, "rate": pytest.approx(2 / 3)}
    # The grouped unit's curated_text starts with the lowercase fragment, and
    # its first word is still the dangling pronoun — grouping changed the unit
    # shape, not the text order the stub concatenated.
    assert after["lowercase_start_rate"] == {"count": 1, "total": 3, "rate": pytest.approx(1 / 3)}
    assert after["dangling_pronoun_rate"] == {"count": 1, "total": 3, "rate": pytest.approx(1 / 3)}
    # The stub flagged highlight 3 truncated; it sits alone in its unit.
    assert after["truncated_alone_count"] == {"count": 1, "total": 1}
    grouped_length = len(HIGHLIGHT_TEXTS[0]) + 1 + len(HIGHLIGHT_TEXTS[1])
    assert after["avg_unit_source_length"] == pytest.approx(
        (grouped_length + len(HIGHLIGHT_TEXTS[2]) + len(HIGHLIGHT_TEXTS[3])) / 3
    )
