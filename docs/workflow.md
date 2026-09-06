# Recally — Development Workflow

How the project gets built: which tools hold the backlog, run the agents, and gate the work. The *what* is in the other docs; this is the *how*.

## Tooling

| Role | Choice | Why |
|---|---|---|
| Backlog | GitHub Issues + milestones | Repo already lives on GitHub; Orca opens worktrees from issues natively; zero cost |
| Progress view | GitHub Project board "[Recally Roadmap](https://github.com/users/MingLu0/projects/2)" | One pane across parallel agents; issues stay the source of truth |
| Agent control plane | [Orca](https://www.onorca.dev/) | Parallel worktrees, diff review with line comments back to the agent, GitHub issue/PR drawer, BYO subscription |
| Coding agent | Claude Code (any Orca-supported CLI works) | Reads `AGENTS.md` / `CLAUDE.md` |
| Pre-push gate | [no-mistakes](https://github.com/kunchenguid/no-mistakes) | Runs review, test, lint, docs, PR and CI as one pipeline; the agent that wrote the code does not get to declare it good |
| Agent instructions | `AGENTS.md` | Hard rules and conventions; the docs are the spec |

Not used: Linear (single-user project, paid tier + AI credits for anything beyond a board), beads (no Orca integration; would be a second backlog Orca cannot see). Linear Coding Sessions or Orca's SSH/remote mode are optional for unattended backend work only (see "Optional: unattended work").

## One-time setup

1. Prerequisites from `roadmap.md` step 0: `uv` with Python ≥ 3.10, APScheduler pinned to 3.x. Done by hand, once.
2. Commit gate fixtures. The step 1 gate needs the 2026-08-25 (326 rows) and 2026-09-04 (380 rows) exports of the same book. `data/` is gitignored, so copies live in `backend/tests/fixtures/`. An agent in a fresh worktree cannot run the gate without them.
3. Create one GitHub issue per roadmap step (1–6). Body = the step's bullets plus its **Tests** and **You verify** gates verbatim. The issue closes only after *You verify* passes, not on merge. Sub-issues where a step fans out (table below); a sub-issue carries only a Tests gate. Milestones: `Backend` (steps 1–3), `App` (steps 4–6). Step 0 is a manual checklist issue, assigned to the human, in no milestone.
4. Project board `Recally Roadmap`: columns Todo / In Progress / In Review / Verifying / Done, and the built-in workflows *item added → Todo*, *PR merged → Verifying*, *item closed → Done*. There is no built-in "PR opened" trigger, so In Review is set by hand or by Orca.
5. `no-mistakes init` once for the repository, from a checkout with an `origin` remote. It registers a bare gate repo and adds a `no-mistakes` remote; Orca worktrees share the same git config, so they inherit it and do not need their own init. `no-mistakes doctor` must show a running daemon and at least one runnable agent.
6. Orca: add the repo, connect GitHub, setup script `cd backend && uv sync`.

## The per-issue loop

```
GitHub issue
  → Orca: open worktree from the issue (GitHub drawer, or
    `orca worktree create --repo id:<id> --name <slug> --issue <n>`)
  → prompt: "Implement #<n>. Read AGENTS.md and the docs it points to.
             TDD against the Tests gate. Run pytest + ruff and paste the output."
  → review the diff in Orca, leave line comments, iterate
  → commit on the feature branch, then
    `no-mistakes axi run --intent "<the issue's goal, in the issue's words>"`
    → drive each gate; `ask-user` findings come back to Ming
  → outcome `checks-passed` → review and merge the PR to main
  → sub-issue: closes on merge (its Tests gate is the whole gate)
  → step issue: board → Verifying; run the You verify gate
    against the real system → issue closes
```

Rules:

- **At most 3 worktrees live at once.** One reviewer's merge bandwidth is the throttle, not agent count.
- **One gate per PR.** Nothing merges without the gate command and its output in the PR description.
- **No branch reaches `main` without `no-mistakes`.** The agent that wrote the code is the worst judge of whether it is right; the pipeline reviews, tests and lints it independently before the PR exists.
- **`ask-user` findings are Ming's call, never the agent's.** The pipeline marks a finding `ask-user` when it challenges deliberate intent or changes product behaviour — which is exactly where the `AGENTS.md` hard rules live. The agent relays the finding verbatim and waits.
- **Verifying is not decoration.** A card there means a roadmap step is merged but unproven against the real system — real exports, running server, real provider. Fixtures cannot reach those failures. The column should usually be empty.
- Branch names follow `AGENTS.md`: `feat/…`, `fix/…`, `docs/…`.

## The no-mistakes gate

`roadmap.md` defines two gates per step. no-mistakes adds a third that sits before both:

| Gate | Asks | Run by | Blocks |
|---|---|---|---|
| no-mistakes | Is this change well-made? | the pipeline, driven by the agent | the PR opening |
| **Tests** | Does the step's own acceptance criterion hold? | the agent, output pasted in the PR | the merge |
| **You verify** | Does it work against the real system? | Ming, after merge | the issue closing |

They do not overlap. no-mistakes runs a generic review, the repo's own test and lint commands, and CI; it does not
know what step 1 is supposed to prove. The *Tests* gate is the roadmap's own assertion — exact new/removed/updated
counts, a 401 without the key — and still belongs in the PR description in its own words. Passing the pipeline is
not evidence the Tests gate was met.

Running it:

```sh
no-mistakes axi run --intent "<what the issue asked for, plus the decisions made along the way>"
no-mistakes axi respond --action fix --findings <ids>   # mechanical, agent decides
no-mistakes axi respond --action approve                # accept the step
no-mistakes axi status                                  # progress, without disturbing the run
```

Notes that matter here:

- **`--intent` is not a diff summary.** It is what the issue asked for and what was decided while doing it. A thin
  intent makes the review flag deliberate choices as mistakes — and this repo is full of choices that look wrong
  without context: `cost_microusd` instead of a float, truncated highlights left unreconstructed (hard rule 7),
  `on_moved` instead of `on_created` (hard rule 12). Say so in the intent.
- **Never fix a finding by hand mid-run.** The pipeline owns both the finding and the fix; editing the worktree
  under it discards its work. Respond with `--action fix`.
- `checks-passed` means the PR is green and waiting for a human merge — that is the agent's stopping point.
- **`--yes` is off by default here.** It auto-resolves `ask-user` findings without asking, which is precisely the
  class of finding that would let an agent quietly overrule a hard rule. Use it only when explicitly asked.

Steps skip individually (`--skip=lint`) when there is a reason. Docs-only branches are the common case: review and
document earn their keep, `pytest` has nothing to say about a Markdown change.

## Unattended dispatch

An hourly Orca automation ("Roadmap autostart") starts agents on sub-issues that are ready, with no
human in the loop. It is **disabled until deliberately turned on** and dispatches at most one issue per run.

### What makes an issue dispatchable

`scripts/orca-ready-issues.sh` is the automation's precheck: exit 0 (with the single
lowest-numbered dispatchable issue on stdout) starts a run, anything else skips it. All five
conditions must hold.

| # | Condition | Source |
|---|---|---|
| 1 | labelled `ready` | a human — the only judgment in the list |
| 2 | no open blocker | GitHub native issue dependencies |
| 3 | is a sub-issue, not a parent | GraphQL `parent` / `subIssues` |
| 4 | unassigned | an assignee means someone owns it |
| 5 | no open PR already closes it | prevents double dispatch on a retry |

Condition 4 also covers work in flight: the dispatched agent self-assigns its issue as its first
act (`scripts/orca-autostart-prompt.md`), so the next hourly tick skips an issue that is being
worked but has no PR yet. The script fails closed: any error prints nothing and exits 1, so a
broken query can never cause a dispatch.

### `ready` vs. a blocker

These are different states and live in different places:

- **Blocked** — waiting on another issue. Recorded in GitHub's native issue dependencies
  (`gh api repos/{owner}/{repo}/issues/{n}/dependencies/blocked_by`) and *derived*: it resolves itself when
  the blocker closes. Never a label; a label would go stale.
- **Not ready** — nothing blocks it, but the spec is not settled. Only a human knows this, so it is the
  `ready` label.

The label is deliberately **opt-in**. Forgetting to add it means nothing happens (visible on the board);
an opt-out `needs-spec` label would mean forgetting it lets an agent start on an unsettled spec unattended.

### Auto-merge policy

The dispatched agent runs the no-mistakes gate and may merge its own PR **only** when all four hold:

- outcome is `checks-passed`, and
- no gate produced an `ask-user` finding, and
- it never responded `--action skip`, and
- the issue is a sub-issue.

Anything else leaves the PR open with a comment naming the finding verbatim, for a human. That is the
expected outcome, not a failure.

Two consequences worth being explicit about:

- **`ready` is now the approval, not a scheduling hint.** Once an issue carries it, work can reach `main`
  without a human seeing the diff. `auto-fix` findings still auto-merge; they are mechanical by the
  pipeline's own classification.
- **Parent step issues are excluded on purpose.** They carry the `You verify` gate, which only a human can
  run against the real system. Auto-merging one would skip the gate the roadmap says the step is not done
  without (ADR-010).

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
| 6. Learner + stats | sequential | — |

Steps 5–6 are small and depend on real review data; do not fan them out.

## Validation checkpoint

`roadmap.md`: use the backend with a minimal client for ~2 weeks before building the app. The minimal client is the `recally` CLI built in step 3 (`pending`, `approve <id>`, `reject <id>`, `due`, `rate <id> <1-4>`). No agent work during the checkpoint; findings go into GitHub issues labelled `pipeline-quality`. If the approval queue is not draining, that is the point to consider `AUTO_APPROVE_ROUND1_ACCEPT` (PRD; default off).

## Android

Steps 4–5 need the Mac: Gradle, emulator or device, FCM tokens. Keep them in local Orca worktrees. Backend and Android run as two parallel tracks from here on, since the API contract is proven by the checkpoint.

## Optional: unattended work

Steps 1–3 are plain Python and do not depend on the Mac. If progress is wanted while away, either:

- Linear Coding Sessions (Basic plan + AI credits; managed sandbox, drafts a PR), or
- Orca in SSH / remote-server mode against a small VPS.

Neither is part of the default loop. Android work stays local regardless.

## Guardrails

`AGENTS.md` hard rules 1–12 are the contract every agent prompt points at: approval gate before FSRS, LLM calls only via `llm.py`, no SQLite-specific SQL, server-authoritative FSRS, bounded Writer ⇄ Critic loop. When an agent's output and a doc disagree, the doc wins.
