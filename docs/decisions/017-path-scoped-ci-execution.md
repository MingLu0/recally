# ADR-017: Path-scoped CI execution

**Status**: accepted
**Date**: 2026-10-24

## Context

`.github/workflows/ci.yml` ran both full suites on every PR and every push to `main`: an Android-only change paid for `uv sync`, Ruff, mypy, pytest, Bandit and pip-audit, and a backend-only change paid for Gradle setup, ktlint, Android Lint, unit tests and an APK build.

One constraint removes the obvious fix. The active `main` protection ruleset **requires the `backend` check**, and GitHub documents that a workflow skipped by event-level `paths:` filtering leaves its required checks **Pending** — an Android-only PR would be blocked forever. So filtering at the `on:` level is not available, and putting `backend` behind a separate detection job is not either: a detection job whose failure causes `backend` to be skipped would report the required check as successful without ever running it.

## Decision

**Option A: path-scoped execution inside the existing CI workflow** (#271). One workflow, the existing `backend` / `android` / `distribute` job IDs, and all existing check commands unchanged. Each of `backend` and `android` gains a "Detect changed paths" step immediately after checkout, before any dependency/toolchain setup, using the pinned `dorny/paths-filter@v4`. Every subsequent setup, lint, test, scan and assemble step in that job is conditional on the job's filter result:

- backend: `backend/**`, `.python-version` (the root interpreter pin `uv sync` reads), `.github/workflows/ci.yml` (a shared CI edit must exercise both suites)
- android: `android/**`, `.github/workflows/ci.yml`

Every gated step's condition carries an explicit fallback — `github.event_name == 'workflow_dispatch' || startsWith(github.ref, 'refs/tags/')` — so v* tag pushes and manual dispatches keep running the full suites untouched; detection is itself skipped on those events, so a tag push can never fail on change detection. Path scoping applies to PRs and branch pushes only. A detection failure fails the containing job (no `continue-on-error`), never silently selecting "no changes".

The structural contract is enforced by `backend/tests/test_ci_workflow.py`, which parses the workflow with a real YAML parser and asserts against its real filter configuration.

## Rationale

- **In-job detection, not event-level filtering, is the only shape compatible with the required `backend` check.** The job always runs; for an irrelevant change it completes after checkout + detection, reporting a successful lightweight check.
- **`dorny/paths-filter` over a hand-rolled diff.** It conventionally handles the three cases a shell script gets wrong: PR base SHAs (via the GitHub API), branch pushes (merge-base / previous commit via git, fetching the history it needs itself), and pagination. Its glob dialect (picomatch) is the de-facto standard for this.
- **The fallback lives in every gated step rather than in a `should-run` output.** A reader sees the release-preserving behavior at the step that has it, and the required `backend` job keeps no job-level `if` or `needs` that could skip it.
- **Option B (separate backend/android workflow files or an aggregate CI gate) is not currently needed.** With two suites and one shared workflow file, in-job gating gives the same savings without a new aggregate check name, new required-check configuration, or the Pending-checks problem in another shape. It stays available if the suite count grows or required checks are renegotiated.

## Consequences

- An irrelevant PR costs seconds instead of minutes: checkout + detection, then skipped steps. A change to `.github/workflows/ci.yml` runs both full suites by design.
- `docs/scripts`-only PRs run neither full suite; the required `backend` check still completes green.
- The path sets are filter configuration in `ci.yml`, asserted by the structural tests — adding a module means adding its filter there, and the tests fail if the sets drift from the contract.
- `distribute`, `concurrency`, and the tag/dispatch triggers are unchanged; release behavior is preserved by the explicit fallback, not by trusting detection on tag pushes.
- Historical ADRs (`009`, `012`) that say CI runs "on every PR" are amended by this record, not rewritten.
