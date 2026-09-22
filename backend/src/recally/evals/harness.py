"""Replay the Writer ⇄ Critic loop over the committed eval set and score it (#243).

This is what makes a prompt change measurable instead of guessed at: the harness
replays the loop over `tests/fixtures/eval/writer_eval.jsonl` (#242) and scores the
output against the human's own past decisions — **would Ming have kept this card** —
not against the Critic's opinion (live-run metrics already measure that).

Safety is structural, not remembered:

- Agents are resolved through `agents/registry.py` and driven directly, never through
  the pipeline's public entry points, so no eval run can create a card or flip a
  `processed` flag. Agents hold no DB session by construction (ADR-007), so the loop
  has nothing to write with.
- `llm_calls` rows are still written (hard rule 3), so the CLI runs against a
  **throwaway copy** of the database (`_database_copy`) and deletes it afterwards.
- `run_harness` refuses to start when its database URL resolves to the configured
  production path (`ProductionDatabaseError`) — fail loudly rather than rely on the
  operator remembering.

The artifact carries the produced card text per unit so two runs can be read side by
side: the failure mode this exists to catch is cards getting *vaguer*, and no score
detects that. 45 units is deliberately small enough to eyeball.

Run it (never from pytest or CI — it costs real LLM calls):

    uv run python -m recally.evals.harness
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import tempfile
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

from sqlalchemy import make_url

from recally.agents.base import WriterRequest
from recally.agents.registry import AgentRegistry, default_registry
from recally.config import Settings, get_settings
from recally.db import create_database_engine, create_session_factory
from recally.evals.score import score_run
from recally.llm import LlmCaller
from recally.models import CuratedUnit

# The loop itself lives in the pipeline (it owns round mechanics, hard rule 9); the
# harness borrows its session-free resolution helpers and never goes near its public
# entry points, so replay semantics can never drift from production semantics.
from recally.pipeline import _agent_context, _CardOutcome, _latest_guidance, _resolve_card

DEFAULT_EVAL_SET = Path(__file__).parents[3] / "tests" / "fixtures" / "eval" / "writer_eval.jsonl"

_KEPT_STATUSES = ("pending_review", "approved")


class ProductionDatabaseError(RuntimeError):
    """The harness was pointed at the configured production database. Refused."""


@dataclass(frozen=True)
class EvalUnit:
    """One eval-set entry: a curated unit plus the human's verdicts on its cards."""

    unit_id: int
    curated_text: str
    tags: list[str]
    source_truncated: bool
    labels: list[dict[str, Any]]


def _sqlite_path(database_url: str) -> Path | None:
    """The resolved file behind a SQLite URL; None for other backends or `:memory:`."""
    url = make_url(database_url)
    if url.get_backend_name() != "sqlite":
        return None
    database = url.database
    if not database or database == ":memory:":
        return None
    return Path(database.removeprefix("file:")).expanduser().resolve()


def _refuse_if_production(database_url: str, settings: Settings) -> None:
    """Raise when the harness target is the configured production database.

    Compared on resolved paths so `data/recally.db` and an absolute spelling of the
    same file cannot slip past each other; non-SQLite URLs fall back to exact match.
    """
    target = _sqlite_path(database_url)
    production = _sqlite_path(settings.database_url)
    same = (
        target == production
        if target is not None and production is not None
        else database_url == settings.database_url
    )
    if same:
        raise ProductionDatabaseError(
            f"eval harness target {database_url!r} is the configured production database "
            "(RECALLY_DATABASE_URL); run against a throwaway copy — "
            "`python -m recally.evals.harness` makes one for you"
        )


@contextmanager
def _database_copy(source_url: str) -> Iterator[str]:
    """A throwaway copy of a file-backed SQLite database, deleted on exit.

    Uses SQLite's backup API over a read-only source connection, so the copy is a
    consistent snapshot even of a WAL-mode database the app is holding open.
    """
    source_path = _sqlite_path(source_url)
    if source_path is None:
        raise ValueError("the eval harness can only copy a file-backed SQLite database")
    if not source_path.exists():
        raise FileNotFoundError(f"no database at {source_path}")
    fd, copy_path = tempfile.mkstemp(prefix="recally-eval-", suffix=".db")
    os.close(fd)
    try:
        source = sqlite3.connect(f"file:{source_path}?mode=ro", uri=True)
        try:
            target = sqlite3.connect(copy_path)
            try:
                source.backup(target)
            finally:
                target.close()
        finally:
            source.close()
        yield f"sqlite:///{copy_path}"
    finally:
        os.unlink(copy_path)


