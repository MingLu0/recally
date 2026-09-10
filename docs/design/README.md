# Design

Screen designs for the Android app.

## What to read

**If you are implementing a screen**, read [`design-system.md`](design-system.md). It carries the colour tokens (light and dark), the type scale mapped to Material 3 names, spacing and radius values, component specs, and the required states. That document is authoritative — where it and an artboard disagree, it wins.

**If you want to see the designs**, open the canvas: <https://claude.ai/code/artifact/25472047-e5cc-473c-8709-81f1ffebf89f>

Two pages: *Dark* and *Recally — white + teal*. Superseded directions moved to `_archive/` and are no longer on the canvas.

**If you want to read a screen's markup**, the artboards are plain HTML in this directory — the table below says which file is which screen. Each is a 390×844 screen inside a decorative device frame — the frame and the tinted backdrop are presentation only, and the content inside is what Compose implements. No status bar is drawn: the OS renders its own there.

## Files

| | |
|---|---|
| `design-system.md` | The implementable spec. Start here. |
| `issue-context.md` | A block to paste into Android GitHub issues — points a plan agent at the spec, the right artboards, and the remaining G5–G6 blockers. |
| `canvas.json` | Artboard positions, pages, and the sticky notes. |
| `icon/` | The launcher icon: its own canvas, the generator, and the shipped vectors. See `icon/README.md`. |
| `_archive/` | Superseded exploration. **Not the spec** — see its README. |

Every `.dc.html` in this directory (not `_archive/`) is current. Ten screens, light and dark:

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

`recally-style-explore.html` is **not committed**. It is the canvas editor payload: the artboards plus ~2 MB of editor code, regenerated on every publish. Git cannot diff it usefully.

## Rebuilding the canvas

The seeded page is produced by the `design` skill's helper, which is not vendored here. From this directory, with the skill's base directory as `$SK`:

```
node "$SK/seed-canvas.mjs" \
  --template "$SK/payload.template.html" \
  --out recally-style-explore.html \
  --title "Recally Style Directions" \
  --artboard DarkNeutral.dc.html --artboard DkFront.dc.html ... \
  --canvas canvas.json

node "$SK/seed-canvas.mjs" --check recally-style-explore.html
```

Pass every `.dc.html` in this directory as its own `--artboard`. Run `/design` in Claude Code to get `$SK` if the path has gone.

Publishing the result to the artifact URL above keeps that link working; publishing without the URL creates a separate artifact instead.

## Open items

`design-system.md` ends with one list that matters before anyone builds:

- **API gaps G5–G6** — data the designs show that no documented endpoint returns. Tracked with proposed field shapes and test gates in [`../roadmap.md`](../roadmap.md) under *Feature gaps*. (Pending counts closed in #132, per-book progress in #133, `next_due_at` in #134, bulk approve in #168.)

The former *Open decisions* (bottom navigation, session progress indicator, the "needs you" filter, the summary sheet's rating buckets, theme switching) were adopted into [`../android.md`](../android.md) and are no longer open.
