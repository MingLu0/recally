# Backend

FastAPI backend + agent pipeline. See [../docs/backend.md](../docs/backend.md) for the package layout, [../docs/architecture.md](../docs/architecture.md) and [../docs/agents.md](../docs/agents.md) for behaviour.

Ingestion (O'Reilly CSV adapter, UUID dedupe, watcher), the Curator → Writer ⇄ Critic pipeline, FSRS scheduling, the reviews/cards/decks/stats API, the notifier and the `recally` CLI are in. Conventions for agents are in [AGENTS.md](AGENTS.md); remaining work is tracked in [../docs/roadmap.md](../docs/roadmap.md).

```sh
uv sync
uv run pytest
uv run ruff check . && uv run ruff format --check .
uv run mypy src/
uv run alembic upgrade head
uv run uvicorn recally.main:app --reload
```
