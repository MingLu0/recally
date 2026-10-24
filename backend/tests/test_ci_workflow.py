"""CI-workflow structural tests: path-scoped execution in .github/workflows/ci.yml, as code.

Issue #271 / ADR-017: the `backend` and `android` CI jobs run their full suites only
when their module's paths changed, while the `backend` check — required by the main
protection ruleset — still completes on every PR after cheap no-op detection. v* tag
pushes and workflow_dispatch runs keep their current behavior through an explicit
fallback in every gated step.

These tests parse the workflow with a real YAML parser and assert against the parsed
structure and its real filter configuration. They never re-implement the desired YAML
as a second hand-maintained copy of the workflow, and never use regexes as a YAML
parser.
"""

import fnmatch
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = REPO_ROOT / ".github" / "workflows" / "ci.yml"

DETECTION_STEP_NAME = "Detect changed paths"
DETECTION_ACTION = "dorny/paths-filter"
ANDROID_GRADLE_CHECK_STEP = "Check for a Gradle project"


# Every expensive step's `if` must gate on the module's changed-path result AND carry
# the explicit tag/dispatch preservation fallback (#271, "Hard constraint").
def _path_result(module: str) -> str:
    return f"steps.changes.outputs.{module} == 'true'"


DISPATCH_FALLBACK = "github.event_name == 'workflow_dispatch'"
TAG_FALLBACK = "startsWith(github.ref, 'refs/tags/')"

# Representative changed-path sets and the full suites they must select (#271,
# "Tests": docs/orchestration-only select neither; module-only selects that suite;
# mixed selects both). Evaluated against the filters parsed out of the workflow.
REPRESENTATIVE_PATH_SETS: dict[str, tuple[list[str], set[str]]] = {
    "docs-only": (["docs/PRD.md"], set()),
    "orchestration-script-only": (["scripts/orchestrate.sc"], set()),
    "backend-only": (["backend/src/recally/main.py"], {"backend"}),
    "android-only": (
        ["android/app/src/main/java/dev/recally/ui/screens/TodayScreen.kt"],
        {"android"},
    ),
    "mixed": (["backend/pyproject.toml", "android/app/build.gradle.kts"], {"backend", "android"}),
}


def _workflow() -> dict[str, Any]:
    return yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))


def _triggers(workflow: dict[str, Any]) -> dict[str, Any]:
    """The `on:` block.

    PyYAML implements YAML 1.1, where the unquoted scalar key `on` parses as boolean
    True; GitHub's workflow parser reads the same file with `on` as a plain string.
    Support both so the tests assert what any real parser sees.
    """
    return workflow.get("on") or workflow[True]


def _job(workflow: dict[str, Any], name: str) -> dict[str, Any]:
    return workflow["jobs"][name]


def _step_label(step: dict[str, Any]) -> str:
    return str(
        step.get("name") or step.get("uses") or str(step.get("run", "<run step>")).splitlines()[0]
    )


def _is_checkout(step: dict[str, Any]) -> bool:
    return str(step.get("uses", "")).startswith("actions/checkout@")


def _detection_step(job: dict[str, Any]) -> dict[str, Any] | None:
    for step in job["steps"]:
        if step.get("name") == DETECTION_STEP_NAME:
            return step
    return None


def _module_filters(workflow: dict[str, Any], job_name: str) -> dict[str, list[str]]:
    """The changed-path filter configuration of a job's detection step, as parsed YAML."""
    detection = _detection_step(_job(workflow, job_name))
    assert detection is not None, f"{job_name} job has no '{DETECTION_STEP_NAME}' step"
    return yaml.safe_load(detection["with"]["filters"])


def _gate_defects(step: dict[str, Any], module: str) -> list[str]:
    """What is missing from an expensive step's `if` for the path-scoping contract."""
    expr = str(step.get("if", ""))
    defects = []
    if _path_result(module) not in expr:
        defects.append("no gate on the module's changed-path result")
    if DISPATCH_FALLBACK not in expr:
        defects.append("no workflow_dispatch preservation fallback")
    if TAG_FALLBACK not in expr:
        defects.append("no v* tag preservation fallback")
    return defects


