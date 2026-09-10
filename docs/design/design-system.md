# Recally — Android Design System

The visual spec for the Android app. Screens are designed on a canvas (see *Canvas* below); this document is the part Compose implements. Where this file and a mockup disagree, **this file wins** — the mockups were drawn first and carry some drift.

Direction: flat, high-contrast, near-white. Cards are defined by a hairline border, never a fill or a shadow. Colour appears almost exclusively on numbers and on the single primary action per screen.

Status: settled 2026-09-07, light and dark. Implemented at roadmap step 4 (`docs/roadmap.md`).

## Canvas

Design canvas: <https://claude.ai/code/artifact/25472047-e5cc-473c-8709-81f1ffebf89f>

Source artboards are committed beside this file as `*.dc.html` with `canvas.json`. Pages *Recally — white + teal* (light, `Rc*`) and *Dark* (`Dk*`) are current; the other pages are superseded exploration, kept for reference.

## Colour

Both themes are specified. The dark theme uses **elevated surfaces**: cards take a fill one step above the ground and keep a faint border, because hairline borders alone do not separate on dark. This is the one structural departure from light mode, where cards are unfilled.

### Roles

| Token | Light | Dark | M3 role | Used for |
|---|---|---|---|---|
| `ground` | `#FFFFFF` | `#121413` | `surface` | Page background. |
| `surface` | — | `#232725` | `surfaceContainer` | **Dark only.** Card fill, one step above the ground. In light mode cards have no fill; on dark, lightness carries the hierarchy because hairlines alone do not read. |
| `ink` | `#17181A` | `#ECEFED` | `onSurface` | Headings, card question text, primary labels. |
| `ink-strong` | `#3A3A36` | `#C9CECB` | — | Secondary interactive labels — outlined button text, collapsed chapter rows. |
| `ink-muted` | `#57574F` | `#ADB3B0` | `onSurfaceVariant` | Body copy, answer text, secondary labels. |
| `ink-soft` | `#76766D` | `#8F9693` | — | Tertiary labels: list-row values, sub-labels beside a metric. |
| `ink-faint` | `#94948D` | `#8B928F` | `outline` | Metadata, chapter names, placeholder text. Dark value is **lifted** — the naive equivalent failed AA on `surface`. |
| `line` | `#E3E3E0` | `#2C302E` | `outlineVariant` | Card and control borders. |
| `line-soft` | `#F0F0EE` | `#2C302E` | — | Dividers inside a card. Collapses onto `line` in dark; two hairline steps are indistinguishable there. |
| `track` | `#EDEDEA` | `#2C302E` | — | Unfilled portion of a progress bar. Fills only, never a border. |
| `neutral-wash` | `#F4F4F2` | `#232725` | `surfaceVariant` | Neutral chip fills, status-free icon backgrounds. |
| `primary-muted` | `#CFE0DA` | `#2B4A40` | — | Non-focal data marks — forecast chart's non-today bars, unrevealed cloze rule. Never text. |
| `primary` | `#1F6F5C` | `#4FB894` | `primary` | Primary action fill, active nav, cloze blank, selected filter. |
| `primary-dark` | `#175646` | `#4FB894` | — | Pressed state. Dark mode presses by opacity, not hue. |
| `primary-wash` | `#E4F0EB` | `#153A30` | `primaryContainer` | Cloze blank background, primary-tinted surfaces. |
| `success` | `#2F9E6E` | `#4FC78F` | — | "Good" rating, retention figures, streak band. |
| `success-wash` | `#DBF0E6` | `#123528` | — | Success icon backgrounds. |
| `warn` | `#B85C2E` | `#E8975F` | — | "Hard" rating, `truncated` badge, critique text. |
| `warn-wash` | `#F8E6D8` | `#3A2A1C` | — | Warn badge backgrounds. |
| `accent` | `#D97742` | `#E8975F` | `tertiary` | New-card counts, unread dot, critique left rule. |
| `danger` | `#C0453A` | `#E8756A` | `error` | "Again" rating, lapse counts, `needs_human` badge text. |
| `danger-wash` | `#F7DEDB` | `#3A1F1C` | `errorContainer` | Danger badge backgrounds. |

Dark values are **re-derived, not inverted** — each hue lifted until it carries on a dark ground, each wash darkened to match. Mechanically flipping a light palette is what makes dark modes look wrong.

