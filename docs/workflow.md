# Recally — Development Workflow

How the project gets built: which tools hold the backlog, run the agents, and gate the work. The *what* is in the other docs; this is the *how*.

## Tooling

| Role | Choice | Why |
|---|---|---|
| Backlog | GitHub Issues + milestones | Repo already lives on GitHub; Orca opens worktrees from issues natively; zero cost |
| Progress view | GitHub Project board "[Recally Roadmap](https://github.com/users/MingLu0/projects/2)" | One pane across parallel agents; issues stay the source of truth |
| Agent control plane | [Orca](https://www.onorca.dev/) | Parallel worktrees, diff review with line comments back to the agent, GitHub issue/PR drawer, BYO subscription |
| Coding agent | Claude Code (any Orca-supported CLI works; the orchestrator's failover pool is claude → opencode) | Reads `AGENTS.md` / `CLAUDE.md` |
| Parallel dispatcher | `scripts/orchestrate.sc` (scala-cli, ADR-013) | Hand-started, stateful: dispatches up to 10 unblocked sub-issues, retries on failure, fixes merge conflicts by rebase dispatch |
| Agent instructions | `AGENTS.md` | Hard rules and conventions; the docs are the spec |

Not used: Linear (single-user project, paid tier + AI credits for anything beyond a board), beads (no Orca integration; would be a second backlog Orca cannot see). Linear Coding Sessions or Orca's SSH/remote mode are optional for unattended backend work only (see "Optional: unattended work").

## One-time setup

1. Prerequisites from `roadmap.md` step 0: `uv` with Python ≥ 3.10, APScheduler pinned to 3.x. Done by hand, once.
2. Commit gate fixtures. The step 1 gate needs exports of the same book; the committed pair is `oreilly-annotations-a.csv` and `oreilly-annotations-b.csv`, two trimmed 15-row Chapter 9 exports derived from the real export. `data/` is gitignored, so copies live in `backend/tests/fixtures/`. An agent in a fresh worktree cannot run the gate without them.
3. Create one GitHub issue per roadmap step (1–6). Body = the step's bullets plus its **Tests** and **You verify** gates verbatim. The issue closes only after *You verify* passes, not on merge. Sub-issues where a step fans out (table below); a sub-issue carries only a Tests gate, written as the named test functions that must exist and pass plus a `Done when` checklist (ADR-012). The `roadmap-issues` skill in `.claude/skills/` carries the templates and the wiring commands. Milestones: `Backend` (steps 1–3), `App` (steps 4–6). Step 0 is a manual checklist issue, assigned to the human, in no milestone.
4. Project board `Recally Roadmap`: columns Todo / In Progress / In Review / Verifying / Done, and the built-in workflows *item added → Todo*, *PR merged → Verifying*, *item closed → Done*. There is no built-in "PR opened" trigger, so In Review is set by hand or by Orca.
5. Orca: add the repo, connect GitHub, setup script `cd backend && uv sync`.
6. scala-cli (needed only to run the orchestrator; Java 17+ suffices for it). To silence its
   outdated-dependency hints: `scala-cli config suppress-warning.outdated-dependencies-files true`
   (global, machine-level; the pinned versions in `orchestrate.sc` stay as they are).

## The per-issue loop

```
GitHub issue
  → Orca: open worktree from the issue (GitHub drawer, or
    `orca worktree create --repo id:<id> --name <slug> --issue <n>`)
  → prompt: "Implement #<n>. Read AGENTS.md and the docs it points to.
             TDD against the Tests gate. Run pytest + ruff and paste the output."
  → review the diff in Orca, leave line comments, iterate
  → commit on the feature branch; run the issue's named tests
    and paste the output in the PR
  → CI green → review and merge the PR to main
  → sub-issue: closes on merge (its Tests gate is the whole gate)
  → step issue: board → Verifying; run the You verify gate
    against the real system → issue closes
```

Rules:

- **At most 10 worktrees live at once** (raised from 3 in ADR-013, when the orchestrator took over parallel dispatch). Reviewer bandwidth is still the throttle; it moved from per-PR review to the parent You verify gates, and the dependency graph is what sequences it (ADR-014).
- **One gate per PR.** Nothing merges without the gate command and its output in the PR description.
- **No branch reaches `main` without its named tests green**, with the output pasted in the PR (ADR-012). The agent that wrote the code is the worst judge of whether it is right, so the gate is evidence rather than self-assessment: a listed test either exists and passes or it does not. The design-invariant suite (#31) runs on every PR.
- **A hard rule is Ming's call, never the agent's.** An agent that believes an `AGENTS.md` hard rule is wrong — or that its ticket asks it to work around one — stops and asks. It never quietly overrules one.
- **Verifying is not decoration.** A card there means a roadmap step is merged but unproven against the real system — real exports, running server, real provider. Fixtures cannot reach those failures. The column should usually be empty.
- Branch names follow `AGENTS.md`: `feat/…`, `fix/…`, `docs/…`.

## The two gates

`roadmap.md` defines two gates per step. They do not overlap:

| Gate | Asks | Run by | Blocks |
|---|---|---|---|
| **Tests** | Does the step's own acceptance criterion hold? | the agent, output pasted in the PR | the merge |
| **You verify** | Does it work against the real system? | Ming, after merge | the issue closing |

The *Tests* gate is the roadmap's own assertion — exact new/removed/updated counts, a 401 without the key — and
belongs in the PR description in its own words. CI (`ruff`, `mypy`, `pytest`, `bandit`, `pip-audit`) runs on every
PR and is the only automated check independent of the agent that wrote the code.

A sub-issue's Tests gate is written out as **named test functions** (ADR-012). Every ticket carries:

- **Acceptance criteria as named tests** — the list of test functions that must exist and pass. A named test is
  falsifiable in a way that prose is not.
- **A `Done when` checklist of binary statements**, several of them literal `grep` commands, so the checks a
  reviewer would eyeball are commands instead.
- **Test-first work, with the red output pasted.** Every assertion of a raise, a refusal or a negative
  (`*_raises`, `*_never_*`, `*_no_*`, `*_only_*`) is confirmed to **fail** before the implementation exists, and
  that red output goes in the PR next to the green run. A test that has never failed is not evidence.

Any listed test not written is named in the PR with a reason. Never drop one silently; if one turns out to be wrong
or unimplementable, say so and propose the replacement.

## Unattended dispatch

An hourly Orca automation ("Roadmap autostart") starts agents on unblocked sub-issues, with no
human in the loop. It is **disabled until deliberately turned on** and dispatches at most one issue per run.

### What makes an issue dispatchable

`scripts/orca-ready-issues.sh` is the automation's precheck: exit 0 (with the single
lowest-numbered dispatchable issue on stdout) starts a run, anything else skips it. All
conditions must hold (ADR-014: unblocked is the trigger; there is no human label gate).

| # | Condition | Source |
|---|---|---|
| 1 | no open blocker | GitHub native issue dependencies |
| 2 | is a sub-issue, not a parent | GraphQL `parent` / `subIssues` |
| 3 | not labelled `manual` | human-only tickets (e.g. Firebase setup) never dispatch |
| 4 | unassigned | an assignee means someone owns it |
| 5 | no open PR already closes it | prevents double dispatch on a retry |

Condition 4 also covers work in flight: the dispatched agent self-assigns its issue as its first
act (`scripts/orca-autostart-prompt.md`), so the next hourly tick skips an issue that is being
worked but has no PR yet. The script fails closed: any error prints nothing and exits 1, so a
broken query can never cause a dispatch.

Condition 2 has a filing consequence: a standalone bug found outside a step (e.g. during a You
verify run) has no parent and **never dispatches**. File such bugs as sub-issues of the "Bug inbox"
issue — it exists to give them a parent; the inbox itself is never dispatched and never closes.

### Auto-merge policy

The dispatched agent may merge its own PR **only** when all five hold (ADR-012, ADR-013):

- every test named in the ticket exists and passes, with the output pasted in the PR, and
- CI is green, and
- the PR carries a `## TDD evidence` section (that exact heading): the red output of every negative
  assertion, captured before the implementation, followed by the green run, and
- the PR is not docs-only — `gh pr diff <pr> --name-only` lists at least one file outside `docs/`
  that is not Markdown — and
- the issue is a sub-issue.

Anything else leaves the PR open with a comment naming what failed, for a human. That is an
expected outcome, not a failure. An agent that skipped a listed test, or that hit an `AGENTS.md`
hard rule it thinks is wrong, stops and says so rather than merging. A docs-only sub-issue is still
dispatchable; its PR simply always waits for a human, because a mechanical gate cannot judge a spec
change (ADR-013).

Two consequences worth being explicit about:

- **Unblocked is the trigger; the parent's You verify is the approval** (ADR-014). Work can reach
  `main` without a human seeing the diff — on the strength of the ticket's own test list. A thin
  list is a real coverage gap (ADR-012), and a bad pattern can land across a whole wave before a
  human sees one; that risk is accepted deliberately and bounded by the parent gate.
- **Parent step issues and `manual` tickets are excluded on purpose.** Parents carry the `You verify`
  gate, which only a human can run against the real system; `manual` tickets are human work by
  definition. Auto-merging or auto-dispatching either would skip the gates the roadmap says the step
  is not done without (ADR-010, ADR-014).

### Parallel dispatch: the orchestrator

The hourly automation above dispatches one issue per tick and stays the default. For higher throughput,
`scripts/orchestrate.sc` (scala-cli; ADR-013) is a stateful driver started by hand:

```sh
scala-cli scripts/orchestrate.sc                        # loop: dispatch, monitor, recover
scala-cli scripts/orchestrate.sc -- --dry-run           # print the dispatch plan, touch nothing
scala-cli scripts/orchestrate.sc -- --once              # a single tick
scala-cli scripts/orchestrate.sc -- --step=step-4       # only issues carrying that label
```

It reads eligibility from `scripts/orca-ready-issues.sh --all` (the same conditions — the
orchestrator never decides dispatchability itself), keeps up to 10 issues in flight, hot-swaps a
rate-limited agent along the pool `claude → opencode`, and dispatches a rebase into the same worktree
when a PR goes CONFLICTING (twice, then it leaves the PR for a human with a comment). State lives in
`.orca/orchestrator-state.json` (gitignored) and is reconciled against GitHub every tick, so restarting
it never double-dispatches. It never runs `gh pr merge` — merge authority stays with the worktree
agent under the five conditions above.

Loop mode is single-instance: a second start takes one look at the OS-level lock on
`.orca/orchestrator.lock` and exits with a pointer to the running instance's log (the kernel
releases the lock on exit or crash, so there is no stale-lock state). `--once` and `--dry-run`
never lock — they are safe alongside a running loop.

Logging is change-only (ADR-014): on a TTY the dashboard runs in the alternate screen, pinned at
the top with the event log scrolling beneath (flat print-on-change when piped); events (dispatches,
merges, conflicts, escalations) always print, a dim heartbeat every ten ticks proves the loop is
alive, and every event is appended to `.orca/orchestrator.log`. The panel's top section is the
project graph — a per-step tree of sub-issues with live glyphs (dispatched, PR open, blocked-with-
blocker, manual), so the dependency structure and the implementation progress are the same picture.
A panel with the run meta renders at startup and for `--dry-run`, which doubles as the "state of the
project" command. Needs-human issues are
reconciled against reality each tick — a closed issue flips to merged, an unassigned one drops back
into the dispatchable pool — so stale action-needed lines cannot outlive the situation that caused
them. A macOS notification (banner + sound) fires when the needs-you set changes.

When an issue merges, its worktree sleeps: the worker terminal is released, any remaining terminals
are closed, and the worktree moves to the completed column in Orca. The worktree itself stays for
diff browsing; disk cleanup is a manual sweep (`orca worktree rm --worktree issue:<n>`).

The dashboard's two human-gate surfaces: needs-you items are listed in the panel (and notified on
change), and when every sub-issue of a tracked parent closes, a `🔑 parent #N — ready for You verify`
row appears with a one-time notification. Worker mailbox messages are drained each tick — though a
Run dies with its process, so messages from before a restart are orphaned (accepted: nothing blocks
on the mailbox), and workers are instructed never to *ask* there; human contact is a GitHub issue
comment.

