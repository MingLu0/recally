"""The pipeline runner: Curator → Writer ⇄ Critic, owning every database transition.

Spec: docs/agents.md, "Pipeline runner and handoffs". Agents never call each other
and hold no DB session; this module resolves them through `agents/registry.py`
(ADR-007 — never a direct variant import), drives them in order, and is the only
writer of `cards.status`, `highlights.processed`, `highlights.truncated` and the
`curated_units` rows.

Handoffs:

- **Ingestion → Curator is DB state.** Every `highlights` row with `processed=false`,
  grouped by (book, chapter), regardless of which ingest inserted it. That is what
  makes re-running the pipeline the retry path (docs/architecture.md, "Failure
  handling").
- **Curator → Writer ⇄ Critic is in memory, within one run.** Curator output is
  persisted to `curated_units` + `curated_unit_highlights` (with `ingest_run_id`)
  *before any Writer call*, so a crash mid-run leaves retryable state rather than
  lost work.
- **Critic → human is DB state.** The verdict becomes `cards.status`: `accept` →
  `pending_review` (`approved` only when `AUTO_APPROVE_ROUND1_ACCEPT` is on and it is
  round 1 — the hard-rule-1 exception), `revise` re-enters the Writer for that card
  only, `reject` → `needs_human` with the critique in `status_reason`, and
  `LLM_MAX_ROUNDS` without an `accept` → `needs_human` (hard rule 9). The pipeline
  never writes `rejected`.

Idempotency (docs/agents.md): `highlights.processed` flips only when the covering
unit reaches a terminal outcome (`drop`, or every card written with a status) — not
when the Curator merely ran. Keep units that already have cards are skipped, and
cardless `keep` units whose highlights are all still unprocessed are orphans of a
crashed run: they are deleted *before* the Curator runs, and the Curator recreates
them.

Failure handling (docs/architecture.md): an agent exception is recorded on
`ingest_runs.error`, the run is marked finished, and the exception does not
propagate — the watcher and the scheduler keep running, and the next run retries.

One transaction rule throughout: the runner's session holds no open transaction
while an agent runs. Every agent call is therefore preceded by a commit, and result
writes follow it. Two independent reasons, both live — this is not test-only
scaffolding, and deleting the commit-before-agent-call discipline would break
production:

1. `LlmCaller` commits each `llm_calls` row on its own session (ADR-006). SQLite
   serializes writers across connections, so an open runner transaction while the
   wrapper commits on its own pooled connection yields `database is locked`. WAL
   (`db.py`) relaxes reader/writer contention but not writer/writer, so the rule
   still stands.
2. Under the test suite's `StaticPool` the runner and the wrapper genuinely share
   one connection, where the wrapper's commit would also commit the runner's
   pending work.

Concurrency (ADR-015): with `LLM_CONCURRENCY > 1` each keep unit's Writer ⇄ Critic
chain resolves on a worker thread and only `_persist_unit` touches the session, on
the main thread. That split is what the `_resolve_*` helpers' missing `session`
parameter encodes — SQLAlchemy's `Session` is not thread-safe, and the failure mode
is silent. Units commit as they complete, so a failure leaves finished units intact
and the failed unit's highlights retryable.
"""

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Any, Literal, overload

from sqlalchemy import func, select
from sqlalchemy.orm import Session, sessionmaker

from recally.agents.base import (
    AgentContext,
    CardDraft,
    Critic,
    CriticRequest,
    Curator,
    CuratorRequest,
    HighlightInput,
    Writer,
    WriterRequest,
)
from recally.agents.registry import AgentRegistry, default_registry
from recally.config import Settings
from recally.llm import LlmCaller
from recally.models import (
    Card,
    CuratedUnit,
    CuratedUnitHighlight,
    Highlight,
    IngestRun,
    LlmCall,
    WriterGuidance,
)
from recally.models.base import utc_now


