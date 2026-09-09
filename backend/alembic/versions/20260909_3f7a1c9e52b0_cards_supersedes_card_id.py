"""cards.supersedes_card_id: the leech-rewrite self-FK

A Learner-driven leech rewrite (docs/agents.md §7) is a new `cards` row that
points at the approved card it replaces. The link must be a column, not just the
`status_reason` string: `POST /cards/{id}/approve` receives only the id of the
card being approved, so without a stored FK the gate has no way to know the card
in front of it replaces anything. NULL for every ordinary card; nullable, so
existing rows stay valid without a backfill.

Revision ID: 3f7a1c9e52b0
Revises: 9c4e2b17f0a1
Create Date: 2026-09-09 09:00:00.000000+00:00

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "3f7a1c9e52b0"
down_revision: str | Sequence[str] | None = "9c4e2b17f0a1"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("cards", schema=None) as batch_op:
        batch_op.add_column(sa.Column("supersedes_card_id", sa.Integer(), nullable=True))
        batch_op.create_foreign_key(
            op.f("fk_cards_supersedes_card_id_cards"), "cards", ["supersedes_card_id"], ["id"]
        )


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("cards", schema=None) as batch_op:
        batch_op.drop_constraint(op.f("fk_cards_supersedes_card_id_cards"), type_="foreignkey")
        batch_op.drop_column("supersedes_card_id")
