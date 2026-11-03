"""ingest_runs.unreturned_highlight_ids

Highlight ids a Curator reply left out of every unit (#356). Cycle 7 lost h182 with
nothing recording it: the highlight stayed unprocessed and `ingest_runs.error` was
empty. A nullable JSON list with no backfill: older runs keep null.

Revision ID: 8e1f4a6b2c3d
Revises: 5b8d2e41c7a9
Create Date: 2026-11-03 09:00:00.000000+00:00

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "8e1f4a6b2c3d"
down_revision: str | Sequence[str] | None = "5b8d2e41c7a9"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("ingest_runs", schema=None) as batch_op:
        batch_op.add_column(sa.Column("unreturned_highlight_ids", sa.JSON(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("ingest_runs", schema=None) as batch_op:
        batch_op.drop_column("unreturned_highlight_ids")
