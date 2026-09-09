"""Step 6b-a gate: the Writer ⇄ writer_guidance seam end to end.

`pipeline.py` already reads the newest `writer_guidance` row (`_latest_guidance`)
and threads `guidance_version`/`guidance` into every `WriterRequest`, and the
Writer prompt already substitutes `$guidance` — what was missing until this step
was a second row to read. This test asserts the EXISTING seam works once a v2
exists: it must pass without any change to `pipeline.py`, the Writer, or its
prompt. If it does not, the seam — not this test — is the bug.
"""

import json
from collections import deque
from collections.abc import Iterator
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.pool import StaticPool

from recally.config import Settings
from recally.container import Container
from recally.ingest import ingest_file
from recally.ingest.adapters import OReillyCsvAdapter
from recally.models import Base, Card, LlmCall, WriterGuidance

TEST_API_KEY = "test-key-not-a-real-secret"
FIXTURE_A = Path(__file__).parent / "fixtures" / "oreilly-annotations-a.csv"

V1_TEXT = "v1: prefer application questions over definitions."
V2_TEXT = "v2 GUIDANCE MARKER: definition cards lapse at 40% — prefer application questions."


class ScriptedLlm:
    """Monkeypatched over `litellm.completion`; replays recorded responses in order.

    Same seam as tests/test_pipeline.py: any call beyond the script fails loudly
    rather than inventing a response.
    """

    def __init__(self, monkeypatch: pytest.MonkeyPatch, responses: list[str]) -> None:
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
            return SimpleNamespace(
                model=model,
                choices=[
                    SimpleNamespace(message=SimpleNamespace(role="assistant", content=scripted))
                ],
                usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5),
            )

        monkeypatch.setattr("recally.llm.litellm.completion", fake_completion)
        monkeypatch.setattr("recally.llm.litellm.completion_cost", lambda **kwargs: 0.0)


@pytest.fixture
def container() -> Iterator[Container]:
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    settings = Settings(RECALLY_DATABASE_URL="sqlite://", RECALLY_API_KEY=TEST_API_KEY)
    try:
        yield Container(settings, engine=engine)
    finally:
        engine.dispose()


def test_next_writer_call_request_contains_the_v2_text(
    container: Container, monkeypatch: pytest.MonkeyPatch
) -> None:
    """After a v2 row exists, the next pipeline run's WriterRequest carries v2's
    text and `guidance_version=2`, and the logged `llm_calls.request` contains
    the v2 text (ADR-006: the trace shows what the model actually saw)."""
    with container.session() as session:
        session.add(WriterGuidance(version=1, guidance=V1_TEXT, basis={}, user_id=1))
        session.add(WriterGuidance(version=2, guidance=V2_TEXT, basis={}, user_id=1))
        session.commit()

    with container.session() as session:
        run = ingest_file(session, FIXTURE_A, OReillyCsvAdapter())
        session.commit()
        run_id = run.id

    # One keep unit over the first highlight, one Writer card, one Critic accept.
    with container.session() as session:
        from recally.models import Highlight

        first_highlight_id = session.scalars(select(Highlight.id).limit(1)).one()

    scripted = ScriptedLlm(
        monkeypatch,
        [
            json.dumps(
                {
                    "units": [
                        {
                            "highlight_ids": [first_highlight_id],
                            "curated_text": "The curated unit text.",
                            "tags": ["agents"],
                            "truncated_highlight_ids": [],
                            "decision": "keep",
                            "reason": "",
                        }
                    ]
                }
            ),
            json.dumps(
                {
                    "cards": [
                        {
                            "type": "qa",
                            "front": "Why does the Writer see the newest guidance?",
                            "back": "The pipeline reads the latest writer_guidance row.",
                            "rationale": "r",
                        }
                    ]
                }
            ),
            json.dumps([{"verdict": "accept", "critique": ""}]),
        ],
    )

    container.run_pipeline(run_id)

    # The remaining highlights are still unprocessed (the Curator only kept one),
    # so exactly one Writer call happened; its card is stamped v2.
    with container.session() as session:
        cards = list(session.scalars(select(Card)).all())
    assert len(cards) == 1
    assert cards[0].guidance_version == 2, (
        f"the card was stamped guidance_version={cards[0].guidance_version}, expected 2"
    )

    writer_requests = [
        request for request in scripted.requests if V2_TEXT in str(request["messages"])
    ]
    assert len(writer_requests) == 1, (
        f"expected exactly one Writer call carrying the v2 text, got {len(writer_requests)}"
    )
    assert V1_TEXT not in str(writer_requests[0]["messages"])

    with container.session() as session:
        writer_calls = list(
            session.scalars(select(LlmCall).where(LlmCall.agent == "writer/default")).all()
        )
    assert len(writer_calls) == 1
    # `request` comes back from the JSON column already decoded; str() keeps the
    # literal text, where json.dumps would escape the em dash as .
    assert V2_TEXT in str(writer_calls[0].request), (
        "the logged llm_calls.request does not contain the v2 guidance text"
    )
