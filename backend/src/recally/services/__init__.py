"""Query and command services shared by every entry point.

These sit below `api/` and import no FastAPI (docs/backend.md, "Layering" rule 1): the
same reads back the CLI in roadmap step 3, so they take a `Session` and return plain
dataclasses rather than request or response objects.
"""
