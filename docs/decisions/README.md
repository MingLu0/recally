# Architecture Decision Records

One file per cross-cutting or hard-to-reverse decision. Small local decisions get a one-sentence "why" next to the item in the doc that owns it; if reversing a decision would touch more than one doc, it belongs here. Files are never edited after acceptance except to change `Status` (e.g. `superseded by 00N`).

| # | Title | Status | Decision |
|---|---|---|---|
| [001](001-plain-python-over-langgraph.md) | Plain Python over LangGraph | accepted | Plain modules + LiteLLM + APScheduler; approval is DB state, not a graph pause |
| [002](002-fsrs-over-sm2.md) | FSRS over SM-2 | accepted | `py-fsrs` 6.x for scheduling |
| [003](003-litellm-provider-agnostic.md) | LiteLLM, provider-agnostic | accepted | Single `llm.py` wrapper; every call logged to `llm_calls` |
| [004](004-sqlite-then-postgres.md) | SQLite first, Postgres-ready | accepted | SQLAlchemy + Alembic, no SQLite-specific SQL, `user_id` everywhere |
| [005](005-same-session-relearning.md) | Same-session relearning | accepted | Client re-queues Again/Hard in-session; server stays authoritative |
| [006](006-no-formal-evals-in-v1.md) | No formal evals in v1 | accepted | Approval queue is the labelled set; `llm_calls` stores prompts for later replay |