class _CorrelatedLlm(LlmCaller):
    """An `LlmCaller` view that pins the correlation fields the runner knows.

    Agents pass only messages, a model and their own `role/variant` name; which unit
    and round a call serves is the runner's knowledge (agents hold no state between
    calls), so it is injected here and lands on the `llm_calls` row
    (docs/data-model.md).
    """

    def __init__(
        self,
        inner: LlmCaller,
        *,
        unit_id: int | None = None,
        card_id: int | None = None,
        round: int | None = None,
    ) -> None:
        self._inner = inner
        self._unit_id = unit_id
        self._card_id = card_id
        self._round = round

    def __call__(
        self,
        messages: list[dict[str, Any]],
        *,
        model: str,
        agent: str,
        ingest_run_id: int | None = None,
        unit_id: int | None = None,
        card_id: int | None = None,
        round: int | None = None,
    ) -> str:
        return self._inner(
            messages,
            model=model,
            agent=agent,
            ingest_run_id=ingest_run_id,
            unit_id=self._unit_id if unit_id is None else unit_id,
            card_id=self._card_id if card_id is None else card_id,
            round=self._round if round is None else round,
        )


@dataclass(frozen=True)
class _CardOutcome:
    """One card's resolved result, before anything touches the database.

    The Writer ⇄ Critic loop produces this and nothing else; `_write_card` turns it
    into a `cards` row on the main thread. Keeping resolution and persistence apart
    is what lets units resolve on worker threads (ADR-015) — a worker never holds a
    `Session`, which SQLAlchemy does not make thread-safe.
    """

    draft: CardDraft
    status: str
    status_reason: str | None
    generation_rounds: int


@dataclass
class _RunStats:
    """The pipeline's `ingest_runs` counters, accumulated as work commits."""

    units_kept: int = 0
    units_dropped: int = 0
    highlights_dropped: int = 0
    cards_generated: int = 0
    # (unit, cards) for the run's finished keep units; per-card cost shares are
    # attributed from `llm_calls` at finalize time.
    finished: list[tuple[CuratedUnit, list[Card]]] = field(default_factory=list)


@overload
def _agent_context(
    role: Literal["curator"],
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    ingest_run_id: int | None,
    *,
    unit_id: int | None = None,
    card_id: int | None = None,
    round: int | None = None,
) -> tuple[Curator, AgentContext]: ...


@overload
def _agent_context(
    role: Literal["writer"],
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    ingest_run_id: int | None,
    *,
    unit_id: int | None = None,
    card_id: int | None = None,
    round: int | None = None,
) -> tuple[Writer, AgentContext]: ...


@overload
def _agent_context(
    role: Literal["critic"],
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    ingest_run_id: int | None,
    *,
    unit_id: int | None = None,
    card_id: int | None = None,
    round: int | None = None,
) -> tuple[Critic, AgentContext]: ...


def _agent_context(
    role: str,
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    ingest_run_id: int | None,
    *,
    unit_id: int | None = None,
    card_id: int | None = None,
    round: int | None = None,
) -> tuple[Any, AgentContext]:
    """Resolve `role` through the registry and build the context for one invocation."""
    implementation = registry.resolve(role, settings)
    context = AgentContext(
        ingest_run_id=ingest_run_id,
        settings=settings,
        llm=_CorrelatedLlm(llm_caller, unit_id=unit_id, card_id=card_id, round=round),
    )
    return implementation, context


def run(
    *,
    settings: Settings,
    session_factory: sessionmaker[Session],
    llm_caller: LlmCaller,
    ingest_run_id: int,
    agent_registry: AgentRegistry | None = None,
) -> None:
    """Run Curator → Writer ⇄ Critic over every unprocessed highlight.

    Never raises on an agent failure: the error is recorded on the run row and the
    run is marked finished, so the watcher keeps running and the next run retries
    (docs/architecture.md, "Failure handling").
    """
    registry = agent_registry or default_registry
    with session_factory() as session:
        ingest_run = session.get(IngestRun, ingest_run_id)
        if ingest_run is None:
            raise ValueError(f"no ingest_runs row with id {ingest_run_id}")
        stats = _RunStats()
        try:
            _run_phases(session, registry, settings, llm_caller, ingest_run, stats)
        except Exception as exc:
            session.rollback()
            _record_outcome(session, ingest_run, stats, error=f"{type(exc).__name__}: {exc}")
            session.commit()
            return
        _record_outcome(session, ingest_run, stats, error=None)
        session.commit()


