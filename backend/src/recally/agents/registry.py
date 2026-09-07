"""The (role, variant) -> implementation registry (ADR-007).

Variants register under a `(role, variant)` key; the active variant per role comes
from `AGENT_CURATOR` / `AGENT_WRITER` / `AGENT_CRITIC` / `AGENT_LEARNER` (default
`default`, docs/config.md). `pipeline.py` and the container resolve through this
module, never by importing a variant module directly — registry.py is the only place
variant modules are imported.

An unknown configured variant fails at startup (container build) with a message
naming the role and the registered variants, not at the first LLM call half a
chapter into a run.
"""

from typing import Any

from recally.agents.critic.default import DefaultCritic
from recally.config import Settings

ROLES = ("curator", "writer", "critic", "learner")
DEFAULT_VARIANT = "default"


class UnknownAgentVariantError(ValueError):
    """A configured `AGENT_<ROLE>` variant is not registered for that role."""


class AgentRegistry:
    """The variant table. The process-wide instance is `default_registry`; tests
    build their own so registrations never leak between tests."""

    def __init__(self) -> None:
        self._implementations: dict[tuple[str, str], Any] = {}

    def register(self, role: str, variant: str, implementation: Any) -> None:
        """Register `implementation` under `(role, variant)`, replacing any prior entry."""
        if role not in ROLES:
            raise ValueError(f"unknown agent role {role!r}; roles are {', '.join(ROLES)}")
        self._implementations[(role, variant)] = implementation

    def variants(self, role: str) -> list[str]:
        """The registered variants for a role, sorted, for error messages and checks."""
        return sorted(
            variant for registered_role, variant in self._implementations if registered_role == role
        )

    def resolve(self, role: str, settings: Settings) -> Any:
        """The implementation for the variant `AGENT_<ROLE>` names in `settings`."""
        variant = str(getattr(settings, f"agent_{role}"))
        try:
            return self._implementations[(role, variant)]
        except KeyError:
            raise UnknownAgentVariantError(
                f"unknown {role} variant {variant!r}; "
                f"registered variants: {self.variants(role) or ['(none)']}"
            ) from None

    def validate(self, settings: Settings) -> None:
        """Startup check: every configured variant for a role that has registrations
        must be one of them.

        A role with nothing registered yet is skipped — agent implementations land in
        the later step-2 tickets, and until one does there is nothing to validate
        against. Once a role has any registration, a typo'd `AGENT_<ROLE>` raises
        here at container build rather than mid-run.
        """
        for role in ROLES:
            registered = self.variants(role)
            configured = str(getattr(settings, f"agent_{role}"))
            if registered and configured not in registered:
                raise UnknownAgentVariantError(
                    f"unknown {role} variant {configured!r}; registered variants: {registered}"
                )


default_registry = AgentRegistry()

# The built-in variants. Registered as instances (they are stateless) so
# `container.agent(role)` hands the pipeline a callable that already satisfies the
# role protocol.
default_registry.register("critic", DEFAULT_VARIANT, DefaultCritic())


def register(role: str, variant: str, implementation: Any) -> None:
    """Register on the process-wide registry; variant modules call this at import."""
    default_registry.register(role, variant, implementation)


def resolve(role: str, settings: Settings) -> Any:
    """Resolve on the process-wide registry."""
    return default_registry.resolve(role, settings)


# Variant modules self-register at import; registry.py is the only place variant
# modules are imported, so the pipeline never imports an implementation directly.
# New variants append below.
from recally.agents.curator import default as _curator_default  # noqa: E402, F401
from recally.agents.writer import default as _writer_default  # noqa: E402, F401
