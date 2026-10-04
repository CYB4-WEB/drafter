# notes-agent log

# Round 2

## Understanding
I own the ink stack (`ink/**`, `strings_ink.xml` en + ar): the Notability / GoodNotes / OneNote-style editor used by notes
(isNote = true) and as the annotation layer of PDF and PowerPoint (isNote = false). What I must deliver, all real and working:

1. **Infinite whiteboard** (`InkDoc.infinite`): a single page that grows in every direction when the user writes, inserts or pans
   near an edge; growing left/top shifts the content and the scroll so the view never jumps. No page border, background = paper
   colour, paper pattern drawn only over the visible area (clip rect passed to `InkRender.drawPaper`, fast on huge canvases),
   wider zoom-out, no add/delete page. `exportNoteToPdf` crops a whiteboard to content bounds + margin. Paged notes unchanged.
2. **Pen types** BALL / FOUNTAIN / PENCIL / BRUSH / MARKER rendered by `InkRender` so editor, thumbnails and every export match:
   ballpoint light pressure; fountain width from nib angle + pressure; pencil grainy + lower alpha; brush strong pressure +
   tapered ends; marker uniform, opaque, flat cap. Tapping Pen again opens a style popup (icon + name); style remembered.
3. **Size bar**: slider with live preview dot + value for pen (0.5–24), highlighter (4–48), eraser radius and tape thickness, plus
   3 presets. Last style/colour/width per tool remembered across sessions in my own `ink_prefs` SharedPreferences.
4. **Link items**: "Insert link" dialog → web/video URL (+ optional label) or a library file via `LibraryFilePickerDialog`. Drawn as a
   chip (video icon for YouTube/Vimeo/mp4, globe for web, coloured file badge for files) + label. Tap (finger in any tool, or pen
   tap without drawing) opens inside Daftar (`pane.open` / `pane.push(Screen.Web)` or ACTION_VIEW when `Prefs.linksInApp` is off).
   Long-press menu: Open, Open side by side, Edit, Delete. Lasso-selectable (move/resize/duplicate/delete), undo/redo, exported.
5. **Tape** (self-quiz): drag lays a straight opaque pastel strip; tap toggles reveal (transient); ⋮ "Reveal all / Hide all";
   exports draw tapes hidden.
6. **Laser pointer**: fading red trail, never saved, pen and finger.
7. **Zoom**: pinch (exists), buttons (`ZoomControls`), pen (Hand tool: pen drag pans, pen double-tap toggles zoom, side button +
   vertical drag zooms), mouse/trackpad (Ctrl+wheel zooms, wheel scrolls).
8. **Drop target** (round2b drag & drop contract) on `InkView` via `setOnDragListener`, at the drop position:
   `daftar:file:` image → image, other file → link; http(s) → link; other text → text box; image content URI → image.
9. **Toolbar** regrouped: tools | style + colours + size bar | insert (image, link, dictate, add page); scrolls on narrow panes,
   `Prefs.largeControls` → 48dp buttons / 26dp icons; `view.keepScreenOn = Prefs.keepScreenOn`.
10. **Note header**: `ConvertButton(rememberViewerActions(noteFile))`, ⋮ gets `ViewerMenuItems(actions, close, onShare = share-as-PDF)`.
11. **Performance**: no per-frame allocations in `onDraw`; live stroke built incrementally (no point-array copy per frame);
    per-style geometry cached on the stroke; undo snapshots stay shallow.
12. **Compatibility**: old `.note` files load (all new fields defaulted); undo/redo covers every new item type.
13. Optional ruler only after everything else.

Must not break PDF/slides (`isNote = false`): they get tape, laser, links, pen types, size bar; no page add/delete, no whiteboard.
Keep signatures: `InkDoc.newWhiteboard(paper)`, `exportNoteToPdf(doc, out, withPaper)`, `InkRender.drawPaper(c, page, dark)`,
`InkRender.drawPageContent(c, page)` (only optional params added).

