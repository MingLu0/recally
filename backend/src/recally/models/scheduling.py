"""FSRS state, review history, the fitted parameters, and push bookkeeping."""

from datetime import datetime
from typing import Any

from sqlalchemy import (
    JSON,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from recally.models.base import Base, UserScopedMixin, utc_now
from recally.models.cards import Card


class CardState(UserScopedMixin, Base):
    """FSRS scheduling state, mirroring the `py-fsrs` 6.x `Card` object.

    The columns match the library's `to_dict`/`from_dict` shape so state round-trips
    without translation. FSRS 6 has no `new` state — a fresh card is `learning` at step
    0 — and `reps`/`lapses` are not on the library object, so they are derived from
    `review_logs` rather than stored here.
    """

    __tablename__ = "card_state"

    card_id: Mapped[int] = mapped_column(ForeignKey("cards.id"), primary_key=True)
    state: Mapped[str] = mapped_column(String(16), nullable=False)
    # Learning/relearning step index; NULL while in `review`.
    step: Mapped[int | None] = mapped_column(Integer, nullable=True)
    stability: Mapped[float | None] = mapped_column(Float, nullable=True)
    difficulty: Mapped[float | None] = mapped_column(Float, nullable=True)
    due: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    last_review: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)

    card: Mapped[Card] = relationship()


class Device(UserScopedMixin, Base):
    """A registered Android device, addressed by its FCM token."""

    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    fcm_token: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    platform: Mapped[str] = mapped_column(String(32), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utc_now)
    # Denormalised from `push_runs` so the one-push-per-day check is a single read.
    last_push_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class ReviewLog(UserScopedMixin, Base):
    """Every rating, at full fidelity: the Learner's and the optimizer's training data.

    `rated_at` is the *client* timestamp, which is what offline batches replay in order
    of; `received_at` is when the server saw it. The uniqueness of the pair makes
    re-sending a batch a no-op, so a flaky connection cannot double-count a review.
    """

    __tablename__ = "review_logs"
    __table_args__ = (UniqueConstraint("card_id", "rated_at"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    card_id: Mapped[int] = mapped_column(ForeignKey("cards.id"), nullable=False)
    rated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    received_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utc_now)
    # 1=Again 2=Hard 3=Good 4=Easy.
    rating: Mapped[int] = mapped_column(Integer, nullable=False)
    # Flip-to-rate duration, captured by the app on every rating.
    response_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    scheduled_days: Mapped[int] = mapped_column(Integer, nullable=False)
    # FSRS state when rated, taken from the library `ReviewLog`.
    state_before: Mapped[str] = mapped_column(String(16), nullable=False)
    device_id: Mapped[int | None] = mapped_column(ForeignKey("devices.id"), nullable=True)


class FsrsParams(UserScopedMixin, Base):
    """A fit from `fsrs.Optimizer`. The latest row is the active one.

    Library defaults are used until the first fit clears `OPTIMIZER_MIN_REVIEWS`.
    """

    __tablename__ = "fsrs_params"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    # The 21 FSRS weights.
    parameters: Mapped[list[float]] = mapped_column(JSON, nullable=False)
    desired_retention: Mapped[float] = mapped_column(Float, nullable=False)
    review_count: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utc_now)


class PushRun(UserScopedMixin, Base):
    """One FCM push actually sent (hard rule 8).

    Backs the "no push while the previous batch is unreviewed" check: the notifier
    loads the latest row and looks for a `review_logs` entry after `sent_at` for each
    id in `card_ids`. That loop runs in Python over one small batch per day, so the
    `push_run_cards` join table is deferred to the Postgres cutover (ADR-004).
    """

    __tablename__ = "push_runs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    device_id: Mapped[int] = mapped_column(ForeignKey("devices.id"), nullable=False)
    sent_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utc_now)
    card_ids: Mapped[list[int]] = mapped_column(JSON, nullable=False, default=list)
    due_count: Mapped[int] = mapped_column(Integer, nullable=False)


class LlmCall(UserScopedMixin, Base):
    """One call through `llm.py`: the trace and the replay corpus (ADR-006).

    Every LLM call in the system writes a row here, so a bad verdict can be inspected
    and prompts can be re-run against human-labelled cards later.
    """

    __tablename__ = "llm_calls"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    # `role/variant`, e.g. `writer/default` — the trace names the implementation that
    # made the call, not just the role (ADR-007).
    agent: Mapped[str] = mapped_column(String(64), nullable=False)
    ingest_run_id: Mapped[int | None] = mapped_column(ForeignKey("ingest_runs.id"), nullable=True)
    # Set for Writer and Critic calls; null for the Curator (batch) and the Learner.
    unit_id: Mapped[int | None] = mapped_column(ForeignKey("curated_units.id"), nullable=True)
    # Set for Critic calls and for Writer revisions of an existing card.
    card_id: Mapped[int | None] = mapped_column(ForeignKey("cards.id"), nullable=True)
    # 1-based Writer ⇄ Critic round; null outside the loop.
    round: Mapped[int | None] = mapped_column(Integer, nullable=True)
    model: Mapped[str] = mapped_column(String(128), nullable=False)
    # Both null when `LLM_LOG_PAYLOADS=false`.
    request: Mapped[Any | None] = mapped_column(JSON, nullable=True)
    response: Mapped[Any | None] = mapped_column(JSON, nullable=True)
    input_tokens: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    # From `litellm.completion_cost`; 0 for Ollama. Integer micro-USD throughout.
    cost_microusd: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    latency_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utc_now)
