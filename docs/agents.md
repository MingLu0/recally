# Recally — Multi-Agent Design

Plain Python modules (no orchestration framework) for v1; see ADR-001. Each "agent" is a module with an LLM call, a prompt file, and structured inputs/outputs.

## Roster

### 1. Ingestion Agent — deterministic
No LLM. Watch folder → adapter → dedupe → `highlights` rows with `processed=false`.

### 2. Curator Agent — LLM
**Input**: batch of unprocessed highlights for one chapter, in export order (newest first; within a day, reverse creation order, which approximates reverse reading order).
**Jobs**:
- **Filter**: drop low-value highlights — bare headings with no sibling context, navigation text, isolated short phrases. Real data (two exports, 380 rows): ~7% of rows are under 40 characters, none are figure/table references. Junk is a small minority, so the Curator should default to `keep`.
- **Group**: fold sibling highlights that only make sense together into one curated unit. The observed case is a run of headings highlighted in sequence ("Stage 1: Task assignment", "Stage 2: Code synthesis", "Stage 3: Test synthesis") which becomes one structural card. Grouping is semantic, within the same chapter and day; there is no positional key in the export beyond that ordering.
- **Flag truncation**: some rows are clipped mid-word at the start or end ("t's also crucial…", "…written to the san"). The missing text does not exist elsewhere in the export, so do not attempt to reconstruct it. Set `truncated=true` on that `highlights` row so the Writer works from the partial text and the Critic checks fidelity against a clipped source.
- **Tag**: topic tags beyond book/chapter (`evals`, `rag`, `agents`) enabling cross-book decks.

**Output**: curated units, each `{highlight_ids: [...], curated_text, tags, truncated_highlight_ids: [...], decision[keep|drop], reason}`. A unit with more than one `highlight_id` is a group. `truncated_highlight_ids` is written back to the `highlights` rows; the unit itself does not store the flag.

### 3. Card Writer Agent — LLM
**Input**: one curated unit (one or more source highlights) plus the current `writer_guidance` version.
**Output**: 1–3 cards, each `{type: qa|cloze, front, back, rationale}`, stamped with `guidance_version`.
**Rules** (enforced via prompt):
- Minimum information principle: one idea per card.
- Card must be answerable without the book open.
- Cloze cards: single deletion only (multi-deletion cards lapse more).
- Prefer "why/how" questions over trivia.
**Feedback loop**: prompt includes Learner-generated guidance from my review history (e.g. "cards asking for definitions lapse 40% — prefer application questions").

### 4. Critic Agent — LLM
**Input**: candidate cards from Writer (with source highlight).
**Checks**: atomicity, unambiguity, self-containedness, non-triviality, factual fidelity to the highlight.
**Output**: per card `verdict[accept|revise|reject]` + critique.
**Outcomes**:
- `accept` → `status=pending_review` (or `approved` if `AUTO_APPROVE_ROUND1_ACCEPT` is on and this is round 1).
- `revise` → that card only goes back to the Writer with the critique; sibling cards already accepted are kept as they are. After 3 rounds without `accept` → `status=needs_human`. `generation_rounds` is per card.
- `reject` (unsalvageable, e.g. source is a bare heading) → `status=needs_human` with the critique in `status_reason`. The pipeline never sets `rejected`; only a human does.

### 5. Human approval queue — me
Cards land in `pending_review` (critic-approved) or `needs_human` (critic-writer stalemate). Nothing enters FSRS scheduling until I approve in the app. This is DB state, not orchestration:

```
cards.status: pending_review → approved | rejected
              needs_human    → approved | rejected
```

Volume check: one book of ~380 highlights at ~90% keep and 1–3 cards each is 350–1000 cards to approve. The queue is grouped by chapter so a sitting clears a coherent batch. A config flag (`AUTO_APPROVE_ROUND1_ACCEPT`, default off) lets cards the Critic accepted on the first round skip the queue; turn it on only if the queue stops draining.

### 6. Scheduler Agent — deterministic
No LLM. FSRS due computation + daily batch selection (due cards + up to `NEW_CARDS_PER_DAY` never-reviewed cards) + FCM trigger via APScheduler. Push policy: at most one per day, inside `PUSH_WINDOW`, skipped while any card in the previous `push_runs` row is still unreviewed.

