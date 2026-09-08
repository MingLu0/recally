"""Design-invariant tests: the structural rules of AGENTS.md / docs/backend.md, as code.

AGENTS.md, "Design invariants" says violations of these rules are bugs, not style.
mypy strict checks that a variant satisfies its `Protocol`; it cannot check import
direction or who writes `cards.status`. These tests walk the source tree with `ast`
(never text search, so formatting cannot defeat a rule, and string literals and
comments are never mistaken for imports) and fail CI when a change breaks a seam.

Each failure message names the offending module, the rule, and the doc that owns it,
so the output alone teaches a future agent why the rule exists.
"""

import ast
from collections.abc import Iterator
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1] / "src" / "recally"

AGENT_ROLES = ("curator", "writer", "critic", "learner")

# Where each rule is written down, for failure messages.
REF_IMPORT_DIRECTION = "docs/backend.md, 'Module rules' 1; AGENTS.md, 'Design invariants'"
REF_AGENT_SEAM = "docs/decisions/007-agent-protocol-registry.md; AGENTS.md, 'Design invariants'"
REF_LLM = "AGENTS.md, hard rule 3; docs/decisions/003-litellm-provider-agnostic.md"
REF_STATUS = "AGENTS.md, hard rules 1 and 9; docs/backend.md, 'Module rules' 2"

CARD_STATUS_WRITE_FILES = {"recally.pipeline", "recally.api.routers.cards"}
GUARDED_CARD_STATUSES = {"approved", "rejected"}
PROVIDER_SDKS = ("anthropic", "openai", "google.generativeai", "cohere")


def _module_name(path: Path) -> str:
    """The dotted module name for a file, e.g. `recally.agents.curator.default`."""
    relative = path.relative_to(PACKAGE_ROOT.parent).with_suffix("")
    parts = list(relative.parts)
    if parts[-1] == "__init__":
        parts.pop()
    return ".".join(parts)


def _is_within(imported: str, root: str) -> bool:
    """True when `imported` names `root` itself or something inside it."""
    return imported == root or imported.startswith(root + ".")


def _source_files() -> Iterator[Path]:
    yield from sorted(PACKAGE_ROOT.rglob("*.py"))


def _imported_modules(path: Path) -> Iterator[str]:
    """Every absolute dotted name a file imports, relative imports resolved.

    `from x import y` yields both `x` and `x.y`, so `from recally.agents import curator`
    is seen as importing the `recally.agents.curator` package it actually pulls in.
    """
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    package_parts = _module_name(path).split(".")
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


def _violations(imports_by_module: dict[str, list[str]], rule: str, reference: str) -> str:
    """Format one failure line per offending module, or an empty string."""
    lines = [
        f"{module} imports {sorted(set(names))}: {rule} ({reference})"
        for module, names in sorted(imports_by_module.items())
    ]
    return "\n".join(lines)


def _modules_importing(roots: tuple[str, ...]) -> dict[str, list[str]]:
    """Map of module -> matched imports, for every module importing any of `roots`."""
    offenders: dict[str, list[str]] = {}
    for path in _source_files():
        matches = [
            name for name in _imported_modules(path) if any(_is_within(name, r) for r in roots)
        ]
        if matches:
            offenders[_module_name(path)] = matches
    return offenders


def test_nothing_below_api_imports_fastapi() -> None:
    """Only the HTTP layer imports FastAPI/Starlette.

    The pipeline also runs from the watcher, APScheduler, the CLI and POST /jobs/run,
    so request-framework types must not leak below `api/`. `recally/main.py` is the
    app factory (docs/backend.md, layout) and belongs to the HTTP layer, so it is
    allowed alongside `recally/api/`.
    """
    offenders = {
        module: names
        for module, names in _modules_importing(("fastapi", "starlette")).items()
        if module != "recally.main" and not _is_within(module, "recally.api")
    }
    assert not offenders, "\n" + _violations(
        offenders,
        "nothing below api/ imports FastAPI; the pipeline runs without HTTP",
        REF_IMPORT_DIRECTION,
    )


def test_agents_import_no_db() -> None:
    """Agents hold no DB session and no models: they return typed results and the
    pipeline owns every write (statuses, `processed`, orphan cleanup)."""
    db_roots = ("recally.db", "sqlalchemy", "recally.models")
    offenders = {
        module: names
        for module, names in _modules_importing(db_roots).items()
        if _is_within(module, "recally.agents")
    }
    assert not offenders, "\n" + _violations(
        offenders,
        "agents must not import the database layer; persistence belongs to pipeline.py",
        REF_AGENT_SEAM,
    )


def test_agents_do_not_import_each_other_or_the_pipeline() -> None:
    """Role packages are isolated: `agents/base.py` and `agents/registry.py` are the
    only shared surface, so a variant can be swapped without touching the pipeline."""
    role_roots = tuple(f"recally.agents.{role}" for role in AGENT_ROLES)
    offenders: dict[str, list[str]] = {}
    for path in _source_files():
        module = _module_name(path)
        owner = next((r for r in role_roots if _is_within(module, r)), None)
        if owner is None:
            continue
        forbidden = tuple(r for r in role_roots if r != owner) + ("recally.pipeline",)
        matches = [
            name for name in _imported_modules(path) if any(_is_within(name, f) for f in forbidden)
        ]
        if matches:
            offenders[module] = matches
    assert not offenders, "\n" + _violations(
        offenders,
        "an agent role must not import another role or the pipeline; share via base.py/registry.py",
        REF_AGENT_SEAM,
    )


