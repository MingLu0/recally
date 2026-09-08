# Design

Screen designs for the Android app.

## What to read

**If you are implementing a screen**, read [`design-system.md`](design-system.md). It carries the colour tokens (light and dark), the type scale mapped to Material 3 names, spacing and radius values, component specs, and the required states. That document is authoritative — where it and an artboard disagree, it wins.

**If you want to see the designs**, open the canvas: <https://claude.ai/code/artifact/25472047-e5cc-473c-8709-81f1ffebf89f>

Page *Dark* and page *Recally — white + teal* are current. The other pages are superseded exploration, kept so a rejected direction is not re-proposed.

**If you want to read a screen's markup**, the artboards are plain HTML in this directory. `Rc*.dc.html` are light, `Dk*.dc.html` dark. Each is a 390×844 screen inside a decorative device frame — the frame and the tinted backdrop are presentation only, and the content inside is what Compose implements. No status bar is drawn: the OS renders its own there.

## Files

| | |
|---|---|
| `design-system.md` | The implementable spec. Start here. |
| `Rc*.dc.html` | Light-theme artboards — Today, Review front/flipped, Session summary, Approve, Decks, Book, Stats, Settings, States. |
| `Dk*.dc.html`, `DarkNeutral.dc.html` | The same ten screens, dark. |
| `canvas.json` | Artboard positions, pages, and the sticky notes. |
| Everything else | Superseded exploration — earlier style directions and the ground-colour comparison. |

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

`design-system.md` ends with two lists that matter before anyone builds:

- **API gaps G1–G6** — data the designs show that no documented endpoint returns. Tracked with proposed field shapes and test gates in [`../roadmap.md`](../roadmap.md) under *Feature gaps*.
- **Open decisions** — four additions to `android.md` that need adopting or dropping (bottom navigation, session progress indicator, the separate "needs you" filter, the summary sheet's rating buckets).