**Verified against WCAG AA** (4.5 body, 3.0 large/UI) on *both* `ground` and `surface`. Text on a card is the harder case and is what these were tuned to: `ink` 14.4:1, `ink-muted` 7.8:1, `ink-faint` 4.8:1, `primary` 6.8:1, all measured on `surface`. Re-run these checks if any value changes.

`surface` sits 1.24:1 above `ground` — deliberately chosen. Below ~1.2 the elevation stops reading; much above it and cards look grey rather than raised.

Rating tiles are the one component whose dark values are hand-tuned rather than mapped: their light form is an outlined tile tinted from its own semantic colour, and on dark both the fill and the border needed re-deriving so the outline still carries. See `RatingRow` below.

Book cover colours are **data, not theme** — each book gets a stable colour derived from its `book_id`. Light `#24403A` forest / `#4A3D55` plum; dark `#2F5349` / `#5D4D6B`, lifted so spines stay visible. Extend as a fixed ordered list; do not generate randomly, or a book's colour changes between installs.

### Presentation only — not implemented

The artboards wrap each screen in a device frame on a tinted backdrop so the canvas reads as a product shot. **None of this is app UI.** Frame `#000000`; backdrop `#E8E8E6` light, `#2A2D2B` dark; frame radii 44/56px. An implementer should build only what is inside the frame — and note that no status bar is drawn there, because the OS renders its own.

### Rules

- One filled button per screen. Everything else is outlined or plain.
- Colour on numbers, not on chrome. A metric is coloured; its label is `ink-faint`.
- Never use a shadow to separate a surface. Use `line`.
- Rating colours are semantic and fixed: Again `danger`, Hard `warn`, Good `success`, Easy `primary`.

## Type

**Inter Tight** throughout (variable, ~180 KB subset). No second family. Fallback `system-ui, sans-serif` — metrics are close enough that a font failure degrades cleanly.

| Token | Size / line | Weight | M3 name | Used for |
|---|---|---|---|---|
| `display` | 27 / 34 | 800 | `headlineMedium` | Sheet titles ("Session complete"). |
| `title` | 22 / 28 | 800 | `titleLarge` | Screen headings ("Stats", "Decks", "Approve"). |
| `section` | 15 / 20 | 800 | `titleSmall` | In-screen section headings ("Coming due", "Lapse rate"). Shares a size with `body` but is always 800 and `ink`. |
| `card-question` | 24 / 32 | 800 | `headlineSmall` | The review card's question. Its own step — it is the app's most-read text. |
| `metric` | 21 / 26 | 800 | `titleMedium` | Stats-strip numbers. |
| `metric-sm` | 19 / 24 | 800 | — | Summary-sheet row counts. |
| `body-lg` | 17 / 27 | 400 | `bodyLarge` | Answer text, cloze sentence. |
| `body` | 15 / 22 | 500 | `bodyMedium` | Button labels, list rows. |
| `body-sm` | 14 / 21 | 400 | `bodySmall` | Secondary copy, sheet subtitles. |
| `label` | 13 / 18 | 600 | `labelLarge` | Metadata, book titles in strips, filter chips. |
| `caption` | 12 / 16 | 400 | `labelMedium` | Metric labels under numbers, nav labels. |
| `badge` | 11 / 14 | 700 | `labelSmall` | Uppercase badges. `letter-spacing: 0.4px`. |

Letter-spacing: `-0.7px` on `title`/`card-question`, `-0.4px` on `metric`, `0` on body sizes, `+0.4px` on `badge`, `+0.7px` on the uppercase action bar.

All ten current artboards conform to this scale — verified 2026-09-07. Anything outside it is a bug in the mockup, not a new step.

## Spacing, radius, elevation

- **Spacing scale**: 4, 8, 12, 14, 16, 20, 22, 26 dp. Screen horizontal padding is **20dp** throughout. Card interior padding is **14dp**, 22dp for the review card.
- **Radius**: `sm` 5dp (badges, chart bar caps), `md` 12dp (cards, buttons, tiles), `lg` 22dp (bottom sheet top corners), `pill` 999dp (progress bars, filter chips). A circular icon background is half its own size — 17dp on the 34dp nav circle — which is `pill` in effect; either spelling is fine. Book spines are the one exception: `3px 6px 6px 3px`, asymmetric so the bound edge reads as a spine. All ten artboards conform — verified 2026-09-07.
- **Elevation**: none. No `Modifier.shadow` anywhere in the app. Separation is `line`.
- **Bottom inset**: 26dp below the nav bar and above sheet bottoms, for the gesture bar.

