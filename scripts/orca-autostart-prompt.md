You are running unattended on a schedule. No human is watching. Read this fully before acting.

## Pick the issue

Run `./scripts/orca-ready-issues.sh` from the repo root. It prints a single JSON
object for the lowest-numbered dispatchable sub-issue, or nothing.

If it prints nothing, stop and say so. Do not look for other work.

Then claim the issue before doing anything else:

1. `gh issue edit <n> --add-assignee @me`
2. Verify it took: `gh issue view <n> --json assignees` must list you. The
   assignee is the board's signal that work is in flight — it is what makes the
   next scheduled run skip this issue while you are working on it.
3. If the assignment failed or did not take, stop. Do not proceed unassigned.

## Do the work

1. `gh issue view <n>` and read it in full.
2. Read `AGENTS.md` and every doc it points at for this area. The docs are the
   spec; if the issue and a doc disagree, the doc wins.
3. TDD against the issue's **Tests** gate. That gate is the acceptance criterion.
4. Commit on a feature branch named per `AGENTS.md` (`feat/…`, `fix/…`, `docs/…`).
   Put `Closes #<n>` in the PR body so the issue closes on merge.

## Validate

The ticket's list of named tests is the whole gate (ADR-012).

1. Write each listed test **before** the code it covers.
2. For every test asserting a raise, a refusal or a negative (`*_raises`, `*_never_*`,
   `*_no_*`, `*_only_*`), confirm it **fails** first and paste that red output in the PR
   next to the green run. A negative assertion that has never failed is not evidence.
   Both runs go under a `## TDD evidence` section (that exact heading) in the PR body —
   red first, green second. The orchestrator checks for the heading; a PR without it is
   not done and will be sent back.
3. Run `uv run pytest`, `uv run ruff check . && uv run ruff format --check .` and
   `uv run mypy src/` from `backend/` (or `./gradlew :app:testDebugUnitTest` from
   `android/`), and paste the output.
4. Name in the PR any listed test you did not write, and why. Never drop one silently.
5. Open the PR and wait for CI.

## Merge policy — read carefully

Auto-merge is allowed ONLY when every one of these holds:

- every test named in the ticket exists and passes, with its output pasted in the PR, and
- CI is green, and
- the PR carries the `## TDD evidence` section with red output preceding green, and
- the PR is not docs-only — check `gh pr diff <pr> --name-only`: if every changed file is
  under `docs/` or ends in `.md`, the PR is docs-only and **waits for a human** — and
- the issue you implemented is a sub-issue (the precheck guarantees this).

If all five hold, nothing needed a human's judgment:

    gh pr merge <pr> --squash --delete-branch

Then comment on the issue saying it was auto-merged and why it qualified.

**Otherwise, leave the PR open.** This is the expected outcome, not a failure.
Comment on the PR with the failing step and what blocks it, then stop. Do not merge,
do not force, do not work around it. A human will pick it up.

## Never

- Never use `orca orchestration ask` or the mailbox to reach a human — nobody
  is listening between orchestrator restarts. If you need a human, comment on
  the issue and stop. (worker_done reports are fine; questions are not.)
- Never work around an `AGENTS.md` hard rule. Several look like bugs without
  context (rule 7 truncated highlights, rule 12 `on_moved`, integer
  `cost_microusd`). If a ticket seems to ask you to break one, stop and say so
  in a comment — that call is the human's.
- Never start a second issue in one run.
- Never touch a parent step issue. Those carry a `You verify` gate that only a
  human can run against the real system.
- Never merge with a listed test unwritten, skipped or failing.
- Never fake the red output. Writing the test after the code and pasting a green run
  twice is detectable — commit order is in the PR history.