def _load_eval_set(path: Path) -> list[EvalUnit]:
    units = []
    with path.open(encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            raw = json.loads(line)
            units.append(
                EvalUnit(
                    unit_id=raw["unit_id"],
                    curated_text=raw["curated_text"],
                    tags=raw["tags"],
                    source_truncated=raw["source_truncated"],
                    labels=raw["labels"],
                )
            )
    return sorted(units, key=lambda unit: unit.unit_id)


def _card_record(outcome: _CardOutcome) -> dict[str, Any]:
    terminal_names: dict[str, Literal["accepted", "rejected", "exhausted"]] = {
        "accept": "accepted",
        "reject": "rejected",
        "exhausted": "exhausted",
    }
    return {
        "type": outcome.draft.type,
        "front": outcome.draft.front,
        "back": outcome.draft.back,
        "status": outcome.status,
        "status_reason": outcome.status_reason,
        "rounds": outcome.generation_rounds,
        "outcome": terminal_names[outcome.terminal],
    }


def _resolve_unit(
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    unit: EvalUnit,
    guidance_version: int | None,
    guidance: str | None,
) -> dict[str, Any]:
    """One unit's replay: the round-1 Writer call, then the bounded loop per draft."""
    # `_resolve_card` reads only id/curated_text/tags off the unit; a transient
    # stand-in carries them without a `curated_units` row ever existing.
    proxy = CuratedUnit(id=unit.unit_id, curated_text=unit.curated_text, tags=unit.tags)
    writer, writer_context = _agent_context(
        "writer", registry, settings, llm_caller, None, unit_id=unit.unit_id, round=1
    )
    result = writer(
        WriterRequest(
            curated_text=unit.curated_text,
            tags=unit.tags,
            guidance_version=guidance_version,
            guidance=guidance,
            critique=None,
        ),
        writer_context,
    )
    cards = [
        _card_record(
            _resolve_card(
                registry,
                settings,
                llm_caller,
                None,
                proxy,
                draft,
                source_truncated=unit.source_truncated,
                guidance_version=guidance_version,
                guidance=guidance,
            )
        )
        for draft in result.cards
    ]
    return {
        "unit_id": unit.unit_id,
        "source_truncated": unit.source_truncated,
        "labels": unit.labels,
        "cards": cards,
        "error": None,
        "kept": any(card["status"] in _KEPT_STATUSES for card in cards),
    }


def _resolve_unit_safely(*args: Any) -> dict[str, Any]:
    """A Writer failure (e.g. returning nothing, #219) is a per-unit outcome, not a crash."""
    try:
        return _resolve_unit(*args)
    except Exception as exc:
        unit = args[3]
        return {
            "unit_id": unit.unit_id,
            "source_truncated": unit.source_truncated,
            "labels": unit.labels,
            "cards": [],
            "error": f"{type(exc).__name__}: {exc}",
            "kept": False,
        }


def run_harness(
    eval_set_path: Path,
    database_url: str,
    settings: Settings,
    registry: AgentRegistry | None = None,
) -> dict[str, Any]:
    """Replay the loop over the eval set against `database_url`; return the artifact.

    Refuses first, before any engine or agent work, when `database_url` is the
    configured production database. The only writes the run can ever make are
    `llm_calls` rows (hard rule 3), which is why the CLI feeds this a copy.
    """
    _refuse_if_production(database_url, settings)
    registry = registry if registry is not None else default_registry
    units = _load_eval_set(eval_set_path)

    engine = create_database_engine(database_url)
    try:
        session_factory = create_session_factory(engine)
        llm_caller = LlmCaller(session_factory, log_payloads=settings.llm_log_payloads)
        with session_factory() as session:
            guidance_version, guidance = _latest_guidance(session)
        with ThreadPoolExecutor(max_workers=settings.llm_concurrency) as pool:
            results = list(
                pool.map(
                    lambda unit: _resolve_unit_safely(
                        registry, settings, llm_caller, unit, guidance_version, guidance
                    ),
                    units,
                )
            )
    finally:
        engine.dispose()

    results.sort(key=lambda result: result["unit_id"])
    return {
        "eval_set": eval_set_path.name,
        "unit_count": len(results),
        "models": {"writer": settings.llm_model_writer, "critic": settings.llm_model_critic},
        "guidance_version": guidance_version,
        "units": results,
        "summary": score_run(results),
    }


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--eval-set", type=Path, default=DEFAULT_EVAL_SET)
    parser.add_argument(
        "--database-url",
        default=None,
        help="source database to copy; defaults to RECALLY_DATABASE_URL. "
        "The run always happens against the throwaway copy, never the source.",
    )
    parser.add_argument("--output", type=Path, default=Path("eval-artifact.json"))
    args = parser.parse_args(argv)

    settings = get_settings()
    source_url = args.database_url or settings.database_url
    with _database_copy(source_url) as run_url:
        artifact = run_harness(args.eval_set, run_url, settings)
    args.output.write_text(
        json.dumps(artifact, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(json.dumps(artifact["summary"], indent=2))
    print(f"wrote {artifact['unit_count']} unit results to {args.output}")


if __name__ == "__main__":
    main()
