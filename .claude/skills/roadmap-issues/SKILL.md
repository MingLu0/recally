---
name: roadmap-issues
description: Create the GitHub issue set for a roadmap step in the recally repo - a parent step issue plus its sub-issues, with labels, milestone, native sub-issue links and native blocked_by dependencies. Use when the user says "create issues for step N", "break down step N", "file the roadmap issues", or asks to turn a docs/roadmap.md step into tickets.
---

# Roadmap step → GitHub issues (recally)

Repo `MingLu0/recally`. Turns one `docs/roadmap.md` step into a parent issue plus sub-issues, wired so `scripts/orca-ready-issues.sh` can dispatch them.

**This skill owns the mechanics: format, labels, wiring, verification. It does not write your test lists.** See *What you must supply* — that part is the gate now (ADR-012), and it comes from reading the specs, not from this file.

## Step 1 — Read before writing anything

The docs are the spec. Read, for the step you are filing:

- `docs/roadmap.md` — the step's bullets, its **Tests** and **You verify** gates. Both go into the parent **verbatim**.
- `docs/workflow.md`, *Parallelism per step* — the blocking piece and what fans out after it.
- Whatever the step's area doc is: `backend.md`, `android.md`, `api-spec.md`, `data-model.md`, `agents.md`. Plus `docs/design/design-system.md` and `docs/design/issue-context.md` for anything with a screen.
- The relevant ADRs. They carry constraints that belong in tickets as named tests.

Then check what already exists — never assume:

```sh
gh issue list --limit 100 --state all --json number,title,state
gh label list
gh api repos/MingLu0/recally/milestones --jq '.[] | "\(.number) \(.title)"'
```

Create any missing label before filing. Milestones: `Backend` (steps 1–3), `App` (steps 4–6).

```sh
gh label create step-N --description "Roadmap step N - <name>" --color 0e8a16
```

Existing track labels: `backend` `#1d76db`, `android` `#3ddc84`. Role labels: `blocking` `#b60205`, `ready` `#0e8a16`, `manual` `#fbca04`.

## Step 2 — Ask the human what the skill cannot decide

Before writing bodies, settle anything that changes the issue set. Real examples from step 4:

- A design element needs an API field no endpoint returns → resolve the gap first, or **scope the element out**? Never invent a field.
- A doc and a design disagree → which gives way, and does a docs sub-issue land first to settle it?
- The step's scope vs. what the roadmap literally lists (step 4's bullets omit Decks, which its ADR-008 bullet implies).

Use `AskUserQuestion`. These change the work; guessing wastes a filing round.

## Step 3 — Write the bodies

Titles: parent `Step N: <lowercase name>`, sub-issue `Step Na: <descriptor>`. Letters are by topic, not creation order.

Doc links use the repo-relative form so they resolve from an issue body:
`[android.md](../blob/main/docs/android.md)`. Issue references are bare `#52`.

### Parent

````markdown
Roadmap step N — [docs/roadmap.md](../blob/main/docs/roadmap.md), "N. <name>".

Parent issue. The work fans out into the sub-issues below, per [docs/workflow.md](../blob/main/docs/workflow.md), "Parallelism per step". Depends on <prior step / prerequisite>.

## Scope
<the roadmap bullets, edited only where a planning decision changed them>

## Tests (merge gate — paste the command and its output in each PR)
<verbatim from roadmap.md>

The gate is split across the sub-issues: <which one owns the end-to-end assertions>.

## You verify (human gate — run after merge; this issue closes only when it passes)
<verbatim from roadmap.md>

## Order
```
<ASCII diagram: blocking pieces, then the parallel fan-out, then the finishers>
```

## Scoped out
<table: what is deliberately not built and why — omit if nothing is>
````

The parent gets `step-N` + track label, its milestone, and **never `ready`**. Parents are never dispatched and never auto-merged (ADR-010): they carry the `You verify` gate only a human can run.

### Sub-issue

```markdown
Sub-issue of the step N parent (#<parent>). **Blocking** — <what depends on it>.
   (or: Parallel with #X and #Y — at most 3 worktrees live at once.)

<## Design context — for any screen ticket: paste docs/design/issue-context.md
  below its line-5 `---`, rewriting doc links to ../blob/main/ form>

## Scope
Spec: [<doc>](../blob/main/docs/<doc>), "<section>".
<bullets naming real file paths>

## Scoped out — do not build
<only if applicable; name the gap and why>

## Tests — these must exist and pass
`<test file path>`
- [ ] `test_name` — what it asserts, concretely.

## Done when
- [ ] <binary statements; several literal `grep` commands>
- [ ] `<test command>` — green, output pasted in the PR.

## How to work this ticket
<the ADR-012 block below, verbatim>
```

