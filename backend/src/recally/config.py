"""Environment-backed settings, the single source of defaults (docs/config.md).

Only the settings step 1b needs are declared here; the rest arrive with the code that
reads them, each one added to docs/config.md and .env.example in the same change.

Variable names are given per field rather than through a shared `env_prefix`: the
documented names are a mix of `RECALLY_*` and bare ones (`LLM_MODEL_WRITER`,
`AGENT_CRITIC`, `NEW_CARDS_PER_DAY`), so a blanket prefix would rename half of them.
"""

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration, read once at startup."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = Field(
        default="sqlite:///data/recally.db", validation_alias="RECALLY_DATABASE_URL"
    )
    watch_dir: Path = Field(
        default_factory=lambda: Path("~/Downloads").expanduser(),
        validation_alias="RECALLY_WATCH_DIR",
    )
    watch_debounce_ms: int = Field(
        default=2000,
        gt=0,
        validation_alias="RECALLY_WATCH_DEBOUNCE_MS",
    )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """The process-wide settings, parsed on first use."""
    return Settings()
