# convert-agent

# Round 2

## Understanding
The user wants one converter that works for every file type Daftar supports, reachable two ways:
1. **Convert tab** (`ConvertScreen(path)`): a hub of conversion cards grouped *From PDF / From images / From PowerPoint /
   From Word / From notes & text / PDF tools*, with type-coloured icons (PDF #EF4444, Word #3B82F6, PowerPoint #F97316,
   text #10B981, images green). The student picks source file(s) from the library (or the device — device documents are
   copied into `Inbox` first), sets options (page range, image format + quality, DPI, split mode), picks the output folder
   (default = source folder, Inbox for device files), runs with a progress bar + cancel, then gets Open / Share / Show in
   folder. A "Recent conversions" list (in memory). Must work on phone, tablet and a narrow split pane. `path` preselects a source.
2. **ConvertSheet(file, onDismiss)**: bottom sheet opened from every viewer, listing only the conversions valid for that
   file's kind; tap = run with defaults (or a small options step), progress + cancel inside the sheet, result row with
   Open / Share, toast `saved_to`.

Engines (`convert/Engines.kt`), off the main thread, cancellable between pages/items, with progress callbacks:
PDF→images (PNG/JPG, DPI, range), images→PDF, PDF→PPTX & images→PPTX (own minimal valid OOXML writer, slide = page aspect),
PDF→DOCX (text per page, `DocxExport.writeDocx`, page break paragraph `"\u000C"`), PPTX→PDF / PPTX→images (`SlidesExport`),
DOCX→PDF (`DocxExport.toPdf`), note→PDF (`exportNoteToPdf`) & note→images (InkRender at 2×), merge PDFs (ordered),
split PDF (ranges / every N, into a folder), TXT/MD→PDF (StaticLayout, A4, RTL/Arabic correct), PDF/DOCX/PPTX→TXT,
image format convert (PNG/JPG/WEBP + quality), "Compress PDF (scanned)" (re-render as JPEG at quality/DPI, size before → after).
Other agents' engines (SlidesExport, DocxExport) are called, never reimplemented; a false/empty result shows a clear error.

Acceptance: every listed conversion produces a valid file that opens in Daftar and other apps; hub and sheet both work;
progress/cancel; Arabic text survives TXT→PDF and PDF→DOCX; generated PPTX/DOCX structurally validated (python zipfile +
minidom + relationship targets).

## Plan
Files (all mine):
- `convert/Catalog.kt` — `Conv` enum (group, accepted source kinds, target kind, icons, strings, which options it shows),
  `ConvOptions`, `ImgFmt`, `SplitMode`, kind → conversions mapping for the sheet.
- `convert/PptxWriter.kt` — pure Kotlin (no Android imports) OOXML package writer: [Content_Types], _rels, docProps,
  presentation + rels, presProps/viewProps/tableStyles, slideMaster + slideLayout (blank) + complete theme, one slide per
  picture (`p:pic` fitted, slide size = first picture aspect). Pure so it can be compiled/run on the JVM for validation.
- `convert/Engines.kt` — all engines as `suspend` functions on `Dispatchers.IO`, `ensureActive()` between items,
  `(done, total)` progress callback, partial outputs deleted on cancel/failure, localized `ConvertException`.
- `convert/TextPdf.kt` — text/markdown/RTF → paginated A4 PDF (StaticLayout chunks, FIRSTSTRONG direction per paragraph).
- `convert/Jobs.kt` — `ConvertJob` (observable state: running/progress/done/error) launched in an app-level scope so a job
  survives the sheet being dismissed; in-memory recent list; completion toast `saved_to`.
- `convert/Convert.kt` — `ConvertScreen` (adaptive grid + setup panel; two-pane on wide screens, stacked on narrow) and
  `ConvertSheet` (kind-filtered list, options step, progress, result).
- `convert/Parts.kt` — shared composables (cards, option chips, result row, source list with reorder).
- `res/values/strings_convert.xml`, `res/values-ar/strings_convert.xml` (`convert_` prefix).
- `tools`-free validation: scratchpad Kotlin runner (embedded kotlin compiler from the Gradle cache) builds a sample PPTX from
  `PptxWriter.kt`, then a python3 script checks zip + every XML part + every relationship target + content types.

