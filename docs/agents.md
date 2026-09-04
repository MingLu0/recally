# Recally — Multi-Agent Design

Plain Python modules (no orchestration framework) for v1. Each "agent" is a module with an LLM call, a prompt file, and structured inputs/outputs. LangGraph migration is a future option if orchestration grows complex (see ADR-001).

## Roster

### 1. Ingestion Agent — deterministic
No LLM. Watch folder → adapter → dedupe → `highlights` rows with `processed=false`.

### 2. Curator Agent — LLM
**Input**: batch of unprocessed highlights for one chapter, in export order (newest first; within a day, reverse creation order, which approximates reverse reading order).
**Jobs**:
- **Filter**: drop low-value highlights — bare headings with no sibling context, navigation text, isolated short phrases. Real data (two exports, 380 rows): ~7% of rows are under 40 characters, none are figure/table references. Junk is a small minority, so the Curator should default to `keep`.
- **Group**: fold sibling highlights that only make sense together into one curated unit. The observed case is a run of headings highlighted in sequence ("Stage 1: Task assignment", "Stage 2: Code synthesis", "Stage 3: Test synthesis") which becomes one structural card. Grouping is semantic, within the same chapter and day; there is no positional key in the export beyond that ordering.
- **Flag truncation**: some rows are clipped mid-word at the start or end ("t's also crucial…", "…written to the san"). The missing text does not exist elsewhere in the export, so do not attempt to reconstruct it. Mark `truncated=true` so the Writer works from the partial text and the Critic checks fidelity against a clipped source.
- **Tag**: topic tags beyond book/chapter (`evals`, `rag`, `agents`) enabling cross-book decks.

**Output**: curated units, each `{highlight_ids: [...], curated_text, tags, truncated, decision[keep|drop], reason}`. A unit with more than one `highlight_id` is a group.

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
**Loop**: `revise` → back to Writer with critique, max 3 rounds → then `status=needs_human`.

### 5. Human approval queue — me
Cards land in `pending_review` (critic-approved) or `needs_human` (critic-writer stalemate). Nothing enters FSRS scheduling until I approve in the app. This is DB state, not orchestration:

```
cards.status: pending_review → approved | rejected
              needs_human    → approved | rejected
```

Volume check: one book of ~380 highlights at ~90% keep and 1–3 cards each is 350–1000 cards to approve. The queue is grouped by chapter so a sitting clears a coherent batch. A config flag (`AUTO_APPROVE_ROUND1_ACCEPT`, default off) lets cards the Critic accepted on the first round skip the queue; turn it on only if the queue stops draining.

### 6. Scheduler Agent — deterministic
No LLM. FSRS due computation + daily batch selection (due cards + capped new cards) + FCM trigger via APScheduler. Push policy: at most one per day, inside a configured window, skipped while cards from the previous push are still unreviewed.

### 7. Learner Agent — two stages (nightly job)
**Stage A, deterministic (from day one)**: run `fsrs.Optimizer` over `review_logs` to fit personal FSRS parameters once there are a few hundred reviews. This is the cheapest and most reliable "learning" available and needs no LLM.

**Stage B, LLM (once there is enough history)**:
**Input**: `review_logs` aggregated — lapse rates by card type, book, tag, guidance version, deletions-per-cloze, failure streaks; plus my rating response times.
**Jobs**:
- Produce a new `writer_guidance` row (versioned, never edited in place) that is injected into the Writer prompt. Cards record the version that generated them, so guidance changes are attributable.
- Flag leech cards (failed 3+ times): propose rewrite as alternative explanation/analogy, or cross-link to a related highlight from another book. Rewrites go through the normal Writer ⇄ Critic → human approval path.

Cold start: with one user, a lapse-rate bucket needs on the order of a hundred reviews before it means anything. Stage B is expected to produce its first useful guidance months in, not weeks.

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

## Cost controls

- Every LLM call logged: model, tokens, cost_microusd, agent, latency.
- Batch processing on ingest (not per-row calls where avoidable).
- Cheap model for Curator/Critic, stronger model for Writer (configurable).
- Nightly Learner caps input size (aggregates, not raw logs).
