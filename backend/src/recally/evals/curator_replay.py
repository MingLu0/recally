"""Re-curate a database's highlights and report the grouping metrics (#253).

The #243 harness replays Writer ⇄ Critic over a committed eval set, so a Curator
change is invisible to it by design — the Curator never runs there. This is the
complementary measurement: run the Curator over a database's highlights and
compare deterministic structural metrics between the units already in the
database ("before") and the re-curated ones ("after"):

- single-highlight rate of keep units,
- rate of keep units starting lowercase mid-sentence,
- rate of keep units starting with a dangling pronoun,
- truncated highlights sitting alone in a unit,
- average keep-unit source length.

Safety is the `harness.py` pattern: the source is opened read-only, every write
(the replay's `curated_units` and the `llm_calls` trace, hard rule 3) lands on a
throwaway copy made by `harness._database_copy`, and the copy is deleted
afterwards. The Curator is resolved through `agents/registry.py` and driven with
the pipeline's own session-free helpers (`_resolve_chapter`, `_persist_chapter`),
so replay semantics cannot drift from production semantics.

One deliberate divergence from the source: the copy's `truncated` flags are
reset before re-curating, so the "after" metrics reflect only what the replay's
Curator flagged — never stale flags inherited from the production run.

Run it (never from pytest or CI — it costs real LLM calls):

    uv run python -m recally.evals.curator_replay
"""

from __future__ import annotations

import argparse
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session, sessionmaker

from recally.agents.registry import AgentRegistry, default_registry
from recally.config import Settings, get_settings
from recally.db import create_database_engine, create_session_factory
from recally.evals.harness import _database_copy
from recally.llm import LlmCaller
from recally.models import CuratedUnit, CuratedUnitHighlight, Highlight, IngestRun

# The pipeline's session-free Curator helpers, borrowed exactly as `harness.py`
# borrows the Writer ⇄ Critic ones: one implementation of batching, persistence
# and truncated write-back, so the replay measures the production code path.
from recally.pipeline import _persist_chapter, _resolve_chapter

# The ticket's "(it/they/this…)": the first word, lowercased and stripped of
# surrounding punctuation. Reproduces the published 45/368 (12.2%) on the
# 2026-09-24 production database; keep the set in sync with the ticket table.
DANGLING_PRONOUNS = frozenset({"it", "they", "this", "these", "those"})


def _first_word(text: str) -> str:
    stripped = text.strip()
    if not stripped:
        return ""
    return stripped.split()[0].strip("'\"“”‘’()[]*,.:;!?").lower()


def _starts_lowercase(text: str) -> bool:
    """The first alphabetic character is lowercase — a mid-sentence start."""
    for char in text.strip():
        if char.isalpha():
            return char.islower()
    return False


def _rate(count: int, total: int) -> dict[str, Any]:
    return {"count": count, "total": total, "rate": count / total if total else None}


def _collect_metrics(session: Session, unit_ids: set[int]) -> dict[str, Any]:
    """The ticket's "numbers to move" set over one side's units.

    Rates are over `keep` units; `truncated_alone_count` is over the truncated
    highlights the side covers, whatever their unit's decision — matching the
    ticket's "27 of 43 truncated highlights sit alone".
    """
    if not unit_ids:
        return {
            "single_highlight_rate": _rate(0, 0),
            "lowercase_start_rate": _rate(0, 0),
            "dangling_pronoun_rate": _rate(0, 0),
            "truncated_alone_count": {"count": 0, "total": 0},
            "avg_unit_source_length": None,
        }
    units = session.scalars(select(CuratedUnit).where(CuratedUnit.id.in_(unit_ids))).all()
    sizes: dict[int, int] = {
        unit_id: count
        for unit_id, count in session.execute(
            select(CuratedUnitHighlight.unit_id, func.count())
            .where(CuratedUnitHighlight.unit_id.in_(unit_ids))
            .group_by(CuratedUnitHighlight.unit_id)
        )
        .tuples()
        .all()
    }
    keep = [unit for unit in units if unit.decision == "keep"]
    truncated_links = session.execute(
        select(CuratedUnitHighlight.unit_id, CuratedUnitHighlight.highlight_id)
        .join(Highlight, Highlight.id == CuratedUnitHighlight.highlight_id)
        .where(CuratedUnitHighlight.unit_id.in_(unit_ids), Highlight.truncated.is_(True))
    ).all()
    alone = sum(1 for unit_id, _ in truncated_links if sizes.get(unit_id, 0) == 1)
    return {
        "single_highlight_rate": _rate(
            sum(1 for unit in keep if sizes.get(unit.id, 0) == 1), len(keep)
        ),
        "lowercase_start_rate": _rate(
            sum(1 for unit in keep if _starts_lowercase(unit.curated_text)), len(keep)
        ),
        "dangling_pronoun_rate": _rate(
            sum(1 for unit in keep if _first_word(unit.curated_text) in DANGLING_PRONOUNS),
            len(keep),
        ),
        "truncated_alone_count": {"count": alone, "total": len(truncated_links)},
        "avg_unit_source_length": (
            sum(len(unit.curated_text) for unit in keep) / len(keep) if keep else None
        ),
    }


