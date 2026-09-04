# ADR-006: No formal evals in v1; the approval queue is the labelled set

**Status**: accepted
**Date**: 2026-09-04

## Context
The pipeline has three LLM agents whose output quality matters. Eval tooling (golden sets, LLM-as-judge, regression suites) is the standard answer, but v1 has one user, no history, and no baseline to regress against. Building evals before there is data to evaluate against would be guessing at what "good" means.

## Decision
No formal eval suite in v1. Instead:
- Every human approve, reject and edit in the approval queue is treated as a labelled example and stored in full (`cards.status`, `status_reason`, `original_front/original_back` vs `front/back`, `generation_rounds`, `model`, `guidance_version`).
- Every LLM call stores its rendered prompt and parsed response on `llm_calls`, linked to the unit and card it served, controlled by `LLM_LOG_PAYLOADS` (default on).
- Agent tests mock `llm.py` with recorded responses and test plumbing, not model quality.
- The roadmap's two-week validation checkpoint ("does card quality sustain a review habit") is the v1 quality gate.

## Rationale
- The human queue produces higher-quality labels than any judge model, at zero extra cost.
- Storing payloads now costs a few kilobytes per card and makes future evals possible without re-running anything.
- Formal evals earn their keep when something changes (model, prompt, guidance version) and a regression check is needed. Nothing changes until there is history.

## Consequences
- `llm_calls` gains `unit_id`, `card_id`, `round`, `request`, `response`.
- Revisit when: switching Writer or Critic model, editing a prompt file after cards have been approved, or the Learner starts producing `writer_guidance`. The first eval is expected to be "replay the Critic over human-labelled cards and measure agreement".
- Run tracing is covered by `llm_calls`, so it is no longer a reason to adopt an orchestration framework (ADR-001).
