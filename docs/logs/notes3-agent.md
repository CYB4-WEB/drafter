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
