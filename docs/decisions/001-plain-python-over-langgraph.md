# ADR-001: Plain Python over LangGraph

**Status**: accepted
**Date**: 2026-09-04

## Context
The backend is a multi-agent system (Curator, Writer, Critic, Learner, scheduler). LangGraph offers state machines, human-in-the-loop, and observability for agent orchestration.

## Decision
Use plain Python modules + LiteLLM + APScheduler. Human-in-the-loop is handled as DB state (`cards.status` + approval queue endpoints), not orchestration.

## Rationale
- The v1 flow is linear (Curator → Writer ⇄ Critic bounded loop → queue); a graph runtime is overhead.
- Simpler to debug and learn; this is a personal learning project.
- Approval is asynchronous (human reviews later in the app), which is a queue, not a graph pause.

## Consequences
- Easy to start; each agent is testable in isolation.
- Revisit LangGraph if we add multi-turn deliberation or branching recovery. Run tracing on its own is not a trigger: `llm_calls` stores the full prompt, response and card linkage (ADR-006), which covers what LangSmith-style tracing would give us.
