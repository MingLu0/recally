"""Environment-backed settings, the single source of defaults (docs/config.md).

Only the settings the code already reads are declared here; the rest arrive with the
code that reads them, each one added to docs/config.md and .env.example in the same
change.

Variable names are given per field rather than through a shared `env_prefix`: the
documented names are a mix of `RECALLY_*` and bare ones (`LLM_MODEL_WRITER`,
`AGENT_CRITIC`, `NEW_CARDS_PER_DAY`), so a blanket prefix would rename half of them.
"""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration, read once at startup."""

    # `validate_by_name` lets a test construct Settings(api_key=...) directly; every
    # field is otherwise filled from the environment, which is why mypy is told not to
    # treat the required ones as missing __init__ arguments.
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", validate_by_name=True)

    database_url: str = Field(
        default="sqlite:///data/recally.db", validation_alias="RECALLY_DATABASE_URL"
    )
    # No default: the key is the only thing standing between the LAN and the API, so a
    # missing `RECALLY_API_KEY` has to fail startup rather than silently pick a value
    # every reader of this file would know (docs/config.md marks it *required*).
    api_key: str = Field(min_length=1, validation_alias="RECALLY_API_KEY")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """The process-wide settings, parsed on first use."""
    return Settings()  # type: ignore[call-arg]  # every field comes from the environment
