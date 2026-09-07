"""The agent contract (docs/backend.md, "The agent contract"; ADR-007).

Each LLM role — Curator, Writer, Critic, Learner — is a `typing.Protocol` with frozen
dataclass request/result types matching the inputs and outputs in docs/agents.md.
Implementations live in `agents/<role>/` packages and register in `agents/registry.py`;
the pipeline binds to the protocols, never to an implementation.

Two structural guarantees live here:

- `AgentContext` carries the run correlation id, config and the `llm.py` callable —
  and deliberately no database handle. Agents return typed results; every write
  (card statuses, `processed` flips, `truncated` write-backs) is the runner's job.
  That is what makes hard rules 1, 7 and 9 structural rather than conventional.
- Result types carry a `verdict`/`decision`, never a `cards.status` value. `approved`
  is inexpressible from an agent, so nothing bypasses the human approval queue.
"""

from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

from recally.config import Settings
from recally.llm import LlmCaller


@dataclass(frozen=True)
class AgentContext:
    """What every agent is handed alongside its request.

    `ingest_run_id` is the run correlation id written to `llm_calls` (None for the
    nightly Learner job, which serves no ingest run). `settings` carries config —
    including the per-role model tiers — and `llm` is the sole LLM access path (hard
    rule 3). There is deliberately no database handle here: agents cannot persist
    anything, so persistence invariants cannot be broken below the runner.
    """

    ingest_run_id: int | None
    settings: Settings
    llm: LlmCaller


# --- Curator (docs/agents.md §2) -------------------------------------------------


@dataclass(frozen=True)
class HighlightInput:
    """One highlight as the Curator sees it: an id and the exported text, as-is.

    `text` may be clipped mid-word; the Curator flags such rows via
    `truncated_highlight_ids` rather than reconstructing them (hard rule 7).
    """

    id: int
    text: str
    personal_note: str | None


@dataclass(frozen=True)
class CuratorRequest:
    """A batch of unprocessed highlights for one chapter, in export order."""

    book_title: str
    chapter: str | None
    highlights: list[HighlightInput]


@dataclass(frozen=True)
class CuratedUnitDraft:
    """One curated unit. More than one `highlight_ids` entry means a group.

    `truncated_highlight_ids` names source rows the runner flags `truncated=true`;
    the unit itself does not store the flag. `decision` is the whole vocabulary —
    `keep` or `drop` — never a status.
    """

    highlight_ids: list[int]
    curated_text: str
    tags: list[str]
    truncated_highlight_ids: list[int]
    decision: Literal["keep", "drop"]
    reason: str


@dataclass(frozen=True)
class CuratorResult:
    units: list[CuratedUnitDraft]


# --- Writer (docs/agents.md §3) ---------------------------------------------------


@dataclass(frozen=True)
class CardDraft:
    """One candidate card. Cloze cards carry a single deletion."""

    type: Literal["qa", "cloze"]
    front: str
    back: str
    rationale: str


@dataclass(frozen=True)
class WriterRequest:
    """One curated unit plus the current `writer_guidance` (hard rule 10).

    `guidance_version`/`guidance` are None before the Learner has produced its first
    guidance row. `critique` carries the previous round's Critic feedback when this
    call is a revision; the round cap lives in the runner, not here (hard rule 9).
    """

    curated_text: str
    tags: list[str]
    guidance_version: int | None
    guidance: str | None
    critique: str | None


@dataclass(frozen=True)
class WriterResult:
    """1–3 candidate cards (docs/agents.md §3 output)."""

    cards: list[CardDraft]

    def __post_init__(self) -> None:
        if not 1 <= len(self.cards) <= 3:
            raise ValueError(f"WriterResult.cards must hold 1-3 cards, got {len(self.cards)}")


# --- Critic (docs/agents.md §4) ---------------------------------------------------


@dataclass(frozen=True)
class CriticRequest:
    """Candidate cards from the Writer plus the source highlight text to check against.

    `source_truncated` is set by the runner when any source highlight of the unit is
    flagged `truncated` (the flag lives on `highlights` rows, not on units or cards).
    A clipped source must be marked as such in the prompt: fidelity is judged against
    the partial text, and the Critic must not penalise a card for text the export
    lost nor accept one that invents the missing part (hard rule 7).
    """

    cards: list[CardDraft]
    source_text: str
    source_truncated: bool = False


@dataclass(frozen=True)
class CardVerdict:
    """Per-card verdict. The runner maps verdicts to statuses; the Critic never names one."""

    verdict: Literal["accept", "revise", "reject"]
    critique: str


@dataclass(frozen=True)
class CriticResult:
    """One verdict per request card, in the same order."""

    verdicts: list[CardVerdict]


# --- Learner stage B (docs/agents.md §7) ------------------------------------------


@dataclass(frozen=True)
class LearnerRequest:
    """Aggregated review history (lapse rates per bucket, edits, response times).

    Aggregates, not raw logs — the nightly job caps input size (docs/agents.md,
    "Cost controls"). The current guidance is included so the Learner can revise it
    rather than starting from a blank page.
    """

    review_aggregates: dict[str, Any]
    current_guidance_version: int | None
    current_guidance: str | None


@dataclass(frozen=True)
class LearnerResult:
    """`guidance` None means no new `writer_guidance` row is warranted.

    The runner writes the new versioned row (hard rule 10) and routes leech rewrites
    back through the normal Writer ⇄ Critic → human path.
    """

    guidance: str | None
    rationale: str
    leech_card_ids: list[int] = field(default_factory=list)


# --- Role protocols ----------------------------------------------------------------


class Curator(Protocol):
    def __call__(self, request: CuratorRequest, ctx: AgentContext) -> CuratorResult: ...


class Writer(Protocol):
    def __call__(self, request: WriterRequest, ctx: AgentContext) -> WriterResult: ...


class Critic(Protocol):
    def __call__(self, request: CriticRequest, ctx: AgentContext) -> CriticResult: ...


class Learner(Protocol):
    def __call__(self, request: LearnerRequest, ctx: AgentContext) -> LearnerResult: ...
