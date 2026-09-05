# ADR-007: Agent protocol + registry boundary

**Status**: accepted
**Date**: 2026-09-04

## Context

ADR-001 chose plain Python modules over an orchestration framework. That left a gap: agents.md specifies each agent's inputs and outputs, but nothing specified how the pipeline binds to an implementation, which meant swapping an agent (a stricter Critic, an experimental Writer) would mean editing `pipeline.py` — the module least safe to edit. We want agent implementations to be replaceable without touching the pipeline, and without reintroducing a framework.

## Decision

Each LLM agent role (curator, writer, critic, learner) is defined as a `typing.Protocol` in `agents/base.py`, with dataclass request/result types. Implementations register under a `(role, variant)` key in `agents/registry.py`; `pipeline.py` resolves the active variant per role through the registry, driven by `AGENT_CURATOR` / `AGENT_WRITER` / `AGENT_CRITIC` / `AGENT_LEARNER` env vars (default `default`). Every agent receives an `AgentContext` carrying the run id and config but **no DB session** — agents return typed results and the runner owns all persistence. Agent resolution lives in `container.py`, which serves both HTTP and non-HTTP entry points; FastAPI's `Depends` only pulls from the container. `llm_calls.agent` records `role/variant`. Full layout in [../backend.md](../backend.md).

## Rationale

- A `Protocol` + registry is a module boundary, not a framework: no runtime, no new dependency, consistent with ADR-001.
- Swapping becomes a config change plus a new file, so experiments never touch `pipeline.py`.
- A session-less `AgentContext` makes hard rules 1, 7 and 9 structural: the Critic returns a verdict, not a status; no agent can approve a card, flip `processed`, or extend the round loop.
- `container.py` rather than FastAPI `Depends` as the source of truth, because the pipeline also runs from the watcher, APScheduler, the CLI and `POST /jobs/run` — non-request callers FastAPI DI cannot serve.
- `role/variant` in `llm_calls.agent` keeps the trace meaningful after a swap; without it the DB cannot say which implementation wrote a card, which defeats the point.

## Consequences

- New agents must conform to the role protocol; mypy enforces it at the boundary.
- Write-backs agents.md attributes to agents (e.g. the Curator's `truncated` flags) are persisted by the runner, not the agent.
- `llm_calls.agent` values change shape (`writer` → `writer/default`); recorded before any code exists, so no migration.
- Reversible: the registry is one module; collapsing back to direct imports touches only `pipeline.py` and `container.py`.
