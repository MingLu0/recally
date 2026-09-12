"""Docs-drift tests: the layout tree in docs/backend.md and the env-var docs stay honest.

The manual audit behind #212 found the tree in docs/backend.md months stale (a whole
`services/` layer missing) because nothing checked the doc against the filesystem —
the commit that added each file went green while the doc quietly rotted. These tests
parse the `## Package layout` fenced block into repo-relative paths and compare them
with the disk and with `config.py`, so the PR that introduces a file without updating
the tree fails CI instead.

Same family as test_design_invariants.py: a documented claim, checked as code, with a
failure message that names the doc owning the claim.
"""

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND_ROOT = REPO_ROOT / "backend"
SRC_ROOT = BACKEND_ROOT / "src" / "recally"
BACKEND_MD = REPO_ROOT / "docs" / "backend.md"
CONFIG_MD = REPO_ROOT / "docs" / "config.md"
ENV_EXAMPLE = BACKEND_ROOT / ".env.example"

REF_TREE = "docs/backend.md, 'Package layout'"
REF_CONFIG = "docs/config.md; backend/.env.example"


def _layout_block() -> list[str]:
    """The lines of the fenced code block under `## Package layout` in docs/backend.md."""
    lines = BACKEND_MD.read_text().splitlines()
    heading = lines.index("## Package layout")
    start = lines.index("```", heading)
    end = lines.index("```", start + 1)
    return lines[start + 1 : end]


def _parse_tree() -> tuple[set[str], set[str]]:
    """Parse the layout block into (file paths, dir paths), repo-relative (e.g. `backend/src/`).

    Indentation is nesting; a line may hold several sibling entries (`health.py  reviews.py`)
    and an entry may carry a subpath (`prompts/curator.md`). A single-token directory line is
    pushed so following deeper lines nest under it; multi-token directory lines are siblings,
    so nothing is pushed.
    """
    files: set[str] = set()
    dirs: set[str] = set()
    stack: list[tuple[int, str]] = []  # (indent, dir name) of open directories
    for raw in _layout_block():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        tokens = line.split()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        parent = "".join(f"{name}/" for _, name in stack)
        for token in tokens:
            if token.endswith("/"):
                dirs.add(parent + token)
            else:
                files.add(parent + token)
        if len(tokens) == 1 and tokens[0].endswith("/"):
            stack.append((indent, tokens[0].rstrip("/")))
    return files, dirs


def _src_modules() -> set[str]:
    """Every non-dunder module under src/recally, repo-relative."""
    return {
        path.relative_to(REPO_ROOT).as_posix()
        for path in SRC_ROOT.rglob("*.py")
        if path.name != "__init__.py"
    }


def _src_dirs() -> set[str]:
    """Every directory under src/recally that holds a module, repo-relative with trailing slash."""
    dirs: set[str] = set()
    for module in _src_modules():
        for parent in Path(module).parents:
            if parent == Path("backend/src"):
                break
            dirs.add(parent.as_posix() + "/")
    return dirs


def _nearest_tree_dir(module: str, tree_dirs: set[str]) -> str | None:
    """The nearest ancestor of `module` that appears as a directory in the tree."""
    for parent in Path(module).parents:
        candidate = parent.as_posix() + "/"
        if candidate in tree_dirs:
            return candidate
    return None


def test_src_modules_all_listed_in_backend_md_tree() -> None:
    tree_files, tree_dirs = _parse_tree()
    # A directory with any file listed beneath it is "expanded" — the tree enumerates its
    # modules, so a new module must join the list (the scheduling/learner.py drift, #212).
    # A directory with no files beneath it (models/, schemas/) is deliberately terse and
    # covers its contents wholesale; test_src_dirs_all_listed_in_backend_md_tree guards those.
    expanded = {d for d in tree_dirs if any(f.startswith(d) for f in tree_files)}
    missing = []
    for module in sorted(_src_modules()):
        if module in tree_files:
            continue
        ancestor = _nearest_tree_dir(module, tree_dirs)
        if ancestor is None or ancestor in expanded:
            missing.append(module)
    assert not missing, (
        f"modules on disk but missing from the layout tree ({REF_TREE}): {missing}. "
        "Their neighbours are listed individually, so list the new module in the same "
        "change that adds the file."
    )


def test_src_dirs_all_listed_in_backend_md_tree() -> None:
    _, tree_dirs = _parse_tree()
    missing = sorted(_src_dirs() - tree_dirs)
    assert not missing, (
        f"package directories on disk but missing from the layout tree ({REF_TREE}): {missing}. "
        "This is how the services/ layer went undocumented (see #212)."
    )


def test_tree_paths_exist_on_disk() -> None:
    tree_files, tree_dirs = _parse_tree()
    stale = sorted(
        path for path in tree_files | tree_dirs if not (REPO_ROOT / path.rstrip("/")).exists()
    )
    assert not stale, (
        f"paths listed in the layout tree but absent on disk ({REF_TREE}): {stale}. "
        "Update or remove the tree entry in the same change that moves the file."
    )


def test_config_env_vars_documented_in_config_md_and_env_example() -> None:
    config_source = (BACKEND_ROOT / "src" / "recally" / "config.py").read_text()
    aliases = set(re.findall(r'validation_alias="([A-Z0-9_]+)"', config_source))
    assert aliases, "no validation_alias found — is config.py still where settings live?"
    config_md = CONFIG_MD.read_text()
    env_example = ENV_EXAMPLE.read_text()
    undocumented = sorted(
        alias for alias in aliases if alias not in config_md or alias not in env_example
    )
    assert not undocumented, (
        f"settings in config.py but missing from {REF_CONFIG}: {undocumented}. "
        "A setting a user cannot discover does not exist."
    )
