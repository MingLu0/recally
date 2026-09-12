"""The #215 gate: units resolve concurrently, persistence stays on the main thread.

Spec: docs/decisions/015-concurrent-unit-resolution.md. The pipeline's LLM calls ran
strictly sequentially, so an ingest cost its full summed latency in wall-clock. Units
are independent chains, so they overlap; the same calls are made, in the same order
*within* a unit, which is why card quality is unchanged by construction.

Two things about the fixtures here are load-bearing, not incidental:

**The database is file-backed, never the in-memory `StaticPool` used in
test_pipeline.py.** `StaticPool` hands every thread one connection with
`check_same_thread=False` — precisely the configuration that misbehaves under real
threads — and an in-memory database silently ignores `PRAGMA journal_mode=WAL`, so a
`StaticPool` fixture would test neither the locking this design has to survive nor
the WAL that makes it survivable.

**Scripted responses are routed by prompt content, not replayed in order.** Under
concurrency the interleaving of calls across units is nondeterministic by design, so
the strictly ordered `deque` in test_pipeline.py's `ScriptedLlm` would fail randomly.
`RoutedLlm` keys each response on the unit text the prompt carries, which makes the
assertions independent of who finishes first — the property actually under test.
"""

import json
import threading
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from sqlalchemy import select

from recally.config import Settings
from recally.container import Container
from recally.models import Base, Book, Card, CuratedUnit, Highlight, IngestRun, LlmCall

TEST_API_KEY = "test-key-not-a-real-secret"

# Each unit's curated text is its routing key: it is substituted into both the Writer
# and the Critic prompt, so a response can be chosen by which unit the call serves.
UNIT_TEXTS = [f"Curated unit number {index}." for index in range(4)]


def make_file_backed_container(tmp_path: Path, **setting_overrides: Any) -> Container:
    """A container on a real SQLite *file*, built through `create_database_engine`.

    File-backed for two reasons that both matter under threads: `sqlite://` ignores
    WAL, and `StaticPool` would share one connection across every worker. Going
    through `create_database_engine` (rather than hand-rolling an engine) is what
    attaches the WAL listener — a hand-rolled engine bypasses it entirely.
    """
    database_url = f"sqlite:///{tmp_path / 'concurrency.db'}"
    settings = Settings(
        RECALLY_DATABASE_URL=database_url, RECALLY_API_KEY=TEST_API_KEY, **setting_overrides
    )
    container = Container(settings)
    Base.metadata.create_all(container.engine)
    return container


@pytest.fixture
def tmp_container(tmp_path: Path) -> Iterator[Container]:
    """The sequential baseline, pinned rather than inherited.

    `LLM_CONCURRENCY` now defaults to 16, so a fixture that relied on the default
    would silently stop testing the sequential path.
    """
    container = make_file_backed_container(tmp_path, LLM_CONCURRENCY=1)
    try:
        yield container
    finally:
        container.engine.dispose()


class RoutedLlm:
    """Monkeypatched over `litellm.completion`; picks a response by prompt content.

    `test_pipeline.py`'s `ScriptedLlm` replays a `deque` in strict call order, which
    is exactly what concurrency makes nondeterministic. Here every response is keyed
    on a marker the prompt contains (a unit's curated text), so which thread calls
    first cannot change the outcome.

    `handler` receives the rendered prompt and returns either a response string or an
    exception instance to raise — the seam the partial-failure tests use to make one
    unit fail while its siblings succeed. Calls are recorded under a lock, since
    several threads append concurrently.
    """

    def __init__(
        self, monkeypatch: pytest.MonkeyPatch, handler: Any, *, cost_microusd: int = 0
    ) -> None:
        self._handler = handler
        self._lock = threading.Lock()
        self.prompts: list[str] = []

        def fake_completion(*, model: str, messages: list[dict[str, Any]], **kwargs: Any) -> Any:
            prompt = "\n".join(message["content"] for message in messages)
            with self._lock:
                self.prompts.append(prompt)
            scripted = self._handler(prompt)
            if isinstance(scripted, Exception):
                raise scripted
            return SimpleNamespace(
                model=model,
                choices=[
                    SimpleNamespace(message=SimpleNamespace(role="assistant", content=scripted))
                ],
                usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5),
            )

        monkeypatch.setattr("recally.llm.litellm.completion", fake_completion)
        monkeypatch.setattr(
            "recally.llm.litellm.completion_cost",
            lambda **kwargs: cost_microusd / 1_000_000,
        )


