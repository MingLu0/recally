# Recally — Development Workflow

How the project gets built: which tools hold the backlog, run the agents, and gate the work. The *what* is in the other docs; this is the *how*.

## Tooling

| Role | Choice | Why |
|---|---|---|
| Backlog | GitHub Issues + milestones | Repo already lives on GitHub; Orca opens worktrees from issues natively; zero cost |
| Agent control plane | [Orca](https://www.onorca.dev/) | Parallel worktrees, diff review with line comments back to the agent, GitHub issue/PR drawer, BYO subscription |
| Coding agent | Claude Code (any Orca-supported CLI works) | Reads `AGENTS.md` / `CLAUDE.md` |
| Agent instructions | `AGENTS.md` | Hard rules and conventions; the docs are the spec |

Not used: Linear (single-user project, paid tier + AI credits for anything beyond a board), beads (no Orca integration; would be a second backlog Orca cannot see). Linear Coding Sessions or Orca's SSH/remote mode are optional for unattended backend work only (see "Optional: unattended work").

## One-time setup

1. Prerequisites from `roadmap.md` step 0: `uv` with Python ≥ 3.10, APScheduler pinned to 3.x. Done by hand, once.
2. Commit gate fixtures. The step 1 gate needs the 2026-08-25 (326 rows) and 2026-09-04 (380 rows) exports of the same book. `data/` is gitignored, so copies live in `backend/tests/fixtures/`. An agent in a fresh worktree cannot run the gate without them.
3. Create one GitHub issue per roadmap step (1–6). Body = the step's bullets plus its **Tests** and **You verify** gates verbatim. The issue closes only after *You verify* passes, not on merge. Sub-issues where a step fans out (table below). Milestones: `Backend` (steps 1–3), `App` (steps 4–6).
4. Orca: add the repo, connect GitHub, setup script `cd backend && uv sync`.

## The per-issue loop

```
GitHub issue
  → Orca: open worktree from the issue (GitHub drawer, or
    `orca worktree create --repo id:<id> --name <slug> --issue <n>`)
  → prompt: "Implement #<n>. Read AGENTS.md and the docs it points to.
             TDD against the Tests gate. Run pytest + ruff and paste the output."
  → review the diff in Orca, leave line comments, iterate
  → PR from Orca → merge to main
  → run the step's You verify gate against the real system → issue closes
```

Rules:

- **At most 3 worktrees live at once.** One reviewer's merge bandwidth is the throttle, not agent count.
- **One gate per PR.** Nothing merges without the gate command and its output in the PR description.
- Branch names follow `AGENTS.md`: `feat/…`, `fix/…`, `docs/…`.

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
