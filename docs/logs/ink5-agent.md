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

## Progress
- Read round2b brief, DESIGN, notes / notes3 / ink4 / pages logs, all of `ink/**`.
- Interrupted once by a usage limit (WIP checkpoint by lead); resumed and finished.
- Lead addendum (user report "shapes in whiteboard not working"): the **Shape tool** now opens a shape picker (all
  library shapes + table + "Auto (draw → recognize)" last); after picking, **dragging draws the shape live** from drag
  start to drag end (S Pen button or a second finger = square / circle / 45° line), committed on lift with pen colour /
  width (one undo step); a Shape-tool or lasso **tap on a library shape selects it** with its handles. Last choice in
  `InkPrefs.shapeKind`. Coordinates are page coordinates of the page under the drag start; whiteboard growth is
  suspended while a stroke/drag is live (existing rule `growIfNeeded` ignores DRAW with points) and growth shifts
  committed strokes like any other, so it works at any zoom and after growth.
- New files: `InkNight.kt`, `InkStickers.kt`, `InkShapes.kt`, `InkRuler.kt`.
- Changed: `InkModel.kt` (`Stroke.shape`, `StickerItem`, `InkPage.stickers`), `InkRender.kt` (night mapping, straight
  shape segments, stickers in page content, Cornell dark line), `InkTiles.kt` (night flag in renders), `InkView.kt`
  (ruler, ruled strokes, drag shapes, stickers / shapes insertion, selection rotation + shape handles + stickers,
  two-page layout, night), `InkToolbar.kt` (shape picker button, ruler toggle, Insert → Stickers / Shapes),
  `InkEditorImpl.kt` (⋮ Two pages / Cover page alone / Night paper, Export as shown, trays, spread chip, ToolSync),
  `PageSource.kt` (`EditorController.nightMode`), `EditorServices.exportNoteToPdf` + `NoteExport` (`night` param),
  `InkTextOverlay.kt` (editor text colour mapped at night), `InkPrefs.kt`, `InkAssist.kt` / `PageManager.kt` (carry
  `shape` / scale stickers), strings `ink5_*` en + ar (33 keys, both files parse).

## Decisions & limits
- **Ruler**: screen-anchored (stays put while the page pans / zooms, like GoodNotes / Samsung Notes); ticks are in page
  units at the current zoom (cm + mm on the top edge, inches in 1/8 on the bottom edge, LOD thinning), so it measures
  what is drawn. Live angle pill in the middle (0–179°, blue when snapped). Finger on the body = move; two fingers
  (gesture started on the ruler) = move + rotate around the fingers; snap to every 15° (incl. 0/15/30/45/90) within 2.5°
  with `CLOCK_TICK` haptics. A finger that lands elsewhere keeps pinch-zoom (ruler hit-test happens first at
  ACTION_DOWN only). Pens never move it. Pen / highlighter strokes starting within 30 dp outside / 14 dp inside either
  long edge become a straight 2-point stroke along that edge (offset by half the pen width so the ink touches the edge),
  extent = union of where the pen went along the ruler; live preview is a plain line in the tool's colour / width / cap,
  the committed stroke uses the real pen style. Toggle in the tool row (Straighten icon); not persisted, never saved.
- **Stickers**: new `StickerItem` list on `InkPage` (`@EncodeDefault(NEVER)`: pages without stickers are written exactly
  as before; old files load). Drawn with paths + text (vector in PDF), rotation per item. Text stamps store their label
  in the language used when placed. Placed at the middle of the view on the page under it, constant on-screen size
  (scaled by zoom), floating selected (move, corner scale, new rotate handle), tool switches to Lasso like images.
  Recent row = last 8 kinds (InkPrefs). Lasso / finger tap selects a sticker; lasso polygon selects by centre; colour,
  duplicate, delete work; the eraser does not erase stickers (lasso → delete). Not placed at a tap point (centre of the
  view instead — the request allowed either).
- **Shapes**: each shape is ONE `Tool.SHAPE` stroke polyline (tables / arrow heads retrace edges, invisible for one
  stroked path) tagged with `Stroke.shape` (`@EncodeDefault(NEVER)`), so eraser, lasso, undo, tiles, thumbnails and PDF
  vectors work unchanged. SHAPE strokes are now drawn with straight segments (before, recognized rectangles had their
  corners rounded off by the quad smoothing). Handles when exactly one library shape is selected: box kinds 4 corner
  handles (opposite corner fixed, regenerated so width / corners stay crisp), line / arrow / double arrow 2 end-point
  handles; plus move + rotate. Rotating a box shape drops its tag on commit (it stays a normal shape, editable via
  lasso scale / rotate). Table: rows × cols (1–20 × 1–12); Text-tool tap inside a cell creates a text box fitted to the
  cell width (text size capped to the cell height).
- **Selection rotation**: new rotate handle above the frame, offered only when the selection has no text boxes /
  pictures / link chips (the model has no rotation for those); 15° snap with haptics.
- **Two-page view**: ⋮ "Two pages" (+ "Cover page alone"), persisted in InkPrefs, for notes, PDFs and slides (not
  whiteboards). Spreads are used only while the canvas is landscape and ≥ 600 dp wide (a toast says so otherwise);
  rotating the device switches live, keeping the page and zoom. Pages are laid out per page (`pageLefts`), so ink,
  selection, text overlay, tiles, PDF tiles and the zoom pill work unchanged; fit = the whole spread; hit-testing picks
  the nearer page of a row; current page = first page of the spread at 40 % height; chip "3–4 / 20". In an RTL (Arabic)
  UI spreads open right-to-left. No "auto" mode (opt-in only, to avoid changing existing users' layout).
- **Night**: one `InkView.night` flag. Notes: paper #1C1F24, dark-aware lines (Cornell fixed too), ink mapped
  (HSL: L < 0.6 → 0.93 − 0.55·L, hue/saturation kept, bright colours / highlighters unchanged) for strokes, dots, live
  ink, text (incl. the on-canvas editor), floating selection; stickers, tapes, link chips keep their colours. PDF / slides:
  page bitmaps + tiles drawn through `InkNight.pageFilter` (invert → 180° hue rotation → squeeze into #1C1F24…#E8EAED)
  and the same ink mapping. Toggling clears the ink tiles once (they bake the mapping). Mapping is per thread
  (`InkRender.setNight`), set only around the editor's own frame, tile renders and "as shown" exports, so thumbnails,
  pen previews and normal exports are never affected. Export submenu shows "Export as shown (night)" while night paper
  is on (PDF, PNG/JPG, share page image).

## nightMode API (for pdf3-agent)
```kotlin
// com.daftar.app.ink.EditorController (PageSource.kt) — new member with a default implementation
var nightMode: Boolean   // observable Compose state in the editor's controller; default false
```
Use it from any `InkEditorScaffold` host: e.g. in `extraActions = { ctl -> … }` or a menu item:
`ctl.nightMode = !ctl.nightMode` (and `Checkbox(checked = ctl.nightMode, …)`). Effect: page bitmaps from your
`PageSource.render` are shown inverted with hues kept (white → #1C1F24), the page fill / borders go dark, ink colours
are mapped for readability; nothing is saved into the `.ink.json`, exports are unaffected. Persisting the choice is the
host's job (InkPrefs only stores the notes' "Night paper"). No `PageSource` change needed; `refreshPages()` is not
required after toggling.