## Plan
- `InkModel.kt`: `Stroke` gets a transient geometry cache (`geom`), transient `revealed` (tape), `shifted(dx, dy)` that keeps caches;
  `LinkItem` gets `h` (resizable chip) + helpers (`isVideo`, `isWeb`); `InkPage` gets `shiftX/shiftY` (cumulative whiteboard
  shift, used only to keep the view steady on undo/redo) + `shifted()` + `contentBounds()`. All defaults → old files load.
- `InkRender.kt`: `StrokeGeom` incremental builder shared by live drawing and saved strokes:
  chunked constant-width paths (ball, pencil, marker, highlighter, shape) and a filled variable-width outline made of
  consistently-oriented quads (fountain, brush). Pencil = cached tiled noise `BitmapShader` + colour filter, alpha 0.85.
  Tape geometry/drawing (hidden/revealed), link chip drawing (vector icons, cached ellipsized label), paper with clip + level of
  detail (skip lines closer than ~6px), `drawPageContent` order: images, ink, text, links, tapes.
- `InkView.kt`: infinite layout (margin 0, growth in 936-unit steps = multiple of every paper period so the pattern never jumps),
  incremental live stroke, tape / laser / link hit-testing (tap, long-press), pen zoom (side button drag), Hand-tool pen pan,
  `onGenericMotionEvent` (wheel / Ctrl+wheel), drag-and-drop listener, selection with links, preallocated draw objects.
- `InkPrefs.kt` (new): `ink_prefs` SharedPreferences (tool, pen style, colours, widths, eraser radius, tape colour/width).
- `InkToolbar.kt` (new): regrouped toolbar, pen-style popup with real rendered previews, size bar, one/two-row adaptive layout.
- `InkLinks.kt` (new): link dialog (URL or library file), link open / side-by-side helpers, long-press menu.
- `InkEditorImpl.kt`: wiring, header Convert + ViewerMenuItems, tape reveal/hide menu, whiteboard-aware menus, save keeps `infinite`.
- `EditorServices.kt`: `exportNoteToPdf` crops whiteboards to content (+ margin, ≤ 14400pt per side).
- `strings_ink.xml` en + ar for every new text.

## Progress
- Read round2b / round2 briefs, AGENT_RULES, DESIGN, SPEC R2.x, all of `ink/**`, Nav, ViewerActions, FilePicker, Common
  (ZoomControls), Workspace, Prefs, and how PdfScreen / SlidesScreen / PdfTools use the scaffold and `drawPageContent`.
- Baseline compile: only errors were in the lead's `Screens.kt` (missing strings) — nothing in `ink/`.
- `InkModel.kt`: `Stroke.geom` (render cache), `Stroke.revealed` (tape, transient), `shifted()`, `withTime()` (keeps pen style —
  the old `stampTime` silently dropped it); `LinkItem.h` + `defaultLabel` / `normalizeUrl` / `isVideoUrl` + render caches;
  `InkPage.shiftX/shiftY`, `shifted()`, `contentBounds()`; `InkDoc.exportRect(i)`; `newWhiteboard` signature kept (default board
  2340×1638 = multiples of 234).
- `InkRender.kt`: new `StrokeGeom` builder (shared by live and saved strokes), five pen styles, pencil grain shader, tape,
  link chips, paper with clip + level of detail, tapes-on-top content order; Tehreer font key (lead request).
- `InkView.kt`: rewritten around the new pieces — infinite board growth / trim / undo-steady scroll, incremental live stroke,
  tape + laser + link tap / long-press, pen zoom, mouse wheel, drag-and-drop, links in lasso selection, no allocation in onDraw.
- New `InkPrefs.kt`, `InkToolbar.kt`, `InkLinks.kt`; `InkEditorImpl.kt` rewired (header Convert + ViewerMenuItems, link
  dialog/menu, tapes menu, whiteboard-aware menus, saving keeps `infinite`, keepScreenOn); `EditorServices.kt` whiteboard crop.