def _expensive_steps(job: dict[str, Any], cheap_step_names: set[str]) -> list[dict[str, Any]]:
    """Every step that must carry the path gate: all but checkout and the cheap
    detection/change-analysis steps that precede toolchain setup."""
    return [
        step
        for step in job["steps"]
        if not _is_checkout(step) and step.get("name") not in cheap_step_names
    ]


def _selected_modules(workflow: dict[str, Any], changed_paths: list[str]) -> set[str]:
    """Which full suites the workflow's real filter configuration selects for a path set."""
    selected = set()
    for job_name, module in (("backend", "backend"), ("android", "android")):
        patterns = _module_filters(workflow, job_name)[module]
        if any(
            fnmatch.fnmatchcase(path, pattern) for path in changed_paths for pattern in patterns
        ):
            selected.add(module)
    return selected


def test_backend_steps_only_run_for_relevant_changes() -> None:
    """Every expensive backend step is gated on the backend path result with the
    explicit tag/dispatch preservation fallback, while checkout (and detection, in
    the test below) stays unconditional so the required `backend` check completes
    even on an Android-only PR."""
    workflow = _workflow()
    job = _job(workflow, "backend")

    ungated = [
        f"{_step_label(step)}: {', '.join(_gate_defects(step, 'backend'))}"
        for step in _expensive_steps(job, cheap_step_names={DETECTION_STEP_NAME})
        if _gate_defects(step, "backend")
    ]
    assert not ungated, "expensive backend steps missing the path gate:\n" + "\n".join(ungated)

    checkout = next(step for step in job["steps"] if _is_checkout(step))
    assert "if" not in checkout, (
        "checkout must stay unconditional: the ruleset-required backend check has to "
        "run on every PR, so the job can never be skipped at the top"
    )


def test_android_steps_only_run_for_relevant_changes() -> None:
    """Every expensive Android step is gated on the Android path result with the same
    event preservation fallback, and the pre-existing Gradle-wrapper gate survives."""
    workflow = _workflow()
    job = _job(workflow, "android")
    cheap = {DETECTION_STEP_NAME, ANDROID_GRADLE_CHECK_STEP}

    ungated = [
        f"{_step_label(step)}: {', '.join(_gate_defects(step, 'android'))}"
        for step in _expensive_steps(job, cheap_step_names=cheap)
        if _gate_defects(step, "android")
    ]
    assert not ungated, "expensive android steps missing the path gate:\n" + "\n".join(ungated)

    unwrapped = [
        _step_label(step)
        for step in _expensive_steps(job, cheap_step_names=cheap)
        if "steps.gradle_project.outputs.present == 'true'" not in str(step.get("if", ""))
    ]
    assert not unwrapped, (
        "Android toolchain setup and check steps must keep their Gradle-wrapper gate: "
        + ", ".join(unwrapped)
    )


def test_shared_ci_and_python_version_paths_are_covered() -> None:
    """Both path sets include `.github/workflows/ci.yml` (a shared CI edit must
    exercise both suites); backend also includes `backend/**` and `.python-version`
    (the root interpreter pin uv sync reads); Android includes `android/**`."""
    workflow = _workflow()
    backend_filters = set(_module_filters(workflow, "backend")["backend"])
    android_filters = set(_module_filters(workflow, "android")["android"])

    assert ".github/workflows/ci.yml" in backend_filters
    assert ".github/workflows/ci.yml" in android_filters
    assert {"backend/**", ".python-version", ".github/workflows/ci.yml"} <= backend_filters
    assert {"android/**", ".github/workflows/ci.yml"} <= android_filters


