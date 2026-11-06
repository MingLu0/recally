"""curated_units.drop_resolution, curated_units.drop_resolved_at

The human's answer to a Curator drop (#365, ADR-019): `dismissed` or `rescued`,
and when. `decision` stays the Curator's output and is never rewritten. Both
nullable with no backfill: every historical drop reads NULL, i.e. unresolved,
so it appears in `GET /units/dropped`.

Revision ID: c7d2a9e4f1b8
Revises: 8e1f4a6b2c3d
Create Date: 2026-11-06 09:00:00.000000+00:00

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "c7d2a9e4f1b8"
down_revision: str | Sequence[str] | None = "8e1f4a6b2c3d"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("curated_units", schema=None) as batch_op:
        batch_op.add_column(sa.Column("drop_resolution", sa.String(length=16), nullable=True))
        batch_op.add_column(sa.Column("drop_resolved_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("curated_units", schema=None) as batch_op:
        batch_op.drop_column("drop_resolved_at")
        batch_op.drop_column("drop_resolution")