def _chapters(
    session: Session,
) -> list[tuple[tuple[str, str | None], list[Highlight]]]:
    """Every highlight, grouped by (book, chapter) in export order — the
    pipeline's Curator input shape, but over all rows, not only unprocessed."""
    highlights = session.scalars(select(Highlight).order_by(Highlight.export_position)).all()
    groups: dict[tuple[str, str | None], list[Highlight]] = {}
    for highlight in highlights:
        groups.setdefault((highlight.book.title, highlight.chapter), []).append(highlight)
    return list(groups.items())


def _recurate(
    session: Session,
    session_factory: sessionmaker[Session],
    settings: Settings,
    registry: AgentRegistry,
    groups: list[tuple[tuple[str, str | None], list[Highlight]]],
) -> int:
    """Run the Curator over every chapter and persist its units; the new run's id."""
    replay_run = IngestRun(filename="curator-replay")
    session.add(replay_run)
    session.flush()
    # The "after" flags must come from this run's Curator alone (module docstring).
    for highlight in session.scalars(select(Highlight)).all():
        highlight.truncated = False
    session.commit()  # no open transaction while an agent runs (pipeline.py)

    llm_caller = LlmCaller(session_factory, log_payloads=settings.llm_log_payloads)

    def record(highlights: list[Highlight], result: Any) -> None:
        _persist_chapter(session, replay_run, highlights, result)
        session.commit()

    if settings.llm_concurrency == 1 or len(groups) < 2:
        for (book_title, chapter), highlights in groups:
            record(
                highlights,
                _resolve_chapter(
                    registry, settings, llm_caller, replay_run.id, book_title, chapter, highlights
                ),
            )
        return replay_run.id

    # Chapters are independent Curator calls (docs/agents.md §2), so they overlap
    # exactly as the pipeline overlaps them; persistence stays on the main thread.
    first_error: Exception | None = None
    with ThreadPoolExecutor(max_workers=min(settings.llm_concurrency, len(groups))) as pool:
        futures = {
            pool.submit(
                _resolve_chapter,
                registry,
                settings,
                llm_caller,
                replay_run.id,
                book_title,
                chapter,
                highlights,
            ): highlights
            for (book_title, chapter), highlights in groups
        }
        for future in as_completed(futures):
            try:
                result = future.result()
            except Exception as exc:
                first_error = first_error or exc
                continue
            record(futures[future], result)
    if first_error is not None:
        raise first_error
    return replay_run.id


def run_replay(
    source_url: str,
    settings: Settings,
    registry: AgentRegistry | None = None,
) -> dict[str, Any]:
    """Re-curate every highlight in `source_url`; return the before/after report.

    The source is only ever opened read-only (through `harness._database_copy`);
    the Curator's units and the `llm_calls` trace land on the throwaway copy.
    """
    registry = registry if registry is not None else default_registry
    with _database_copy(source_url) as run_url:
        engine = create_database_engine(run_url)
        try:
            session_factory = create_session_factory(engine)
            with session_factory() as session:
                before = _collect_metrics(
                    session, set(session.scalars(select(CuratedUnit.id)).all())
                )
                groups = _chapters(session)
                replay_run_id = _recurate(session, session_factory, settings, registry, groups)
                after_ids = set(
                    session.scalars(
                        select(CuratedUnit.id).where(CuratedUnit.ingest_run_id == replay_run_id)
                    ).all()
                )
                after = _collect_metrics(session, after_ids)
                units = session.scalars(
                    select(CuratedUnit)
                    .where(CuratedUnit.ingest_run_id == replay_run_id)
                    .order_by(CuratedUnit.id)
                ).all()
                highlight_count = session.scalar(select(func.count(Highlight.id))) or 0
                # Full curated text, so two reports can be read side by side — the
                # same reason the #243 artifact carries card text: no metric
                # catches vagueness. Built inside the session: `highlight_links`
                # lazy-loads.
                unit_details = [
                    {
                        "unit_id": unit.id,
                        "decision": unit.decision,
                        "reason": unit.reason,
                        "highlight_count": len(unit.highlight_links),
                        "curated_text": unit.curated_text,
                    }
                    for unit in units
                ]
        finally:
            engine.dispose()
    return {
        "model": settings.llm_model_curator,
        "highlights": highlight_count,
        "chapters": len(groups),
        "before": before,
        "after": after,
        "units": unit_details,
    }


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--database-url",
        default=None,
        help="source database to copy; defaults to RECALLY_DATABASE_URL. "
        "The source is only ever opened read-only; the run happens against a copy.",
    )
    parser.add_argument("--output", type=Path, default=Path("curator-replay.json"))
    args = parser.parse_args(argv)

    settings = get_settings()
    source_url = args.database_url or settings.database_url
    report = run_replay(source_url, settings)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    summary = {
        name: {"before": report["before"][name], "after": report["after"][name]}
        for name in report["before"]
    }
    print(json.dumps(summary, indent=2))
    print(f"model {report['model']}; wrote {report['chapters']} chapters to {args.output}")


if __name__ == "__main__":
    main()