def test_backend_detection_lives_in_the_required_job() -> None:
    """Change detection is an early step of the required `backend` job — not a
    separate detection job and not a job-level skip — so a detection failure fails
    the job (fails closed) and irrelevant changes still produce a completed
    `backend` check."""
    workflow = _workflow()
    jobs = workflow["jobs"]
    backend = jobs["backend"]

    assert "if" not in backend, (
        "backend must not carry a job-level skip: the ruleset requires its check on "
        "every PR, including Android-only ones"
    )
    assert backend.get("needs") in (None, []), (
        "backend must not depend on a detection job: a failed detection would skip "
        "the required check and report success instead of failing"
    )

    detection = _detection_step(backend)
    assert detection is not None, f"backend job has no '{DETECTION_STEP_NAME}' step"
    assert str(detection["uses"]).startswith(DETECTION_ACTION), (
        f"detection must use the pinned {DETECTION_ACTION} action so PR base SHAs, "
        f"branch pushes and pagination are handled conventionally; found {detection['uses']}"
    )
    assert detection.get("continue-on-error") is not True, (
        "a detection error must fail the backend job, never silently select 'no changes'"
    )

    steps = backend["steps"]
    detection_index = next(
        i for i, step in enumerate(steps) if step.get("name") == DETECTION_STEP_NAME
    )
    checkout_index = next(i for i, step in enumerate(steps) if _is_checkout(step))
    assert detection_index == checkout_index + 1, (
        "detection must run immediately after checkout, before dependency setup, "
        "so irrelevant changes pay nothing but checkout + detection"
    )

    standalone_detection_jobs = [
        name
        for name, job in jobs.items()
        if job["steps"]
        and all(
            _is_checkout(step) or str(step.get("uses", "")).startswith(DETECTION_ACTION)
            for step in job["steps"]
        )
    ]
    assert not standalone_detection_jobs, (
        "change detection must live inside the required job, never in a standalone "
        f"job other jobs could gate on: {standalone_detection_jobs}"
    )


def test_docs_only_changes_do_not_match_either_suite() -> None:
    """Representative docs-only and orchestration-script-only path sets select
    neither full suite, while backend-only, Android-only and mixed path sets select
    the expected suite(s) — evaluated against the filters in the workflow itself."""
    workflow = _workflow()
    failures = [
        f"{label}: expected suites {sorted(expected)}, workflow filters select "
        f"{sorted(_selected_modules(workflow, paths))}"
        for label, (paths, expected) in REPRESENTATIVE_PATH_SETS.items()
        if _selected_modules(workflow, paths) != expected
    ]
    assert not failures, "wrong suite selection:\n" + "\n".join(failures)


def test_tag_and_manual_dispatch_behavior_is_preserved() -> None:
    """The tag/manual triggers and the `distribute` gate remain, and the new step
    conditions cannot suppress backend/Android work on those events."""
    workflow = _workflow()
    triggers = _triggers(workflow)
    assert "pull_request" in triggers
    assert triggers["push"]["branches"] == ["main"]
    assert triggers["push"]["tags"] == ["v*"]
    assert "workflow_dispatch" in triggers

    distribute = _job(workflow, "distribute")
    assert distribute["if"] == (
        "github.event_name == 'workflow_dispatch' || startsWith(github.ref, 'refs/tags/')"
    ), "the distribute gate must stay dispatch/tag-only; path scoping must not reach it"

    cheap_by_job = {
        "backend": {DETECTION_STEP_NAME},
        "android": {DETECTION_STEP_NAME, ANDROID_GRADLE_CHECK_STEP},
    }
    missing_fallback = [
        f"{job_name}/{_step_label(step)}"
        for job_name, cheap in cheap_by_job.items()
        for step in _expensive_steps(_job(workflow, job_name), cheap_step_names=cheap)
        if DISPATCH_FALLBACK not in str(step.get("if", ""))
        or TAG_FALLBACK not in str(step.get("if", ""))
    ]
    assert not missing_fallback, (
        "every gated step must keep the tag/dispatch fallback so release behavior "
        "cannot change accidentally: " + ", ".join(missing_fallback)
    )

    release_steps = {_step_label(step) for step in distribute["steps"]}
    assert {"Assemble debug", "Upload to Firebase App Distribution", "Cut the GitHub Release"} <= (
        release_steps
    ), "the distribute job's release steps must all remain"