def curator_response(unit_texts: list[str], highlight_ids: list[int]) -> str:
    """One keep unit per text, each covering one highlight, in order."""
    return json.dumps(
        {
            "units": [
                {
                    "highlight_ids": [highlight_id],
                    "curated_text": text,
                    "tags": ["agents"],
                    "truncated_highlight_ids": [],
                    "decision": "keep",
                    "reason": "",
                }
                for text, highlight_id in zip(unit_texts, highlight_ids, strict=True)
            ]
        }
    )


def writer_response(*fronts: str) -> str:
    return json.dumps(
        {
            "cards": [
                {"type": "qa", "front": front, "back": f"Answer to: {front}", "rationale": "r"}
                for front in fronts
            ]
        }
    )


def critic_response(*verdicts: tuple[str, str]) -> str:
    return json.dumps(
        [{"verdict": verdict, "critique": critique} for verdict, critique in verdicts]
    )


ACCEPT = ("accept", "")
REVISE = ("revise", "Not atomic; split the two ideas.")


def is_curator_prompt(prompt: str) -> bool:
    """The Curator is the only call whose prompt carries no unit text yet."""
    return not any(text in prompt for text in UNIT_TEXTS)


def unit_index_for(prompt: str) -> int:
    """Which unit this Writer/Critic prompt serves, by the curated text it embeds."""
    for index, text in enumerate(UNIT_TEXTS):
        if text in prompt:
            return index
    raise AssertionError(f"prompt matched no unit text: {prompt[:200]}")


def is_critic_prompt(prompt: str) -> bool:
    """The Critic prompt is the one asking for a verdict on drafted cards."""
    return '"verdict"' in prompt or "verdict" in prompt.lower().split("json")[-1][:400]


def seed_highlights(container: Container, count: int) -> tuple[int, list[int]]:
    """One book, `count` unprocessed highlights in one chapter, and a run row."""
    with container.session() as session:
        run = IngestRun(filename="concurrency-oreilly-annotations.csv", user_id=1)
        session.add(run)
        book = Book(
            title="Concurrent Reading",
            source="oreilly",
            external_id="isbn-concurrency",
            user_id=1,
        )
        session.add(book)
        session.flush()
        highlight_ids = []
        for position in range(count):
            highlight = Highlight(
                book_id=book.id,
                chapter="Chapter 9",
                raw_text=f"The raw highlight number {position}.",
                dedupe_key=f"uuid-concurrency-{position}",
                source="oreilly",
                highlighted_at=date(2026, 9, 9),
                export_position=position,
                truncated=False,
                processed=False,
                user_id=1,
            )
            session.add(highlight)
            session.flush()
            highlight_ids.append(highlight.id)
        session.commit()
        return run.id, highlight_ids


def all_cards(container: Container) -> list[Card]:
    with container.session() as session:
        return list(session.scalars(select(Card).order_by(Card.id)).all())


def card_rows(container: Container) -> set[tuple[str, str, str, int]]:
    """Cards as an order-independent set: `as_completed` makes id order arbitrary."""
    return {
        (card.front, card.back, card.status, card.generation_rounds)
        for card in all_cards(container)
    }


def fetch_run(container: Container, run_id: int) -> IngestRun:
    with container.session() as session:
        run = session.get(IngestRun, run_id)
        assert run is not None
        return run


def straightforward_handler(highlight_ids: list[int], unit_count: int) -> Any:
    """Curator keeps `unit_count` units; every card is accepted on round 1."""

    def handle(prompt: str) -> str:
        if is_curator_prompt(prompt):
            return curator_response(UNIT_TEXTS[:unit_count], highlight_ids[:unit_count])
        index = unit_index_for(prompt)
        if is_critic_prompt(prompt):
            return critic_response(ACCEPT)
        return writer_response(f"Question for unit {index}?")

    return handle


