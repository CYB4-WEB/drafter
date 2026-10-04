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
