# ADR-014: Sub-issues auto-dispatch when unblocked; the `ready` label is retired

**Status**: accepted — amends [ADR-010](010-unattended-dispatch-and-auto-merge.md) (retires the opt-in `ready` label) and [ADR-013](013-scala-orchestrator.md) (removes the ready-picker; needs-human becomes reconciled)
**Date**: 2026-09-08

## Context
ADR-010 made dispatch opt-in via a human-applied `ready` label, on the argument that *unblocked* and *the spec is settled* are different states and only a human can judge the second. In practice the label became a throughput brake: after every blocker merge, progress stalled until a human noticed and labelled — exactly the mechanical step automation exists to remove. The orchestrator's picker (ADR-013) reduced that to a click, but the click was still on the critical path of every wave.

Meanwhile the needs-human phase was write-once: `reconcile` skipped non-active phases, so an issue escalated and later resolved by other means (#55 auto-merged while marked needs-human) logged `[action-needed]` forever. And the per-tick status line repeated unchanged, burying real events in noise.

## Decision
1. **Unblocked is the trigger.** A sub-issue is dispatchable when it is open, a sub-issue, unblocked, unassigned, and has no open PR closing it. The `ready` label is retired as a dispatch condition. Parent step issues and `manual`-labelled tickets (e.g. Firebase setup) remain excluded — parents carry the You verify gate, which is now the **only** human gate.
2. **The ready-picker is removed** one day after it shipped; its only job was applying the retired label.
3. **needs-human is reconciled, not terminal.** Every tick, a needs-human entry follows reality: issue closed → `merged`; open + unassigned → dropped from tracking (immediately redispatchable, per 1); open + assigned → still reported. Escalation no longer touches labels.
4. **Logging is change-only.** The loop prints the status line only when the tracked picture changes (signature in the state file), events always print, and a dim heartbeat every 10 ticks proves the loop is alive. A boxed state panel renders at startup and for `--dry-run`.

## Rationale
- **The human gates that remain are the ones that need judgment.** Auto-merge still requires the five mechanical conditions (ADR-012/013), and every parent still waits for a human run against the real system. The label gate was scheduling, not judgment — the step-4 tickets proved their Tests gates could carry them.
- **Manual tickets needed a structural exclusion the moment the label gate fell.** `manual` joins the parent exclusion in the precheck; both fail closed.
- **A status line that repeats teaches the reader to ignore it.** Change-only output keeps the signal: silence means working, a line means something changed, ⚠️ means you.

## Consequences
- **Accepted risk:** waves fan out fully automatically (cap 10). When a blocking issue merges, every newly unblocked sub-issue dispatches on the next tick. A systematically wrong interpretation (e.g. misreading a screen artboard) can land across several PRs before a human sees one; the bound is ticket test-list quality, CI, and the parent You verify gate.
- The hourly ADR-010 automation inherits the wider dispatchability set (any unblocked, non-manual sub-issue repo-wide). Scope control for it is deliberate enablement, and for the orchestrator `--step=<label>`.
- `docs/workflow.md` loses the "`ready` vs. a blocker" section and the picker paragraph; the dispatchable-conditions table drops the label row and gains the `manual` exclusion.
- State schema: `readyPrompted` is gone, `lastStatus` added (old state files read fine via defaults).
- Revisit when: an auto-dispatched wave lands a repeated defect a label pause would have prevented, or the Verifying column outpaces the human's ability to run You verify gates.