def record_pool_widths(monkeypatch: pytest.MonkeyPatch) -> list[int]:
    """Record the `max_workers` of every ThreadPoolExecutor the pipeline builds.

    Returns a list that fills in as pools are constructed — empty means the
    sequential path ran and no pool was built at all.
    """
    widths: list[int] = []
    real_executor = ThreadPoolExecutor

    class RecordingExecutor(real_executor):  # type: ignore[valid-type,misc]
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            widths.append(kwargs.get("max_workers", 0))
            super().__init__(*args, **kwargs)

    monkeypatch.setattr("recally.pipeline.ThreadPoolExecutor", RecordingExecutor)
    return widths


# --- The gate ------------------------------------------------------------------


def test_resolve_card_takes_no_session() -> None:
    """Negative: the resolution helper has no `session` parameter.

    A worker thread must never be handed a `Session` — SQLAlchemy does not make one
    thread-safe, and the failure mode is silent corruption rather than an exception.
    The signature is the enforceable form of that rule.
    """
    import inspect

    from recally import pipeline

    parameters = inspect.signature(pipeline._resolve_card).parameters
    assert "session" not in parameters, (
        f"_resolve_card must take no `session` parameter, got {list(parameters)}: "
        "it runs on a worker thread under LLM_CONCURRENCY > 1 (ADR-015)"
    )


