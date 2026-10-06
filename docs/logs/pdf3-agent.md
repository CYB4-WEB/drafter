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
- 2026-10-04 — read briefs, pdf-agent log, all of pdf/**, ml/Ml.kt (stubs), ink/InkEditor.kt + PageSource.kt, InkPrefs; checked
  pdfbox-android 2.0.27 APIs with javap (processOperator, PDResources.isImageXObject, PDType0Font.load/encode, RenderingMode,
  PDPageContentStream AppendMode + resetContext, util.Matrix).
- `PdfSearch.kt`: `PageText` gains `lineBoxes`, `lineSizes`, `images`, `fromOcr` (defaulted params, old constructor calls still
  valid) + helpers (`lines`, `lineBox`, `imageBox`, `hasText`); IndexStripper records a box (union of glyphs) and the largest font
  size per line, and picture boxes from `Do` image XObjects / inline `BI` (CTM unit square → displayed points through /Rotate, tiny
  decorations skipped). `PdfTextIndex`: `ocrPage` provider, `effective()`, blocking `walk(from,to)` and `page(i)`; search merges OCR
  text for pages without a text layer (Arabic-folded like the rest).
- `PdfBlocks.kt` (new): lines → paragraphs (size / gap / overlap / direction / short-last-line rules, Latin hyphen joins), items in
  reading order with pictures, `visualOrder` (bidi) for the text layer.
- `PdfOcr.kt` (new): `OcrStore` sidecar `ocr.json` (atomic writes, observable `version`, pages dropped on load when the page size no
  longer matches), synthetic glyph boxes so OCR matches highlight on the page; `PdfOcr.recognize` (separate renderer, ~200 dpi,
  ≤ 3000 px, each page saved as soon as done), `extractTextMerged` (Copy text), `writeSearchable` (Amiri PDType0Font subset,
  RenderingMode.NEITHER, horizontal scaling to the box, all four page rotations, temp → rename, cancellable).
- `PdfOcrUi.kt` (new): Recognize text → This page / Pages without text / All pages → `Ocr.isReady` → one-time download prompt
  (`Ocr.prepare` with progress, retry) → BusyDialog with progress + cancel.
- `PdfTranslate.kt` (new): `TranslationCache` sidecar `translate.json` (per page:target, reused only while the page text hash
  matches, ≤ 400 entries); `PdfTranslate.detect/translate` (auto-detect with letter-count fallback for "und", same-language and
  needs-model states); `TranslationOverlay` (PageOverlay drawn by PdfSource: paper-coloured 93 % opaque boxes, StaticLayout at 4×
  for precise metrics, binary-searched font size, RTL for Arabic, cached per block); `ModelDownloadDialog` (shared with OCR);
  `TranslateDialog` (detected language + target chips); `TranslatePanel` (end card on wide, bottom card on narrow; follows the
  current page, target switch, overlay toggle, show original, copy, "Recognize text" for scans, inline download/retry).
- `PdfReading.kt` (new): reading mode drawn over the editor (editor stays alive, Back closes); progressive load from the text index
  (OCR merged), page markers (copy page text, "recognized text" badge), headings from the document's body size (char-weighted
  mode), "[image]" rows → jump to the page, empty scanned pages → Recognize text / View page; SelectionContainer for selection +
  copy; settings: size 12–36 sp (sp, so Prefs.textScale applies on top), line spacing 1.1–2.2, Serif (Amiri) / Sans (Cairo) /
  System, Auto / Paper / Sepia / Dark; position remembered per file (item key + offset) and restored; reloads on new OCR text
  keeping the position.
- `PdfSource.kt`: `PageOverlay` + `overlay` (drawn after search marks). `PdfSession.kt`: `ocr`, `translations`, `loadOcr()`.
  `PdfDialogs.kt`: `CopyTextDialog(..., ocr: OcrStore? = null)`. `PdfPages.kt`: `rotateInkPage` rotates stickers (lead request).
- `PdfScreen.kt`: header Reading-mode button (wide); Tools: Reading mode, Night mode (switch), Translate page, Recognize text,
  Save as searchable PDF (enabled once OCR exists); OCR flow, translate dialog/panel, reading overlay wired; OCR updates re-run
  the panel's translation.
- Night mode: first built as my own post-process filter in PdfSource; when ink5 delivered `EditorController.nightMode` I switched to
  it (persisted in my `pdf3` prefs) and removed my filter so pages are never inverted twice.
- strings_pdf.xml en + ar: 55 `pdf3_` strings. `FILTER=pdf/ tools/compile.sh` → BUILD OK.

## Decisions & limits
- ml is still stubbed: OCR/translation show the download prompt (isReady=false, prepare=false → "Download failed"). The code paths
  are written against the published signatures and need no change when ml-agent lands.
- OCR / translation sidecars are keyed by page index; after page edits OCR pages whose size changed are dropped on load and
  translations are re-done when the text hash differs. A reorder of same-size pages can mis-assign OCR until re-recognized.
- Searchable PDF: Arabic is written unshaped in visual order (invisible layer; extraction/search in other apps get base letters).
  Characters missing from Amiri are written as spaces.
- Translation works on paragraph blocks (better quality than single lines); overlay boxes cover the block, text auto-fits down
  to 3 pt and is clipped beyond that.
- Reading mode is an overlay over the editor (no re-creation of the editor, ink and page position preserved).
- Two-page view: ink5's spread mode is toggled in the editor's own ⋮ menu (Two pages / Cover page alone, InkPrefs) and works for
  PDFs. A PDF Tools shortcut needs a controller API (the editor keeps the state in its own composition) — request 1.
- Thumbnails stay in normal colours in night mode (ink5's mapping is editor-only).

## Requests to lead
1. (ink owner) For a "Two pages" shortcut in the PDF Tools menu: expose on `EditorController`
   `var twoPages: Boolean` and `var coverAlone: Boolean` (observable; setters call `view.setTwoPages(..)` and update InkPrefs and
   the ⋮ menu state). Then I add two DropdownMenuItems with Switches in `PdfHeaderActions`.

## Self-check
1. Reading mode (reflow): toggle in header + Tools; LazyColumn reflow; size (sp, honours textScale), spacing, serif/sans,
   sepia/dark; page markers; headings by size; [image] → page; selectable + copy; position remembered; scans use OCR — **PASS**
   (no device here).
2. Night paper: Tools switch using ink5's `ctl.nightMode`, persisted — **PASS**.
3. OCR: detection of textless pages, current/missing/all in background with progress/cancel, download prompt, sidecar with lines +
   boxes in page points, merged into search (Arabic-normalised, highlights), Copy text, reading mode, translation; searchable PDF
   with embedded Amiri, NEITHER — **PASS** in code; real recognition depends on ml-agent (**PARTIAL** until then).
4. Translate: target ar⇄en with auto-detect, overlay boxes (paper bg, auto-fit, RTL) + side/bottom panel, per-page sidecar cache,
   download prompt with progress — **PASS** in code; real output depends on ml-agent.
5. Two-page view: available through the editor's ⋮ (ink5) for PDFs; Tools shortcut — **PARTIAL** (request 1).
6. Existing features (search, outline, page tools, sign, thumbnails) and public signatures kept; compiles — **PASS**.