Contract note for docs-agent (`DocxExport.writeDocx`): a paragraph that is exactly `"\u000C"` (form feed) means
**page break** (`<w:r><w:br w:type="page"/></w:r>`); U+000C is not a legal XML 1.0 character, so it must never be written
as text. Arabic paragraphs should get `<w:bidi/>` + `<w:rtl/>` runs when the paragraph's first strong character is RTL.

## Progress
- Read round2b/round2 briefs, rules, DESIGN, SPEC R2.3 and every API listed in the brief.
- `PptxWriter.kt` (pure JVM) written first and validated on the JVM (embedded Kotlin compiler from the Gradle cache) + python.
- `Catalog.kt` (20 conversions in 6 groups, options, sources, outputs), `Engines.kt` (all engines), `TextPdf.kt`
  (text/Markdown/RTF → A4 PDF), `Jobs.kt` (app-scoped runner + recents), `Parts.kt` (cards, options editor, job panel),
  `Convert.kt` (`ConvertScreen`, `ConvertSheet`), `strings_convert.xml` en + ar (123 keys each, same key set, both parse).
- First compile: only errors in `word/` (docs-agent mid-edit) + one in mine (`maxWidth` in a nested scope) → fixed. **BUILD OK**.
- slides-agent and docs-agent finished their engines meanwhile: switched to their progress + cancellation overloads
  (`SlidesExport.toPdf/toImages(…, progress, isCancelled)`, `DocxExport.toPdf(src, out) { onPage -> continue? }`), call
  `SlidesExport.bind(ctx)` for localized placeholder labels, and use `DocLoader.load(f).plainText()` for Word → Text
  (reads .docx **and** legacy .doc). docs-agent's `writeDocx` already treats `"\u000C"` as a page break (contract met).
- Lead/notes-agent request handled: Note → Images crops with `doc.exportRect(i)` (whiteboards render content + margin,
  not the whole board) and uses `drawPaper(c, page, dark, clip = r, bounded = !doc.infinite)`. Rebuilt: **BUILD OK**.

## Decisions & limits
- **Jobs run in an app-wide scope** (`ConvertJobs`), not the sheet's composition: dismissing the sheet / leaving the hub does
  not kill a conversion; completion always toasts `saved_to` (folder shown as "Files › Math › …"). Recents are in memory.
- **Images → PDF** is my own PDFBox engine instead of `PdfTools.imagesToPdf`: that function has no progress/cancel and stores
  photos losslessly (huge files). Mine: per-image progress + cancel, JPEG (q 90) for photos, lossless for small PNG/GIF/BMP,
  EXIF orientation (incl. mirrored) for library files and photo-picker images, temp-file backed PDFBox memory.
- **PDF → Images / PPTX / Compress** render through pdf-agent's `PdfSource` (PdfRenderer) at the chosen DPI, capped at
  8192 px side / 20 MP. Optional "Include my annotations" draws the PDF's ink sidecar (only shown when one exists).
