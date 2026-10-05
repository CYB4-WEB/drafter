# pdf3-agent — v3 (reading mode, night paper, OCR, translate, two pages)

## Understanding
Extend the PDF viewer (pdf/**, built by pdf-agent in Round 2) with five features, keeping every existing public signature
and feature (search, outline, page tools, sign, thumbnails):
1. **Reading mode (reflow)** — full-screen reflowed text of the PDF (LazyColumn) opened from the header / Tools: font size,
   line spacing, serif/sans, paper/sepia/dark; page markers; headings guessed from font size; images as "[image]" rows that
   jump to the page in the normal view; selectable text + copy; remembers the position; scanned pages use OCR text.
2. **Night paper** — "Night mode" toggle. ink5-agent's night API is not in ink/ yet (no ink5 log, no API in PageSource /
   InkEditor), so: post-process colour filter in `PdfSource.render` (hue-preserving inversion onto dark paper), switchable,
   refreshed through `EditorController.refreshPages()`; request filed to swap to ink5's API (also maps ink colours).
3. **OCR for scans** with `com.daftar.app.ml.Ocr` (stubs today): detect pages without a text layer, "Recognize text"
   (current / all / pages without text) in the background with progress + cancel and a one-time model download prompt
   (`Ocr.isReady` / `Ocr.prepare`); results in `Storage.sidecar(file, "ocr.json")` (per page: lines + boxes in displayed
   page points); search, copy text, reading mode and translation use them; "Save as searchable PDF" writes an invisible
   text layer (PDType0Font Amiri embedded subset, rendering mode NEITHER) to a new file.
4. **Translate page** with `com.daftar.app.ml.Translator`: target Arabic ⇄ English (source auto-detected), blocks from the
   text layer or OCR; (a) overlay drawn into the rendered page (paper-coloured semi-opaque boxes, auto-fit text, RTL for
   Arabic) and (b) a side/bottom panel with the translated text; cached per page in sidecar `translate.json`; model download
   prompt with progress.
5. **Two pages** — "Two pages" toggle (+ "cover page alone") that turns on ink5-agent's spread mode.

Acceptance (my reading): each toggle/feature reachable from the PDF header/Tools; works on phones / split panes (narrow →
panels at the bottom); all heavy work off the main thread with progress/cancel; sidecars JSON; en + real Arabic strings
(`pdf3_` prefix); compiles with `FILTER=pdf/ tools/compile.sh`.

## Plan
- `PdfSearch.kt` — `PageText` gains per-line boxes + font sizes and image boxes (IndexStripper: line box = union of its
  glyphs, size = max font size; images from `Do` (image XObjects, forms recursed by the engine) and inline `BI`, CTM unit
  square mapped through /Rotate). New `PdfTextIndex.pages(...)` walk (used by reading mode, OCR detection, translation);
  search merges OCR text for pages without a text layer (`ocrProvider`).
- `PdfOcr.kt` (new) — `OcrStore` (sidecar load/save, synthetic glyph boxes so OCR hits highlight), OCR job (render page at
  ~2.5k px → `Ocr.recognize` → boxes to page points), merged text extraction for Copy text, searchable-PDF writer.
- `PdfTranslate.kt` (new) — blocks (lines grouped into paragraphs), translation cache sidecar, overlay drawing
  (StaticLayout auto-fit) used by `PdfSource`, translate panel UI + language/download dialogs.
- `PdfReading.kt` (new) — reading-mode screen (overlay on top of the editor so ink/position stay alive), settings sheet,
  position memory (SharedPreferences "pdf3").
- `PdfSource.kt` — night post-process, translation overlay drawing.
- `PdfScreen.kt` — header Reading-mode button, Tools items (Reading mode, Night mode, Two pages, Recognize text, Save as
  searchable PDF, Translate page), dialogs, wiring.
- `PdfDialogs.kt` — `CopyTextDialog(..., ocr = null)` optional param merging OCR text.
- strings_pdf.xml en/ar with `pdf3_` strings.

## Progress

## Decisions & limits

## Requests to lead

## Self-check