- Strings: 28 new keys in `strings_ink.xml` en + ar (both parse as XML).
- Compile: `ink/` clean from the first full pass (other agents' errors only); after they settled the whole app: **BUILD OK**.

## Decisions & limits
- **Whiteboard growth**: the board is kept at least half a screen larger than the viewport in every direction (checked after pan,
  zoom, fling end, resize, every edit). Growth step is a multiple of 234 pt (LCM of the 26 pt lined and 18 pt grid/dot periods)
  so the paper pattern never jumps when content shifts. Growing left/top really shifts the content (as the brief asks), carrying
  the cached geometry (offset copies, no rebuild), a floating selection and the laser trail; the scroll moves by the same amount.
  Each page snapshot records its cumulative shift (`shiftX/shiftY`), so undo/redo across a growth keeps the view steady.
  On open, empty space far from the content (left over from panning) is trimmed (shift in 234 pt steps). Max board 400 000 pt.
- **Whiteboard zoom**: 100% = 2 dp per point (physical size, so growth never changes the zoom %); range 10%–800% (paged 25%–800%).
  "Fit" on a whiteboard fits all content (capped at 200%); double-tap toggles 100% ↔ 2×.
- **Pen styles**: ballpoint/marker/highlighter/shape = smoothed constant-width chunks; fountain/pencil/brush = one filled outline
  of clockwise quads (non-zero winding → no seams, no double-darkening of the 85%-alpha pencil). Fountain = broad nib at 45°
  (`0.22 + 0.95·|sin(dir − nib)|`) × pressure; brush = `0.12 + 1.5·p^1.3`, tapered over 2.5× width at both ends (the end
  taper is applied when the stroke is committed — live you see the start taper); pencil = cached 64×64 noise `BitmapShader`
  tinted with a `PorterDuffColorFilter(SRC_IN)` + 85% alpha; marker = forced opaque, BUTT cap. Width is remembered per pen style.
- **Tapes** are strokes (`Tool.TAPE`, 2 points, width = thickness) so lasso, eraser, undo and every export handle them for
  free. They are always drawn last (on top) and exports/thumbnails always draw them hidden. Reveal state is transient.
  A pen tap on a tape (or link) with a drawing tool reveals/opens instead of leaving a dot.
- **Links**: chips are vector-drawn in `InkRender` (white pill, 1pt border, red play badge / blue globe / file badge in the
  design-sheet type colours with PDF/PPT/DOC/TXT/NOTE tag) so they look the same in exports. Inserted links float selected
  (like images) so they can be placed; dropped links are placed directly at the drop point. Long-press (any tool, any pointer)
  opens Open / Open side by side / Edit / Delete. A link to a deleted file shows a toast instead of failing.
- **Laser**: ring buffer (256 points, doc coordinates, 650 ms life), glow + core passes; works with a finger even in pen-only
  mode (two fingers still zoom).
- **Pen zoom**: Hand tool → pen drag pans, pen double-tap toggles zoom, S Pen side button + vertical drag zooms around the
  press point. Mouse: wheel scrolls, Shift+wheel scrolls sideways, Ctrl+wheel zooms at the pointer.
- **Toolbar**: one row ≥ 1200 dp, otherwise two rows (tools + insert / style + colours + size bar), each scrolls horizontally,
  so it stays usable in a narrow split pane; tools without settings (lasso, hand, laser) show a one-line hint in the style row.
  Pen-type popup opens from a second tap on Pen or from the style chip and shows a real rendered sample per style.
- **Performance**: onDraw uses index loops, preallocated rects/paths, cached stroke geometry, cached link labels/badges and a
  thread-local dot buffer; the live stroke is built incrementally (old code copied the whole point array and rebuilt every path
  each frame). Lasso path is extended incrementally too. Undo snapshots stay shallow page-list copies.
- **Ruler (optional item 13): not implemented.** Everything else took priority; a ruler needs a second two-finger gesture mode
  that conflicts with pinch-zoom and I could not verify it on a device here. Left for a later round.
- No device/emulator here: behaviour is verified by reasoning and compilation only.

## Requests to lead
1. **convert-agent (note → images)**: crop whiteboards like `exportNoteToPdf` does, otherwise a large board renders at full
   board size. Snippet per page `i`:
   ```kotlin
   val r = doc.exportRect(i)                       // whole page for paged notes, content + margin for a whiteboard
   val bmp = Bitmap.createBitmap((r.width() * s).toInt(), (r.height() * s).toInt(), Bitmap.Config.ARGB_8888)
   val c = Canvas(bmp); c.drawColor(doc.paperColor); c.scale(s, s); c.translate(-r.left, -r.top)
   InkRender.drawPaper(c, page, clip = r, bounded = !doc.infinite); InkRender.drawPageContent(c, page)
   ```
   (existing `drawPaper(c, page)` / `drawPageContent(c, page)` calls still work unchanged.)
2. **workspace-agent**: `InkDoc.newWhiteboard(paper)` signature unchanged (default board is now 2340×1638). Drag sources
   following the round2b contract (`daftar:file:<path>`, URL, text, image content URI) are accepted by notes/PDF/slides canvases.
3. Nothing needed in shared files.

## Self-check
1. Infinite whiteboard — **PASS**: grows in every direction on write/insert/pan/zoom, left/top growth shifts content + scroll
   (no jump, undo-steady), no page border, background = paper colour, paper drawn only over the visible area with level of
   detail, 10% min zoom, page add/delete hidden, PDF export cropped to content + 36 pt margin; paged notes unchanged.
2. Pen types — **PASS**: ballpoint / fountain / pencil / brush / marker rendered by `InkRender` (editor, thumbnails, PdfTools
   overlays and note → PDF all use it); style popup with icon, name and live sample; style remembered.
3. Size bar — **PASS**: slider + live preview + value for pen 0.5–24, highlighter 4–48, eraser 4–48, tape 8–80, 3 presets each;
   tool, style, colours, widths (per pen style) remembered in `ink_prefs`.
4. Link items — **PASS**: URL (optional label) or library file via `LibraryFilePickerDialog`; video / globe / coloured file badge;
   tap opens inside Daftar (`pane.open`, `pane.push(Screen.Web)` or ACTION_VIEW when links-in-app is off); long-press Open /
   side by side / Edit / Delete; lasso move/resize/duplicate/delete; undo/redo; drawn in exports.
5. Tape — **PASS**: straight pastel strip, tap toggles reveal, ⋮ Reveal all / Hide all, exports always hidden.
6. Laser — **PASS**: fading red trail, never saved, pen and finger.
7. Zoom — **PASS**: pinch, `ZoomControls`, pen (Hand-tool pan, double-tap, side-button drag), Ctrl+wheel / wheel scroll.
8. Drop target — **PASS** (logic; not testable here): `daftar:file:` image → image, other file → link, http(s) → link, text →
   text box, image content URI → image (with drag-and-drop permissions), at the drop position, undoable.
9. Toolbar — **PASS**: tools | style + colours + size bar | insert (image, link, dictate, add page); one or two scrolling rows;
   large controls 48 dp / 26 dp; `keepScreenOn` follows `Prefs.keepScreenOn`.
10. Note header — **PASS**: `ConvertButton(rememberViewerActions(noteFile))`; ⋮ has `ViewerMenuItems` with share-as-PDF; export,
    record, paper, tapes etc. kept.
11. Performance — **PASS**: incremental live stroke, cached per-style geometry, no allocation in onDraw paths, shallow undo.
12. Backward compatibility — **PASS**: every new field has a default (`style`, `links`, `h`, `shiftX/Y`, `infinite`); unknown keys
    ignored; undo/redo covers links, tapes, images, text, strokes and whiteboard growth.
13. Ruler — **FAIL (optional, not attempted)**: see Decisions & limits.
- PDF / slides (`isNote = false`) — **PASS**: tape, laser, links, pen types and size bar available; no page add/delete, no
  whiteboard; `InkEditorScaffold`, `EditorController`, `drawPaper`, `drawPageContent`, `exportNoteToPdf` signatures kept.
- Lead extra: Tehreer font key "tehreer" ("Tehreer تحرير") in `InkRender.fonts`, typeface loading and the text-box font picker — **PASS**.
