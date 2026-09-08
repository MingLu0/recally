"""The step 2f gate: the pipeline runner and the approval-queue API.

Spec: docs/agents.md, "Pipeline runner and handoffs", and docs/api-spec.md,
"Approval queue". The pipeline tests run the `default` agent variants against
fixture-ingested highlights with `litellm.completion` mocked (the same seam as
test_llm.py), so no test touches a provider, and every call still writes its real
`llm_calls` row through the unmocked `LlmCaller`. The API tests seed cards directly
and override the container onto an in-memory database.

One deliberate narrowing of the ticket text, because the data model is the spec:
`test_every_call_writes_an_llm_calls_row` asserts `unit_id` and `round` only for
Writer and Critic calls. docs/data-model.md (`llm_calls`) sets them null for the
Curator — it serves a batch, not a unit — and null outside the Writer ⇄ Critic
loop, so asserting them on the Curator row would assert the opposite of the schema.
"""

import json
from collections import deque
from collections.abc import Iterator
from datetime import date
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from recally.api.deps import container_dependency
from recally.config import Settings, get_settings
from recally.container import Container
from recally.ingest import ingest_file
from recally.ingest.adapters import OReillyCsvAdapter
from recally.main import create_app
from recally.models import (
    Base,
    Book,
    Card,
    CuratedUnit,
    CuratedUnitHighlight,
    Highlight,
    IngestRun,
    LlmCall,
)

TEST_API_KEY = "test-key-not-a-real-secret"
FIXTURE_A = Path(__file__).parent / "fixtures" / "oreilly-annotations-a.csv"


def make_container(**setting_overrides: Any) -> tuple[Container, Any]:
    """A container on a fresh in-memory database, with optional Settings overrides.

    `StaticPool` on a single connection is what makes `sqlite://` usable here:
    without it every checkout gets its own empty database. The engine is returned so
    the caller can dispose it.
    """
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    settings = Settings(
        RECALLY_DATABASE_URL="sqlite://", RECALLY_API_KEY=TEST_API_KEY, **setting_overrides
    )
    return Container(settings, engine=engine), engine


@pytest.fixture
def container() -> Iterator[Container]:
    """The default container: AUTO_APPROVE_ROUND1_ACCEPT off, LLM_MAX_ROUNDS 3."""
    test_container, engine = make_container()
    try:
        yield test_container
    finally:
        engine.dispose()


class ScriptedLlm:
    """Monkeypatched over `litellm.completion`; replays recorded responses in order.

    A response that is an exception instance is raised instead of returned, which is
    how the crash-recovery tests simulate a provider failure mid-run. Any call beyond
    the script fails the run loudly rather than inventing a response.
    """

    def __init__(self, monkeypatch: pytest.MonkeyPatch, responses: list[Any]) -> None:
        self._responses = deque(responses)
        self.requests: list[dict[str, Any]] = []

        def fake_completion(*, model: str, messages: list[dict[str, Any]], **kwargs: Any) -> Any:
            self.requests.append({"model": model, "messages": messages})
            if not self._responses:
                raise AssertionError(
                    f"scripted LLM received an unscripted call (model={model}); "
                    f"{len(self.requests)} calls made so far"
                )
            scripted = self._responses.popleft()
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
        monkeypatch.setattr("recally.llm.litellm.completion_cost", lambda **kwargs: 0.0)


# --- Scripted-response builders ------------------------------------------------


def keep_unit(
    highlight_ids: list[int],
    curated_text: str = "The curated unit text.",
    *,
    truncated: tuple[int, ...] = (),
) -> dict[str, Any]:
    return {
        "highlight_ids": highlight_ids,
        "curated_text": curated_text,
        "tags": ["agents"],
        "truncated_highlight_ids": list(truncated),
        "decision": "keep",
        "reason": "",
    }


def drop_unit(
    highlight_ids: list[int], reason: str = "Bare heading, no sibling context."
) -> dict[str, Any]:
    return {
        "highlight_ids": highlight_ids,
        "curated_text": "The dropped text.",
        "tags": [],
        "truncated_highlight_ids": [],
        "decision": "drop",
        "reason": reason,
    }


def curator_response(*units: dict[str, Any]) -> str:
    return json.dumps({"units": list(units)})


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
REJECT = ("reject", "Source is a bare heading; unsalvageable.")


# --- Pipeline helpers ----------------------------------------------------------


