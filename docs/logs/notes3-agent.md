# notes3-agent — Round 2 (continued ink editor)

## Understanding
I own `ink/**` and `res/values*/strings_ink.xml`. Continuing from notes-agent's round 2 (whiteboard, pen types, links,
tape, laser, zoom, drop target). New user requests:
1. **Text directly on the paper (WYSIWYG)**: Text tool tap creates a box right there and you type on the canvas in the
   box's real font/size/colour (RTL included); tap an existing box to edit in place; commit with undo when editing ends
   (tap elsewhere, Done, back, tool change). Selected boxes get a frame with handles (move body, corner = proportional
   font scale, sides = wrap width, two-finger pinch = scale) and a floating format bar (font, size −/+, bold, colour,
   alignment, delete, duplicate). Old dialog kept only as "Edit text…" in the long-press menu. Keyboard must not cover
   the caret. Works in notes, whiteboards, PDFs and slides.
2. **Export** submenu for notes: PDF, PNG/JPG (current/all pages, 2×, whiteboards cropped with `exportRect`),
   share page as image, Word .docx (typed + recognized handwriting, `DocxExport.writeDocx`, `\u000C` page breaks), plain
   text; next to the note via `Storage.uniqueFile`, with Open / Share.
3. **Print** (all / current / range) through a temp PDF and `PdfTools.print(activity, pdf, name)`.
4. **Smoothness**: page ink cached as bitmap layers, cheap live-stroke frames, Compose state reads isolated, saving off
   the main thread from an immutable snapshot with debounce.
5. Keep every public signature and old `.note` files working; keep the lead's `isPen()` mouse rule and
   `penOnly = Prefs.penOnly && Prefs.stylusSeen`.

## Plan
- `InkModel.kt`: `TextItem.align` (0 start / 1 centre / 2 end, default 0 → old files load). `InkDoc.save` streams JSON.
- `InkRender.kt`: text layout honours `align`.
- New `InkTextOverlay.kt`: a native `EditText` overlay (same Typeface / px size / width / no font padding / simple
  break strategy / first-strong-LTR direction as the `StaticLayout` used on the canvas) living in a `FrameLayout` with
  the `InkView`; InkView positions it every frame (translation only on scroll, size on zoom) — no Compose recomposition.
  Format bar + text long-press menu composables.
- `InkView.kt`: text box selection/editing state, handle hit-testing (4 corners scale, 2 sides width, ring = move),
  pinch on a selected box, long-press menu for text (any tool), Text tool tap / drag-to-size creation, keyboard-safe
  scrolling (`ensureCaretVisible`), whiteboard growth shifts the edited box. **Ink layer cache**: per visible page a
  bitmap of the committed strokes for the visible area + overscan at the current scale, rendered on a background
  thread from an immutable stroke list; onDraw blits it pixel-aligned and draws only strokes appended since (prefix
  check) as vectors; vectors while the cache is stale or while a pinch exposes uncovered area; bounded by
  `maxMemory/8`, recycled on release.
- `InkEditorImpl.kt`: host FrameLayout, text UI state, BackHandler, format bar, Export submenu, Print dialog, export
  result bar (Open / Share), busy bar with cancel; `ZoomPill` / `PageChip` as small composables; saving via a
  serialized background saver with change counter (no redundant writes, no main-thread encoding on back/pause).
- New `NoteExport.kt`: page → bitmap, images, docx/txt paragraphs (typed text + recognized lines merged by y), print.
- `strings_ink.xml` en + ar for every new text.

## Progress
- Read round2b brief, DESIGN, notes-agent round 2 log, all of `ink/**`, `DocxExport.writeDocx`, `PdfTools.print`,
  `Storage.uniqueFile/cacheDir`, `shareFiles`, FileProvider paths, build.gradle (no `androidx.graphics:graphics-core`).
- `InkModel.kt`: `TextItem.align` (default start → old files load); `InkDoc.save` streams JSON (`encodeToStream`) through a
  64 KB buffer into the temp file (no full-document String).
- `InkRender.kt`: `alignmentOf()`; text layout honours `align`.
- New `InkTextOverlay.kt`: `TextOverlay` (native EditText editor), `TextFormatBar`, `TextBoxMenu`, `TextBoxUi`.
- `InkView.kt`: text selection / editing / handles / pinch / long-press menu / Text-tool tap & drag creation / caret
  visibility / whiteboard-growth shift; ink layer cache (`drawStrokesCached`, `updateLayers`, background `ink-layer`
  thread, budget, prefix tail); new listener callbacks `onTextBox`, `onTextMenu` (defaulted, source compatible).
