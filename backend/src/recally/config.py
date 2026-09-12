"""Environment-backed settings, the single source of defaults (docs/config.md).

Only the settings the code already reads are declared here; the rest arrive with the
code that reads them, each one added to docs/config.md and .env.example in the same
change.

Variable names are given per field rather than through a shared `env_prefix`: the
documented names are a mix of `RECALLY_*` and bare ones (`LLM_MODEL_WRITER`,
`AGENT_CRITIC`, `NEW_CARDS_PER_DAY`), so a blanket prefix would rename half of them.
"""

from datetime import datetime, time, timedelta
from functools import lru_cache
from pathlib import Path
from zoneinfo import ZoneInfo

from apscheduler.triggers.cron import CronTrigger
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _parse_push_window(value: str) -> tuple[time, time]:
    """Parse "HH:MM-HH:MM" into (start, end). `strptime` rather than
    `time.fromisoformat` so "08:00" parses on every supported Python."""
    start, separator, end = value.partition("-")
    if not separator:
        raise ValueError("must be a local-time range, e.g. '08:00-21:00'")
    try:
        return (
            datetime.strptime(start.strip(), "%H:%M").time(),
            datetime.strptime(end.strip(), "%H:%M").time(),
        )
    except ValueError:
        raise ValueError("must be a local-time range, e.g. '08:00-21:00'") from None


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
    # IANA zone owning every day boundary: streaks, "one push per day", the new-card
    # allotment, bury's next-midnight (docs/config.md, ADR-008). Validated at startup
    # so a typo fails boot rather than the first midnight rollover.
    timezone: str = Field(default="Pacific/Auckland", validation_alias="RECALLY_TIMEZONE")

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

    # Writer ⇄ Critic round cap (hard rule 9); do not raise without a doc change.
    llm_max_rounds: int = Field(default=3, gt=0, validation_alias="LLM_MAX_ROUNDS")
    # Hard-rule-1 exception: a Critic `accept` on round 1 skips the human queue.
    auto_approve_round1_accept: bool = Field(
        default=False, validation_alias="AUTO_APPROVE_ROUND1_ACCEPT"
    )
    # Units resolved in parallel (ADR-015). 1 = sequential (the historical path).
    # Default 16, measured rather than guessed: on a 70-highlight reference ingest
    # (`openai/k3`) 1 -> 8 -> 16 ran 35.2 / 6.2 / 4.5 min, with zero 429s and zero
    # `database is locked` at any level. Past ~16 the slowest single unit dominates
    # and there is little left to win — 8 -> 16 already bought only 1.39x. Lower it
    # if a provider starts rate-limiting; 1 restores the pre-ADR-015 path exactly.
    llm_concurrency: int = Field(default=16, gt=0, validation_alias="LLM_CONCURRENCY")

    # Registered variant per agent role (ADR-007); the registry validates them at
    # startup, so a typo fails the container build rather than the first LLM call.
    agent_curator: str = Field(default="default", validation_alias="AGENT_CURATOR")
    agent_writer: str = Field(default="default", validation_alias="AGENT_WRITER")
    agent_critic: str = Field(default="default", validation_alias="AGENT_CRITIC")
    agent_learner: str = Field(default="default", validation_alias="AGENT_LEARNER")

    # FSRS (docs/config.md, "Scheduling and push"). Used until the optimizer writes
    # an `fsrs_params` row; the latest row then overrides both (docs/data-model.md).
    fsrs_desired_retention: float = Field(
        default=0.9, gt=0, lt=1, validation_alias="FSRS_DESIRED_RETENTION"
    )
    # Cap on never-reviewed cards in a `GET /reviews/due` response.
    new_cards_per_day: int = Field(default=10, gt=0, validation_alias="NEW_CARDS_PER_DAY")
    # Comma-separated minutes, e.g. "1,10". A str field rather than a list because
    # pydantic-settings parses complex env values as JSON, and the documented value
    # is not JSON.
    fsrs_learning_steps_minutes: str = Field(
        default="1,10", validation_alias="FSRS_LEARNING_STEPS_MINUTES"
    )

    # Push policy (docs/config.md, "Scheduling and push"; hard rule 8). The window
    # is a single local-time range string, "HH:MM-HH:MM" in RECALLY_TIMEZONE — a
    # range, not two variables. Validated at startup so a typo fails boot rather
    # than the first tick.
    push_window: str = Field(default="08:00-21:00", validation_alias="PUSH_WINDOW")
    # The APScheduler tick for the notify job.
    push_check_interval_min: int = Field(
        default=60, gt=0, validation_alias="PUSH_CHECK_INTERVAL_MIN"
    )
    # Service-account JSON for FCM HTTP v1. No default (docs/config.md marks it
    # "required for push"): unset, the notifier logs and skips rather than failing
    # startup for an install that never registered a device.
    firebase_credentials_file: Path | None = Field(
        default=None, validation_alias="FIREBASE_CREDENTIALS_FILE"
    )

    # Learner stage A (docs/agents.md, "7. Learner"; docs/config.md, "Scheduling and
    # push"). Below this many review_logs the nightly fit writes nothing, so library
    # defaults stay active (docs/data-model.md, `fsrs_params`).
    optimizer_min_reviews: int = Field(default=400, ge=1, validation_alias="OPTIMIZER_MIN_REVIEWS")
    # Learner stage B. Below this many review_logs the nightly guidance job writes
    # nothing and never calls the LLM: a lapse-rate bucket needs on the order of a
    # hundred reviews to mean anything (docs/config.md, `LEARNER_MIN_REVIEWS`).
    learner_min_reviews: int = Field(default=100, ge=1, validation_alias="LEARNER_MIN_REVIEWS")
    # Nightly optimizer schedule: a 5-field cron in RECALLY_TIMEZONE.
    learner_cron: str = Field(default="0 3 * * *", validation_alias="LEARNER_CRON")

    @field_validator("timezone")
    @classmethod
    def _timezone_is_an_iana_zone(cls, value: str) -> str:
        ZoneInfo(value)  # raises ZoneInfoNotFoundError on a typo
        return value

    @field_validator("fsrs_learning_steps_minutes")
    @classmethod
    def _learning_steps_are_positive_minutes(cls, value: str) -> str:
        """Fail startup on a typo rather than the first review with a broken step."""
        steps = [part.strip() for part in value.split(",") if part.strip()]
        if not steps or any(float(step) <= 0 for step in steps):
            raise ValueError("must be comma-separated positive minutes, e.g. '1,10'")
        return value

    @field_validator("learner_cron")
    @classmethod
    def _learner_cron_is_a_crontab(cls, value: str) -> str:
        """Fail startup on a typo rather than the first missed nightly run."""
        CronTrigger.from_crontab(value)  # raises ValueError on a malformed expression
        return value

    @field_validator("push_window")
    @classmethod
    def _push_window_is_a_local_time_range(cls, value: str) -> str:
        """Fail startup on a typo rather than the first tick reading a broken window."""
        start, end = _parse_push_window(value)
        if not start < end:
            raise ValueError("must be a local-time range with start before end, e.g. '08:00-21:00'")
        return value

    @property
    def push_window_bounds(self) -> tuple[time, time]:
        """The parsed window, e.g. "08:00-21:00" -> (08:00, 21:00). Start inclusive,
        end exclusive."""
        return _parse_push_window(self.push_window)

    @property
    def fsrs_learning_steps(self) -> tuple[timedelta, ...]:
        """The parsed learning steps, e.g. "1,10" -> (1 minute, 10 minutes)."""
        return tuple(
            timedelta(minutes=float(part))
            for part in self.fsrs_learning_steps_minutes.split(",")
            if part.strip()
        )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """The process-wide settings, parsed on first use."""
    return Settings()  # type: ignore[call-arg]  # every field comes from the environment
