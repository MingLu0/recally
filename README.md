# Recally

Turn your reading highlights into lasting memory. Recally ingests O'Reilly annotation exports, uses an LLM multi-agent pipeline to generate high-quality flashcards, and delivers them via spaced repetition (FSRS) to an Android app with push reminders.

**Docs**

- [PRD](docs/PRD.md) — what we're building and why
- [Architecture](docs/architecture.md) — system design
- [Agents](docs/agents.md) — the multi-agent pipeline
- [Data model](docs/data-model.md) — schema & FSRS state
- [API spec](docs/api-spec.md) — REST endpoints
- [Android app](docs/android.md) — screens & sync
- [Config](docs/config.md) — env vars & defaults
- [Roadmap](docs/roadmap.md) — build order & productionization
- [Decisions](docs/decisions/README.md) — ADR index

## Repo layout

```
backend/    FastAPI backend + agent pipeline (Python)
android/    Android app (Kotlin, Jetpack Compose)
data/       local SQLite db & ingest drop zone (gitignored)
docs/       product & engineering documentation
docs/decisions/   ADRs
```