def ingest_fixture(container: Container) -> tuple[int, list[Highlight]]:
    """Ingest fixture A (15 Chapter 9 highlights); return the run id and the rows."""
    with container.session() as session:
        run = ingest_file(session, FIXTURE_A, OReillyCsvAdapter())
        session.commit()
        run_id = run.id
    with container.session() as session:
        highlights = session.scalars(select(Highlight).order_by(Highlight.export_position)).all()
        return run_id, list(highlights)


def new_ingest_run(container: Container, filename: str = "rerun-oreilly-annotations.csv") -> int:
    """A fresh `ingest_runs` row, as a re-ingest would create for the retry run."""
    with container.session() as session:
        run = IngestRun(filename=filename, user_id=1)
        session.add(run)
        session.commit()
        return run.id


def all_cards(container: Container) -> list[Card]:
    with container.session() as session:
        return list(session.scalars(select(Card).order_by(Card.id)).all())


def all_units(container: Container) -> list[CuratedUnit]:
    with container.session() as session:
        return list(session.scalars(select(CuratedUnit).order_by(CuratedUnit.id)).all())


def all_llm_calls(container: Container, ingest_run_id: int | None = None) -> list[LlmCall]:
    with container.session() as session:
        statement = select(LlmCall).order_by(LlmCall.id)
        if ingest_run_id is not None:
            statement = statement.where(LlmCall.ingest_run_id == ingest_run_id)
        return list(session.scalars(statement).all())


def fetch_run(container: Container, run_id: int) -> IngestRun:
    with container.session() as session:
        run = session.get(IngestRun, run_id)
        assert run is not None
        return run


def fetch_highlight(container: Container, highlight_id: int) -> Highlight:
    with container.session() as session:
        highlight = session.get(Highlight, highlight_id)
        assert highlight is not None
        return highlight


# --- The roadmap step 2 gate, in full -------------------------------------------


def test_curator_group_persists_one_unit_with_three_links(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A recorded Curator response naming three highlights yields ONE `curated_units`
    row with THREE `curated_unit_highlights` rows (the group case, docs/agents.md §2)."""
    run_id, highlights = ingest_fixture(container)
    grouped_ids = [highlight.id for highlight in highlights[:3]]
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit(grouped_ids, "The three sibling headings, one unit.")),
            writer_response("Why group sibling headings?"),
            critic_response(ACCEPT),
        ],
    )

    container.run_pipeline(run_id)

    units = all_units(container)
    assert len(units) == 1
    with container.session() as session:
        links = session.scalars(
            select(CuratedUnitHighlight).where(CuratedUnitHighlight.unit_id == units[0].id)
        ).all()
    assert len(links) == 3
    assert {link.highlight_id for link in links} == set(grouped_ids)


def test_flagged_highlight_gets_truncated_true(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The Curator returns `truncated_highlight_ids`; the RUNNER writes `truncated=true`
    onto those `highlights` rows (agents hold no session, ADR-007)."""
    run_id, highlights = ingest_fixture(container)
    flagged = highlights[1]
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(
                keep_unit(
                    [highlight.id for highlight in highlights[:3]],
                    truncated=(flagged.id,),
                )
            ),
            writer_response("Why flag clipped rows?"),
            critic_response(ACCEPT),
        ],
    )

    container.run_pipeline(run_id)

    assert fetch_highlight(container, flagged.id).truncated is True
    assert fetch_highlight(container, highlights[0].id).truncated is False
    assert fetch_highlight(container, highlights[2].id).truncated is False


def test_three_revise_rounds_end_in_needs_human(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Hard rule 9: three `revise` verdicts (LLM_MAX_ROUNDS) without an `accept` end
    the loop at `needs_human` with `generation_rounds == 3`."""
    run_id, highlights = ingest_fixture(container)
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([highlights[0].id])),
            writer_response("Draft round 1?"),
            critic_response(REVISE),
            writer_response("Draft round 2?"),
            critic_response(REVISE),
            writer_response("Draft round 3?"),
            critic_response(REVISE),
        ],
    )

    container.run_pipeline(run_id)

    cards = all_cards(container)
    assert len(cards) == 1
    assert cards[0].status == "needs_human"
    assert cards[0].generation_rounds == 3
    calls = all_llm_calls(container, run_id)
    assert len([call for call in calls if call.agent == "writer/default"]) == 3
    assert len([call for call in calls if call.agent == "critic/default"]) == 3


def test_critic_reject_ends_in_needs_human(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A Critic `reject` is not a rejection: the card goes to `needs_human` with the
    critique in `status_reason`. The pipeline never writes `rejected` (hard rule 9)."""
    run_id, highlights = ingest_fixture(container)
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([highlights[0].id])),
            writer_response("What is Stage 1?"),
            critic_response(REJECT),
        ],
    )

    container.run_pipeline(run_id)

    cards = all_cards(container)
    assert len(cards) == 1
    assert cards[0].status == "needs_human"
    assert cards[0].status_reason == REJECT[1]