def rewrite_leech_card(
    *,
    settings: Settings,
    session_factory: sessionmaker[Session],
    llm_caller: LlmCaller,
    leech_card_id: int,
    agent_registry: AgentRegistry | None = None,
) -> int | None:
    """One leech → one rewrite card, through the normal Writer ⇄ Critic loop.

    Spec: docs/agents.md §7 — "Rewrites go through the normal Writer ⇄ Critic →
    human approval path as new cards." This is the same `_resolve_card` initial
    generation uses (the 3-round cap and the queue statuses included); what
    differs is correlation, not the path:

    - the rewrite is a new `cards` row on the leech's `unit_id`, stamped with
      `supersedes_card_id` so approval knows what it replaces;
    - every `llm_calls` row names the leech's `card_id` — these calls serve an
      existing card (docs/data-model.md), unlike the null of initial generation;
    - `ingest_run_id` is null, like the Learner call that prompted the rewrite.

    The pipeline never writes `approved` (hard rule 1): the rewrite enters the
    queue and a human decides. Returns the new card's id, or None when the named
    card is gone (the job validates ids first, so this is a race guard only).
    """
    registry = agent_registry or default_registry
    with session_factory() as session:
        leech = session.get(Card, leech_card_id)
        if leech is None:
            return None
        unit = leech.unit
        highlights = _unit_highlights(session, leech.unit_id)
        guidance_version, guidance = _latest_guidance(session)
        source_truncated = any(highlight.truncated for highlight in highlights)
        session.commit()  # no open transaction while an agent runs (module docstring)

        writer, writer_context = _agent_context(
            "writer",
            registry,
            settings,
            llm_caller,
            None,
            unit_id=unit.id,
            card_id=leech.id,
            round=1,
        )
        result = writer(
            WriterRequest(
                curated_text=unit.curated_text,
                tags=unit.tags,
                guidance_version=guidance_version,
                guidance=guidance,
                critique=_leech_rewrite_critique(leech),
            ),
            writer_context,
        )
        # One leech, one rewrite: the first draft is the rewrite; any further
        # drafts are discarded, exactly as a revision keeps only its first card.
        outcome = _resolve_card(
            registry,
            settings,
            llm_caller,
            None,
            unit,
            result.cards[0],
            source_truncated=source_truncated,
            guidance_version=guidance_version,
            guidance=guidance,
            card_id=leech.id,
        )
        rewrite = _write_card(session, unit, outcome, settings)
        rewrite.supersedes_card_id = leech.id
        session.commit()
        return rewrite.id


def _leech_rewrite_critique(leech: Card) -> str:
    """The leech instruction, sent through the Writer's existing critique channel.

    The protocol has no rewrite-specific field, and a second generation path is
    exactly what docs/agents.md §7 forbids — so the instruction rides the same
    channel a Critic's feedback does.
    """
    return (
        "This card is a leech: it has failed review 3 or more times.\n"
        f"Front: {leech.front}\nBack: {leech.back}\n\n"
        "Rewrite it as an alternative explanation or analogy of the same idea; "
        "the original phrasing is not sticking."
    )


