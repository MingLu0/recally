"""Scheduling: the py-fsrs wrapper, the notifier, and the APScheduler jobs.

Deterministic — nothing in this package imports `llm.py` (hard rule 2). The
`learner` job's aggregate building is deterministic too; the one LLM call of
Learner stage B lives in `agents/learner/`, which the job reaches through the
container like any other agent. `fsrs.py` is the only module in the codebase
that imports `fsrs` (docs/backend.md, "Package layout").
"""