def test_pipeline_never_writes_rejected_or_approved(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Hard rule 1: with AUTO_APPROVE_ROUND1_ACCEPT off, every terminal path — accept,
    revise-then-accept, reject, and three revises — lands in `pending_review` or
    `needs_human`. No card ever ends `rejected` or `approved`."""
    run_id, highlights = ingest_fixture(container)
    ids = [highlight.id for highlight in highlights]
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(
                keep_unit([ids[0]]),
                keep_unit([ids[1]]),
                keep_unit([ids[2]]),
                keep_unit([ids[3]]),
            ),
            # Unit 1: accept on round 1.
            writer_response("Unit 1 card?"),
            critic_response(ACCEPT),
            # Unit 2: revise, then accept on round 2.
            writer_response("Unit 2 draft?"),
            critic_response(REVISE),
            writer_response("Unit 2 revised?"),
            critic_response(ACCEPT),
            # Unit 3: reject.
            writer_response("Unit 3 card?"),
            critic_response(REJECT),
            # Unit 4: three revises.
            writer_response("Unit 4 round 1?"),
            critic_response(REVISE),
            writer_response("Unit 4 round 2?"),
            critic_response(REVISE),
            writer_response("Unit 4 round 3?"),
            critic_response(REVISE),
        ],
    )

    container.run_pipeline(run_id)

    cards = all_cards(container)
    assert len(cards) == 4
    assert {card.status for card in cards} <= {"pending_review", "needs_human"}


def test_every_call_writes_an_llm_calls_row(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """ADR-006: one `llm_calls` row per call, with `agent`, non-null `request` and
    `response`, the run id, and — for Writer and Critic calls — `unit_id` and the
    1-based `round` (null for the Curator batch call, per docs/data-model.md)."""
    run_id, highlights = ingest_fixture(container)
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([highlights[0].id])),
            writer_response("Card A?", "Card B?"),
            critic_response(ACCEPT),  # card A, round 1
            critic_response(REVISE),  # card B, round 1
            writer_response("Card B revised?"),
            critic_response(ACCEPT),  # card B, round 2
        ],
    )

    container.run_pipeline(run_id)

    unit = all_units(container)[0]
    calls = all_llm_calls(container, run_id)
    # 1 Curator + 2 Writer + 3 Critic; a missed row or an extra call both fail here.
    assert len(calls) == 6
    for call in calls:
        assert call.agent in {"curator/default", "writer/default", "critic/default"}
        assert call.ingest_run_id == run_id
        assert call.request is not None
        assert call.response is not None
    for role in ("writer/default", "critic/default"):
        for call in [call for call in calls if call.agent == role]:
            assert call.unit_id == unit.id
            assert call.round is not None and call.round >= 1
    assert [call.round for call in calls if call.agent == "writer/default"] == [1, 2]
    assert [call.round for call in calls if call.agent == "critic/default"] == [1, 1, 2]


# --- Loop and status behaviour ---------------------------------------------------


def test_revise_affects_only_the_named_card(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A `revise` sends THAT card back to the Writer; the accepted sibling keeps
    `pending_review` and is never re-sent (docs/agents.md §4)."""
    run_id, highlights = ingest_fixture(container)
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([highlights[0].id])),
            writer_response("Accepted sibling?", "Revised card draft?"),
            critic_response(ACCEPT),  # sibling, round 1
            critic_response(REVISE),  # the named card, round 1
            writer_response("Revised card, round 2?"),
            critic_response(ACCEPT),  # the named card, round 2
        ],
    )

    container.run_pipeline(run_id)

    cards = all_cards(container)
    assert len(cards) == 2
    sibling, revised = cards
    assert sibling.status == "pending_review"
    assert sibling.generation_rounds == 1
    assert sibling.front == "Accepted sibling?"
    assert revised.status == "pending_review"
    assert revised.generation_rounds == 2
    # The unit's first Writer call plus exactly one revision call for the named card:
    # a second full-unit Writer call would prove the sibling was re-sent.
    writer_calls = [
        call for call in all_llm_calls(container, run_id) if call.agent == "writer/default"
    ]
    assert [call.round for call in writer_calls] == [1, 2]


