"""Scheduling: the py-fsrs wrapper, the notifier, and the APScheduler jobs.

Deterministic — no LLM anywhere below this package (hard rule 2). `fsrs.py` is the
only module in the codebase that imports `fsrs` (docs/backend.md, "Package layout").
"""
