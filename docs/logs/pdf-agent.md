# pdf-agent log

## Understanding
I build the PDF part of Daftar: a viewer where the student reads a lecture PDF and writes on it with the shared ink editor,
plus the classic PDF toolbox. The drawing/editor UI belongs to the lead (`InkEditorScaffold`); I provide the page source,
the screen wiring, the thumbnails side panel, the top-bar actions and all PDF processing (`PdfTools`).

Pieces:
- `PdfSource : PageSource` on `android.graphics.pdf.PdfRenderer`: page sizes (points) cached at open, `render(i, dest, m)` honours
  the matrix (so zoomed tiles are sharp), synchronized (one open page at a time). Corrupt/password PDFs throw at construction.
- `PdfScreen(path)`: opens the source off-thread with a loading state; failure → error state with "Open in another app".
  Hosts `InkEditorScaffold` with ink sidecar `Storage.sidecar(file, "ink.json")`, thumbnails side panel, Go-to-page and a Tools menu:
  export annotated, share, print (all/current/range), copy text (current/all), extract pages (ranges + include annotations),
  pages → images (all/current/range, PNG/JPEG, progress + cancel), open in another app. Long ops → progress dialog on IO; errors → toast + log.
- `PdfTools`: `imagesToPdf` (contract kept; sampling ≤2480px, orientation from MediaStore column, A4-width pages),
  `exportAnnotated` (vector original kept; ink rasterized at 3× per non-empty page via `InkRender.drawPageContent`, drawn over crop box honouring rotation),
  `extractText`, `extractPages`, `pagesToImages`, `print`, range parsing.

Acceptance criteria (restated):
1. Fast open / smooth scroll / sharp zoom: PdfSource must honour the matrix and keep per-call overhead small.
2. Annotated export readable elsewhere, text still selectable, ink exactly placed even on rotated pages.
3. Print all / current / range via Android print dialog.
4. Copy text works (incl. Arabic); scanned page → friendly "no selectable text" message.
5. Extract pages: correct new PDF, chosen pages in the chosen order; invalid range → inline error in the dialog.
6. Pages→images: files named `Page 01.png` etc. in `<name> images/`; Images→PDF: one page per image, orientation corrected.
7. Thumbnails panel + go-to-page work; corrupt/password PDFs show an error state, never crash.
8. All strings in en + ar resources; code compiles.

