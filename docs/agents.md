# Recally — Multi-Agent Design

Plain Python modules (no orchestration framework) for v1. Each "agent" is a module with an LLM call, a prompt file, and structured inputs/outputs. LangGraph migration is a future option if orchestration grows complex (see ADR-001).

## Roster

### 1. Ingestion Agent — deterministic
No LLM. Watch folder → adapter → dedupe → `highlights` rows with `processed=false`.

### 2. Curator Agent — LLM
**Input**: batch of unprocessed highlights (book/chapter context included).
**Jobs**:
- **Filter**: drop low-value highlights — truncated fragments, figure references ("Figure 1-2"), bare headings, navigation text. Real data shows 30–40% of raw highlights are junk.
- **Merge**: stitch fragmented highlights that were split mid-sentence (O'Reilly exports truncate). E.g. "f they stem from specification gaps…" + "If they stem from generalization failures…" → one coherent highlight.
- **Tag**: topic tags beyond book/chapter (`evals`, `rag`, `agents`) enabling cross-book decks.

**Output**: curated highlight list with `curated_text`, `tags`, `decision[keep|merge|drop]`, `reason`.

### 3. Card Writer Agent — LLM
**Input**: one curated highlight.
**Output**: 1–3 cards, each `{type: qa|cloze, front, back, rationale}`.
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

### 6. Scheduler Agent — deterministic
No LLM. FSRS due computation + daily batch selection (due cards + capped new cards) + FCM trigger via APScheduler.

### 7. Learner Agent — LLM (nightly job)
**Input**: `review_logs` aggregated — lapse rates by card type, book, tag, deletions-per-cloze, failure streaks; plus my rating response times.
**Jobs**:
- Produce Writer feedback guidance (persisted, injected into Writer prompt).
- Flag leech cards (failed 3+ times): propose rewrite as alternative explanation/analogy, or cross-link to a related highlight from another book. Rewrites go through the normal Writer ⇄ Critic → human approval path.

## What is LLM vs deterministic

| Component | LLM? | Why |
|---|---|---|
| Ingestion, dedupe | No | Parsing is deterministic; LLM adds cost and flakiness |
| Curator | Yes | Judgment: fragment stitching, value filtering |
| Writer | Yes | Core generative task |
| Critic | Yes | Quality gate needs understanding |
| Scheduler / FSRS | No | Math, not reasoning |
| Learner | Yes | Pattern synthesis over noisy history |

## Cost controls

- Every LLM call logged: model, tokens, cost_cents, agent, latency.
- Batch processing on ingest (not per-row calls where avoidable).
- Cheap model for Curator/Critic, stronger model for Writer (configurable).
- Nightly Learner caps input size (aggregates, not raw logs).
