# Recally — Product Requirements Document

## Problem

I read a lot on O'Reilly and highlight extensively, but:

1. **I forget what I read.** Highlights are written and never revisited.
2. **The exported notes are unreadable.** O'Reilly's CSV export is raw, fragmented (highlights truncated mid-sentence), and mixed with navigation junk (figure references, headings). There is no way to review them effectively.

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

- Automatic ingestion of `oreilly-annotations*.csv` from a watched folder, idempotent across re-exports.
- LLM pipeline: Curator → Writer ⇄ Critic → human approval queue.
- FSRS-based review scheduling with 4-button rating (Again / Hard / Good / Easy).
- Android app: Today, Review session, Approval Queue, Decks, Stats.
- FCM push notifications when cards are due.
- Learner agent that analyzes review logs nightly and feeds quality signals back to the Writer.

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
- Card lapse rate trending down as Learner feedback accumulates.

## Key product decisions

| Decision | Choice | Rationale |
|---|---|---|
| Flashcard content | LLM-generated Q&A + cloze | Raw highlights are not reviewable; generation quality is the core value |
| Quality gate | Critic agent + human approval queue | Bad cards kill retention habits; nothing enters the deck unreviewed |
| Spaced repetition | FSRS (py-fsrs) | Modern algorithm, better retention modeling than SM-2 |
| Review delivery | FCM push | Reminders are essential for a forgetful user (me) |
| Android | Native Kotlin + Compose | Best notifications/offline support |
