"""The one place the backend configures logging (issue #211).

Five modules create `recally.*` loggers — the optimizer, the notifier, the Learner,
the watcher and `llm.py` — and until this module existed nothing ever configured
them. Uvicorn configures only its own `uvicorn.*` loggers, so `recally.*` records
propagated to a handlerless root: WARNING+ leaked bare to stderr through Python's
last-resort handler, and INFO — the nightly jobs' entire operational output — went
nowhere at all. The lines were in the code; no process emitted them.

Two deliberate choices:

- **Scoped to the `recally` namespace logger, not the root.** `basicConfig` would
  pull in SQLAlchemy and APScheduler INFO noise, pollute the root for anything
  embedding this package, and — worst — no-op silently whenever the root already has
  a handler, which is exactly the uvicorn case this issue is about.
- **Stdlib only.** No dependency, and the format mirrors uvicorn's so the job lines
  read as part of the same log rather than as something bolted on.

Called from the two entry points that run jobs: the FastAPI lifespan (`main.py`) and
the watcher's `main()`. The CLI stays print-only — it runs no jobs.
"""

import logging
import sys

NAMESPACE = "recally"

# Uvicorn's default access/error format is `LEVEL:<padding>message`; matching the
# level padding is what makes these lines blend into the same column.
LOG_FORMAT = "%(levelname)s:%(name)s: %(message)s"


def configure_logging(level: int = logging.INFO) -> None:
    """Attach one stderr handler to the `recally` namespace logger, once.

    Idempotent: the FastAPI lifespan re-enters under `--reload`, and the test suite
    builds the app many times in one process. A second call must not attach a second
    handler, or every line would be emitted twice.
    """
    namespace_logger = logging.getLogger(NAMESPACE)
    namespace_logger.setLevel(level)

    if any(getattr(handler, "_recally_configured", False) for handler in namespace_logger.handlers):
        return

    handler = logging.StreamHandler(sys.stderr)
    handler.setLevel(level)
    handler.setFormatter(logging.Formatter(LOG_FORMAT))
    # Marks this handler as ours so the guard above recognises it on re-entry without
    # mistaking a handler someone else attached for a completed configuration.
    handler._recally_configured = True  # type: ignore[attr-defined]
    namespace_logger.addHandler(handler)

    # `propagate` is deliberately left alone. Turning it off would stop double-emission
    # wherever the root also has a handler, but the root is where pytest's `caplog` and
    # any embedding process attach theirs — severing it makes `recally.*` records
    # invisible to exactly the tools that came looking for them, which is the same
    # class of bug as the one this module fixes. Under the entry points that call this
    # (uvicorn, the watcher) the root is handlerless, so nothing doubles in practice.
