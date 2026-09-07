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

**If the issue is a step 2 sub-issue (#29-#35), stop and read this instead.** Step 2 runs
without `no-mistakes` (ADR-011): the ticket's list of named tests is the whole gate.

1. Write each listed test **before** the code it covers.
2. For every test asserting a raise, a refusal or a negative (`*_raises`, `*_never_*`,
   `*_no_*`, `*_only_*`), confirm it **fails** first and paste that red output in the PR
   next to the green run. A negative assertion that has never failed is not evidence.
3. Run `uv run pytest`, `uv run ruff check . && uv run ruff format --check .` and
   `uv run mypy src/` from `backend/`, and paste the output.
4. Name in the PR any listed test you did not write, and why. Never drop one silently.
5. Open the PR, comment that step 2 is the acceptance-criteria trial, and **stop. Do not
   merge** — the merge policy below does not apply to step 2, because all of its conditions
   are `no-mistakes` outcomes.

For every other step, run the gate with a rich intent — what the issue asked for plus the
decisions you made. A thin intent makes the review flag deliberate choices as mistakes:

    no-mistakes axi run --intent "<issue goal + your decisions>"

Drive the gates. **Do not pass `--yes`.**

## Merge policy — read carefully

Auto-merge is **never** allowed for a step 2 sub-issue (#29-#35). Otherwise it is allowed
ONLY when every one of these holds:

- the outcome is `checks-passed`, and
- no gate in the run produced an `ask-user` finding, and
- you never responded with `--action skip`, and
- the issue you implemented is a sub-issue (the precheck guarantees this).

If all four hold, the pipeline made no judgment call a human would need to review:

    gh pr merge <pr> --squash --delete-branch

Then comment on the issue saying it was auto-merged and why it qualified.

**Otherwise, leave the PR open.** This is the expected outcome, not a failure.
Comment on the PR with:
  - the exact `ask-user` finding text, verbatim, if there was one,
  - or the failing step and what blocks it.
Then stop. Do not merge, do not force, do not work around it. A human will pick it up.

## Never

- Never `--yes`, and never resolve an `ask-user` finding yourself. Those findings
  exist because they touch the `AGENTS.md` hard rules — several of which look like
  bugs without context (rule 7 truncated highlights, rule 12 `on_moved`,
  integer `cost_microusd`).
- Never edit files by hand while a run is active; respond `--action fix` instead.
- Never start a second issue in one run.
- Never touch a parent step issue. Those carry a `You verify` gate that only a
  human can run against the real system.
- Never merge a step 2 sub-issue (#29-#35), and never run `no-mistakes` on one.
  Step 2 is the acceptance-criteria trial (ADR-011); the human merges.
