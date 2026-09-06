"""The step 1b acceptance gate: Alembic builds the schema from empty and tears it down.

`upgrade head` then `downgrade base` runs against a throwaway SQLite file, and the
tables and columns the migration created are read back off the live connection, so the
test fails if a model is added without a matching migration.
"""

from collections.abc import Iterator
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import Engine, create_engine, inspect

from recally.models import Base

BACKEND_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def migration_target(tmp_path: Path) -> Iterator[tuple[Config, Engine]]:
    """An Alembic config and engine pointed at an empty throwaway SQLite database."""
    database_url = f"sqlite:///{tmp_path / 'migrations.db'}"
    config = Config(str(BACKEND_ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(BACKEND_ROOT / "alembic"))
    config.set_main_option("sqlalchemy.url", database_url)
    engine = create_engine(database_url)
    try:
        yield config, engine
    finally:
        engine.dispose()


def test_upgrade_head_then_downgrade_base(migration_target: tuple[Config, Engine]) -> None:
    config, engine = migration_target

    command.upgrade(config, "head")
    with engine.connect() as connection:
        migrated_tables = set(inspect(connection).get_table_names())
    assert set(Base.metadata.tables) <= migrated_tables

    command.downgrade(config, "base")
    with engine.connect() as connection:
        remaining_tables = set(inspect(connection).get_table_names())
    assert remaining_tables - {"alembic_version"} == set()


def test_migration_matches_the_models(migration_target: tuple[Config, Engine]) -> None:
    """Every model column exists in the migrated schema, with the same nullability."""
    config, engine = migration_target
    command.upgrade(config, "head")

    with engine.connect() as connection:
        inspector = inspect(connection)
        for table_name, table in Base.metadata.tables.items():
            migrated_columns = {
                column["name"]: column for column in inspector.get_columns(table_name)
            }
            assert set(table.columns.keys()) == set(migrated_columns), table_name
            for column in table.columns:
                assert column.nullable == migrated_columns[column.name]["nullable"], (
                    f"{table_name}.{column.name}"
                )


def test_every_table_carries_user_id() -> None:
    """ADR-004: every table has `user_id`, not null, defaulting to 1, ready for multi-user."""
    for table_name, table in Base.metadata.tables.items():
        assert "user_id" in table.columns, table_name
        user_id_column = table.columns["user_id"]
        assert not user_id_column.nullable, table_name
        assert user_id_column.server_default is not None, table_name
