"""llm_calls trace links: curated_units.llm_call_id and draft_index

Two links the trace was missing (#262). `curated_units.llm_call_id` names the
Curator batch call that produced a unit, so a bad drop or group no longer means
searching every call's request JSON. `draft_index` on `llm_calls` and `cards`
tells a unit's sibling Critic ⇄ Writer loops apart, so a card joins its own calls
on `(unit_id, draft_index)` without the `cards` row being written mid-loop. All
three are nullable with no backfill: older runs keep null links.

Revision ID: 5b8d2e41c7a9
Revises: 3f7a1c9e52b0
Create Date: 2026-10-19 09:00:00.000000+00:00

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "5b8d2e41c7a9"
down_revision: str | Sequence[str] | None = "3f7a1c9e52b0"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("curated_units", schema=None) as batch_op:
        batch_op.add_column(sa.Column("llm_call_id", sa.Integer(), nullable=True))
        batch_op.create_foreign_key(
            op.f("fk_curated_units_llm_call_id_llm_calls"), "llm_calls", ["llm_call_id"], ["id"]
        )
    with op.batch_alter_table("llm_calls", schema=None) as batch_op:
        batch_op.add_column(sa.Column("draft_index", sa.Integer(), nullable=True))
    with op.batch_alter_table("cards", schema=None) as batch_op:
        batch_op.add_column(sa.Column("draft_index", sa.Integer(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("cards", schema=None) as batch_op:
        batch_op.drop_column("draft_index")
    with op.batch_alter_table("llm_calls", schema=None) as batch_op:
        batch_op.drop_column("draft_index")
    with op.batch_alter_table("curated_units", schema=None) as batch_op:
        batch_op.drop_constraint(op.f("fk_curated_units_llm_call_id_llm_calls"), type_="foreignkey")
        batch_op.drop_column("llm_call_id")
