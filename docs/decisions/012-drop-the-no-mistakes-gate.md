# ADR-012: Drop the no-mistakes gate; named tests are the gate on every step

**Status**: accepted
**Date**: 2026-09-08

## Context
[ADR-009](009-no-mistakes-pre-pr-gate.md) made `no-mistakes axi run` the pre-PR gate on every branch: an agent judging its own output is the weakest link in the loop, and one reviewer's time is the project's scarcest resource. Step 1 was built that way.

[ADR-011](011-step-2-acceptance-criteria-trial.md) suspended it for roadmap step 2 as a scoped trial, replacing it with acceptance criteria written as named test functions, a `Done when` checklist of binary statements, and a requirement that the **red** output of every negative assertion be pasted next to the green run. It set its own readout: judge at #28's `You verify` gate, then decide whether steps 3–6 keep the arrangement or restore ADR-009 in full.

Step 2 shipped seven sub-issues that way (#29–#35, all merged). The substitute held, and the gate is not being carried into future work. ADR-009's own *revisit when* clause names the trigger — *the gate blocks more than it catches* — and its cost is paid per PR whether or not the diff had anything wrong with it: a separate review pass with a separate model on every branch, plus a local daemon in the critical path of every merge.

## Decision
**No branch runs `no-mistakes`.** ADR-009 is superseded and the tool leaves the workflow entirely.

ADR-011's mechanics are generalized from step 2 to **every step**. Each sub-issue carries:
- **Acceptance criteria as named tests.** The ticket lists the test functions that must exist and pass. A named test is falsifiable in a way that prose is not.
- **A `Done when` checklist of binary statements**, several of them literal `grep` commands, so the checks a reviewer would eyeball are commands instead.
- **A `How to work this ticket` section** requiring test-first work, requiring the **red** output of every negative assertion (`*_raises`, `*_never_*`, `*_no_*`, `*_only_*`) to be pasted next to the green run, and requiring any listed test not written to be named in the PR with a reason.

The roadmap's two gates are unchanged and are now the whole ladder: **Tests** gates the merge, **You verify** gates the issue closing. CI (`ruff`, `mypy`, `pytest`, `bandit`, `pip-audit` on every PR) is untouched and is now the only automated check independent of the agent that wrote the code.

**Auto-merge survives, re-based on the Tests gate.** [ADR-010](010-unattended-dispatch-and-auto-merge.md)'s four conditions were all no-mistakes outcomes and can no longer be evaluated. A dispatched agent may merge its own PR only when its named tests are green with the output pasted, CI is green, and the issue is a sub-issue. Parent step issues are still never dispatched and never auto-merged — they carry the `You verify` gate.

## Rationale
- **Falsifiability is the property that matters, and only one of the two gates has it.** "Did the pipeline pass?" is a claim about a process; "does `test_hallucinated_highlight_id_raises` exist and pass?" is checkable from the repo months later. This is ADR-009's own objection — that an agent should not self-report — applied to the gate itself.
- **Invariant tests compound; a review pass does not.** #31's design-invariant tests fail on step 3's PRs and on a variant added in six months. A review that would have caught the same violation runs once and is gone. For a repo whose design rests on ADR-007's seams, the durable form is worth more.
- **A test that has never failed is not evidence.** The red-output requirement is the one piece of test-first discipline that leaves an artifact, which is why it is mandated rather than merely instructed. A vacuous test is the cheapest way to make a ticket look finished.
- **The Tests gate is the right auto-merge boundary.** It is the roadmap's own acceptance criterion, written per ticket by whoever specified the work, and it is evidence rather than self-assessment. `ask-user` was the old boundary because the pipeline classified findings; with no pipeline there is no classification, and the per-ticket test list is the thing that remains checkable.
- **One reviewer, one arrangement.** Two regimes — gated steps and trial steps — meant every rule in `workflow.md`, `AGENTS.md` and the autostart prompt carried a step-2 carve-out. Uniform rules are what an unattended agent can actually follow.

## Consequences
- **A defect class that no listed test covers can reach `main` on any step.** ADR-011 recorded this risk bounded to one step; it is now general. It is bounded only by CI, by the `You verify` gate on each parent, and by Ming applying `ready` deliberately.
- **Whoever writes a ticket owns its test list.** A thin list is a real coverage gap, not just a thin ticket. This is the main place quality is now decided.
- `workflow.md` loses its Tooling row, a setup step, two loop rules, the gate section and the pipeline's share of the auto-merge policy; `AGENTS.md` and `scripts/orca-autostart-prompt.md` lose the command and the step-2 carve-out. ADR-010 is amended, not superseded.
- A third-party daemon and a runnable pipeline-agent binary stop being a prerequisite for contributing.
- Wall-clock per PR drops, and so does token cost per PR. The trade is that nothing independent reads the diff before Ming does.
- Revisit when: an auto-merged PR lands a defect a review pass would plainly have caught, or unreviewed merged work starts outpacing Ming's ability to follow what landed.