def _run_phases(
    session: Session,
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    ingest_run: IngestRun,
    stats: _RunStats,
) -> None:
    """The run itself: orphan cleanup, the Curator phase, then the Writer ⇄ Critic phase."""
    _delete_orphan_keep_units(session)
    groups = _unprocessed_by_chapter(session)
    session.commit()  # close the read transaction before the first agent call

    # Phase 1: curate every chapter, persisting units before any Writer call.
    new_keep_units: list[tuple[CuratedUnit, list[Highlight]]] = []
    for (book_title, chapter), highlights in groups:
        curated = _curate_chapter(
            session, registry, settings, llm_caller, ingest_run, book_title, chapter, highlights
        )
        by_id = {highlight.id: highlight for highlight in highlights}
        for unit, highlight_ids in curated:
            covered = [
                by_id[highlight_id] for highlight_id in highlight_ids if highlight_id in by_id
            ]
            if unit.decision == "drop":
                stats.units_dropped += 1
                stats.highlights_dropped += len(covered)
                for highlight in covered:
                    # A drop is terminal the moment the Curator decides it.
                    highlight.processed = True
            else:
                stats.units_kept += 1
                new_keep_units.append((unit, covered))
        session.commit()

    # Phase 2: each keep unit → Writer, each card → Critic ⇄ Writer.
    guidance_version, guidance = _latest_guidance(session)
    card_counts = _card_counts(session, [unit.id for unit, _ in new_keep_units])
    session.commit()  # close the read transaction before the first Writer call
    # Idempotency: a keep unit that already has cards is never re-generated.
    pending = [
        (unit, highlights)
        for unit, highlights in new_keep_units
        if card_counts.get(unit.id, 0) == 0
    ]
    if settings.llm_concurrency == 1:
        # The historical path, untouched: the shipped default is provably today's
        # code, so a rollback needs no deploy (ADR-015, "Rollout").
        for unit, highlights in pending:
            _persist_unit(
                session,
                stats,
                unit,
                highlights,
                _resolve_unit_cards(
                    registry,
                    settings,
                    llm_caller,
                    ingest_run.id,
                    unit,
                    highlights,
                    guidance_version,
                    guidance,
                ),
                settings,
            )
        return

    _resolve_units_concurrently(
        session,
        registry,
        settings,
        llm_caller,
        ingest_run,
        stats,
        pending,
        guidance_version,
        guidance,
    )


def _resolve_units_concurrently(
    session: Session,
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    ingest_run: IngestRun,
    stats: _RunStats,
    pending: list[tuple[CuratedUnit, list[Highlight]]],
    guidance_version: int | None,
    guidance: str | None,
) -> None:
    """Resolve `pending` units on a thread pool; persist each as it finishes.

    Units are independent chains, so overlapping them turns an ingest's summed
    provider latency into roughly its slowest single unit (ADR-015). Only the LLM
    half runs on a worker: `_resolve_unit_cards` holds no session, and every write
    happens back here on the main thread, one unit at a time.

    `as_completed`, never `map`: a unit commits the moment it finishes, so a crash
    partway through keeps everything already done — today's recovery behaviour.
    `map` would batch every write to the end of the run and lose all of it.

    Partial failure is per unit (ADR-015): a raising unit is skipped, its highlights
    stay `processed=false` for the next run to retry, and the loop keeps draining so
    in-flight siblings are not abandoned. The first exception is re-raised once the
    pool has drained, which is what puts it on `ingest_runs.error` via `run()`;
    swallowing it would let the run row claim success while failed units sat
    unprocessed. Units already committed survive the rollback, which discards only
    the uncommitted tail.
    """
    first_error: Exception | None = None
    with ThreadPoolExecutor(max_workers=settings.llm_concurrency) as pool:
        futures = {
            pool.submit(
                _resolve_unit_cards,
                registry,
                settings,
                llm_caller,
                ingest_run.id,
                unit,
                highlights,
                guidance_version,
                guidance,
            ): (unit, highlights)
            for unit, highlights in pending
        }
        for future in as_completed(futures):
            unit, highlights = futures[future]
            try:
                outcomes = future.result()
            except Exception as exc:
                first_error = first_error or exc
                continue
            _persist_unit(session, stats, unit, highlights, outcomes, settings)
    if first_error is not None:
        raise first_error


