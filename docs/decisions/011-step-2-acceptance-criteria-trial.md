# ADR-011: Step 2 runs on written acceptance criteria, without no-mistakes

**Status**: accepted
**Date**: 2026-09-07

## Context
ADR-009 made `no-mistakes axi run` the pre-PR gate on every branch, for a good reason: an agent judging its own output is the weakest link in the loop, and the reviewer's time is the project's scarcest resource. Step 1 was built that way.

It is expensive. The pipeline runs a separate review pass with a separate model on every branch, and the token cost is paid per PR whether or not the diff had anything wrong with it. ADR-009's own "revisit when" clause names the trigger: *the gate blocks more than it catches*.

There is also a cheaper substitute available for step 2 specifically, which was not available for step 1. Step 2's correctness is unusually structural — ADR-007's protocol boundary, the import rules in `backend.md` "Layering", hard rule 1 (no agent can set a card status), hard rule 9 (round counting lives in the runner). Those are not matters of taste that need a reviewer's judgment; they are assertions that can be written as tests and left in the repo, where they keep failing for every later change. A generic review pass finds them once, in one diff, and forgets.

What a review pass does catch and a test list does not is the thing nobody wrote a test for. That is the open question, and it is why this is a trial rather than a reversal.

## Decision
**Roadmap step 2 (#28 and its sub-issues #29–#35) runs without the no-mistakes gate.** Nothing about this extends to any other step: ADR-009 stands for steps 1 and 3–6 unless a later ADR says otherwise.

In its place, each step-2 sub-issue carries:
- **Acceptance criteria as named tests.** The ticket lists the test functions that must exist and pass. A named test is falsifiable in a way that prose is not.
- **A `Done when` checklist of binary statements**, several of them literal `grep` commands, so the checks a reviewer would eyeball are commands instead.
- **A `How to work this ticket` section** requiring test-first work, requiring the **red** output of every negative assertion (`*_raises`, `*_never_*`, `*_no_*`, `*_only_*`) to be pasted next to the green run, and requiring any listed test not written to be named in the PR with a reason.
- **#31, the design-invariant tests**, landing before the agent sub-issues so ADR-007's seams are enforced on every PR in this step and every step after it.

**No step-2 sub-issue is auto-merged.** ADR-010's four auto-merge conditions are all no-mistakes outcomes, so they cannot be satisfied here; rather than restate them in weaker terms, a step-2 PR stays open for the human. An unattended agent opens the PR, comments that step 2 is the acceptance-criteria trial, and stops.

## Rationale
- **Falsifiability is the property that matters, and only one of the two gates has it.** "Did you follow the process?" has no artifact and can be claimed. "Does `test_hallucinated_highlight_id_raises` exist and pass?" cannot. Moving the gate from a review pass to a named test list moves it from self-report to evidence — which is the same objection ADR-009 raised, applied to itself.
- **The invariant tests compound; a review pass does not.** #31 fails on step 3's PRs, and on a variant someone adds in six months. The review that would have caught the same violation runs once and is gone. For a repo whose whole design rests on ADR-007's seams, the durable form is worth more than the one-shot form.
- **A test that has never failed is not evidence.** The red-output requirement is the one piece of test-first discipline that leaves an artifact, so it is the piece that is mandated rather than merely instructed. A vacuous test is the cheapest way to make a ticket look finished.
- **Step 2 is the right trial and the wrong place to auto-merge.** ADR-010 already flagged step 2 as the weaker case for unattended merging: `AGENTS.md` forbids tests that call a real provider, so no automated gate can judge card *quality* (ADR-010, consequences). Keeping the human on the merge preserves the one independent check that remains, and without it the trial has no readout.
- **Scoping to one step keeps the decision reversible.** Step 2 is seven sub-issues against a settled spec — enough evidence to judge on, small enough to absorb if the answer is that the gate was earning its cost.

## Consequences
- `workflow.md` gains a step-2 exception in the Tooling row, the per-issue loop, the loop rules, the gate section and the auto-merge policy; `AGENTS.md` and `scripts/orca-autostart-prompt.md` gain the same carve-out. ADR-009 stays `accepted` — it is narrowed for one step, not superseded.
- **Step 2 PRs consume human review time that step 1's did not.** That is the cost being traded for the token cost, and it is the reason this is scoped to one step rather than adopted.
- **A defect class that no listed test covers can now reach `main` in step 2.** This is the risk the trial exists to measure, not an oversight. It is bounded by the human merge.
- Whoever writes a step-2 ticket owns the test list. A thin list is now a real gap in coverage, not just a thin ticket.
- **Judging the trial** — recorded at #28's `You verify` gate, before it closes: how many sub-issue PRs needed a correction a review would have caught, how many listed tests were dropped or silently substituted, and whether any red output was missing. That evidence decides whether steps 3–6 keep this arrangement or restore ADR-009 in full.
- Revisit at the end of step 2, on that evidence. Sooner if a step-2 PR lands a defect that a generic review pass would plainly have caught.