def test_generation_rounds_is_per_card(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Two cards from one unit can need different round counts; `generation_rounds`
    is recorded per card, not per unit (docs/agents.md §4)."""
    run_id, highlights = ingest_fixture(container)
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([highlights[0].id])),
            writer_response("Easy card?", "Hard card round 1?"),
            critic_response(ACCEPT),  # easy card, round 1
            critic_response(REVISE),  # hard card, round 1
            writer_response("Hard card round 2?"),
            critic_response(REVISE),  # hard card, round 2
            writer_response("Hard card round 3?"),
            critic_response(ACCEPT),  # hard card, round 3
        ],
    )

    container.run_pipeline(run_id)

    cards = all_cards(container)
    assert len(cards) == 2
    assert sorted(card.generation_rounds for card in cards) == [1, 3]


def test_accept_on_round_two_stops_the_loop(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """An `accept` on round 2 ends the loop: no third Writer or Critic call is made."""
    run_id, highlights = ingest_fixture(container)
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([highlights[0].id])),
            writer_response("Draft round 1?"),
            critic_response(REVISE),
            writer_response("Draft round 2?"),
            critic_response(ACCEPT),
        ],
    )

    container.run_pipeline(run_id)

    calls = all_llm_calls(container, run_id)
    assert [call.round for call in calls if call.agent == "writer/default"] == [1, 2]
    assert [call.round for call in calls if call.agent == "critic/default"] == [1, 2]
    assert all(call.round != 3 for call in calls if call.round is not None)
    cards = all_cards(container)
    assert len(cards) == 1
    assert cards[0].status == "pending_review"
    assert cards[0].generation_rounds == 2


def test_auto_approve_flag_on_approves_only_round_one_accepts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The hard-rule-1 exception: with AUTO_APPROVE_ROUND1_ACCEPT on, a round-1
    `accept` skips the queue (`approved`); a round-2 `accept` still does not."""
    flag_container, engine = make_container(AUTO_APPROVE_ROUND1_ACCEPT=True)
    try:
        run_id, highlights = ingest_fixture(flag_container)
        ScriptedLlm(
            monkeypatch,
            [
                curator_response(keep_unit([highlights[0].id])),
                writer_response("Round 1 accept?", "Round 2 accept draft?"),
                critic_response(ACCEPT),  # round 1: auto-approved
                critic_response(REVISE),  # round 1: revise
                writer_response("Round 2 accept revised?"),
                critic_response(ACCEPT),  # round 2: stays in the queue
            ],
        )

        flag_container.run_pipeline(run_id)

        cards = all_cards(flag_container)
        assert len(cards) == 2
        by_rounds = {card.generation_rounds: card for card in cards}
        assert by_rounds[1].status == "approved"
        assert by_rounds[2].status == "pending_review"
    finally:
        engine.dispose()


# --- Idempotency and crash recovery ----------------------------------------------


def test_processed_flips_only_on_terminal_outcome(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """`processed` does not flip when the Curator merely ran: the Curator's units are
    persisted, but with no card statuses yet the highlights stay `processed=false`
    (docs/agents.md, "Pipeline runner and handoffs")."""
    run_id, highlights = ingest_fixture(container)
    covered = [highlight.id for highlight in highlights[:2]]
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit(covered)),
            RuntimeError("provider down"),
        ],
    )

    container.run_pipeline(run_id)

    units = all_units(container)
    assert len(units) == 1  # the Curator's output was persisted before the crash
    assert all_cards(container) == []
    for highlight_id in covered:
        assert fetch_highlight(container, highlight_id).processed is False


