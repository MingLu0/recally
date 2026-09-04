# Recally — Product Requirements Document

## Problem

I read a lot on O'Reilly and highlight extensively, but:

1. **I forget what I read.** Highlights are written and never revisited.
2. **The exported notes are unreadable.** O'Reilly's CSV export is raw: some highlights are clipped mid-word at either end (the lost text appears nowhere else in the export), bare headings are mixed in with content, and rows are ordered newest-first rather than by position in the book. There is no way to review them effectively.

## Solution

Recally is a personal agentic system that:

1. Ingests O'Reilly annotation exports automatically (watch folder).
2. Uses an LLM multi-agent pipeline to clean, curate, and convert highlights into atomic flashcards (Q&A and cloze).
3. Schedules reviews with the FSRS spaced-repetition algorithm.
4. Delivers due cards to an Android app with push reminders.
5. Learns from my review history to improve future card quality.

## User

Single user: me (Ming). Designed with `user_id` columns from day one so multi-user is a future option, not a rewrite.

## Goals (v1)

- Automatic ingestion of `*oreilly-annotations*.csv` from a watched folder (O'Reilly prefixes the filename with the book slug, e.g. `30-agents-every-oreilly-annotations.csv`), idempotent across re-exports.
- LLM pipeline: Curator → Writer ⇄ Critic → human approval queue.
- FSRS-based review scheduling with 4-button rating (Again / Hard / Good / Easy), including same-session relearning steps.
- Android app: Today, Review session, Approval Queue, Decks, Stats.
- FCM push notifications when cards are due: at most one per day, inside a configurable window, never while cards from an earlier push are still unreviewed.
- Learner: FSRS parameter optimisation from review logs (deterministic) first; LLM analysis that feeds quality signals back to the Writer once there is enough history.

## Non-goals (v1)

- Kindle ingestion (adapter interface designed, export format TBD).
- Multi-user auth (API key only).
- iOS app.
- Cloud hosting (local first; see roadmap).
- Social/sharing features.

## Success metrics

- Daily review streak length.
- Card retention rate (% of mature cards rated Good/Easy).
- % of ingested highlights that become approved cards (curation yield).
- Card lapse rate by Writer guidance version (cards record which guidance generated them, so the Learner's effect is attributable rather than inferred from a trend).
- Approval queue drained within a day of ingest (a growing queue means the quality gate is too expensive).

## Key product decisions

| Decision | Choice | Rationale |
|---|---|---|
| Flashcard content | LLM-generated Q&A + cloze | Raw highlights are not reviewable; generation quality is the core value |
| Quality gate | Critic agent + human approval queue | Bad cards kill retention habits; nothing enters the deck unreviewed |
| Approval volume | Queue grouped by chapter; Critic `accept` on round 1 may be auto-approved behind a config flag (off by default) | One book yields several hundred cards; hand-approving every one is itself a habit risk. Turn the flag on only if the queue stops draining |
| Re-exports | Same UUID + same text → skip. UUID missing from a later export → highlight marked `removed`, existing cards untouched. Text or note changed for the same UUID → not observed in real exports; if it ever happens, update the existing row and re-run the Curator on it (the UUID stays the unique key, so it is never a second row) | Two real exports 10 days apart: 324 UUIDs unchanged, 56 added, 2 removed, 0 edited |
| Spaced repetition | FSRS (py-fsrs) | Modern algorithm, better retention modeling than SM-2 |
| Review delivery | FCM push | Reminders are essential for a forgetful user (me) |
| Android | Native Kotlin + Compose | Best notifications/offline support |