- New `NoteExport.kt`: page → bitmap (2×, whiteboard crop, memory-capped), images, text/handwriting extraction,
  docx paragraphs, plain text, `PrintDialog`, `ExportResultBar`, `Context.findActivityOrNull()`.
- `InkEditorImpl.kt`: host FrameLayout (canvas + editor), format bar, text menu, BackHandler, Export submenu (PDF,
  PNG/JPG current/all, share page as image, Word, text), Print (dialog for paged notes, direct for whiteboard / 1 page),
  progress bar with Cancel, Open/Share result bar, `ZoomPill` / `PageChip`, `InkSaver` background saver.
  Lead request applied: `ViewerMenuItems(..., showPrint = false)` in the note editor (our own Print has page ranges).
- `InkPrefs.kt`: last text font / size / bold.
- Strings: 34 new keys, en + ar (both parse).
- Compile: `tools/compile.sh` → **BUILD OK** (whole app).

## Decisions & limits
- **WYSIWYG editor = native EditText, not BasicTextField.** It is laid out by the same Android text stack as the
  `StaticLayout` that draws committed text (same Typeface object, px size = points × zoom, width = box width × zoom,
  `includeFontPadding=false`, `BREAK_STRATEGY_SIMPLE`, no hyphenation, no fallback line spacing, `FIRST_STRONG_LTR`
  direction, gravity START/CENTER/END = ALIGN_NORMAL/CENTER/OPPOSITE), so Arabic/RTL shapes and aligns identically.
  I size the editor in screen px rather than scaling the view so the caret, selection handles and IME cursor anchors
  stay correct (TextView handles ignore view scale); possible wrap differences at a box edge are sub-pixel rounding only.
  It lives in a FrameLayout next to InkView; InkView sets its translation in onDraw (property change only, same frame,
  no layout while scrolling) → no Compose recomposition while typing/scrolling. `IME_FLAG_NO_EXTRACT_UI` keeps the page
  visible in landscape. The edited box is hidden on the canvas while the editor shows it.