def test_dropped_unit_marks_highlights_processed(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A `drop` decision IS a terminal outcome: the covered highlights flip to
    `processed=true` and no card is written, so they are never re-curated."""
    run_id, highlights = ingest_fixture(container)
    kept_id = highlights[0].id
    dropped_ids = [highlights[1].id, highlights[2].id]
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([kept_id]), drop_unit(dropped_ids)),
            writer_response("Kept card?"),
            critic_response(ACCEPT),
        ],
    )

    container.run_pipeline(run_id)

    for dropped_id in dropped_ids:
        assert fetch_highlight(container, dropped_id).processed is True
    assert len(all_cards(container)) == 1  # only the kept unit produced a card
    # The drop never reaches Writer/Critic: 1 Curator + 1 Writer + 1 Critic only.
    assert len(all_llm_calls(container, run_id)) == 3
    run = fetch_run(container, run_id)
    assert run.units_kept == 1
    assert run.units_dropped == 1
    assert run.highlights_dropped == 2


def test_rerun_skips_units_that_already_have_cards(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A second run generates no duplicate cards: the finished unit's highlights are
    `processed`, so the retry path never re-sends the unit to the Writer
    (docs/agents.md, idempotency)."""
    run_id, highlights = ingest_fixture(container)
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([highlights[0].id])),
            writer_response("The one card?"),
            critic_response(ACCEPT),
        ],
    )
    container.run_pipeline(run_id)
    assert len(all_cards(container)) == 1

    # The retry run: the finished unit's highlight is processed, so only the
    # never-curated leftovers go back to the Curator — and no Writer call happens,
    # which is what proves the finished unit was skipped rather than duplicated.
    ScriptedLlm(monkeypatch, [curator_response(drop_unit([h.id for h in highlights[1:]]))])
    second_run_id = new_ingest_run(container)
    container.run_pipeline(second_run_id)

    assert len(all_cards(container)) == 1
    assert [card.front for card in all_cards(container)] == ["The one card?"]
    second_run_calls = all_llm_calls(container, second_run_id)
    assert [call.agent for call in second_run_calls] == ["curator/default"]


def test_orphan_keep_units_are_deleted_before_the_curator_runs(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A cardless `keep` unit whose highlights are all unprocessed is an orphan of a
    crashed run: the runner deletes it BEFORE the Curator runs, and the Curator
    recreates it — so a rerun leaves one unit, not two."""
    run_id, highlights = ingest_fixture(container)
    covered_id = highlights[0].id
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([covered_id], "The orphaned unit.")),
            RuntimeError("crash between Curator and Writer"),
        ],
    )
    container.run_pipeline(run_id)
    assert len(all_units(container)) == 1

    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([covered_id], "The recreated unit.")),
            writer_response("Recovered card?"),
            critic_response(ACCEPT),
        ],
    )
    second_run_id = new_ingest_run(container)
    container.run_pipeline(second_run_id)

    units = all_units(container)
    assert len(units) == 1
    assert units[0].curated_text == "The recreated unit."
    assert units[0].ingest_run_id == second_run_id
    assert len(all_cards(container)) == 1


def test_crash_between_curator_and_writer_recovers_on_rerun(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The retry path end to end: a Writer exception aborts the run; the next run
    completes the chapter with no duplicate `curated_units` and no duplicate cards."""
    run_id, highlights = ingest_fixture(container)
    first_id, second_id = highlights[0].id, highlights[1].id
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([first_id]), keep_unit([second_id])),
            RuntimeError("provider down"),
        ],
    )
    container.run_pipeline(run_id)
    assert fetch_run(container, run_id).error is not None

    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([first_id]), keep_unit([second_id])),
            writer_response("Card for unit 1?"),
            critic_response(ACCEPT),
            writer_response("Card for unit 2?"),
            critic_response(ACCEPT),
        ],
    )
    second_run_id = new_ingest_run(container)
    container.run_pipeline(second_run_id)

    assert fetch_run(container, second_run_id).error is None
    assert len(all_units(container)) == 2
    cards = all_cards(container)
    assert len(cards) == 2
    assert {card.front for card in cards} == {"Card for unit 1?", "Card for unit 2?"}
    assert fetch_highlight(container, first_id).processed is True
    assert fetch_highlight(container, second_id).processed is True