## Components

### Rating row (`ui/screens/review/RatingRow.kt`)
Four equal columns, `grid` with 8dp gap, each **66dp tall** (over the 44dp minimum). Good is `success`-filled; the others are outlined with a 1.5dp border tinted from their own semantic colour.

Light: outlined tiles sit on `ground` with a pale tinted border. Dark: both fill and border are re-derived — a faint tinted fill (`#2A1C1A` danger, `#2A2119` warn, `#16302A` success) under a stronger border (`#6B3730`, `#6B4A2C`, `#2F6B56`), so the outline still carries against `surface`. Mapping the light borders directly leaves them invisible.

**Interval hints appear on Again and Hard only.** Those two are derivable client-side from `learning_steps_minutes` in `GET /reviews/due` (`[1, 10]` → "<1m", "10m"). Good and Easy intervals come from FSRS and are **not** available before the rating is submitted — `POST /reviews/{id}/rate` returns `next_due` only afterwards. Showing a projection there would mean the client computing FSRS state, which `AGENTS.md` hard rule 5 and ADR-005 forbid. Do not add them without a server-side projection field.

### Card surface
Light: no fill at all — the card is `ground`, defined solely by its 1dp `line` border. Dark: `surface` fill plus the same border. `md` radius, no shadow in either theme. Internal sections separated by 1dp `line-soft`. Never nest a bordered card inside a bordered card — use a divider.

### Stats strip (`ui/screens/today/StatsStrip.kt`)
One bordered unit. Left cell fixed 104dp wide with a right `line` border, holding the streak. Right region holds three metrics in an equal grid. A full-width `primary` action bar is fused to the bottom, inside the same border and radius clip — it is part of the strip, not a separate button.

### Waiting for you (`ui/screens/today/TodayScreen.kt`)
Section title in `title`, then the two queue buckets as a two-column grid of bordered tiles (`md` radius, 14dp interior padding, 12dp gap). Each tile is a small `pill` dot in its bucket's colour, then the count in `metric`/`ink` stacked over its label in `label`/400/`ink-soft`.

**Colour lands on the dot, not the number** — `primary` for "to approve", `danger` for "need you". Both tiles count the same kind of thing, so a coloured figure would read as a status rather than a quantity; this is the "colour on numbers" rule (*Rules* above) meeting the case where two numbers are peers.