## Plan
Files (all owned):
- `pdf/PdfSource.kt` — PageSource on PdfRenderer (+ `PdfSource.open(file)` that throws on bad files).
- `pdf/PdfTools.kt` — all processing functions (object, plain functions usable from any screen; callers run them off-main).
- `pdf/PdfScreen.kt` — screen, thumbnails panel (LRU bitmap cache, rendered on IO from a second PdfRenderer instance so it never blocks the editor's renderer), top-bar actions.
- `pdf/PdfDialogs.kt` — go-to-page, print, copy-text, extract, images dialogs and progress dialog.
- `res/values/strings_pdf.xml`, `res/values-ar/strings_pdf.xml`.

Approach notes:
- Rotation: PdfRenderer already reports displayed (rotated) size, which matches ink coordinates. For export, the ink bitmap is in displayed
  orientation; map the unit square onto the crop box with the brief's matrices per `page.rotation`.
- Annotated export when ink is empty for every page → just copy the file (keeps it byte-identical).
- Extract with annotations: export annotated to a temp file first, then extract from it.
- Print: `PrintDocumentAdapter` streaming a prepared PDF file (annotated full doc, or extracted single page/range).

## Progress
- 2026-10-04 — Read AGENT_RULES, DESIGN, SPEC, brief and shared code (PageSource, InkEditor, InkModel, InkRender, Common, Nav, Theme, Storage).
- 2026-10-04 — `PdfSource.kt`: PdfRenderer-backed PageSource; sizes cached at open; `render` honours the Matrix, synchronized, safe after close; `renderFit` helper for thumbnails/images.
- 2026-10-04 — `PdfTools.kt`: imagesToPdf (contract kept), decodeImage (sampling + MediaStore orientation), hasInk, exportAnnotated (rotation-aware overlay on crop box), extractText, parseRanges/rangeLabel/toAsciiDigits, extractPages, pagesToImages (cancellable, progress), print (PrintDocumentAdapter streaming a file).
- 2026-10-04 — `PdfDialogs.kt`: Go to page, Print (All/Current/Range), Copy text (This page/All pages, SelectionContainer, Copy all), Extract (inline-validated ranges + include annotations), Pages→images (scope, PNG/JPEG, include annotations), BusyDialog (appears after 300 ms, determinate progress, cancel).
- 2026-10-04 — `PdfScreen.kt`: loading / error (password vs corrupt, "Open in another app") / editor states; InkEditorScaffold wiring; thumbnails side panel (separate renderer, 24 MB LRU, auto-scroll to current page, tap → goToPage); top bar "x / y" (tap → go to page) + Tools menu.
- 2026-10-04 — strings_pdf.xml en + ar.
- 2026-10-04 — Compiled: zero errors/warnings in `pdf/` (verified the compiler reaches the package by planting and removing a deliberate error). Remaining errors are in other agents' files (ink/EditorServices.kt ML Kit imports, planner/Format.kt, planner/Notify.kt missing strings).

## Decisions & limits
- Annotated export rasterizes only the ink (3×, long side capped at 4096 px) and keeps all original vector content, so text stays selectable. Overlay maps the unit square to the crop box with the brief's matrices for /Rotate 0/90/180/270; ink space = PdfRenderer displayed size, which pdfium also derives from the crop box. If no page has ink, the export is a byte copy.
- Encrypted-but-openable PDFs (owner password only): security is removed on export/extract so PDFBox can save. User-password PDFs → error state (PdfRenderer throws SecurityException).
- Extract keeps the user's order exactly, allows descending ranges ("5-3") and accepts Arabic-Indic digits and the Arabic comma.
- Pages→images: 150 dpi (2.08×), ink included optionally (default on when there is ink). Images go to `<name> images/` next to the PDF; existing same-named pages in that folder are overwritten (re-running converts again rather than duplicating). File/folder names come from string resources (`Page 01.png` in English).
- Print current/range: builds a temporary PDF (annotated if there is ink) of just those pages and prints it, so the print preview is exact. Temp files live in `cacheDir()/pdfwork-*` and are cleaned after 6 h.
- Share: annotated copy (in cache) when there is ink, otherwise the original.
- Thumbnails show the PDF page only (no ink) so the cache never has to be invalidated while drawing.
- Copy text: display capped at 100 000 chars (huge books stay responsive); "Copy all" copies everything.

## Requests to lead
1. `InkRender` uses shared `Paint` objects (`strokePaint`, `bmpPaint`) and caches `Path`/`StaticLayout` on items. My exports call `InkRender.drawPageContent` on `Dispatchers.IO` while the editor may draw on the UI thread, so a stroke could rarely be exported with another stroke's colour/width. Suggested fix: make `drawStroke` use a local paint copy for off-screen work, e.g.
   ```kotlin
   private val strokePaintTl = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND } }
   // in drawStroke: val p = strokePaintTl.get()
   ```
2. Please confirm `EditorController.doc()` may be called from the main thread at any time and returns the latest in-memory doc (I call `saveNow()` then snapshot `doc().pages` before each export).
3. Whether the scaffold calls `source.close()` itself or not: both are fine — `PdfSource.close()` is idempotent and `render` is a no-op after close.

## Self-check
1. Fast open / smooth scroll / sharp zoom — PASS (code-level): sizes cached once, render honours the Matrix per call, one page opened per call. Real scrolling depends on the lead's scaffold (stub at time of writing).
2. Annotated export keeps text selectable, ink exact incl. rotated pages — PASS (code-level): original content untouched, overlay on crop box with rotation matrices. Not verified on device (no emulator per rules).
3. Print all / current / range — PASS: PrintManager + file-streaming PrintDocumentAdapter, using the Activity context.
4. Copy text incl. Arabic; scanned → friendly message — PASS: PDFTextStripper off-thread; blank result → "No selectable text (scanned page)". Arabic quality depends on PDFBox's text extraction of the file's fonts.
5. Extract pages correct order; invalid ranges inline error — PASS.
6. Pages→images naming; Images→PDF one page per image with orientation — PASS (orientation applies when the provider exposes the MediaStore ORIENTATION column; EXIF-only orientation from other providers cannot be read without exifinterface).
7. Thumbnails, go-to-page, corrupt/password error state — PASS.
8. Strings en + ar; compiles — PASS (zero errors in pdf/; other packages currently have errors not owned by me).

# Round 2

## Understanding
Turn the PDF viewer into an Acrobat/Xodo-style reader in Daftar's design, on top of the shared ink scaffold:
- **Header**: no duplicate "x / y" text (the editor's page chip stays); Search button, `ConvertButton(rememberViewerActions(file))`,
  a distinct Tools icon (HomeRepairService) whose menu holds go-to-page, the PDF tools, page management and
  `ViewerMenuItems(actions, close, onShare = annotated share)`. Zoom stays the scaffold's pill.
- **Search**: PDFTextStripper per page on IO, cancellable, progressive results (page + snippet with the match bold), tap → `ctl.goToPage`,
  next/previous; Arabic-aware matching (أإآ→ا, ى→ي, ة→ه, no tashkeel/tatweel) + case-insensitive Latin; match highlights on the page if positions can be mapped.
- **Outline**: PDDocumentOutline as an indented tree in a tab next to Thumbnails; tap → page; "No outline" empty state.
- **Page management**, each writing a new version safely (temp → validate → replace, reload viewer) and remapping the ink sidecar:
  insert blank page after current, delete page(s) with confirm, rotate left/right (ink rotated with the page), move up/down + an organize (reorder) dialog,
  extract (exists), merge with library PDFs (new file), **Sign** (draw once → transparent PNG in filesDir → `ctl.addImage`, re-draw/clear).
- **Fix**: `imagesToPdf` honours EXIF orientation (platform `android.media.ExifInterface(InputStream)`).
- **Thumbnails** show the user's ink; caches bounded by bytes (≤ 1/8 maxMemory in total), released on dispose.
- Works in a split pane / narrow window (side panel → scaffold's bottom sheet; my own overlays measure their real width).

Acceptance (my reading): header as above; search finds Latin case-insensitively and Arabic regardless of hamza/tashkeel forms, results list + next/prev
navigate; outline tree navigates; every page operation leaves a valid PDF and ink that stays on the right page (and rotates with it); merge creates a
new file; signature can be drawn, reused, redrawn, cleared; EXIF-only rotated photos come out upright; thumbnails show ink; no unbounded caches; en + ar strings; compiles.

## Plan
- `pdf/PdfSearch.kt` — text index per page (custom PDFTextStripper: visual-order glyphs per line → line-level bidi reorder → display text,
  folded search text, glyph rectangles in displayed page points), Arabic/Latin folding, progressive cancellable search, bounded index cache.
- `pdf/PdfSearchUi.kt` — search bar that covers the editor header while searching (native-reader style), results card, next/prev, Back closes.
- `pdf/PdfPages.kt` — `PageSpec` plan model; `rebuild` (one generic page-tree rewrite for insert/delete/rotate/move/reorder; temp file → validate with
  PdfRenderer → atomic replace) + ink remap/rotation; `merge` (PDFMergerUtility.appendDocument, ink sidecars concatenated so ink stays editable); outline loader.
- `pdf/PdfPanels.kt` — side panel with tabs Pages | Outline; thumbnails grid with ink overlay (drawn live, polled for changes), long-press page menu.
- `pdf/PdfPageDialogs.kt` — organize pages dialog (multi-select rotate/move/delete/insert blank), merge order dialog, result dialog.
- `pdf/SignatureDialog.kt` — draw / reuse / clear signature.
- `pdf/PdfScreen.kt` — session (renderers + caches, recreated per file version), reload after page edits (editor disposed first so its save cannot
  overwrite the remapped ink), header actions + tools menu with a Pages sub-level.
- `pdf/PdfSource.kt` — search marks drawn into rendered pages (multiply blend) when the editor can re-render pages.
- `pdf/PdfTools.kt` — EXIF orientation.