def test_concurrency_one_creates_no_thread_pool(
    tmp_container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Negative: at the shipped default the legacy path runs and no pool is built.

    The rollout plan rests on `LLM_CONCURRENCY=1` being *provably* today's code path,
    so a rollback needs no deploy. Patching the executor the pipeline imports proves
    it is never constructed, which a timing assertion could not.
    """
    run_id, highlight_ids = seed_highlights(tmp_container, 2)
    RoutedLlm(monkeypatch, straightforward_handler(highlight_ids, 2))

    constructed = record_pool_widths(monkeypatch)

    tmp_container.run_pipeline(run_id)

    assert constructed == [], (
        f"LLM_CONCURRENCY=1 must take the sequential path, but a ThreadPoolExecutor "
        f"was constructed with max_workers={constructed}: the shipped default has to "
        "be provably the historical code path (ADR-015, Rollout)"
    )
    assert len(all_cards(tmp_container)) == 2


def test_concurrent_run_produces_identical_rows(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The same verdicts at concurrency 1 and 8 produce the same card rows.

    Compared as a set: `as_completed` makes id assignment order arbitrary, which is
    the one observable difference the ADR accepts. Front/back/status/rounds are the
    content that must not move.
    """
    sequential = make_file_backed_container(tmp_path / "sequential")
    concurrent = make_file_backed_container(tmp_path / "concurrent", LLM_CONCURRENCY=8)
    try:
        for container in (sequential, concurrent):
            run_id, highlight_ids = seed_highlights(container, 4)
            RoutedLlm(monkeypatch, straightforward_handler(highlight_ids, 4))
            container.run_pipeline(run_id)

        assert card_rows(sequential) == card_rows(concurrent)
        assert len(all_cards(concurrent)) == 4
        assert fetch_run(concurrent, 1).error is None
    finally:
        sequential.engine.dispose()
        concurrent.engine.dispose()


def test_unit_commits_as_it_completes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """A fast unit is committed while a slow sibling is still resolving.

    This is what proves `as_completed` over `map`. With `map`, every write batches to
    the end of the run, so a crash 40 units in would lose all 40 — today's recovery
    behaviour is that they stand.

    The discrimination is in the ordering: **unit 0 is the slow one**, so it is
    submitted first. Draining in submission order (`map`, or iterating the futures
    dict) makes the main thread block on unit 0 before it can persist anything —
    while unit 0 is itself blocked waiting for unit 1's card to appear. That is a
    deadlock the timeout below turns into a failure. Only true completion-order
    draining commits unit 1 while unit 0 is still in flight. Verified by mutation:
    with the slow unit submitted *second*, this test passes against a batch-at-end
    implementation, which is exactly the false pass being avoided here.

    The wait is on a *separate* session seeing the row, so the assertion is about a
    real commit rather than ordering within one transaction.
    """
    container = make_file_backed_container(tmp_path, LLM_CONCURRENCY=4)
    try:
        run_id, highlight_ids = seed_highlights(container, 2)
        fast_card_committed = threading.Event()

        def handle(prompt: str) -> str:
            if is_curator_prompt(prompt):
                return curator_response(UNIT_TEXTS[:2], highlight_ids[:2])
            index = unit_index_for(prompt)
            if index == 0:
                # The slow unit (submitted first) waits for the fast unit's card
                # to be committed by the main thread. If persistence drained in
                # submission order this deadlocks, and the timeout fails the test.
                if not fast_card_committed.wait(timeout=10):
                    raise AssertionError(
                        "the fast unit's card was never committed while a sibling "
                        "was still in flight: persistence is batching to the end of "
                        "the run (pool.map) instead of committing per unit "
                        "(as_completed) — ADR-015"
                    )
            if is_critic_prompt(prompt):
                return critic_response(ACCEPT)
            return writer_response(f"Question for unit {index}?")

        RoutedLlm(monkeypatch, handle)

        def watch_for_the_fast_card() -> None:
            """Poll a separate session until unit 1's card is actually committed."""
            for _ in range(200):
                with container.session() as session:
                    committed = session.scalars(
                        select(Card).where(Card.front == "Question for unit 1?")
                    ).first()
                    if committed is not None:
                        fast_card_committed.set()
                        return
                threading.Event().wait(0.05)

        watcher = threading.Thread(target=watch_for_the_fast_card, daemon=True)
        watcher.start()
        container.run_pipeline(run_id)
        watcher.join(timeout=5)

        assert fast_card_committed.is_set(), (
            "no card was committed while another unit was still resolving: units must "
            "commit as they complete (as_completed), not in one batch at the end"
        )
        assert len(all_cards(container)) == 2
    finally:
        container.engine.dispose()


def test_failed_unit_leaves_highlights_unprocessed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """One unit raising leaves its highlights retryable while siblings commit.

    Each unit fails independently (ADR-015): the failed one keeps `processed=false`,
    so the next run picks it up — the documented retry path (docs/architecture.md,
    "Failure handling") — and the units that finished still stand.
    """
    container = make_file_backed_container(tmp_path, LLM_CONCURRENCY=4)
    try:
        run_id, highlight_ids = seed_highlights(container, 3)

        def handle(prompt: str) -> Any:
            if is_curator_prompt(prompt):
                return curator_response(UNIT_TEXTS[:3], highlight_ids[:3])
            index = unit_index_for(prompt)
            if index == 1:
                return RuntimeError("provider exploded for unit 1")
            if is_critic_prompt(prompt):
                return critic_response(ACCEPT)
            return writer_response(f"Question for unit {index}?")

        RoutedLlm(monkeypatch, handle)
        container.run_pipeline(run_id)

        with container.session() as session:
            highlights = {
                highlight.id: highlight.processed
                for highlight in session.scalars(select(Highlight)).all()
            }
            failed_unit = session.scalars(
                select(CuratedUnit).where(CuratedUnit.curated_text == UNIT_TEXTS[1])
            ).one()
            cards_for_failed = session.scalars(
                select(Card).where(Card.unit_id == failed_unit.id)
            ).all()

        assert highlights[highlight_ids[1]] is False, (
            "the failed unit's highlight must stay processed=false so the next run "
            "retries it (docs/architecture.md, 'Failure handling')"
        )
        assert not cards_for_failed, "a unit that raised must write no cards"
        assert highlights[highlight_ids[0]] is True
        assert highlights[highlight_ids[2]] is True
        assert len(all_cards(container)) == 2, "the two healthy units still commit"
    finally:
        container.engine.dispose()


def test_first_error_recorded_on_ingest_run(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Two units failing leaves `ingest_runs.error` non-null, naming one of them.

    This is the test that catches the swallowed-exception bug: draining the futures
    and returning normally would write `error=None`, so the run row would claim
    success while failed units sat unprocessed. Which of the two is named is
    arbitrary under concurrency — the ADR says the error names one failure, not all.
    """
    container = make_file_backed_container(tmp_path, LLM_CONCURRENCY=4)
    try:
        run_id, highlight_ids = seed_highlights(container, 3)

        def handle(prompt: str) -> Any:
            if is_curator_prompt(prompt):
                return curator_response(UNIT_TEXTS[:3], highlight_ids[:3])
            index = unit_index_for(prompt)
            if index in (0, 1):
                return RuntimeError(f"provider exploded for unit {index}")
            if is_critic_prompt(prompt):
                return critic_response(ACCEPT)
            return writer_response(f"Question for unit {index}?")

        RoutedLlm(monkeypatch, handle)
        container.run_pipeline(run_id)

        run = fetch_run(container, run_id)
        assert run.error is not None, (
            "units failed but ingest_runs.error is null: the run row would claim "
            "success while failed units sit unprocessed (ADR-015)"
        )
        assert "provider exploded for unit" in run.error
        assert run.finished_at is not None
        assert len(all_cards(container)) == 1, "the one healthy unit still commits"
    finally:
        container.engine.dispose()


def test_llm_calls_row_per_call_under_concurrency(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """N concurrent calls write exactly N `llm_calls` rows, none lost to contention.

    Exercises the busy-timeout assumption behind WAL: worker threads commit their own
    `llm_calls` rows on their own sessions while the main thread commits units, so
    writers genuinely contend. A dropped row (or a `database is locked`) fails here.
    """
    container = make_file_backed_container(tmp_path, LLM_CONCURRENCY=8)
    try:
        run_id, highlight_ids = seed_highlights(container, 4)
        routed = RoutedLlm(monkeypatch, straightforward_handler(highlight_ids, 4))
        container.run_pipeline(run_id)

        with container.session() as session:
            rows = session.scalars(select(LlmCall)).all()

        # 1 Curator call + per unit: 1 Writer + 1 Critic.
        assert len(routed.prompts) == 1 + 4 * 2
        assert len(rows) == len(routed.prompts), (
            f"{len(routed.prompts)} calls were made but {len(rows)} llm_calls rows "
            "exist: a row was lost to write contention (ADR-006 — every call writes "
            "exactly one row, concurrency or not)"
        )
        assert fetch_run(container, run_id).error is None
    finally:
        container.engine.dispose()


def test_revise_stays_within_its_card(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Hard rule 9 holds under concurrency: a `revise` re-enters the Writer for that
    card only, and never re-sends an accepted sibling.

    The unit drafts two cards; the first is accepted on round 1 and the second is
    revised once then accepted. The accepted sibling's text must appear in exactly
    one Critic prompt — a second would mean the revision dragged it back through the
    loop, which is the cross-card leak concurrency could plausibly introduce.
    """
    container = make_file_backed_container(tmp_path, LLM_CONCURRENCY=4)
    try:
        run_id, highlight_ids = seed_highlights(container, 2)
        accepted_front = "Accepted sibling question?"
        revised_front = "Revised question, take two?"
        critic_calls: list[str] = []
        lock = threading.Lock()

        def handle(prompt: str) -> str:
            if is_curator_prompt(prompt):
                return curator_response(UNIT_TEXTS[:2], highlight_ids[:2])
            index = unit_index_for(prompt)
            if index == 1:
                # The sibling unit, kept simple: it only has to run alongside.
                if is_critic_prompt(prompt):
                    return critic_response(ACCEPT)
                return writer_response("Question for unit 1?")
            if is_critic_prompt(prompt):
                with lock:
                    critic_calls.append(prompt)
                if accepted_front in prompt:
                    return critic_response(ACCEPT)
                if revised_front in prompt:
                    return critic_response(ACCEPT)
                return critic_response(REVISE)
            # Writer: round 1 drafts both cards; a revision returns only the rewrite.
            if "Not atomic" in prompt:
                return writer_response(revised_front)
            return writer_response(accepted_front, "Second question, needs work?")

        RoutedLlm(monkeypatch, handle)
        container.run_pipeline(run_id)

        prompts_with_accepted = [prompt for prompt in critic_calls if accepted_front in prompt]
        assert len(prompts_with_accepted) == 1, (
            f"the accepted sibling was sent to the Critic {len(prompts_with_accepted)} "
            "times: a `revise` must re-enter the Writer for its own card only, and "
            "siblings already accepted are never re-sent (hard rule 9)"
        )

        unit_zero_cards = {
            (card.front, card.status, card.generation_rounds)
            for card in all_cards(container)
            if card.front in (accepted_front, revised_front)
        }
        assert unit_zero_cards == {
            (accepted_front, "pending_review", 1),
            (revised_front, "pending_review", 2),
        }
    finally:
        container.engine.dispose()


def test_pool_is_never_wider_than_the_units_it_has(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The pool is sized to the work, not to the config ceiling.

    `LLM_CONCURRENCY` is a ceiling on provider concurrency, not a thread quota: a
    small ingest (or the tail of a large one) has fewer pending units than the
    ceiling allows, and spawning threads that can never receive work is pure waste.
    Sizing by `min(ceiling, len(pending))` only ever spawns *fewer* threads than the
    configured width, so it cannot raise provider pressure.

    Three units against a ceiling of 8 must build a pool of 3.
    """
    container = make_file_backed_container(tmp_path, LLM_CONCURRENCY=8)
    try:
        run_id, highlight_ids = seed_highlights(container, 3)
        RoutedLlm(monkeypatch, straightforward_handler(highlight_ids, 3))
        widths = record_pool_widths(monkeypatch)

        container.run_pipeline(run_id)

        assert widths == [3], (
            f"a run with 3 pending units built pools of width {widths} against a "
            "ceiling of 8: max_workers must be min(LLM_CONCURRENCY, len(pending)), "
            "since a thread with no unit to resolve can never do work"
        )
        assert len(all_cards(container)) == 3, "all three units still produce cards"
    finally:
        container.engine.dispose()


def test_run_with_no_pending_units_builds_no_pool(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Negative: a run with nothing to resolve builds no pool and does not raise.

    Reachable two ways: a re-run where every keep unit already has cards (the
    idempotency path), and an ingest the Curator drops entirely. `ThreadPoolExecutor`
    rejects `max_workers=0`, so sizing the pool to `len(pending)` without guarding
    the empty case turns a clean no-op into a crash that `run()` would record on
    `ingest_runs.error`.
    """
    container = make_file_backed_container(tmp_path, LLM_CONCURRENCY=8)
    try:
        run_id, highlight_ids = seed_highlights(container, 2)

        def drop_everything(prompt: str) -> str:
            """The Curator drops both units, so nothing reaches the Writer phase."""
            assert is_curator_prompt(prompt), (
                f"no Writer/Critic call may happen when every unit is dropped: {prompt[:120]}"
            )
            return json.dumps(
                {
                    "units": [
                        {
                            "highlight_ids": [highlight_id],
                            "curated_text": text,
                            "tags": [],
                            "truncated_highlight_ids": [],
                            "decision": "drop",
                            "reason": "Bare heading, no sibling context.",
                        }
                        for text, highlight_id in zip(
                            UNIT_TEXTS[:2], highlight_ids[:2], strict=True
                        )
                    ]
                }
            )

        RoutedLlm(monkeypatch, drop_everything)
        widths = record_pool_widths(monkeypatch)

        container.run_pipeline(run_id)

        assert widths == [], (
            f"a run with no pending units built pools of width {widths}: with nothing "
            "to resolve there is no work to parallelise, and max_workers=0 raises"
        )
        run = fetch_run(container, run_id)
        assert run.error is None, (
            f"an empty Writer phase must be a clean no-op, got error={run.error!r}"
        )
        assert run.units_dropped == 2
        assert all_cards(container) == []
    finally:
        container.engine.dispose()