def test_agent_exception_is_recorded_in_ingest_run_error(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """docs/architecture.md, "Failure handling": an agent exception does not
    propagate (the watcher keeps running); the run is marked finished with the error
    recorded on `ingest_runs.error`, so the next run can retry."""
    run_id, highlights = ingest_fixture(container)
    ScriptedLlm(
        monkeypatch,
        [
            curator_response(keep_unit([highlights[0].id])),
            RuntimeError("provider down"),
        ],
    )

    # Must return normally: an exception here would kill the watcher's observer thread.
    container.run_pipeline(run_id)

    run = fetch_run(container, run_id)
    assert run.error is not None and "provider down" in run.error
    assert run.finished_at is not None


# --- Approval-queue API -----------------------------------------------------------


@pytest.fixture
def client(container: Container, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    app = create_app()
    app.dependency_overrides[container_dependency] = lambda: container
    monkeypatch.setenv("RECALLY_API_KEY", TEST_API_KEY)
    get_settings.cache_clear()
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        get_settings.cache_clear()


def seed_card(
    session: Session,
    *,
    book_title: str = "Evals for AI Engineers",
    external_id: str = "9781098188283",
    chapter: str | None = "1. Introduction",
    export_position: int = 0,
    texts: tuple[str, ...] = ("The Gulf of Specification is this gap.",),
    truncated: tuple[int, ...] = (),
    status: str = "pending_review",
    status_reason: str | None = None,
    front: str = "What is the Gulf of Specification?",
    back: str = "The gap between intent and instructions.",
) -> Card:
    """One book (reused per `external_id`), one unit with `len(texts)` sources, one card."""
    book = session.scalar(
        select(Book).where(Book.source == "oreilly", Book.external_id == external_id)
    )
    if book is None:
        book = Book(title=book_title, source="oreilly", external_id=external_id, user_id=1)
        session.add(book)
        session.flush()
    run = IngestRun(filename="seed-oreilly-annotations.csv", user_id=1)
    session.add(run)
    session.flush()
    unit = CuratedUnit(
        ingest_run_id=run.id, curated_text="The curated text.", tags=[], decision="keep", user_id=1
    )
    session.add(unit)
    session.flush()
    for index, text in enumerate(texts):
        highlight = Highlight(
            book_id=book.id,
            chapter=chapter,
            raw_text=text,
            dedupe_key=f"seed-{external_id}-{chapter}-{export_position}-{index}",
            source="oreilly",
            highlighted_at=date(2026, 6, 19),
            export_position=export_position + index,
            truncated=index in truncated,
            user_id=1,
        )
        session.add(highlight)
        session.flush()
        session.add(CuratedUnitHighlight(unit_id=unit.id, highlight_id=highlight.id, user_id=1))
    card = Card(
        unit_id=unit.id,
        type="qa",
        front=front,
        back=back,
        original_front=front,
        original_back=back,
        tags=[],
        status=status,
        status_reason=status_reason,
        model="test-model",
        user_id=1,
    )
    session.add(card)
    session.flush()
    return card


def test_pending_includes_all_source_highlights_of_a_group(
    client: TestClient, container: Container
) -> None:
    """A grouped unit has several sources; the client needs ALL of them for context
    (docs/api-spec.md, GET /cards/pending)."""
    with container.session() as session:
        seed_card(
            session,
            texts=(
                "Stage 1: Task assignment",
                "Stage 2: Code synthesis",
                "Stage 3: Test synthesis",
            ),
        )
        session.commit()

    response = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY})

    assert response.status_code == 200
    cards = response.json()["cards"]
    assert len(cards) == 1
    assert cards[0]["source_highlights"] == [
        "Stage 1: Task assignment",
        "Stage 2: Code synthesis",
        "Stage 3: Test synthesis",
    ]


def test_pending_truncated_true_if_any_source_is_clipped(
    client: TestClient, container: Container
) -> None:
    """`truncated` is derived: true when ANY source highlight is clipped (the flag
    lives on `highlights` rows only, docs/data-model.md)."""
    with container.session() as session:
        seed_card(session, texts=("Full text.", "…clipped mid-word", "Also full."), truncated=(1,))
        session.commit()

    cards = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()["cards"]

    assert len(cards) == 1
    assert cards[0]["truncated"] is True