def _persist_unit(
    session: Session,
    stats: _RunStats,
    unit: CuratedUnit,
    highlights: list[Highlight],
    outcomes: list[_CardOutcome],
    settings: Settings,
) -> None:
    """Commit one resolved unit: its cards, its `processed` flips, its counters.

    Everything in this function touches the session, and nothing else in the unit's
    path does — which is the whole point (ADR-015). Under `LLM_CONCURRENCY > 1` this
    runs on the main thread only, one unit at a time, while workers resolve the rest.
    A unit commits the moment it is persisted, so a later crash keeps the units
    already done — the documented retry path (docs/architecture.md, "Failure
    handling").
    """
    cards = [_write_card(session, unit, outcome, settings) for outcome in outcomes]
    stats.cards_generated += len(cards)
    for highlight in highlights:
        # Terminal outcome: every card of the unit now has a status.
        highlight.processed = True
    stats.finished.append((unit, cards))
    session.commit()


def _delete_orphan_keep_units(session: Session) -> None:
    """Delete cardless `keep` units whose highlights are all still unprocessed.

    Those are orphans of a run that crashed between the Curator and the Writer; the
    Curator recreates them from the same highlights on this run, so keeping them
    would duplicate the unit (docs/agents.md, idempotency).
    """
    candidates = session.scalars(select(CuratedUnit).where(CuratedUnit.decision == "keep")).all()
    card_counts = _card_counts(session, [unit.id for unit in candidates])
    for unit in candidates:
        if card_counts.get(unit.id, 0) > 0:
            continue
        highlights = _unit_highlights(session, unit.id)
        if highlights and all(not highlight.processed for highlight in highlights):
            for link in unit.highlight_links:
                session.delete(link)
            session.delete(unit)


def _unprocessed_by_chapter(
    session: Session,
) -> list[tuple[tuple[str, str | None], list[Highlight]]]:
    """All `processed=false` highlights, grouped by (book, chapter), in export order."""
    highlights = session.scalars(
        select(Highlight).where(Highlight.processed.is_(False)).order_by(Highlight.export_position)
    ).all()
    groups: dict[tuple[str, str | None], list[Highlight]] = {}
    for highlight in highlights:
        key = (highlight.book.title, highlight.chapter)
        groups.setdefault(key, []).append(highlight)
    return list(groups.items())


def _curate_chapter(
    session: Session,
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    ingest_run: IngestRun,
    book_title: str,
    chapter: str | None,
    highlights: list[Highlight],
) -> list[tuple[CuratedUnit, list[int]]]:
    """One Curator call over the chapter's highlights; persist every unit it returns.

    The runner writes `truncated=true` back onto the flagged `highlights` rows —
    the Curator only names ids (agents hold no session, ADR-007; hard rule 7).
    Returns each persisted unit with the highlight ids it covers.
    """
    curator, context = _agent_context("curator", registry, settings, llm_caller, ingest_run.id)
    request = CuratorRequest(
        book_title=book_title,
        chapter=chapter,
        highlights=[
            HighlightInput(
                id=highlight.id, text=highlight.raw_text, personal_note=highlight.personal_note
            )
            for highlight in highlights
        ],
    )
    result = curator(request, context)

    truncated_ids = {
        truncated_id for unit in result.units for truncated_id in unit.truncated_highlight_ids
    }
    by_id = {highlight.id: highlight for highlight in highlights}
    for truncated_id in truncated_ids:
        if truncated_id in by_id:
            by_id[truncated_id].truncated = True

    persisted: list[tuple[CuratedUnit, list[int]]] = []
    for draft in result.units:
        unit = CuratedUnit(
            ingest_run_id=ingest_run.id,
            curated_text=draft.curated_text,
            tags=draft.tags,
            decision=draft.decision,
            reason=draft.reason or None,
        )
        session.add(unit)
        session.flush()
        for highlight_id in draft.highlight_ids:
            session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight_id))
        persisted.append((unit, draft.highlight_ids))
    return persisted