### Turning it on

The precheck runs from the repo root of the main checkout, so `scripts/` must be on `main` first.

```sh
orca automations list                       # find the id
orca automations edit <id> --enabled
orca automations run <id>                   # dry run now, without waiting for the hour
orca automations runs <id>                  # history, including skipped runs
```

## Parallelism per step

Contracts are fixed up front (`api-spec.md`, `data-model.md`, `agents.md`), so work inside a step can fan out once the blocking piece lands.

| Step | Blocking (do first) | Then in parallel |
|---|---|---|
| 1. Skeleton + ingestion | SQLAlchemy models + Alembic batch mode | O'Reilly adapter + dedupe · watcher (`on_moved`, debounce) · FastAPI skeleton + `X-API-Key` auth |
| 2. Agent pipeline | `llm.py` LiteLLM wrapper + `llm_calls` logging | Curator · Writer · Critic modules, each with its prompt file · then the Writer ⇄ Critic loop + approval queue |
| 3. FSRS + reviews | py-fsrs engine wrapper | `/reviews/due`, `/rate`, `/rate-batch` · APScheduler notifier · offline replay (ADR-005) · minimal CLI client |
| 4. Android MVP | Retrofit client + Room entities | Today · Review · Approval Queue screens |
| 5. Notifications | sequential | — |
| 6. Learner + stats | 6a-a nightly FSRS optimizer (#94) | 6a-b Stats screen (#95) · 6b-a Learner stage B: aggregates, agent, versioned writer_guidance (#99) · 6b-b leech detection + rewrites (#100, after 6b-a) |

Step 5 is small; do not fan it out. Step 6 fans out as above: 6b-b needs `LearnerResult.leech_card_ids` from a real Learner call, so it waits on 6b-a, and everything waits on real review data accumulating after 6a.

## Validation checkpoint

`roadmap.md`: use the backend with a minimal client for ~2 weeks before building the app. The minimal client is the `recally` CLI built in step 3 (`pending`, `approve <id>`, `reject <id>`, `due`, `rate <id> <1-4>`). No agent work during the checkpoint; findings go into GitHub issues labelled `pipeline-quality`. If the approval queue is not draining, that is the point to consider `AUTO_APPROVE_ROUND1_ACCEPT` (PRD; default off).

## Android

Steps 4–5 need the Mac: Gradle, emulator or device, FCM tokens. Keep them in local Orca worktrees. Backend and Android run as two parallel tracks from here on, since the API contract is proven by the checkpoint.

## Optional: unattended work

The default unattended mechanism is the local hourly automation in "Unattended dispatch". The options below are
for running agents on a machine other than this Mac.

Steps 1–3 are plain Python and do not depend on the Mac. If progress is wanted while away, either:

- Linear Coding Sessions (Basic plan + AI credits; managed sandbox, drafts a PR), or
- Orca in SSH / remote-server mode against a small VPS.

Neither is part of the default loop. Android work stays local regardless.

## Guardrails

`AGENTS.md` hard rules 1–12 are the contract every agent prompt points at: approval gate before FSRS, LLM calls only via `llm.py`, no SQLite-specific SQL, server-authoritative FSRS, bounded Writer ⇄ Critic loop. When an agent's output and a doc disagree, the doc wins.
