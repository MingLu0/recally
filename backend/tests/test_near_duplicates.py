"""Deterministic near-duplicate flagging at curation (#291, docs/agents.md §2).

Hard rule 6 dedupes by annotation UUID, so the same passage highlighted twice
reaches the Curator as two rows. The texts below are the cycle-4 card-102 pairs
(h215/h220, h216/h221) and the committed fixture's clipped row `efaf55cf-…`, an
exact prefix of `bf9830d8-…`. Lexical only — no LLM on this path (hard rule 2).
"""

from recally.near_duplicates import find_near_duplicates

AUTONOMY_TEXT = "operational autonomy, contextual awareness, and decision-making authority."
LEVELS_TEXT = (
    "understanding the levels of agent interaction becomes essential for designing "
    "robust, scalable, and intelligent architectures"
)
SANDBOX_FULL_TEXT = (
    "Specifically, the generated code is written to the sandboxed file system, where it "
    "can be executed and tested in isolation. The critic then reviews the test results "
    "and either approves the code or sends it back with feedback."
)
SANDBOX_CLIPPED_TEXT = SANDBOX_FULL_TEXT[:97]  # clipped mid-word, as exports are


def test_near_duplicate_detection_exact_and_truncated_pairs() -> None:
    """Exact, punctuation-variant and truncated-prefix pairs link to their fuller
    sibling; unrelated texts — including ones sharing a short opening — do not."""
    highlights = [
        (215, AUTONOMY_TEXT),
        (216, LEVELS_TEXT),
        (217, SANDBOX_CLIPPED_TEXT),
        (218, "Version these contracts explicitly so that agents can evolve independently."),
        (219, "Version these prompts alongside the code that calls them."),
        (220, AUTONOMY_TEXT),
        (
            221,
            "Understanding the levels of agent interaction becomes essential for designing "
            "robust, scalable  and intelligent architectures.",
        ),
        (222, SANDBOX_FULL_TEXT),
    ]

    duplicate_of = find_near_duplicates(highlights)

    assert duplicate_of == {
        220: 215,  # exact: equal length, so the earlier row in export order is canonical
        216: 221,  # case, punctuation and whitespace variant: the longer raw text wins
        217: 222,  # truncated prefix points at its fuller sibling, never the reverse
    }
    # Negative: two rows that open alike but say different things are not duplicates.
    assert 218 not in duplicate_of
    assert 219 not in duplicate_of


def test_short_contained_fragment_is_not_a_duplicate() -> None:
    """Containment needs a substantial shorter text: a bare phrase that happens to
    occur inside a longer highlight is a different highlight, not a re-highlight."""
    duplicate_of = find_near_duplicates([(1, "the critic"), (2, SANDBOX_FULL_TEXT)])

    assert duplicate_of == {}
