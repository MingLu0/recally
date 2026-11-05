"""The #244 gate: validation measures judge-vs-human agreement, honestly.

`recally.evals.validate` runs the judge over the committed 71-label set and
reports agreement the way the ticket demands: rejection agreement broken out
from the (easy, meaningless) approval agreement, a Wilson confidence interval on
every rate — n=71 with only 23 rejections is a small sample and a bare point
estimate would launder it into false precision — a per-axis breakdown, a
confusion matrix, and the label set size n next to every number. Tests never
touch a real LLM: the judge is injected.
"""

import json
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from recally.config import Settings
from recally.evals.harness import ProductionDatabaseError
from recally.evals.judge import JudgeRequest, JudgeVerdict
from recally.evals.validate import axis_for_reason, run_validation
from recally.models import Base, Card, CuratedUnit, IngestRun

REAL_EVAL_SET = Path(__file__).parents[1] / "fixtures" / "eval" / "writer_eval.jsonl"

# Every distinct rejection reason in the committed label set and the axis the
# deterministic keyword mapping assigns it (quoted in validate.py's docstring).
# The two unmapped ones stay unmapped on purpose: "the critic is correct" maps
# to nothing, and honesty beats coverage.
EXPECTED_REASON_AXES = {
    "very isolated and does not make much sense question": "context_sufficiency",
    "this seems that only questions being captured without the answers and we r only "
    "making questions around a question": "unmapped",
    "too abstract": "triviality",
    "missing the main point": "main_pointedness",
    "context lost": "context_sufficiency",
    "the critic is correct": "unmapped",
    "meaningless info": "triviality",
    "too isolated info. does not make sense": "context_sufficiency",
    "too trivial meaningless": "triviality",
    "meaningless": "triviality",
    "too trivial": "triviality",
    "this question missing the main point this book is concerning": "main_pointedness",
    "too trivial. little context": "triviality",
    "there's too little context and the question it asks is not the main point of the "
    "book is about": "context_sufficiency",
    "i think the answer summarize too much about the highlights , losing important "
    "info": "main_pointedness",
    "truncated source to a point that it no longer meaningful": "context_sufficiency",
    "truncated source , too trivial to be meaningful": "context_sufficiency",
    "not too much meaningful when asking as a cloze question": "triviality",
    "why is more important than what": "main_pointedness",
}


class StubJudge:
    """Rejects cards whose front is marked bad, like the human did; records requests."""

    def __init__(self) -> None:
        self.requests: list[JudgeRequest] = []

    def __call__(
        self, request: JudgeRequest, *, unit_id: int | None = None, card_id: int | None = None
    ) -> JudgeVerdict:
        self.requests.append(request)
        if "BAD" in request.front:
            return JudgeVerdict(verdict="reject", axis="triviality", rationale="stub reject")
        return JudgeVerdict(verdict="approve", axis="none", rationale="stub approve")


def _settings(tmp_path: Path, **overrides: Any) -> Settings:
    kwargs: dict[str, Any] = {
        "RECALLY_DATABASE_URL": f"sqlite:///{tmp_path}/production.db",
        "RECALLY_API_KEY": "test-key-not-a-real-secret",
        "LLM_CONCURRENCY": 1,
    }
    kwargs.update(overrides)
    return Settings(**kwargs)


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


def _add_card(
    session: Session,
    *,
    card_id: int,
    unit_id: int,
    front: str,
    run: IngestRun,
    units: dict[int, CuratedUnit],
) -> None:
    if unit_id not in units:
        unit = CuratedUnit(
            id=unit_id, ingest_run_id=run.id, curated_text="unit text", decision="keep", tags=[]
        )
        session.add(unit)
        session.flush()
        units[unit_id] = unit
    session.add(
        Card(
            id=card_id,
            unit_id=unit_id,
            type="qa",
            front=front,
            back="back",
            original_front=front,
            original_back="back",
            tags=[],
            status="approved",
            model="test-model",
        )
    )
    session.flush()


def _seed_cards(card_fronts: dict[tuple[int, int], str]) -> Callable[[Session], None]:
    """Seed (unit_id, card_id) -> front rows for the fixture labels to join to."""

    def seed(session: Session) -> None:
        run = IngestRun(filename="a-oreilly-annotations.csv")
        session.add(run)
        session.flush()
        units: dict[int, CuratedUnit] = {}
        for (unit_id, card_id), front in sorted(card_fronts.items()):
            _add_card(session, card_id=card_id, unit_id=unit_id, front=front, run=run, units=units)

    return seed


def _write_eval_set(tmp_path: Path, entries: list[dict[str, Any]]) -> Path:
    path = tmp_path / "eval_set.jsonl"
    path.write_text(
        "".join(json.dumps(entry, ensure_ascii=False) + "\n" for entry in entries),
        encoding="utf-8",
    )
    return path


def _entry(unit_id: int, labels: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "unit_id": unit_id,
        "curated_text": f"unit {unit_id} text",
        "tags": [],
        "source_truncated": False,
        "labels": labels,
        "review_signal": {"reviews": 0, "again_hard": 0, "good_easy": 0},
    }


def _label(card_id: int, verdict: str, reason: str | None = None) -> dict[str, Any]:
    return {"card_id": card_id, "verdict": verdict, "reason": reason}


@pytest.fixture
def small_run(tmp_path: Path, seeded_url: Callable[[Callable[[Session], None]], str]) -> dict:
    """Three labels: one approval, one rejection the judge catches, one it misses."""

    url = seeded_url(
        _seed_cards({(1, 11): "good card", (1, 12): "BAD trivial card", (2, 13): "good card"})
    )
    eval_set = _write_eval_set(
        tmp_path,
        [
            _entry(1, [_label(11, "approve"), _label(12, "reject", "too trivial")]),
            _entry(2, [_label(13, "reject", "context Lost")]),
        ],
    )
    artifact = run_validation(eval_set, url, _settings(tmp_path), judge=StubJudge())
    return artifact


