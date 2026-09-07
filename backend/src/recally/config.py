"""Environment-backed settings, the single source of defaults (docs/config.md).

Only the settings the code already reads are declared here; the rest arrive with the
code that reads them, each one added to docs/config.md and .env.example in the same
change.

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
    watch_dir: Path = Field(
        default_factory=lambda: Path("~/Downloads").expanduser(),
        validation_alias="RECALLY_WATCH_DIR",
    )
    watch_debounce_ms: int = Field(
        default=2000,
        gt=0,
        validation_alias="RECALLY_WATCH_DEBOUNCE_MS",
    )

    # Per-role model tiers (docs/config.md): cheap for Curator/Critic, stronger for
    # Writer/Learner. The registry passes the resolved one to `llm.py` per call.
    llm_model_curator: str = Field(
        default="claude-haiku-4-5-20251001", validation_alias="LLM_MODEL_CURATOR"
    )
    llm_model_writer: str = Field(default="claude-sonnet-5", validation_alias="LLM_MODEL_WRITER")
    llm_model_critic: str = Field(
        default="claude-haiku-4-5-20251001", validation_alias="LLM_MODEL_CRITIC"
    )
    llm_model_learner: str = Field(default="claude-sonnet-5", validation_alias="LLM_MODEL_LEARNER")
    # Off only if `llm_calls` bloats (ADR-006); the replay corpus is the default.
    llm_log_payloads: bool = Field(default=True, validation_alias="LLM_LOG_PAYLOADS")

    # Max highlights per Curator call; a longer chapter is split into consecutive
    # batches and grouping cannot span a batch boundary (docs/agents.md §2).
    curator_max_batch: int = Field(default=40, gt=0, validation_alias="CURATOR_MAX_BATCH")

    # Registered variant per agent role (ADR-007); the registry validates them at
    # startup, so a typo fails the container build rather than the first LLM call.
    agent_curator: str = Field(default="default", validation_alias="AGENT_CURATOR")
    agent_writer: str = Field(default="default", validation_alias="AGENT_WRITER")
    agent_critic: str = Field(default="default", validation_alias="AGENT_CRITIC")
    agent_learner: str = Field(default="default", validation_alias="AGENT_LEARNER")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """The process-wide settings, parsed on first use."""
    return Settings()  # type: ignore[call-arg]  # every field comes from the environment