def test_pipeline_resolves_agents_only_via_registry() -> None:
    """`pipeline.py` imports only `recally.agents.base` / `.registry` — never a
    variant module — so swapping a variant is config, not a code change (ADR-007).

    Passes trivially while `pipeline.py` does not exist yet.
    """
    pipeline_path = PACKAGE_ROOT / "pipeline.py"
    if not pipeline_path.exists():
        return
    allowed = ("recally.agents.base", "recally.agents.registry")
    matches = [
        name
        for name in _imported_modules(pipeline_path)
        if _is_within(name, "recally.agents")
        and name != "recally.agents"
        and not any(_is_within(name, a) for a in allowed)
    ]
    assert not matches, (
        f"recally.pipeline imports agent implementation modules {sorted(set(matches))}: "
        "agents are resolved through agents/registry.py, never imported directly "
        f"({REF_AGENT_SEAM})"
    )


def test_llm_is_the_only_litellm_importer() -> None:
    """Every LLM call goes through the single `llm.py` wrapper so calls are logged to
    `llm_calls` and prompts stay provider-agnostic."""
    importers = set(_modules_importing(("litellm",)))
    assert importers == {"recally.llm"}, (
        f"litellm may be imported only by recally/llm.py, found in {sorted(importers)}: "
        f"all LLM calls go through the single llm.py wrapper ({REF_LLM})"
    )


def _card_status_writes(path: Path) -> Iterator[int]:
    """Line numbers where a guarded cards.status literal is *written*.

    Covers attribute/name assignment (`card.status = "approved"`), keyword arguments
    (`Card(status=...)`, `.values(status=...)`), and dict entries
    (`.values({"status": ...})`). Comparisons like `Card.status == "approved"` are
    reads, not writes, and are allowed anywhere.
    """

    def is_guarded_constant(node: ast.expr) -> bool:
        return isinstance(node, ast.Constant) and node.value in GUARDED_CARD_STATUSES

    def is_status_target(target: ast.expr) -> bool:
        return (isinstance(target, ast.Attribute) and target.attr == "status") or (
            isinstance(target, ast.Name) and target.id.endswith("status")
        )

    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign):
            if is_guarded_constant(node.value) and any(is_status_target(t) for t in node.targets):
                yield node.lineno
        elif isinstance(node, ast.AnnAssign):
            if (
                node.value is not None
                and is_guarded_constant(node.value)
                and is_status_target(node.target)
            ):
                yield node.lineno
        elif isinstance(node, ast.keyword):
            if node.arg == "status" and is_guarded_constant(node.value):
                yield node.lineno
        elif isinstance(node, ast.Dict):
            for key, value in zip(node.keys, node.values, strict=True):
                if (
                    isinstance(key, ast.Constant)
                    and key.value == "status"
                    and is_guarded_constant(value)
                ):
                    yield node.lineno


def test_only_the_pipeline_and_api_assign_card_status() -> None:
    """Nothing schedules a card without human approval: `approved`/`rejected` writes
    live in `pipeline.py` (into `pending_review`/`needs_human` only) and the review
    route; agents can never set them."""
    offenders: list[str] = []
    for path in _source_files():
        module = _module_name(path)
        if module in CARD_STATUS_WRITE_FILES:
            continue
        lines = list(_card_status_writes(path))
        if lines:
            offenders.append(f"{module} lines {lines}")
    assert not offenders, (
        f"cards.status 'approved'/'rejected' may be assigned only in pipeline.py and "
        f"api/routers/cards.py, never under agents/; found writes in "
        f"{'; '.join(offenders)} ({REF_STATUS})"
    )


def test_services_do_not_import_api() -> None:
    """Import direction is one-way: the API layer depends on services, ingest,
    agents and scheduling — never the reverse."""
    below_api = ("recally.services", "recally.ingest", "recally.agents", "recally.scheduling")
    offenders = {
        module: names
        for module, names in _modules_importing(("recally.api",)).items()
        if any(_is_within(module, layer) for layer in below_api)
    }
    assert not offenders, "\n" + _violations(
        offenders,
        "lower layers must not import recally.api; the dependency direction is api/ -> services",
        REF_IMPORT_DIRECTION,
    )


def test_no_provider_sdk_imports() -> None:
    """No provider SDK anywhere in src/ — LiteLLM is the only client (ADR-003), and
    provider-specific prompt features are banned for portability."""
    offenders = _modules_importing(PROVIDER_SDKS)
    assert not offenders, "\n" + _violations(
        offenders,
        f"provider SDKs {PROVIDER_SDKS} are banned; call models through llm.py (LiteLLM)",
        REF_LLM,
    )
