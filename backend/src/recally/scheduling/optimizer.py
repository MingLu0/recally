"""Learner stage A: the nightly FSRS parameter fit.

Spec: docs/agents.md, "7. Learner Agent" (stage A is deterministic — "parameter
fitting is math", so nothing here imports `llm.py`; hard rule 2);
docs/data-model.md, `fsrs_params`; docs/config.md, `OPTIMIZER_MIN_REVIEWS`;
docs/architecture.md, "Optimizer". No FastAPI either (docs/backend.md,
"Layering" rule 1): APScheduler, an external cron and `POST /jobs/run` all call
this module, and only one of those is an HTTP request.

Two write-suppression rules, both from the docs:

- Below `OPTIMIZER_MIN_REVIEWS` reviews, nothing is written — no row of any
  kind. "Defaults are used until the first fit" (docs/data-model.md), and an
  `fsrs_params` row is what ends that state; a premature row would silently
  replace tuned-by-omission defaults with a fit from noise.
- `fsrs.Optimizer` needs the optional `fsrs[optimizer]` extra (torch + pandas),
  kept out of the default install so CI and Android PRs never pay for ~800MB of
  torch. Without it the job is a no-op with a log line naming the install
  command — never a raise, never a row.

Rows are append-only ("latest row is active"): a re-run inserts, never edits.
The only read of the table stays in `scheduling/fsrs.py` — a second read path is
how two schedulers start to disagree.
"""

import logging
from datetime import timezone
from typing import TYPE_CHECKING

from sqlalchemy import func, select

from recally.models import FsrsParams, ReviewLog

if TYPE_CHECKING:
    from recally.container import Container

logger = logging.getLogger(__name__)


def fit_parameters(container: "Container") -> FsrsParams | None:
    """Fit FSRS parameters from `review_logs` and append one `fsrs_params` row.

    Returns the appended row (detached, so callers can read it after the session
    closes), or `None` when nothing was written — below the threshold, or the
    `fsrs[optimizer]` extra not installed.
    """
    settings = container.settings
    with container.session() as session:
        review_count = session.scalar(select(func.count()).select_from(ReviewLog)) or 0
        if review_count < settings.optimizer_min_reviews:
            logger.info(
                "optimizer: %d review_logs below OPTIMIZER_MIN_REVIEWS=%d; skipping the fit",
                review_count,
                settings.optimizer_min_reviews,
            )
            return None

        rows = session.scalars(select(ReviewLog).order_by(ReviewLog.id)).all()
        # `fsrs` is imported only here and in scheduling/fsrs.py — the two
        # sanctioned sites (docs/backend.md, "Package layout"), the same rule
        # llm.py follows for litellm. The extra-free parts of the library (the
        # ReviewLog/Rating value types) import cleanly without torch.
        from fsrs import Rating
        from fsrs import ReviewLog as LibraryReviewLog

        library_logs = [
            LibraryReviewLog(
                card_id=row.card_id,
                rating=Rating(row.rating),
                # Stored naive UTC; the library's own docs use aware datetimes.
                review_datetime=row.rated_at.replace(tzinfo=timezone.utc),
                review_duration=row.response_ms,
            )
            for row in rows
        ]

        try:
            from fsrs import Optimizer

            optimizer = Optimizer(library_logs)
        except ImportError:
            logger.warning(
                "optimizer: the fsrs[optimizer] extra is not installed; skipping the fit. "
                "Install it with: uv sync --extra optimizer"
            )
            return None

        parameters = optimizer.compute_optimal_parameters()
        row = FsrsParams(
            parameters=[float(weight) for weight in parameters],
            desired_retention=settings.fsrs_desired_retention,
            review_count=len(rows),
            user_id=1,
        )
        session.add(row)
        session.commit()
        # commit() expires the ORM attributes; reload and detach so the caller
        # can read the row after this session closes.
        session.refresh(row)
        session.expunge(row)

    logger.info(
        "optimizer: fitted %d parameters from %d reviews into fsrs_params row %d",
        len(parameters),
        len(rows),
        row.id,
    )
    return row
