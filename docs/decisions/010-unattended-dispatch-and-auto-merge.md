# ADR-010: Unattended dispatch and auto-merge for sub-issues

**Status**: accepted
**Date**: 2026-09-06

## Context
Work is dispatched by hand: a human picks an issue, opens an Orca worktree, prompts an agent, reviews the PR, merges. The picking and the merging are mechanical whenever nothing surprising happens, and they only occur while a human is awake.

Orca automations support a `--precheck` (exit 0 runs, non-zero records a skipped run) and `--workspace-mode new-per-run`, which is enough to start agents on a schedule. The missing piece was a machine-readable answer to "may this issue be started?".

Two things were not machine-readable before this ADR:
- **Blockers existed only as prose** in issue bodies (`⛔ **Blocked by #14**`). GitHub's native issue-dependencies API was available but unused.
- **Readiness had no representation at all.** "Unblocked" and "the spec is settled" were the same thing to a query, though they are different states: a blocker resolves itself when the other issue closes, while readiness is a judgment only a human can make.

## Decision
An hourly Orca automation dispatches at most one sub-issue per run, gated by `scripts/orca-ready-issues.sh`. An issue is dispatchable only when it is labelled `ready`, has no open blocker, is a sub-issue, is unassigned, and has no open PR closing it.

- Blockers live in GitHub's native issue dependencies, never in a label.
- Readiness is the `ready` label, applied by a human.
- At-most-one is structural, not prompt-enforced: the precheck prints only the single lowest-numbered unblocked candidate, so a run cannot receive two issues.
- The dispatched agent self-assigns its issue before doing any work and aborts if the assignment fails, so condition 4 (unassigned) closes the window between dispatch and PR creation.
- The dispatched agent may **merge its own PR** only when the no-mistakes outcome is `checks-passed`, no gate produced an `ask-user` finding, it never responded `--action skip`, and the issue is a sub-issue. Otherwise the PR stays open with the finding quoted verbatim.
- Parent step issues are never dispatched and never auto-merged.
- The automation ships **disabled**.

## Rationale
- **The label is opt-in because the failure directions are not symmetric.** An opt-out label (`needs-spec`) fails open: forget it, and an unattended agent starts on an unsettled spec. Opt-in fails closed: forget it, nothing happens, and the omission is visible on the board. For an unattended loop, only fail-closed is defensible.
- **Blockers are derived, readiness is a judgment.** A "blocked" label would need manual removal when the blocker closed, and would go stale. The dependencies API resolves itself.
- **The parent exclusion protects the `You verify` gate.** `roadmap.md` says a step is not done until a human checks it against the real system — real exports, running server, real provider. Sub-issues carry only a Tests gate, which is exactly what the pipeline runs, so auto-merging one skips nothing. Auto-merging a parent would skip the gate that exists because fixtures cannot reach those failures.
- **`ask-user` is the right auto-merge boundary.** The pipeline marks a finding `ask-user` when it challenges deliberate intent or changes product behaviour — where the `AGENTS.md` hard rules live. `auto-fix` findings are mechanical by the pipeline's own classification, so blocking on them would leave trivial fixes waiting overnight.
- **The precheck must exclude more than blockers.** Verified against live data: a dependencies-only check would have dispatched three wrong issues immediately — the parent step issue #12, plus #16 and #17 whose blocker edges were still unrecorded.

## Consequences
- **`ready` becomes the approval, not a scheduling hint.** Once applied, that work can reach `main` without a human reading the diff. This deliberately removes the reviewer throttle described in `workflow.md`.
- The safety of auto-merge is bounded by the review step. Sub-issues in step 1 have fixture tests with exact counts; step 2 is weaker, because `AGENTS.md` forbids tests that call a real provider, so no automated gate can judge card *quality*. Apply `ready` accordingly.
- `scripts/orca-ready-issues.sh` must be on `main` before the automation can fire; it runs from the main checkout, not a worktree.
- The worktree cap in `workflow.md` ("at most 3 live") is not enforced by the precheck. It was already exceeded by hand before this ADR, and enforcing it was explicitly declined.
- Revisit when: an auto-merged PR causes a regression a human would have caught, `ask-user` escalations become noise, or unreviewed merged work starts outpacing the human's ability to follow what landed.
