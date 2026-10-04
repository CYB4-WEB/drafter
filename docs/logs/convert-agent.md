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