- **Device sources**: documents are copied to `Inbox` (wrong types deleted again with a toast); photo-picker images are used
  straight from their URIs (not copied, so converting 40 photos doesn't flood the library). Output default: source folder,
  else Inbox.
- **PDF → Word/Text**: one PDFBox load, `PDFTextStripper` per page with paragraph markers; lines joined into paragraphs,
  end-of-line hyphenation undone; scanned PDFs (no text layer) give a clear error pointing to PDF → Images.
- **Text → PDF**: own StaticLayout paginator (A4, 56 pt margins, page numbers, per-paragraph FIRSTSTRONG direction → Arabic
  right-aligned and shaped by the platform), light Markdown styling (headings, bold/italic, code, quotes, lists, rules, task
  boxes), encoding detection (BOM / strict UTF-8 / windows-1256 / Latin-1). RTF/CSV/TSV go first through docs-agent's
  `DocxExport.toPdf` (keeps RTF formatting, CSV as a table), falling back to my renderer.
- **Extras beyond the list** (cheap, use the same engines): Note → Word (typed text + links, page breaks), Text → Word.
- **Merge** uses `PDFMergerUtility.appendDocument` (deep copy, keeps outlines/forms) with sources open until saved.
  **Split** loads once and writes `<name> (split)/<name> 1-3.pdf`. **Compress** reports size before → after (+/− %).
- **Cancel**: own engines check `ensureActive()` between pages/items; other agents' engines poll a captured `Job.isActive`.
  Partial outputs (files or the images/split folder) are deleted on cancel or failure.
- **Limit**: no emulator here, so rendering (Arabic TXT→PDF shaping, PdfRenderer output, photo picker) is verified by code
  review only; generated OOXML is verified structurally (below). A cancel pressed in the last millisecond after an engine
  returned can leave the complete output file while the row says "cancelled" (harmless).
- LibreOffice in this box has no Impress/Writer modules, so it could not be used as an extra opener check.

## Requests to lead
- None required. Optional: copy `scratchpad/check_ooxml.py` to `tools/check_ooxml.py` if you want the OOXML checker in the
  repo (I don't own `tools/`); its content is reproduced in my final message on request.

## Self-check
OOXML validation (python3 `zipfile.testzip` + `xml.dom.minidom` on every part + content type for every part + every
internal relationship target exists + PPTX ids/sizes/blip rels/theme style counts; then python-pptx / python-docx open):
- PPTX from `PptxWriter` (3 pictures: portrait, landscape, square; Arabic + `<&">` title; aborted writer leaves no file):
  23 parts **OK**; python-pptx: 3 slides, 8615487×12192000 EMU, one picture per slide, all inside the slide.
- DOCX from docs-agent `writeDocx` fed exactly like my engines (pages split by `"\u000C"`, Arabic, tabs, quotes, a control
  char, form feed inside a paragraph): 8 parts **OK**; python-docx: 9 paragraphs, 2 page breaks; Arabic paragraphs carry
  `<w:bidi/>` + `<w:rtl/>` runs.

Per conversion (all compile; runtime on device to be confirmed by lead):
- PDF → Images (PNG/JPG, quality, DPI 72–300, range, annotations): PASS (code) — `<name> (images)/Page 01.png`.
- PDF → PowerPoint: PASS — validated writer; slide size = first selected page aspect.
- PDF → Word: PASS — text per page, page breaks, Arabic survives the writer (verified); extraction via PDFBox.
- PDF → Text: PASS.
- Images → PDF / Images → PowerPoint / Image format (PNG/JPG/WEBP + quality): PASS.
- PowerPoint → PDF / → Images (format, 1–3×) via SlidesExport, PowerPoint → Text (titles, shape + table text, notes): PASS.
- Word → PDF (DocxExport.toPdf, cancellable) / Word → Text (DocLoader, .doc too): PASS.
- Note → PDF (exportNoteToPdf, crops whiteboards) / Note → Images (2×, exportRect crop) / Note → Word: PASS.
- Text → PDF (TXT/MD/RTF/CSV/LOG, RTL aware) / Text → Word: PASS (code; Arabic shaping relies on platform StaticLayout +
  PdfDocument, same stack the Word viewer uses).
- Merge (ordered, reorder with up/down), Split (every N / ranges, live "creates N files"), Compress (size before → after): PASS.

Acceptance criteria:
- Every listed conversion produces a valid file that opens in Daftar and other apps — PARTIAL: OOXML outputs validated
  structurally + by python-pptx/docx; PDF/PNG outputs come from PdfDocument/PDFBox/Bitmap.compress; not run on a device here.
- Hub works (grouped type-coloured cards, library/device sources, options, output folder, progress + cancel, Open / Share /
  Show in folder, recent list, phone / tablet two-pane / narrow pane) — PASS (code review; no emulator).
- Sheet works (only conversions valid for the kind, tap = run with defaults, options step via tune icon, split always asks,
  progress + cancel in the sheet, Open / Share / Show in folder, toast `saved_to`, link to hub / merge) — PASS (code).
- Progress / cancel — PASS (determinate per page/item; indeterminate only inside DocxExport.toPdf, which reports no total).
- Arabic survives TXT→PDF and PDF→DOCX — PARTIAL: DOCX side verified (bidi/rtl runs); TXT→PDF uses per-paragraph
  FIRSTSTRONG direction and platform shaping, needs an on-device look.
- Generated PPTX/DOCX validated — PASS.