- Boxes have no rotation in the model, so "rotated to match" is a no-op (nothing is ever rotated).
- **Interaction**: Text tool tap = new box at the tap (in an Arabic UI the box's end edge sits at the tap), tap on a
  box = edit in place, Text-tool drag = box with that width. Lasso tap or finger tap (any tool) = select; tap a selected
  box = edit; long-press with any tool = select + menu (Edit text, Edit text in a window… = old dialog, Duplicate,
  Delete). Frame: 4 corner handles scale font + width proportionally (opposite corner fixed), 2 side bars change wrap
  width, body / frame ring moves (while typing, touches inside the box belong to the editor, the ring moves it), second
  finger = pinch scale around the centre. One undo step per gesture; typing commits as one undo step (unchanged text
  = no step; blank = box removed). Editing ends on tap elsewhere (that tap doesn't create a new box, but a pen stroke
  starting there draws), Done, back (BackHandler), tool change, undo/redo, page add/delete/clear, pause, leaving.
  Format bar sits top-centre of the canvas (stays visible above the keyboard); size steps 6…200 (±); formatting is
  remembered for new boxes (`ink_prefs`).
- **Keyboard**: the editor root has `imePadding`, so InkView shrinks; `onSizeChanged` and every text/caret change call
  `ensureCaretVisible()`, which scrolls the canvas so the caret line stays 24 dp above the bottom (and the box inside
  horizontally when it fits).
- **Export**: files go next to the note via `Storage.uniqueFile`; result bar (8 s) "Saved to …" with Open (in-app
  viewer via `pane.open`) and Share instead of a separate toast (a toast would cover the bar). Images at 2× (A4 ≈
  1190×1684), whiteboards cropped with `exportRect`, capped at min(40 MP, heap/16) and 16000 px per side. Multi-page
  images are "Name - N.png"; a cancelled/failed run deletes the partial set. Word/text: typed boxes + handwriting lines
  (ML Kit, `Prefs.inkLang`, model downloaded if needed) merged top-to-bottom per page, `\u000C` between pages; if the
  model is unavailable the export still succeeds with typed text and a toast says handwriting was left out. All work
  runs on IO with a progress bar + Cancel, from an immutable snapshot.
- **Print**: whiteboard / one page prints directly; otherwise a dialog (all / current / range). Temp PDF in cache via
  `exportNoteToPdf` of a sub-document, then `PdfTools.print(activity, pdf, title)` with the Activity unwrapped from
  the ContextWrapper chain.
- **Performance — what changed and why** (no device: reasoning from the rendering pipeline):
  1. *Ink layer cache.* Before, every frame (each pen move, each scroll/fling frame) recorded one `drawPath` per stroke
     on the UI thread and the render thread re-rasterised all of them: cost O(strokes) per frame → lag on full pages.
     Now each visible page has a bitmap of its committed strokes for the visible area + overscan (½ screen above/below,
     ¼ left/right), rendered at the current zoom on a background thread from the page's immutable stroke list. A frame
     blits it 1:1 on whole pixels (crisp) and draws as vectors only strokes appended since (identity prefix check), the
     live stroke and tapes (reveal state). New strokes are folded in once the pen rests (600 ms, skipped while drawing).
     Erase / undo / lasso → vectors until the background re-render lands (~one frame of work off-thread). During a
     pinch or right after a zoom step, a layer that still covers the view is drawn scaled until the sharp one arrives;
     uncovered → vectors. Memory: ≤ min(2 screens, heap/8 ÷ 2) pixels per layer, only visible pages, recycled on
     page leave / release / document change. Ghost replay (audio sync) bypasses the cache.
  2. *Live stroke.* `androidx.graphics:graphics-core` is not on the classpath (no new deps allowed), so no front-buffer
     rendering. `invalidate(Rect)` is ignored by the hardware renderer since API 28 (the whole display list is
     re-recorded anyway), so dirty-rect invalidation would not help; the win is that a pen frame is now paper + one
     bitmap + a few vectors + the incremental live path, independent of how much ink is on the page. Unbuffered
     dispatch + historical points were already in place.
  3. *Compose.* `zoomPercent` and `currentPage` were read in the body of `InkEditorImpl`, so every pinch frame / page
     change recomposed the whole editor; now only `ZoomPill` / `PageChip` read them. Save bookkeeping moved from
     `mutableStateOf` to a plain holder. Text box moves don't notify Compose (only select/format changes do).
  4. *Saving.* Snapshot (O(1), immutable lists) on main; encoding + write on a single `ink-save` thread, coalesced per
     file (only the newest snapshot is written), debounced 800 ms, skipped when nothing changed (change counter). Back,
     pause, dispose and link open no longer encode JSON on the main thread; only rename and `EditorController.saveNow()`
     wait (bounded 4 s) because callers need the file on disk. JSON is streamed (no full String).
- Compatibility: `exportNoteToPdf`, `InkRender.drawPaper/drawPageContent`, `InkDoc.newWhiteboard`, `InkEditorScaffold`
  params (incl. `bottomPanelOpen`, `onPageChipClick`), `EditorController` (refreshPages, goToPage(i,y), addImage(b,w),
  saveNow) unchanged; `isPen()` mouse rule and `penOnly = Prefs.penOnly && Prefs.stylusSeen` untouched.

## Requests to lead
1. None required. FYI: `EditorController.saveNow()` stays synchronous (waits for the background writer, ≤ 4 s).
2. Please verify on the Tab: (a) typing Arabic in a box at 200–400% zoom matches the committed text; (b) keyboard
   opening in landscape keeps the caret visible; (c) scrolling a page with ~2000 strokes is now smooth.

## Self-check
1. WYSIWYG text — **PASS** (logic, compiled; not device-tested): tap-to-create, edit in place on the canvas in the
   box's font/size/colour/alignment (RTL), commit with undo on tap elsewhere / Done / back / tool change; frame with
   move, corner scale, side width, pinch; format bar (font incl. Tehreer/Cairo/Amiri/handwriting, size −/+ with value,
   bold, colour + more colours, alignment, duplicate, delete, done); dialog kept as "Edit text in a window…"; works in
   notes, whiteboards, PDFs, slides (same InkView); caret kept above the keyboard. Rotation: n/a (model has none).
2. Export — **PASS**: PDF, PNG/JPG current or all pages at 2× (whiteboard cropped), share page as image, Word .docx
   (typed + handwriting, page breaks), plain text; next to the note, Open/Share bar; notes only.
3. Print — **PASS**: all / current / range (whiteboard = one page), temp PDF + `PdfTools.print` with the Activity.
4. Smoothness — **PARTIAL**: bitmap ink layers, cheaper pen frames, isolated Compose reads, off-thread coalesced saves
   done; front-buffered low-latency rendering not possible without a new dependency; no device measurement here.
5. Compatibility — **PASS**: signatures kept, old `.note` files load (`align` defaulted), lead's pen rules kept.
