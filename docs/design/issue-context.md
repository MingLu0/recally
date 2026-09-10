# Issue context block

Paste this into any GitHub issue that touches an Android screen, so every agent planning or building one starts from the same framing. Keep it in sync with `design-system.md` — that file is the spec; this one is a pointer to it.

---

## Design context

**Read [`docs/design/design-system.md`](docs/design/design-system.md).** It is authoritative — where it and an artboard disagree, it wins. It carries:

- Colour tokens with **light and dark columns**, mapped to Material 3 roles
- An 11-step type scale mapped to M3 names (`titleLarge`, `bodyMedium`, …) — write `MaterialTheme.typography.*`, never a raw `sp`
- Spacing, radius (`sm` 5 / `md` 12 / `lg` 22 / `pill`), and **no elevation anywhere** — separation is a hairline border, never a shadow
- Per-component specs: rating row, card surface, stats strip, session progress, chapter header, critique block, cloze rendering, highlight disclosure, bottom sheet, bottom nav
- The **states table** — offline, 401, nothing-due, queue-clear, loading, `needs_human`, `truncated`, push window, connection test. These are part of the spec, not extras.

**Screen artboards** (plain HTML, 390×844 inside a decorative device frame — the frame and backdrop are presentation only; build what is inside):

| Screen | Light | Dark |
|---|---|---|
| Today | `RcWhite.dc.html` | `DarkNeutral.dc.html` |
| Review — front | `RcFront.dc.html` | `DkFront.dc.html` |
| Review — flipped | `RcReview.dc.html` | `DkReview.dc.html` |
| Session summary | `RcSheet.dc.html` | `DkSheet.dc.html` |
| Approval queue | `RcApprove.dc.html` | `DkApprove.dc.html` |
| Decks | `RcDecks.dc.html` | `DkDecks.dc.html` |
| Book detail | `RcBook.dc.html` | `DkBook.dc.html` |
| Stats | `RcStats.dc.html` | `DkStats.dc.html` |
| Settings | `RcSettings.dc.html` | `DkSettings.dc.html` |
| States | `RcStates.dc.html` | `DkStates.dc.html` |

All under `docs/design/`. Canvas: https://claude.ai/code/artifact/25472047-e5cc-473c-8709-81f1ffebf89f

**Do not read `docs/design/_archive/`.** Fifteen rejected directions, kept only so none is re-proposed. Nothing there is spec.

### Constraints the design is bound by

- **No status bar is drawn** in any artboard — the OS renders its own. Do not add one.
- **`response_ms` is flip-to-rate.** Nothing that hints at the answer or invites a decision may be on screen before the flip — hence the separate front and flipped screens.
- **The client never computes FSRS state** (hard rule 5, ADR-005). Interval hints appear on Again and Hard only, derived from `learning_steps_minutes`; Good and Easy have no projection available before rating.
- **Session progress is "cards left", never "N of 12".** `docs/android.md` (*Screens → 2. Review session*) re-queues Again/Hard cards inside the session, so the total is not fixed.
- **`truncated` is flagged, never reconstructed** (hard rule 7).
- **Bulk approve excludes `needs_human` cards** (hard rule 1).
- Screen composables are pure (`docs/android.md`, *Architecture*), so each artboard maps to a `@Preview` with a hand-built `UiState`.

### Blockers — check before planning a screen

Three API gaps remain in [`docs/roadmap.md`](docs/roadmap.md) under *Feature gaps*. The designs show data no documented endpoint returns; this was deliberate, and the endpoints are expected to catch up. Each has a proposed field shape and a test gate. Pending counts were closed in issue #132, per-book progress in #133 and the next-due timestamp in #134.

| | Blocks | Needs |
|---|---|---|
| G5 | Book detail, Decks | `GET /decks/{book_id}/cards` has no documented response at all |
| G6 | Decks | A truncated count per book |

If an issue covers a screen with an open gap, either resolve the gap in `api-spec.md` first or scope the dependent element out — do not invent a field.

Navigation (bottom nav: Today, Decks, Stats, Settings; Review and Approve entered from Today), the session progress indicator, the "needs you" filter chip, the summary sheet's rating buckets, and system-theme-following are all specified in `docs/android.md` (*Design*, *Navigation*, *Screens*) — build to that doc, they are settled.
