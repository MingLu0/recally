# Superseded design explorations

**Nothing here is the spec.** These are directions that were tried and rejected. They are kept so a rejected direction is not re-proposed as if it were new — not as a reference for building anything.

For the current design, read [`../design-system.md`](../design-system.md).

| Files | What it was | Why it was dropped |
|---|---|---|
| `Main`, `Editorial`, `Bold`, `Focus` | First pass: the review card alone, four style directions, no device frame | Too flat and unpresented — judged as wireframes rather than a product |
| `SoftToday`, `SoftReview` | Pastel green/lilac, soft cards, arc gauge | Friendly but generic; that palette is a common 2024-25 app look |
| `InkToday`, `InkReview` | Warm paper, Fraunces serif, books drawn as spines | Characterful, but the serif and warm ground lost to the flat direction |
| `HwToday`, `HwReview`, `HwSheet` | The chosen flat structure in Headway's blue-on-cream | Structure kept, palette replaced with Recally's teal |
| `RcToday`, `RcCool` | The teal design on warm cream and cool near-white grounds | Ground comparison; pure white won |
| `DarkBlack`, `DarkTeal` | Dark theme on true black and a teal-tinted ground | Ground comparison; near-black `#121413` won |

Note `RcToday` and `RcCool` share the `Rc` prefix with the current light screens but are **not** current — that naming collision is why these files were moved here.

These are not on the canvas. To view one, open the file in a browser (it renders standalone apart from the missing `support.js` runtime) or re-add it to `canvas.json` and re-seed.