def test_validation_reports_rejection_agreement_separately(small_run: dict) -> None:
    # Agreement on approvals alone is meaningless — a judge that approves
    # everything scores 68% on this label set — so rejections are broken out.
    summary = small_run["summary"]

    assert summary["rejection_agreement"]["count"] == 1  # card 12 caught; 13 missed
    assert summary["rejection_agreement"]["n"] == 2
    assert summary["approval_agreement"]["count"] == 1
    assert summary["approval_agreement"]["n"] == 1
    assert summary["agreement"]["count"] == 2
    assert summary["agreement"]["n"] == 3


def test_validation_reports_a_confidence_interval(small_run: dict) -> None:
    # A bare point estimate is not accepted output: every rate carries a Wilson
    # score interval (n is small; the width is the honest part).
    summary = small_run["summary"]
    for key in ("agreement", "rejection_agreement", "approval_agreement"):
        metric = summary[key]
        lo, hi = metric["ci95"]
        assert lo <= metric["rate"] <= hi
        assert lo < hi

    # Pinned Wilson values for 1/2 at z=1.96 (computed independently):
    # [0.0945, 0.9055] — wide, because n=2 proves almost nothing.
    lo, hi = summary["rejection_agreement"]["ci95"]
    assert lo == pytest.approx(0.0945, abs=1e-3)
    assert hi == pytest.approx(0.9055, abs=1e-3)

    # An empty bucket reports n=0 with no rate and no interval, never a fake 0%.
    per_axis = summary["per_axis"]
    assert per_axis["main_pointedness"] == {"count": 0, "n": 0, "rate": None, "ci95": None}

    # Per-axis agreement is grouped by the human's mapped axis.
    assert per_axis["triviality"]["n"] == 1
    assert per_axis["triviality"]["count"] == 1
    assert per_axis["context_sufficiency"]["n"] == 1
    assert per_axis["context_sufficiency"]["count"] == 0

    # And the confusion matrix is a 2x2, not a single number.
    assert summary["confusion"] == {
        "human_approve": {"judge_approve": 1, "judge_reject": 0},
        "human_reject": {"judge_approve": 1, "judge_reject": 1},
    }


def test_validation_records_the_label_set_size(
    tmp_path: Path, seeded_url: Callable[[Callable[[Session], None]], str]
) -> None:
    # Every validation result carries n, so a later reader knows how much to
    # trust it. Run over the real committed set: 45 units / 71 labels.
    entries = [json.loads(line) for line in REAL_EVAL_SET.read_text().splitlines()]
    card_fronts = {
        (entry["unit_id"], label["card_id"]): f"card {label['card_id']}"
        for entry in entries
        for label in entry["labels"]
    }
    url = seeded_url(_seed_cards(card_fronts))

    artifact = run_validation(REAL_EVAL_SET, url, _settings(tmp_path), judge=StubJudge())

    summary = artifact["summary"]
    assert summary["n"] == 71
    assert summary["rejection_agreement"]["n"] == 23
    assert summary["approval_agreement"]["n"] == 48
    # The stub approves everything: the disagreement pattern is deterministic.
    assert summary["rejection_agreement"]["count"] == 0
    assert summary["approval_agreement"]["count"] == 48
    # The human-axis mapping buckets all 23 rejection reasons.
    assert per_axis_ns(summary) == {
        "triviality": 11,
        "context_sufficiency": 6,
        "main_pointedness": 4,
        "unmapped": 2,
    }


def per_axis_ns(summary: dict) -> dict[str, int]:
    return {axis: metric["n"] for axis, metric in summary["per_axis"].items()}


def test_every_real_rejection_reason_maps_as_documented() -> None:
    entries = [json.loads(line) for line in REAL_EVAL_SET.read_text().splitlines()]
    reasons = [
        label["reason"]
        for entry in entries
        for label in entry["labels"]
        if label["verdict"] == "reject"
    ]
    assert len(reasons) == 23
    for reason in reasons:
        assert axis_for_reason(reason) == EXPECTED_REASON_AXES[reason.strip().lower()], reason


def test_validation_refuses_to_run_against_the_production_database(
    tmp_path: Path, seeded_url: Callable[[Callable[[Session], None]], str]
) -> None:
    url = seeded_url(lambda session: None)
    eval_set = _write_eval_set(tmp_path, [_entry(1, [_label(11, "approve")])])
    judge = StubJudge()
    # The validation target *is* the configured production path: refuse before
    # any judging, like run_harness does — llm_calls rows are always written.
    settings = _settings(tmp_path, RECALLY_DATABASE_URL=url)

    with pytest.raises(ProductionDatabaseError):
        run_validation(eval_set, url, settings, judge=judge)

    assert judge.requests == []


def test_validation_raises_on_a_labelled_card_missing_from_the_database(
    tmp_path: Path, seeded_url: Callable[[Callable[[Session], None]], str]
) -> None:
    # The fixture carries card_id but no card text; a labelled card absent from
    # the database fails loudly rather than silently scoring a smaller set.
    url = seeded_url(lambda session: None)
    eval_set = _write_eval_set(tmp_path, [_entry(1, [_label(999, "approve")])])

    with pytest.raises(ValueError, match="999"):
        run_validation(eval_set, url, _settings(tmp_path), judge=StubJudge())
