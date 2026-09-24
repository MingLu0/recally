"""Seed a throwaway QA database with real production highlights, for the phone
approval-queue pass over the changed Curator (#253).

Not a test and not part of `src/recally/` — a developer tool, same precedent as
`scripts/seed_demo.py`. Unlike the demo seed, what lands in the queue is real
LLM output: this script only copies `books` + `highlights` (reset to
`processed=false`) out of the production database — opened read-only — into a
fresh, migrated throwaway. The pipeline trigger and the phone then do the rest.

Default scope is the five chapters behind the 45 rejected cards (211 highlights
across 3 books, measured 2026-09-24), so the phone queue holds exactly the
material that produced rejects before. `--book` / `--chapter` narrow it.

One deviation from the ticket's runbook, called out in the PR: `POST /jobs/run`
cannot trigger the pipeline — `JobName` is `notify | learner | optimizer`
(`scheduling/jobs.py`), and adding a job name is an API-spec change this ticket
did not scope. The pipeline is triggered through the composition root instead:

    RECALLY_DATABASE_URL=sqlite:///<target> uv run python scripts/qa_curator_server.py --run

Usage (from `backend/`):

    uv run python scripts/qa_curator_server.py            # seed + print runbook
    uv run python scripts/qa_curator_server.py --run      # run the pipeline on the seed
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import subprocess
import sys
from datetime import date, datetime
from pathlib import Path

# Import from the source tree without installing the script as a package.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from recally.config import Settings  # noqa: E402
from recally.db import create_database_engine, create_session_factory  # noqa: E402
from recally.models.ingest import Book, Highlight, IngestRun  # noqa: E402

BACKEND_DIR = Path(__file__).resolve().parent.parent
DEFAULT_TARGET = BACKEND_DIR.parent / "data" / "qa-curator.db"


def _sqlite_path(database_url: str) -> Path:
    prefix = "sqlite:///"
    if not database_url.startswith(prefix):
        raise ValueError(f"only file-backed SQLite URLs are supported, got {database_url!r}")
    return Path(database_url.removeprefix(prefix)).expanduser().resolve()


def _connect_read_only(path: Path) -> sqlite3.Connection:
    if not path.exists():
        raise FileNotFoundError(f"no database at {path}")
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def _scope_chapters(
    source: sqlite3.Connection, books: list[str], chapters: list[str]
) -> list[tuple[int, str | None]]:
    """The (book_id, chapter) pairs to seed.

    Default: the chapters behind the rejected cards — the material the phone
    queue must re-produce. Filters match case-insensitively on book title (or
    exact id) and chapter text.
    """
    if not books and not chapters:
        return [
            (book_id, chapter)
            for book_id, chapter in source.execute(
                """
                SELECT DISTINCT h.book_id, h.chapter
                FROM highlights h
                JOIN curated_unit_highlights cuh ON cuh.highlight_id = h.id
                JOIN curated_units u ON u.id = cuh.unit_id
                JOIN cards c ON c.unit_id = u.id
                WHERE c.status = 'rejected'
                ORDER BY h.book_id, h.chapter
                """
            )
        ]
    rows = source.execute(
        """
        SELECT DISTINCT h.book_id, h.chapter
        FROM highlights h JOIN books b ON b.id = h.book_id
        """
    ).fetchall()
    scoped = []
    for book_id, chapter in rows:
        title = source.execute("SELECT title FROM books WHERE id = ?", (book_id,)).fetchone()[0]
        if books and not any(
            needle.lower() in title.lower() or needle == str(book_id) for needle in books
        ):
            continue
        if chapters and not any(
            chapter is not None and needle.lower() in chapter.lower() for needle in chapters
        ):
            continue
        scoped.append((book_id, chapter))
    return scoped


def _migrate(target: Path) -> None:
    env = os.environ | {"RECALLY_DATABASE_URL": f"sqlite:///{target}"}
    subprocess.run(
        ["uv", "run", "alembic", "upgrade", "head"],
        cwd=BACKEND_DIR,
        env=env,
        check=True,
    )


def _seed(
    source: sqlite3.Connection, target: Path, scope: list[tuple[int, str | None]]
) -> dict[str, int]:
    book_ids = sorted({book_id for book_id, _ in scope})
    settings = Settings(
        RECALLY_API_KEY="qa-key-not-a-real-secret",
        RECALLY_DATABASE_URL=f"sqlite:///{target}",
    )
    engine = create_database_engine(settings.database_url)
    session_factory = create_session_factory(engine)
    highlight_count = 0
    with session_factory() as session:
        for book_id in book_ids:
            title, author, book_source, external_id, url = source.execute(
                "SELECT title, author, source, external_id, url FROM books WHERE id = ?",
                (book_id,),
            ).fetchone()
            session.add(
                Book(
                    id=book_id,
                    title=title,
                    author=author,
                    source=book_source,
                    external_id=external_id,
                    url=url,
                )
            )
        for book_id, chapter in scope:
            rows = source.execute(
                """
                SELECT id, book_id, chapter, location, raw_text, personal_note, color,
                       dedupe_key, source, highlighted_at, export_position, truncated,
                       removed_at
                FROM highlights
                WHERE book_id = ? AND chapter IS NOT DISTINCT FROM ?
                ORDER BY export_position
                """,
                (book_id, chapter),
            ).fetchall()
            for row in rows:
                highlight_count += 1
                session.add(
                    Highlight(
                        id=row[0],
                        book_id=row[1],
                        chapter=row[2],
                        location=row[3],
                        raw_text=row[4],
                        personal_note=row[5],
                        color=row[6],
                        dedupe_key=row[7],
                        source=row[8],
                        highlighted_at=date.fromisoformat(row[9]),
                        export_position=row[10],
                        truncated=bool(row[11]),
                        removed_at=datetime.fromisoformat(row[12]) if row[12] else None,
                        # Reset, so the pipeline re-curates every copied row.
                        processed=False,
                    )
                )
        session.commit()
    engine.dispose()
    return {"books": len(book_ids), "chapters": len(scope), "highlights": highlight_count}


def _run_pipeline(target: Path) -> None:
    """Curator → Writer ⇄ Critic over the seed, via the composition root."""
    from recally.container import Container

    settings = Settings(
        RECALLY_API_KEY="qa-key-not-a-real-secret",
        RECALLY_DATABASE_URL=f"sqlite:///{target}",
    )
    container = Container(settings)
    with container.session() as session:
        run = IngestRun(filename="qa-curator-seed")
        session.add(run)
        session.commit()
        run_id = run.id
    container.run_pipeline(run_id)
    with container.session() as session:
        finished = session.get(IngestRun, run_id)
        if finished is not None:
            print(
                f"run {run_id}: kept={finished.units_kept} dropped={finished.units_dropped} "
                f"cards={finished.cards_generated} error={finished.error!r}"
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        default=os.environ.get("RECALLY_DATABASE_URL", "sqlite:///../data/recally.db"),
        help="production database to copy from (opened read-only)",
    )
    parser.add_argument(
        "--target",
        type=Path,
        default=DEFAULT_TARGET,
        help="throwaway QA database to (re)create (default: data/qa-curator.db)",
    )
    parser.add_argument("--book", action="append", default=[], help="book title substring or id")
    parser.add_argument("--chapter", action="append", default=[], help="chapter substring")
    parser.add_argument(
        "--run",
        action="store_true",
        help="run the pipeline over an already-seeded target instead of seeding",
    )
    args = parser.parse_args()

    target = args.target.expanduser().resolve()

    if args.run:
        _run_pipeline(target)
        return 0

    source = _connect_read_only(_sqlite_path(args.source))
    try:
        scope = _scope_chapters(source, args.book, args.chapter)
        if not scope:
            print("no chapters in scope — check the --book / --chapter filters")
            return 1
        target.parent.mkdir(parents=True, exist_ok=True)
        target.unlink(missing_ok=True)
        _migrate(target)
        counts = _seed(source, target, scope)
    finally:
        source.close()

    url = f"sqlite:///{target}"
    print(f"Seeded {target}:")
    for name, count in counts.items():
        print(f"  {count:3d}  {name}")
    print(
        f"""
Serve it (debug-build phone on the same Wi-Fi, or Tailscale):

    RECALLY_DATABASE_URL={url} RECALLY_API_KEY=qa-key \\
      uv run uvicorn recally.main:app --host 0.0.0.0 --port 8001

Run the pipeline over the seed (Curator → Writer ⇄ Critic, real LLM calls):

    RECALLY_DATABASE_URL={url} uv run python scripts/qa_curator_server.py --run

Then on the phone: Settings → base URL http://<mac-lan-or-tailnet-ip>:8001,
key qa-key → connection test → Approval queue.
"""
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
