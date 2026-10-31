"""Text-level near-duplicate detection over one chapter's highlights (#291).

Hard rule 6 dedupes by annotation UUID, which is structurally blind to the same
passage highlighted twice under two UUIDs. This module catches that case lexically,
so the runner can flag it in the Curator payload; it never drops or rewrites a row.
Deterministic, no LLM (hard rule 2), and no embeddings — two texts are duplicates
only when, after normalizing case, punctuation and whitespace:

- they are equal (any length), or
- the shorter one, of at least `MIN_CONTAINED_CHARS`, occurs inside the longer —
  the truncated-prefix case (a row clipped mid-word, hard rule 7), a row clipped at
  the start, or a sentence highlighted again inside a longer passage.
"""

import re
from collections.abc import Sequence

# Below this a shared phrase ("the critic") is coincidence, not a re-highlight.
# Real clipped rows are far longer: the fixture's `efaf55cf-…` prefix is 149 chars.
MIN_CONTAINED_CHARS = 40

_NON_WORD_RUN = re.compile(r"[\W_]+")


def normalize(text: str) -> str:
    """Casefold and collapse every run of punctuation or whitespace to one space."""
    return _NON_WORD_RUN.sub(" ", text.casefold()).strip()


def find_near_duplicates(highlights: Sequence[tuple[int, str]]) -> dict[int, int]:
    """Map each duplicate highlight id to the canonical id of its duplicate cluster.

    `highlights` is `(id, text)` in export order. Duplicate pairs are merged
    transitively into clusters; each cluster's canonical member is its longest raw
    text — the fuller sibling of a clipped row — with ties going to the earliest in
    export order. Canonical ids and rows with no duplicate are absent from the result.
    """
    normalized_texts = [normalize(text) for _, text in highlights]
    cluster_parent = list(range(len(highlights)))

    def cluster_root(index: int) -> int:
        while cluster_parent[index] != index:
            cluster_parent[index] = cluster_parent[cluster_parent[index]]
            index = cluster_parent[index]
        return index

    for first_index, first_text in enumerate(normalized_texts):
        for second_index in range(first_index + 1, len(normalized_texts)):
            if _is_duplicate_pair(first_text, normalized_texts[second_index]):
                cluster_parent[cluster_root(second_index)] = cluster_root(first_index)

    members_by_root: dict[int, list[int]] = {}
    for index in range(len(highlights)):
        members_by_root.setdefault(cluster_root(index), []).append(index)

    duplicate_of: dict[int, int] = {}
    for member_indexes in members_by_root.values():
        if len(member_indexes) < 2:
            continue
        # `min` keeps the first of equal keys, and members are in export order.
        canonical_index = min(member_indexes, key=lambda index: -len(highlights[index][1]))
        for member_index in member_indexes:
            if member_index != canonical_index:
                duplicate_of[highlights[member_index][0]] = highlights[canonical_index][0]
    return duplicate_of


def _is_duplicate_pair(first_text: str, second_text: str) -> bool:
    if not first_text or not second_text:
        return False
    if first_text == second_text:
        return True
    shorter_text, longer_text = sorted((first_text, second_text), key=len)
    return len(shorter_text) >= MIN_CONTAINED_CHARS and shorter_text in longer_text