def _resolve_unit_cards(
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    ingest_run_id: int | None,
    unit: CuratedUnit,
    highlights: list[Highlight],
    guidance_version: int | None,
    guidance: str | None,
) -> list[_CardOutcome]:
    """The Writer ⇄ Critic loop for one keep unit; one outcome per draft, each
    resolved to its terminal verdict (docs/agents.md §3–§4).

    Session-free (ADR-015), so a whole unit's LLM chain can run on a worker thread
    while the main thread persists whichever units have already finished. The
    caller turns each outcome into a `cards` row.
    """
    source_truncated = any(highlight.truncated for highlight in highlights)
    writer, writer_context = _agent_context(
        "writer", registry, settings, llm_caller, ingest_run_id, unit_id=unit.id, round=1
    )
    result = writer(
        WriterRequest(
            curated_text=unit.curated_text,
            tags=unit.tags,
            guidance_version=guidance_version,
            guidance=guidance,
            critique=None,
        ),
        writer_context,
    )
    return [
        _resolve_card(
            registry,
            settings,
            llm_caller,
            ingest_run_id,
            unit,
            draft,
            source_truncated=source_truncated,
            guidance_version=guidance_version,
            guidance=guidance,
        )
        for draft in result.cards
    ]


def _resolve_card(
    registry: AgentRegistry,
    settings: Settings,
    llm_caller: LlmCaller,
    ingest_run_id: int | None,
    unit: CuratedUnit,
    draft: CardDraft,
    *,
    source_truncated: bool,
    guidance_version: int | None,
    guidance: str | None,
    card_id: int | None = None,
) -> _CardOutcome:
    """One card's Critic ⇄ Writer rounds, bounded by LLM_MAX_ROUNDS (hard rule 9).

    The round count lives here, not in the agents: the Writer and the Critic each
    see one request and return one result. A `revise` re-enters the Writer for this
    card only — siblings already accepted keep their status and are never re-sent.

    Session-free by construction (ADR-015): this returns a `_CardOutcome` and the
    caller writes it. That is what lets a whole unit resolve on a worker thread —
    the only state it reads off `unit` (`curated_text`, `tags`) was loaded before
    the thread started and stays readable because the session sets
    `expire_on_commit=False` (`db.py`).
    """
    round_number = 1
    current = draft
    while True:
        critic, critic_context = _agent_context(
            "critic",
            registry,
            settings,
            llm_caller,
            ingest_run_id,
            unit_id=unit.id,
            card_id=card_id,
            round=round_number,
        )
        verdict = critic(
            CriticRequest(
                cards=[current],
                source_text=unit.curated_text,
                source_truncated=source_truncated,
            ),
            critic_context,
        ).verdicts[0]

        if verdict.verdict == "accept":
            status = (
                "approved"
                if settings.auto_approve_round1_accept and round_number == 1
                else "pending_review"
            )
            return _CardOutcome(
                draft=current,
                status=status,
                status_reason=None,
                generation_rounds=round_number,
            )
        if verdict.verdict == "reject":
            return _CardOutcome(
                draft=current,
                status="needs_human",
                status_reason=verdict.critique,
                generation_rounds=round_number,
            )
        if round_number >= settings.llm_max_rounds:
            return _CardOutcome(
                draft=current,
                status="needs_human",
                status_reason=(
                    f"Critic: {verdict.critique}; "
                    f"Writer {settings.llm_max_rounds} rounds unresolved."
                ),
                generation_rounds=round_number,
            )

        round_number += 1
        writer, writer_context = _agent_context(
            "writer",
            registry,
            settings,
            llm_caller,
            ingest_run_id,
            unit_id=unit.id,
            card_id=card_id,
            round=round_number,
        )
        revised = writer(
            WriterRequest(
                curated_text=unit.curated_text,
                tags=unit.tags,
                guidance_version=guidance_version,
                guidance=guidance,
                critique=verdict.critique,
            ),
            writer_context,
        )
        # A revision rewrites the one card under review, so its first card is the
        # rewrite; any further cards in the response are discarded.
        current = revised.cards[0]