The counts are the collection-wide `counts` on `GET /cards/pending` (G1, issue #132) — never a list length. **The whole section is absent while the counts are null**: the approval queue requires connectivity and Today must render before it is reachable, so a heading with nothing under it would read as an error rather than an empty queue. The "need you" tile is dropped at zero — `needs_human` is an exception state, and a permanent "0 need you" tile makes the ordinary case look like it has an outstanding problem.

### Your books rail (`ui/screens/today/TodayScreen.kt`)
Section title in `title` over a `body`/`ink-faint` subtitle ("From your O'Reilly highlights"), then a horizontally scrolling row of 190dp book cards. Each card carries the spine chip and title, the card count in `caption`/`ink-faint`, and the server's `progress` on the same bar Decks uses — `BookSpineChip` and `DeckProgressBar` are shared, so a book reads identically on both screens.

Rendered from `GET /decks` (G2, issue #133; rail built in #154). Decks are remote-only, so an empty list draws **nothing** — no section header over an empty rail.

The artboard's "Import" tile is deliberately not built: ingestion is the watched folder (`AGENTS.md` hard rule 12) and `api-spec.md` documents no client-initiated import. Do not add one to fill the space.

### Session progress (`ui/screens/review/SessionProgress.kt`)
A single 6dp `pill` bar with proportional fills — `success` for cards answered Good/Easy, `warn` for those pending a repeat — plus a "N left" count in `caption`/`ink-faint`.

**Not a fixed segment-per-card bar.** `docs/android.md` (*Screens → 2. Review session*) re-queues Again/Hard cards inside the session, so a 12-card session produces more than 12 presentations. A fixed denominator would either jump backwards mid-session or silently drop the repeat. The header reads "6 done · 1 to repeat" rather than "7 of 12" for the same reason: **cards remaining, never a fixed total.** Derived from local session state; the server owns the real schedule.

### Chapter group header (`ui/screens/approve/ChapterHeader.kt`)
15×20dp book-colour spine chip, then book title (`label`, 700 `ink`), then `· chapter` (`label`, 400 `ink-faint`). Groups a run of cards; the flat list from `GET /cards/pending` is grouped client-side, ordered by book, chapter, `export_position`.

### Critique block (`ui/screens/approve/CritiqueBlock.kt`)
`#FDF6F3` fill, 3dp `accent` left rule, radius `0 8 8 0`. Label "CRITIC" in `badge`/`warn`, body in `body-sm`/`#6B5A4F`. Visually subordinate to the card text — it is context, not content.

Critique length is unbounded by design (the Writer ⇄ Critic loop runs up to 3 rounds), so a body longer than 4 lines clamps to 4 lines behind a chevron disclosure — the same idiom as the source-highlight disclosure below: chevron + "Read full critique" / "Show less" in `label`/`ink-muted`, expanding in place. The toggle appears only when the text actually overflows; a short critique renders bare. Without the clamp a long critique buries the card's own action row, inverting the subordination (issue #150).

### Cloze rendering (`ui/components/ClozeText.kt`)
`{{c1::answer}}` renders as the answer text on `primary-wash` with a 2dp `primary` bottom border, 4dp radius, 1×7dp padding, weight 700, colour `primary`. **Never show raw braces.** In review (unflipped) the same span renders as a blank of equivalent width. `docs/android.md` does not specify this; it is a design decision recorded here.

### Source-highlight disclosure (`ui/screens/approve/HighlightDisclosure.kt`)
Collapsed by default, above a `line-soft` top divider: chevron + "N source highlight(s)" in `label`/`ink-muted`. A grouped unit can carry several and they would otherwise dominate the card. Expanded, each highlight is `body-sm`/`ink-muted` with a `warn` "truncated" chip where `truncated` is true.

### Bottom sheet
`ground`, `lg` top radius, 38dp `rgba(20,26,24,0.38)` scrim. 38×4dp `#DEDEDA` grab handle, centred. Content padding 20dp horizontal, 26dp bottom.

### Bottom navigation
Four items: Today, Decks, Stats, Settings. Active item's icon sits in a 34dp `primary` filled circle with a white glyph; its label is `caption`/700/`primary`. Inactive icons are 21dp `ink-faint` strokes with `caption`/500 labels. 1dp `line-soft` top border.

Adopted into `docs/android.md` (*Navigation*): Review and Approve are entered from Today rather than being nav destinations, because both are modal tasks you finish and leave.

## Screens → packages → endpoints

Package root `dev.recally.ui` — layout in `docs/android.md`, *Project structure*.

| Artboard | Package | Endpoint |
|---|---|---|
| Today | `ui/screens/today` | `GET /reviews/due`, `GET /stats`, `GET /decks`, `GET /cards/pending` |
| Review — front | `ui/screens/review` | `GET /reviews/due` |
| Review — flipped | `ui/screens/review` | `POST /reviews/{id}/rate`, queued to `POST /reviews/rate-batch` |
| Session summary | `ui/screens/review` | local session state + `GET /stats` for next-due |
| Approval queue | `ui/screens/approve` | `GET /cards/pending`, `POST /cards/{id}/approve`, `POST /cards/{id}/reject` |
| Decks | `ui/screens/decks` | `GET /decks` |
| Book — chapters | `ui/screens/decks` | `GET /decks/{book_id}/cards`, grouped client-side by chapter |
| Stats | `ui/screens/stats` | `GET /stats` |
| Settings | `ui/screens/settings` | connection test against any authenticated endpoint |
| States | — | reference sheet; every screen implements these |

All ten screens are drawn. Two notes on the later ones:

- **Book detail expands chapters in place** rather than pushing a third screen. One book has 30 chapters (*30 Agents in 30 Days*), so a third navigation level would be tedious to browse. Cards carry the per-card controls from ADR-008 — edit, suspend, unsuspend — per `docs/android.md` (*Screens → 4. Decks*); only review actions (rate, approve) do not happen here.
- **Stats invents nothing.** Every figure maps one-to-one onto `GET /stats`: `streak_days`, `reviews_today`, `retention_30d`, `forecast[]`, `lapse_rate_by_type`. It is the only screen with no G-gap dependency.

## States

Every screen implements these. They are as much a part of the design as the happy path.

| State | Treatment |
|---|---|
| Offline | Persistent bar below the app bar: `warn-wash` fill, `warn` text, "Offline — N ratings queued". Review works; Approve is disabled with an explanatory row, per `docs/android.md` (*Offline-first sync*). |
| 401 | `danger-wash` banner, "Check your API key in Settings", tapping opens Settings. Never a crash (`docs/android.md`, *Connecting to the backend*). |
| Nothing due | Today's action bar becomes `line`-bordered and `ink-faint`: "Nothing due — next card in 4 hours". |
| Queue drained | Approve shows a centred `success` check with "Queue clear". |
| Loading | Skeleton blocks in `line-soft` at the real component's dimensions. No spinners. |
| `needs_human` | `danger-wash` "NEEDS YOU" badge on the card; also a filter chip on Approve. |
| `truncated` | `warn-wash` "TRUNCATED SOURCE" badge. Flag only — **never** attempt to reconstruct the text (`AGENTS.md` hard rule 7). |
| Push window | Read-only status row, never a toggle. `PUSH_WINDOW` is a server env var in `RECALLY_TIMEZONE` local time (`docs/config.md`), and `devices` carries no per-device preference — a switch would imply control the backend does not offer. State the window and say where it is set. |
| Connection test | Four results, all specified: connected, 401, no answer, and **HTTPS required** — the network security config (`docs/android.md`, *Connecting to the backend*) blocks cleartext to any non-private host before a request leaves the phone. |

## Constraints this design must not break

From `AGENTS.md` hard rules — the design is bound by these, not merely aware of them.

1. **Nothing enters FSRS without human approval** (rule 1). Bulk "Approve all N clean" acts only on `pending_review` cards that passed round-1 accept; `needs_human` cards are excluded from any bulk action and must be opened individually.
2. **The pipeline never sets `rejected`** (rule 9). Reject is a human action in the UI only.
3. **The client never computes FSRS state** (rule 5, ADR-005). Same-session re-queueing of Again/Hard cards uses `learning_steps_minutes` from `GET /reviews/due` to decide *when to show a card again in this session* — the server owns the real schedule, and the summary sheet's "next due" comes from the server, never from client arithmetic.
4. **Ratings carry the client `rated_at`** and flush via `rate-batch` in order (`docs/android.md`, *Offline-first sync*). The UI must not renumber or reorder the queue.
5. **`response_ms` is flip-to-rate.** Nothing that hints at the answer or invites a decision may be on screen before the flip — hence the separate front artboard with a single button.

## API gaps this design depends on

Audited against `docs/api-spec.md` on 2026-09-07. Each item is data the design displays that no documented endpoint returns. These are **kept in the design deliberately** — the screens are built as intended and the endpoints catch up.

Tracked as **G4** in [`docs/roadmap.md`](../roadmap.md) → *Feature gaps*, which carries the proposed field shapes and test gates. Resolve them before the step 4 gate passes. G5–G6 were added by a second audit on 2026-09-07 covering Decks, Book, Stats, Settings and States. The pending-counts gap was closed in issue #132 (`counts` on `GET /cards/pending` backs the tiles and the Approve header), per-book progress in issue #133 (`progress` on `GET /decks`), and the next-due timestamp in issue #134 (`next_due_at` on `GET /stats`).

| # | Design element | Needs | Where |
|---|---|---|---|
| G4 | "Approve 5 ready" bulk action | A batch endpoint, or a recorded decision that the client fans out. `needs_human` cards excluded either way. | Approve |
| G5 | Per-card due dates and state; chapter counts | `GET /decks/{book_id}/cards` has no documented response at all. Needs `state` + `due` per card, and a `chapters` count on `GET /decks`. | Book, Decks |
| G6 | "2 TRUNCATED" per book | A truncated count on `GET /decks`, or drop the badge. | Decks |

Fixed during the audit, recorded so they are not reintroduced: Good/Easy interval hints (violated hard rule 5); a "142 reviews / 38 new" stats strip mixing three timeframes under one "week" heading, when `NEW_CARDS_PER_DAY` caps new cards at 10; a fixed 12-segment progress bar incompatible with same-session re-queueing; "Lapsed" as a summary label, colliding with the spec's `lapse_rate_by_type`; and an approval card missing its required approve/edit/reject row (`docs/android.md`, *Screens → 3. Approval queue*).
