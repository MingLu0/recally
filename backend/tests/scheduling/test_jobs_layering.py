"""The layering rule for `scheduling/jobs.py` (docs/backend.md, "Layering", rule 1).

The same functions are called by APScheduler, by an external cron and by the
`POST /jobs/run` router, and none of the first two is an HTTP request — so
request-framework types must not leak into the module. Same AST shape as the
ADR-007 import tests in tests/test_design_invariants.py: parsing, never text
search, so formatting and string literals cannot defeat the rule.
"""

import ast
from pathlib import Path

JOBS_PY = Path(__file__).resolve().parents[2] / "src" / "recally" / "scheduling" / "jobs.py"

FORBIDDEN_ROOTS = ("fastapi", "starlette")


def _imported_modules(path: Path) -> list[str]:
    """Every absolute dotted name the file imports (relative imports resolved)."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    names: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.extend(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                # jobs.py is flat inside the package; a relative import here can
                # only point at recally.scheduling.* — never fastapi.
                continue
            if node.module:
                names.append(node.module)
    return names


def test_scheduling_jobs_does_not_import_fastapi() -> None:
    """`scheduling/jobs.py` must stay framework-free: APScheduler and external
    cron call it without an HTTP request in sight."""
    assert JOBS_PY.exists(), (
        f"{JOBS_PY} does not exist: scheduling/jobs.py carries the notify/learner/"
        "optimizer entry points (docs/backend.md, 'Package layout')"
    )
    offenders = [
        name
        for name in _imported_modules(JOBS_PY)
        if any(name == root or name.startswith(root + ".") for root in FORBIDDEN_ROOTS)
    ]
    assert not offenders, (
        f"recally/scheduling/jobs.py imports {sorted(offenders)}: nothing below api/ "
        "imports FastAPI — the same functions run from APScheduler and external cron "
        "(docs/backend.md, 'Layering' rule 1; 'Wiring and entry points')"
    )
