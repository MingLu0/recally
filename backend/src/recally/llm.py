"""The sole LiteLLM wrapper (hard rule 3, ADR-003) and the `llm_calls` writer (ADR-006).

This is the only module in the repo that imports `litellm` (docs/backend.md, layering
rule 5). Agents receive an `LlmCaller` via `AgentContext` and never see a provider
SDK; structured output is requested in the prompt text and parsed by the agent, so
nothing here uses provider-specific prompt features (ADR-003).

Every call — successful or not — writes exactly one `llm_calls` row. The correlation
fields (`agent`, `ingest_run_id`, `unit_id`, `card_id`, `round`) are supplied by the
caller: the agent returns a typed result and the runner is the one that knows which
unit/card/round a call served. `agent` is `role/variant` (e.g. `writer/default`) so
the trace names the implementation (ADR-007).

Failure handling (docs/architecture.md): a provider exception still writes its row
(`response` null, zero tokens/cost) and then propagates, so the runner can record the
error on `ingest_runs.error` and the next run retries. The log row is written on its
own session and committed independently of any caller transaction, so a pipeline
rollback cannot lose the trace.
"""

import logging
import time
from typing import Any

import litellm
from sqlalchemy.orm import Session, sessionmaker

from recally.models import LlmCall

logger = logging.getLogger(__name__)

MICROUSD_PER_USD = 1_000_000


class LlmCaller:
    """The one callable agents use for LLM access, injected via `AgentContext`.

    `log_payloads` comes from `LLM_LOG_PAYLOADS` (default true); when false,
    `request`/`response` are stored as null and every other column is still populated.
    """

    def __init__(self, session_factory: sessionmaker[Session], *, log_payloads: bool) -> None:
        self._session_factory = session_factory
        self._log_payloads = log_payloads

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
        """Make one completion call, log it, and return the assistant message text."""
        started = time.monotonic()
        try:
            response = litellm.completion(model=model, messages=messages)
        except Exception:
            self._write_row(
                agent=agent,
                model=model,
                ingest_run_id=ingest_run_id,
                unit_id=unit_id,
                card_id=card_id,
                round=round,
                request=messages if self._log_payloads else None,
                response=None,
                input_tokens=0,
                output_tokens=0,
                cost_microusd=0,
                latency_ms=_elapsed_ms(started),
            )
            raise
        latency_ms = _elapsed_ms(started)

        content = response.choices[0].message.content or ""
        usage = response.usage
        self._write_row(
            agent=agent,
            model=model,
            ingest_run_id=ingest_run_id,
            unit_id=unit_id,
            card_id=card_id,
            round=round,
            request=messages if self._log_payloads else None,
            response=content if self._log_payloads else None,
            input_tokens=usage.prompt_tokens if usage else 0,
            output_tokens=usage.completion_tokens if usage else 0,
            cost_microusd=_cost_microusd(model, response),
            latency_ms=latency_ms,
        )
        return content

    def _write_row(self, **fields: Any) -> None:
        """Persist one `llm_calls` row on its own session, committed immediately."""
        with self._session_factory() as session:
            session.add(LlmCall(**fields))
            session.commit()


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


def _cost_microusd(model: str, response: Any) -> int:
    """Integer micro-USD from `litellm.completion_cost` (1 USD = 1_000_000).

    Ollama runs locally and costs nothing, so it is never priced. If LiteLLM cannot
    price another model the cost is recorded as 0 with a warning rather than losing
    the call's row: the trace matters more than an exact figure.
    """
    if model.startswith("ollama"):
        return 0
    try:
        cost_usd = float(litellm.completion_cost(completion_response=response))
    except Exception:
        logger.warning("completion_cost failed for model %s; recording 0", model)
        return 0
    return int(cost_usd * MICROUSD_PER_USD)
