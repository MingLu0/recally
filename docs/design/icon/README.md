# Launcher icon

The Recally launcher mark: an Inter Tight ExtraBold **R** cut by a horizontal
*recall gap* — the letter is partly forgotten and the eye completes it.

The implementable spec is [`../design-system.md`](../design-system.md), section
**Icon** — geometry, file roles, and the two things not to undo. That document
wins over anything here.

Canvas: <https://claude.ai/code/artifact/c76492df-6e98-4534-915b-ff6843e2887a>

## Files

| | |
|---|---|
| `build_icon.py` | Generates all five vectors from the vendored font and one set of constants. Run it rather than hand-editing the XML. |
| `ic_launcher*.xml`, `ic_notification.xml` | Generated output, mirrored into `android/app/src/main/res/`. |
| `Final.dc.html` | The shipping asset, rendered from the committed XML. |
| `Gap.dc.html` | Why the gap is the width it is. |
| `Monogram.dc.html` | The six monogram executions D4 was chosen from. |
| `Main.dc.html` | The original four directions. |

## Regenerating

```
python3 docs/design/icon/build_icon.py
cp docs/design/icon/ic_launcher_{background,foreground,monochrome}.xml \
   docs/design/icon/ic_notification.xml \
   android/app/src/main/res/drawable/
cp docs/design/icon/ic_launcher.xml \
   android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
cp docs/design/icon/ic_launcher.xml \
   android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
```

The script fails loudly if a geometry change would push the mark outside the
circular mask under launcher parallax — that check is the reason it exists.

`recally-launcher-icon.html` is **not committed**: it is the canvas editor
payload, ~2 MB regenerated on every publish. Rebuild it as described in
[`../README.md`](../README.md).
