# ink5-agent — Round 2 v3 (ruler, stickers, shapes, two-page view, night paper)

## Understanding
I own `ink/**` (except transcript-agent's Recorder/transcript code in `EditorServices.kt`, `ink/Transcript*.kt` and its
hook in the recording bar of `InkEditorImpl`) and `strings_ink.xml` keys `ink5_`. User requests (v3):
1. **Ruler**: toolbar toggle; semi-transparent straightedge with cm / inch ticks and the live angle; one finger on it
   moves it, two fingers on it rotate it (gesture decided by hit-testing the ruler body first; two fingers elsewhere still
   zoom); pen / highlighter strokes that start near either long edge become a perfectly straight line along that edge;
   angle snap 0/15/30/45/90° with a haptic tick; notes, whiteboards, PDFs, slides; survives zoom/pan; never saved.
2. **Stickers**: Insert → Stickers tray (✓ ✗ ★ ! ?, text stamps Important / Exam / Review / Done / Question in en/ar,
   arrows → ← ↑ ↓ ↗ curved, numbered circles 1–9; flat, Daftar colours, no emoji); recently used row; a stamp is placed
   floating selected (move / resize / rotate); crisp at any zoom and in PDF export.
3. **Shapes**: Insert → Shapes (rectangle, rounded rectangle, ellipse, triangle, line, arrow, double arrow, star,
   speech bubble, table rows × cols whose cells take text boxes); pen colour / width; editable after placing (lasso;
   handles for rectangle / ellipse / arrow); vector in PDF.
4. **Two-page view** (notes + PDF/slides): book spreads in landscape on wide windows, optional cover page alone, ⋮ toggle
   persisted in InkPrefs; scrolling by spreads; ink, selection, text overlay, tiles, zoom pill keep working; chip "3–4 / 20".
5. **Night paper**: ⋮ toggle for notes (paper #1C1F24, dimmed lines, on-screen ink mapping, saved colours untouched,
   exports unaffected unless "export as shown"); `EditorController.nightMode` for PDF / slides (hue-preserving inverted
   page bitmaps + same ink mapping) for pdf3-agent.
Keep: public signatures, `.note` compatibility, `isPen()` mouse rule, `penOnly` rule; no per-frame allocation.

## Plan
- **Model** (`InkModel.kt`): `Stroke.shape: String = ""` (shape kind tag, `@EncodeDefault(NEVER)` → old files and
  plain strokes unchanged on disk) carried by `mapped/shifted/withColor/withTime`; new `StickerItem` +
  `InkPage.stickers` (default empty, never encoded when empty); `isEmpty/shifted/contentBounds` include stickers.
- **New `InkShapes.kt`**: shape generator (polyline `Tool.SHAPE` strokes; tables / arrows as one retraced polyline),
  box / endpoint parameters for handles, table cell lookup, the Shapes tray (Compose previews).
- **New `InkStickers.kt`**: sticker catalogue + vector drawing (Path + text, thread-safe paints), Stickers tray with a
  recently-used row (InkPrefs).
- **New `InkRuler.kt`**: ruler state, hit-testing, snapping projection, drawing (preallocated, cached label strings).
- **New `InkNight.kt`**: on-screen ink colour mapping (HSL: dark → light, bright kept), hue-preserving inversion
  `ColorMatrix` for page bitmaps, constants.
- **`InkRender.kt`**: SHAPE strokes drawn with straight segments (sharp corners); per-thread night ink mapping applied to
  stroke / dot / live / text colours; stickers in `drawPageContent`.
- **`InkView.kt`**: ruler gestures + snapped strokes; stickers / shapes insertion floating selected; `Sel` gains stickers
  and rotation (rotate handle when nothing unrotatable is selected) + shape handles (box corners / arrow ends);
  two-page layout (per-page left/top arrays, spreads, x-aware hit test, current page = page at view centre, spread end);
  night mode (paper, borders, page bitmap filter, tile re-render).
- **`InkTiles.kt`**: renders with the night flag; cleared on toggle.
- **`InkToolbar.kt`**: ruler toggle button; Insert → Stickers / Shapes buttons.
- **`InkEditorImpl.kt`** / **`PageSource.kt`**: ⋮ Two pages / Cover page alone / Night paper; "Export as shown" in
  Export; `EditorController.nightMode` (default-implemented interface property, so other implementers still compile)
  + `spreadEnd`-aware page chip; tray dialogs.
- **`EditorServices.exportNoteToPdf` / `NoteExport.renderPage`**: trailing `night: Boolean = false` param.
- `PageManager.PageOps.scaled` scales stickers.