def _write_card(
    session: Session,
    unit: CuratedUnit,
    outcome: _CardOutcome,
    settings: Settings,
) -> Card:
    """Stage the single write of a resolved card, at its terminal verdict.

    Main thread only: this is the half of resolution that touches the session
    (ADR-015). `original_front`/`original_back` keep the Writer text the Critic
    accepted (or gave up on), so a human edit at approval time stays distinguishable
    from what the model produced (docs/data-model.md, `cards`). No flush here: the
    unit's cards commit together with the `processed` flips at the end of the unit,
    and no transaction may be open while the next agent call runs (module docstring).
    """
    draft = outcome.draft
    card = Card(
        unit_id=unit.id,
        type=draft.type,
        front=draft.front,
        back=draft.back,
        original_front=draft.front,
        original_back=draft.back,
        tags=unit.tags,
        status=outcome.status,
        status_reason=outcome.status_reason,
        generation_rounds=outcome.generation_rounds,
        model=settings.llm_model_writer,
        guidance_version=draft.guidance_version,
    )
    session.add(card)
    return card


def _record_outcome(
    session: Session, ingest_run: IngestRun, stats: _RunStats, *, error: str | None
) -> None:
    """Write the run's counters, cost and finish state, on success or on failure.

    `cost_microusd` is the sum of this run's `llm_calls` rows; each card gets an
    equal share of its unit's calls (the unit's first Writer call produces all of
    its drafts, so a fairer per-card attribution does not exist).
    """
    costs_by_unit: dict[int | None, int] = {
        unit_id: int(total or 0)
        for unit_id, total in session.execute(
            select(LlmCall.unit_id, func.sum(LlmCall.cost_microusd))
            .where(LlmCall.ingest_run_id == ingest_run.id)
            .group_by(LlmCall.unit_id)
        )
        .tuples()
        .all()
    }
    for unit, cards in stats.finished:
        if not cards:
            continue
        unit_cost = costs_by_unit.get(unit.id) or 0
        share, remainder = divmod(unit_cost, len(cards))
        for index, card in enumerate(cards):
            card.cost_microusd = share + (1 if index < remainder else 0)

    ingest_run.units_kept = stats.units_kept
    ingest_run.units_dropped = stats.units_dropped
    ingest_run.highlights_dropped = stats.highlights_dropped
    ingest_run.cards_generated = stats.cards_generated
    ingest_run.cost_microusd = int(
        session.scalar(
            select(func.coalesce(func.sum(LlmCall.cost_microusd), 0)).where(
                LlmCall.ingest_run_id == ingest_run.id
            )
        )
        or 0
    )
    ingest_run.error = error
    ingest_run.finished_at = utc_now()


def _latest_guidance(session: Session) -> tuple[int | None, str | None]:
    """The newest `writer_guidance` row, or (None, None) before the Learner's first."""
    guidance = session.scalar(
        select(WriterGuidance).order_by(WriterGuidance.version.desc()).limit(1)
    )
    if guidance is None:
        return None, None
    return guidance.version, guidance.guidance


def _card_counts(session: Session, unit_ids: list[int]) -> dict[int, int]:
    """How many cards each listed unit has; units with no cards are absent."""
    if not unit_ids:
        return {}
    return {
        unit_id: count
        for unit_id, count in session.execute(
            select(Card.unit_id, func.count())
            .where(Card.unit_id.in_(unit_ids))
            .group_by(Card.unit_id)
        )
        .tuples()
        .all()
    }


def _unit_highlights(session: Session, unit_id: int) -> list[Highlight]:
    """A unit's source highlights, in export order."""
    return list(
        session.scalars(
            select(Highlight)
            .join(CuratedUnitHighlight, CuratedUnitHighlight.highlight_id == Highlight.id)
            .where(CuratedUnitHighlight.unit_id == unit_id)
            .order_by(Highlight.export_position)
        ).all()
    )
