"""pipeline counters on ingest_runs

The pipeline's Curator/filter counters (`units_kept`, `units_dropped`,
`highlights_dropped`) from docs/data-model.md, added when the runner lands in
roadmap step 2. `server_default="0"` keeps existing rows valid; the ORM uses its
own Python-side default for new rows, matching the other counter columns.

Revision ID: 9c4e2b17f0a1
Revises: 72acaac80383
Create Date: 2026-09-07 10:00:00.000000+00:00

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "9c4e2b17f0a1"
down_revision: str | Sequence[str] | None = "72acaac80383"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("ingest_runs", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column("units_kept", sa.Integer(), server_default=sa.text("0"), nullable=False)
        )
        batch_op.add_column(
            sa.Column("units_dropped", sa.Integer(), server_default=sa.text("0"), nullable=False)
        )
        batch_op.add_column(
            sa.Column(
                "highlights_dropped", sa.Integer(), server_default=sa.text("0"), nullable=False
            )
        )


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("ingest_runs", schema=None) as batch_op:
        batch_op.drop_column("highlights_dropped")
        batch_op.drop_column("units_dropped")
        batch_op.drop_column("units_kept")