def test_pending_ordering(client: TestClient, container: Container) -> None:
    """The flat list is ordered by book, then chapter, then `export_position`; the
    client renders the chapter grouping (docs/api-spec.md)."""
    with container.session() as session:
        seed_card(
            session,
            book_title="Zulu Book",
            external_id="zulu",
            chapter="1. Only Chapter",
            export_position=0,
            front="Zulu card?",
        )
        seed_card(
            session,
            book_title="Alpha Book",
            external_id="alpha",
            chapter="2. Second",
            export_position=1,
            front="Alpha chapter 2 card?",
        )
        seed_card(
            session,
            book_title="Alpha Book",
            external_id="alpha",
            chapter="1. First",
            export_position=7,
            front="Alpha chapter 1, later position?",
        )
        seed_card(
            session,
            book_title="Alpha Book",
            external_id="alpha",
            chapter="1. First",
            export_position=2,
            front="Alpha chapter 1, earlier position?",
        )
        session.commit()

    cards = client.get("/cards/pending", headers={"X-API-Key": TEST_API_KEY}).json()["cards"]

    assert [card["front"] for card in cards] == [
        "Alpha chapter 1, earlier position?",
        "Alpha chapter 1, later position?",
        "Alpha chapter 2 card?",
        "Zulu card?",
    ]
    assert [(card["book"], card["chapter"]) for card in cards] == [
        ("Alpha Book", "1. First"),
        ("Alpha Book", "1. First"),
        ("Alpha Book", "2. Second"),
        ("Zulu Book", "1. Only Chapter"),
    ]


def test_pending_filters_by_status_book_and_chapter(
    client: TestClient, container: Container
) -> None:
    """The filter chips round-trip back into the query params (docs/api-spec.md)."""
    with container.session() as session:
        seed_card(session, chapter="1. Introduction", status="pending_review", front="Pending?")
        seed_card(
            session,
            chapter="2. Error Analysis",
            status="needs_human",
            status_reason="Critic: ambiguous.",
            front="Needs human?",
        )
        seed_card(
            session,
            book_title="Another Book",
            external_id="another",
            chapter="1. Introduction",
            front="Other book card?",
        )
        session.commit()

    needs_human = client.get(
        "/cards/pending?status=needs_human", headers={"X-API-Key": TEST_API_KEY}
    ).json()["cards"]
    assert [card["front"] for card in needs_human] == ["Needs human?"]

    by_book = client.get(
        f"/cards/pending?book_id={needs_human[0]['book_id']}",
        headers={"X-API-Key": TEST_API_KEY},
    ).json()["cards"]
    assert {card["front"] for card in by_book} == {"Pending?", "Needs human?"}

    by_chapter = client.get(
        "/cards/pending?chapter=1. Introduction", headers={"X-API-Key": TEST_API_KEY}
    ).json()["cards"]
    assert {card["front"] for card in by_chapter} == {"Pending?", "Other book card?"}

    bad_status = client.get("/cards/pending?status=approved", headers={"X-API-Key": TEST_API_KEY})
    assert bad_status.status_code == 422
    assert bad_status.json()["status"] == 422


def test_approve_with_edits_preserves_original_front_back(
    client: TestClient, container: Container
) -> None:
    """Edits overwrite `front`/`back`; `original_front`/`original_back` keep the
    Writer's text for the Learner, and `approved_at` is set (hard rule 1's gate)."""
    with container.session() as session:
        card = seed_card(session, front="Writer front?", back="Writer back.")
        session.commit()
        card_id = card.id

    response = client.post(
        f"/cards/{card_id}/approve",
        headers={"X-API-Key": TEST_API_KEY},
        json={"front": "Edited front?", "back": "Edited back."},
    )

    assert response.status_code == 200
    with container.session() as session:
        updated = session.get(Card, card_id)
        assert updated is not None
        assert updated.status == "approved"
        assert updated.approved_at is not None
        assert updated.front == "Edited front?"
        assert updated.back == "Edited back."
        assert updated.original_front == "Writer front?"
        assert updated.original_back == "Writer back."


def test_reject_sets_status_reason_from_the_body(client: TestClient, container: Container) -> None:
    """Only a human sets `rejected`; the reason lands in `status_reason` and feeds
    the Learner (docs/api-spec.md, POST /cards/{id}/reject)."""
    with container.session() as session:
        card = seed_card(session)
        session.commit()
        card_id = card.id

    response = client.post(
        f"/cards/{card_id}/reject",
        headers={"X-API-Key": TEST_API_KEY},
        json={"reason": "Too trivial to be worth a card."},
    )

    assert response.status_code == 200
    with container.session() as session:
        updated = session.get(Card, card_id)
        assert updated is not None
        assert updated.status == "rejected"
        assert updated.status_reason == "Too trivial to be worth a card."


def test_endpoints_require_the_api_key(client: TestClient) -> None:
    """Every approval-queue route carries the guard (docs/api-spec.md, auth)."""
    assert client.get("/cards/pending").status_code == 401
    assert client.post("/cards/1/approve").status_code == 401
    assert client.post("/cards/1/reject", json={"reason": "x"}).status_code == 401
