# ADR-004: SQLite first, Postgres-ready

**Status**: accepted
**Date**: 2026-09-04

## Context
v1 runs locally on one machine for one user. Productionization (HF Spaces → AWS) will need a real database.

## Decision
SQLite via SQLAlchemy + Alembic migrations, with no SQLite-specific SQL. All tables include `user_id` for future multi-user.

## Rationale
- Zero-ops locally; file backups are trivial.
- ORM + migrations make the Postgres cutover a connection-string change.
- `user_id` from day one avoids a painful schema retrofit later.

## Consequences
- Single-writer limitation is fine for one user; must migrate before multi-user.
- Alembic migration discipline required from the first schema change.