### 7. Learner Agent — two stages (nightly job)
**Stage A, deterministic (from day one)**: run `fsrs.Optimizer` over `review_logs` to fit personal FSRS parameters once there are a few hundred reviews. This is the cheapest and most reliable "learning" available and needs no LLM.

**Stage B, LLM (once there is enough history)**:
**Input**: `review_logs` aggregated — lapse rates by card type, book, tag, guidance version, deletions-per-cloze, failure streaks; plus my rating response times.
**Jobs**:
- Produce a new `writer_guidance` row (versioned, never edited in place) that is injected into the Writer prompt. Cards record the version that generated them, so guidance changes are attributable.
- Flag leech cards (failed 3+ times): propose rewrite as alternative explanation/analogy, or cross-link to a related highlight from another book. Rewrites go through the normal Writer ⇄ Critic → human approval path as new cards; the leech keeps its FSRS state until the human approves the rewrite, at which point the old card is set to `rejected` with `status_reason="superseded by <id>"`.
- Human edits at approval time (`cards.original_front/original_back` differ from `front/back`) and rejection reasons are inputs too; they are the most direct quality signal available.

Cold start: with one user, a lapse-rate bucket needs on the order of a hundred reviews before it means anything. Stage B is expected to produce its first useful guidance months in, not weeks.

## Pipeline runner and handoffs

Agents never call each other. One runner module (`pipeline.py`) calls them in order and every handoff is either a typed return value or a database row whose state marks it ready for the next stage.

- **Ingestion → Curator: DB state.** The runner selects all `highlights` with `processed=false`, grouped by chapter, regardless of which ingest inserted them. This is what makes re-running the pipeline the retry path: leftover rows from a failed run are picked up next time, without the file having to change.
- **Curator → Writer → Critic: in memory, within one run.** Curator output is persisted to `curated_units` (with `ingest_run_id`) before any Writer call, then each `keep` unit is passed to the Writer, and each returned card to the Critic. A `revise` verdict feeds the critique back into the next Writer call for that card only.
- **Critic → human → Scheduler: DB state.** The final verdict is written as `cards.status`; the Scheduler reads only `approved`.

Idempotency: `highlights.processed` flips to `true` only when the unit covering it has reached a terminal outcome (`drop`, or every card written with a status). The runner skips any `keep` unit that already has cards, so a crash between Curator and Writer re-runs the Curator for the remaining unprocessed highlights without generating duplicate cards for units that finished. Before calling the Curator, the runner deletes `keep` units that have no cards and whose highlights are all still `processed=false`; those are orphans from a crashed run and the Curator will recreate them.

## What is LLM vs deterministic

| Component | LLM? | Why |
|---|---|---|
| Ingestion, dedupe | No | Parsing is deterministic; LLM adds cost and flakiness |
| Curator | Yes | Judgment: sibling grouping, value filtering |
| Writer | Yes | Core generative task |
| Critic | Yes | Quality gate needs understanding |
| Scheduler / FSRS | No | Math, not reasoning |
| Learner stage A (optimizer) | No | Parameter fitting is math |
| Learner stage B | Yes | Pattern synthesis over noisy history |

## Quality signals and evals

v1 has no formal eval suite (ADR-006). The human approval queue is the labelled dataset, and the schema already captures it:
- `cards.status` and `status_reason` record accept/reject and why.
- `cards.original_front/original_back` vs `front/back` record every edit made at approval time.
- `cards.generation_rounds`, `model` and `guidance_version` make quality attributable to a prompt, model and guidance version.
- `llm_calls.request/response` keep the exact prompts and outputs so they can be replayed.

Formal evals (replaying the Critic over human-labelled cards, golden sets, LLM-as-judge) are added only when a model or prompt change needs a regression check. Until then the roadmap's two-week validation checkpoint is the eval.

## Cost controls

- Every LLM call logged: model, tokens, cost_microusd, agent, latency.
- Batch processing on ingest (not per-row calls where avoidable).
- Cheap model for Curator/Critic, stronger model for Writer (configurable).
- Nightly Learner caps input size (aggregates, not raw logs).
