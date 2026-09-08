# ADR-013: Stateful Scala orchestrator for parallel dispatch

**Status**: accepted, adjusted by [ADR-014](014-auto-ready-on-unblock.md) — the ready-picker is removed (the `ready` label it applied is retired) and needs-human is reconciled rather than terminal — amends [ADR-010](010-unattended-dispatch-and-auto-merge.md); builds on the gate defined by [ADR-012](012-drop-the-no-mistakes-gate.md)
**Date**: 2026-09-08

## Context
The hourly Orca automation (ADR-010) dispatches at most one sub-issue per run and has no recovery for merge conflicts between parallel tracks: a conflicting PR waits for a human even when the fix is a mechanical rebase. Throughput is bounded by the hour tick, and with the no-mistakes gate retired (ADR-012) the merge decision is fully mechanical, so the remaining human bottleneck is dispatch cadence and conflict babysitting, not judgment.

ADR-012's auto-merge boundary also has a hole: its conditions (named tests green and pasted, CI green, sub-issue) are vacuous for a **docs-only** PR, whose Tests gate is a grep checklist or a prose statement. A mechanical gate cannot judge a spec change, yet nothing stops such a PR from qualifying.

## Decision
1. **A stateful Scala driver** (`scripts/orchestrate.sc`, scala-cli + cats-effect) automates dispatch, monitoring, failover and conflict recovery:
   - Eligibility comes only from `scripts/orca-ready-issues.sh --all`; the orchestrator never decides dispatchability itself.
   - `--step=<label>` scopes dispatch to issues carrying that label (e.g. `--step=step-4`), so a new step can be trialled without opening the whole backlog to automation.
   - Up to **10** issues in flight (was 3 worktrees). Dispatch is via Orca worktrees + supervised workers; the prompt is `scripts/orca-autostart-prompt.md`.
   - State lives in `.orca/orchestrator-state.json` (gitignored) and is reconciled against GitHub every tick, so restarts never double-dispatch.
   - Rate-limit failures hot-swap the agent along a pool (`opencode` → `claude`); other failures retry once, then the issue is marked needs-human with a comment.
   - A PR marked CONFLICTING gets a rebase dispatched into the same worktree, at most twice, then it is left for a human with a comment.
   - The orchestrator **never merges**: merge authority stays with the worktree agent under the policy in `workflow.md`.
2. **The PR must carry a `## TDD evidence` section** (that exact heading): the red output of every negative assertion, captured before the implementation, followed by the green run. This is what makes ADR-012's red-output requirement mechanically checkable — by the orchestrator's nudge path and by a human scanning the PR.
3. **Docs-only PRs are never auto-merged.** The agent checks `gh pr diff <pr> --name-only`: if every changed file is under `docs/` or is Markdown, the PR is opened, commented, and left for a human. Docs-only sub-issues (e.g. spec-adoption tickets like #53) stay dispatchable; only their merge waits.
4. The hourly ADR-010 automation remains as the low-throughput default; the orchestrator is started by hand when higher throughput is wanted.
5. **The loop reports, it does not go quiet.** Every tick prints a one-line status (active issues, free slots) plus an `[action-needed]` line for anything only a human can unblock: unblocked-but-unlabelled issues in the step scope, needs-human escalations, and docs-only PRs parked for merge. A macOS notification (banner + sound) fires when the action-needed set changes; the dedup signature lives in the state file. The report is read-only and never a dispatch input — the precheck stays the only eligibility source.
6. **The ready prompt is a click, not a command.** When a new issue becomes unblocked-but-unlabelled, a native macOS list picker pops (multi-select); chosen issues get the `ready` label via `gh` and dispatch on the next tick. The `ready` label stays the only dispatch gate — the picker just removes the typing. Dismissed issues are remembered in the state file (`readyPrompted`) and not re-prompted; in loop mode the dialog never blocks a tick.

## Rationale
- **The mechanical boundary only works if it is checkable.** ADR-012 requires red output but named no location for it; without a fixed heading, neither the orchestrator nor a reviewer can verify presence without reading the whole PR. The heading costs nothing and makes the requirement enforceable.
- **Docs-only is where "tests green" means nothing.** A spec change is exactly the class of change the auto-merge boundary exists to catch, so it is excluded by file shape — mechanical, no judgment.
- **Fail-closed composition.** Every safety property still comes from components that fail closed: the precheck (eligibility), the prompt (gates and merge policy), and GitHub itself (blockers, PR state). The orchestrator's own failure mode is "stops dispatching", never "merges something".
- **Conflict recovery is a dispatch, not a merge.** Rebasing a conflicting PR is ordinary agent work in the same worktree; an integration branch or a local merge would bypass the PR gates entirely.

## Consequences
- `docs/workflow.md` gains the orchestrator section; the worktree cap rises from 3 to 10 and the auto-merge policy gains the `## TDD evidence` and docs-only conditions.
- `scripts/orca-autostart-prompt.md` gains the evidence-heading requirement and the docs-only merge exclusion; `AGENTS.md` gains the orchestrator command and the five-condition enumeration.
- scala-cli becomes a prerequisite for running the orchestrator (not for the backend or app). The Orca JSON shapes the script parses (`worker-show`, `worker-start`, `task-create`) are not contractual and are read defensively — the first real run may need field-name fixes.
- **Risk accepted deliberately:** with mechanical auto-merge and cap 10, merged-but-unverified work accumulates against parent You verify gates; the `Verifying` column will no longer be "usually empty". A bad pattern can land in several PRs before a human sees it. The mitigation is `ready`-label discipline (ADR-012) plus staging labels in waves within a step.
- Revisit when: a mechanically-merged PR lands a defect the You verify gate has to untangle, the red-output requirement is gamed, or `Verifying` grows faster than it drains.
