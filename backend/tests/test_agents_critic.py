"""The #251 gate for the Critic prompt (docs/agents.md, "4. Critic Agent").

The Critic almost never refuses a card: on the #248 eval baseline it scored
missed-keep 0/48 with false-keep 20/23, and 13 of the 20 false-keeps are single
cards on units where the human binned everything. The cause is the prompt, not
the loop: rule 4's "bare headings, navigation fragments" list does not name the
shapes the human actually bins (sentence fragments missing their subject,
chapter transitions, over-generic claims), and nothing asks whether the source
can support a card before the card itself is judged.

These tests pin the prompt contract the fix must satisfy. They assert on the
*rendered* prompt — the text the model actually sees — via the module's own
render path, so a placeholder that stops substituting fails here too.
"""

from recally.agents.base import CardDraft, CriticRequest
from recally.agents.critic.default import _render_prompt

SOURCE_TEXT = (
    "Spaced repetition works because each review is scheduled just before the "
    "predicted moment of forgetting, so the memory is reconsolidated at peak "
    "retrieval effort."
)
CARD = CardDraft(
    type="qa",
    front="Why does spaced repetition schedule a review just before forgetting?",
    back="Retrieval at peak effort reconsolidates the memory.",
    rationale="Tests the mechanism, not the definition.",
    guidance_version=None,
)


def _rendered_prompt(source_truncated: bool = False) -> str:
    request = CriticRequest(
        cards=[CARD], source_text=SOURCE_TEXT, source_truncated=source_truncated
    )
    return _render_prompt(request)


def test_critic_prompt_asks_about_source_worthiness_first() -> None:
    """The rendered prompt directs the Critic to judge whether the source can
    support a card before judging the card itself (#251)."""
    prompt = _rendered_prompt().lower()
    assert "can this source support" in prompt
    assert prompt.index("can this source support") < prompt.index("atomicity")


def test_critic_prompt_names_the_measured_reject_shapes() -> None:
    """The rubric names the shapes of the 12 pure-reject units — sentence
    fragments, connective prose and over-generic claims — and quotes the
    human's recorded reject reasons, the only written record of the standard."""
    prompt = _rendered_prompt().lower()
    assert "sentence fragment" in prompt
    assert "connective prose" in prompt or "chapter transition" in prompt
    assert "true of any system" in prompt
    for reason in ("too trivial", "too abstract", "too isolated", "does not make much sense"):
        assert reason in prompt


def test_critic_prompt_routes_specific_fragments_to_revise_not_reject() -> None:
    """The source shapes are suspicion triggers, not verdicts: a fragment or
    generic-sounding source that still names something specific (a list, a
    named pair, a concrete mechanism) routes to `revise`; `reject` is reserved
    for sources with no nameable fact (#252 review — the v1 wording treated the
    shapes as sufficient for reject and cost 6 missed-keeps)."""
    prompt = _rendered_prompt().lower()
    assert "names something specific" in prompt
    assert "no nameable fact" in prompt
    specific_idx = prompt.index("names something specific")
    assert "revise" in prompt[max(0, specific_idx - 200) : specific_idx + 600]
    empty_idx = prompt.index("no nameable fact")
    assert "reject" in prompt[max(0, empty_idx - 200) : empty_idx + 600]


def test_critic_prompt_keeps_all_five_criteria() -> None:
    """Atomicity, unambiguity, self-containedness, non-triviality and factual
    fidelity all survive the edit (regression guard)."""
    prompt = _rendered_prompt().lower()
    for criterion in (
        "atomicity",
        "unambiguity",
        "self-containedness",
        "non-triviality",
        "factual fidelity",
    ):
        assert criterion in prompt


def test_critic_prompt_keeps_reject_distinct_from_revise() -> None:
    """`reject` stays for the unsalvageable, not the bad: a card that could be
    rewritten is `revise`. The distinction decides whether a human ever sees
    the card, so an edit that blurs it is a regression (regression guard)."""
    prompt = _rendered_prompt().lower()
    assert "unsalvageable" in prompt
    assert "could be rewritten" in prompt


def test_critic_prompt_renders_all_placeholders() -> None:
    """`$source_text` and `$cards` substitute, the truncation block appears only
    for a clipped source, and no stray `$` survives rendering."""
    rendered = _rendered_prompt()
    assert SOURCE_TEXT in rendered
    assert CARD.front in rendered
    assert "$" not in rendered
    assert "TRUNCATION_NOTE" not in rendered

    truncated = _rendered_prompt(source_truncated=True)
    assert SOURCE_TEXT in truncated
    assert "$" not in truncated
    assert "TRUNCATION_NOTE" not in truncated
    assert "clipped" in truncated.lower()
