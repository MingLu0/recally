# ADR-003: Provider-agnostic LLM access via LiteLLM

**Status**: accepted
**Date**: 2026-09-04

## Context
Card generation, curation, criticism, and learning all need an LLM. Provider choice (Claude, OpenAI, local Ollama) has cost/quality/privacy tradeoffs that will shift over time.

## Decision
All LLM calls go through a single `llm.py` wrapper around LiteLLM. Provider and per-agent model tiers are env-configured. Every call is logged to `llm_calls` (model, tokens, cost, latency).

## Rationale
- Switch providers without code changes; compare quality/cost empirically.
- Per-agent model tiers: cheap model for Curator/Critic, stronger for Writer.
- Cost logging is essential for a personal project running nightly jobs.

## Consequences
- LiteLLM dependency and its occasionally leaky abstraction; wrapper isolates it to one module.
- Prompts must avoid provider-specific features.
