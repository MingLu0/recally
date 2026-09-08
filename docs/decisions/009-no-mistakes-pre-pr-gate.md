# ADR-009: no-mistakes as the pre-PR gate

**Status**: superseded by [ADR-012](012-drop-the-no-mistakes-gate.md), which drops the gate entirely
**Date**: 2026-09-06

## Context
Work on this repo is done by parallel coding agents in Orca worktrees, reviewed by one human. That human's review bandwidth is the throttle on the whole project (`workflow.md`, "at most 3 worktrees live at once"), and every PR arrives claiming its own gate passed — self-assessed by the agent that wrote the code.

The roadmap already defines two gates per step: **Tests** (fixture-based, gates the merge) and **You verify** (human, real system, gates the issue closing). Neither catches the middle ground: a change that passes its own step-specific assertion while still being badly made — an ignored error, a hard rule quietly worked around, a lint failure that only surfaces in CI after the review time was already spent.

`no-mistakes` is a local pipeline (intent, rebase, review, test, document, lint, push, PR, CI) that validates committed work on a feature branch before the PR exists, driven by the agent through `no-mistakes axi`.

## Decision
Every branch passes `no-mistakes axi run` before its PR opens. Specifically:
- `no-mistakes init` is part of one-time setup, once for the repository. Orca worktrees share the git config and inherit the `no-mistakes` remote.
- The agent drives the gates, but **relays every `ask-user` finding to the human verbatim and waits.**
- `--yes` is not used by default. It auto-resolves `ask-user` findings without asking.
- `--intent` carries what the issue asked for plus the decisions made, not a summary of the diff.
- The Tests gate is unchanged and still pasted into the PR in its own words.

## Rationale
- The reviewer is the bottleneck, so the cheapest win is anything that stops an obviously-flawed diff from consuming a review slot. The pipeline runs the repo's own `ruff`/`mypy`/`pytest` before the human looks, not after.
- Independence is the point. An agent judging its own output is the weakest link in the loop; the review step is a separate pass with a separate model.
- `ask-user` maps almost exactly onto this repo's failure mode. The pipeline marks a finding `ask-user` when it challenges deliberate intent or changes product behaviour — which is where the `AGENTS.md` hard rules live. Several of them look like bugs to a reviewer without context: truncated highlights left unreconstructed (rule 7), `on_moved` over `on_created` (rule 12), `cost_microusd` over float dollars. Escalating rather than auto-fixing keeps an agent from "correcting" a hard rule.
- It replaces nothing. The three gates ask different questions and fail in different ways.

## Consequences
- `workflow.md` gains a Tooling row, a setup step, two loop rules and a "The no-mistakes gate" section; `AGENTS.md` gains the obligation and the command.
- A local daemon and a runnable pipeline-agent binary become a prerequisite for contributing. `no-mistakes doctor` reports both. This is a real dependency on a third-party tool in the critical path of every merge.
- Wall-clock per PR goes up — review, test and CI steps take minutes each. The trade is agent time for human time, which is the scarce one here.
- Docs-only branches skip the steps that have nothing to say about Markdown (`--skip=test,lint`).
- Revisit when: the gate blocks more than it catches, `ask-user` escalations become noise rather than signal, or the project stops being one reviewer against parallel agents.
