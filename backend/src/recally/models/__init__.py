"""SQLAlchemy models for the tables in docs/data-model.md.

Importing this package registers every model on `Base.metadata`, which is what
Alembic's autogenerate compares the database against. A new model must be imported
here or the migration that should create it will come out empty.
"""

from recally.models.base import Base, UserScopedMixin, utc_now
from recally.models.cards import Card, CuratedUnit, CuratedUnitHighlight, WriterGuidance
from recally.models.ingest import Book, Highlight, IngestRun
from recally.models.scheduling import (
    CardState,
    Device,
    FsrsParams,
    LlmCall,
    PushRun,
    ReviewLog,
)

__all__ = [
    "Base",
    "Book",
    "Card",
    "CardState",
    "CuratedUnit",
    "CuratedUnitHighlight",
    "Device",
    "FsrsParams",
    "Highlight",
    "IngestRun",
    "LlmCall",
    "PushRun",
    "ReviewLog",
    "UserScopedMixin",
    "WriterGuidance",
    "utc_now",
]