A sub-issue carries **only** a Tests gate — never a `You verify` (`workflow.md`).

### The `How to work this ticket` block — paste verbatim

```markdown
## How to work this ticket

The tests above are the whole quality gate (ADR-012), so they have to be written in a way that proves they work — a test that passes because it asserts nothing is worse than no test.

Work test-first:

1. Write each test from the list above **before** the code it covers, and run it.
2. For every test asserting a **raise, a refusal, or a negative** (`*_raises`, `*_never_*`, `*_no_*`, `*_only_*`), confirm it **fails** against the not-yet-written implementation, and paste that red output in the PR next to the green run. A negative assertion that has never failed is not evidence.
3. Then implement until green.
4. In the PR, list any test from the ticket you did **not** write, and why. Do not silently drop one.

If a test in the list turns out to be wrong or unimplementable, say so in the PR and propose the replacement — do not quietly substitute it. The docs are the spec; if the ticket and a doc disagree, the doc wins (`AGENTS.md`).
```

## Step 4 — Create, then wire

Write each body to a file and pass `--body-file`; heredocs inside `gh issue create` mangle backticks and tables.

```sh
gh issue create --title "Step N: <name>" --body-file parent.md \
  --label <track> --label step-N --milestone <Backend|App>
```

Then the two native APIs. **Both take the global `.id`, not the issue number, and `-F` (integer), not `-f`.**

```sh
# sub-issue link
gh api repos/MingLu0/recally/issues/<parent>/sub_issues -X POST \
  -F sub_issue_id="$(gh api repos/MingLu0/recally/issues/<child> -q .id)"

# dependency
gh api repos/MingLu0/recally/issues/<blocked>/dependencies/blocked_by -X POST \
  -F issue_id="$(gh api repos/MingLu0/recally/issues/<blocker> -q .id)"
```

Blockers live **only** in the dependencies API, never in a label — a label would go stale, while a dependency resolves itself when the blocker closes (`workflow.md`, "`ready` vs. a blocker").

**Two traps, both hit in real use:**

1. The POST response echoes the **subject** issue, not the blocker, so success looks wrong. Never read it as confirmation.
2. Setting several blockers on one issue in a loop can silently no-op after the first. **Always read back and re-apply what is missing.**

```sh
for n in <all sub-issues>; do
  printf "#%-4s %s\n" "$n" \
    "$(gh api repos/MingLu0/recally/issues/$n/dependencies/blocked_by --jq '[.[].number]|@json')"
done
```

## Step 5 — Verify

Do not report done until all four pass:

```sh
# 1. the set exists, correctly labelled
gh issue list --milestone <M> --state open --json number,title,labels

# 2. sub-issues link to the parent — exactly the expected list
gh api repos/MingLu0/recally/issues/<parent>/sub_issues --jq '.[] | "\(.number) \(.title)"'

# 3. every blocker landed (see the read-back loop above)

# 4. the precheck agrees — the real proof the wiring is machine-readable
./scripts/orca-ready-issues.sh; echo "exit=$?"
```

`ready` goes only on sub-issues whose spec is genuinely settled and unblocked. It is opt-in and load-bearing: it is now the approval for work to reach `main` without a human reading the diff. Everything else stays unlabelled until its blockers land.

Exit 1 with no output means nothing is dispatchable — correct when no issue carries `ready`, not a failure.

## What you must supply

The skill gives you the shape. These come from reading the specs, and a weak version of any is a real defect:

- **The named tests.** Since ADR-012 the ticket's test list is the whole merge gate. Derive them from the docs' hard constraints — the ones that look like bugs without context. Step 4's review ticket asserts `response_ms` is measured flip-to-rate, that no interval hint appears on Good/Easy (hard rule 5), and that a card at `step` 1 does not restart at step 0. None of that is guessable from the format.
- **Real file paths** in `## Scope`, from the area doc's project-structure section.
- **The `Done when` greps.** Turn each invariant into a command: `grep -rn "import litellm" backend/src/recally/` returning only `llm.py`; `grep -rn "Modifier.shadow" android/` returning nothing.
- **The scoped-out list**, when the design outruns the API. Name the gap, say what is not built, and say why. Never invent a field to fill a screen.

## Gotchas

- **`--milestone` takes the title** (`App`), not the number.
- **`gh issue list` can serve a stale read** right after a close. Re-query, or check `gh issue view <n> --json state`.
- **An interrupt does not un-run completed commands.** If a create loop is interrupted, list the milestone and clean up what landed.
- **Never put a `You verify` gate on a sub-issue**, and never label a parent `ready`.
