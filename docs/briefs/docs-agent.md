# Brief: docs-agent — Word (.docx) reader and image viewer

## Goal
Students receive lab sheets, assignment briefs and reports as Word files. They need to read them comfortably on the tablet
(headings, bold/italic, lists, tables, images, Arabic RTL), copy text, zoom, and open externally if needed. They also open photos
(whiteboard pictures, scanned pages) with a smooth zoomable viewer and can turn them into a note or a PDF.

## You own
- `app/src/main/java/com/daftar/app/word/**` (replace stubs `WordScreen.kt`, `ImageScreen.kt`; add `DocxParser.kt` etc.)
- `res/values/strings_word.xml`, `res/values-ar/strings_word.xml`
- log: `docs/logs/docs-agent.md`

## Public contracts (keep)
`@Composable fun WordScreen(path: String)` and `@Composable fun ImageScreen(path: String)` in package `com.daftar.app.word`.

## How — DOCX
1. Parse with `ZipFile` + `XmlPullParser` (no POI). `word/document.xml` body in order → blocks:
   - Paragraph `w:p`: `w:pPr` → style id (`w:pStyle`) resolved through `word/styles.xml` (basedOn chain: font size, bold, italic, colour, alignment, spacing;
     Heading1..6 / Title / Subtitle by id or `w:name`), alignment `w:jc` (left/start, center, right/end, both), `w:bidi` (RTL paragraph), indentation,
     spacing before/after, numbering `w:numPr` (numId+ilvl) → resolve `word/numbering.xml` abstractNum lvl `numFmt` (bullet → "•"/"◦"/"▪" by level; decimal,
     lowerLetter, upperLetter, lowerRoman, upperRoman → keep counters per numId/level, restart deeper levels) and indent per level.
   - Runs `w:r`: `w:rPr` b, i, u, strike, sz (half-points), color, highlight/shd fill, vertAlign super/sub, `w:rtl`; text `w:t` (respect xml:space), `w:tab` → tab,
     `w:br` → newline (page break → divider), `w:sym`, hyperlinks `w:hyperlink r:id` → `word/_rels/document.xml.rels` target → clickable link (opens browser).
   - Images: `w:drawing` → `a:blip r:embed` → media; size from `wp:extent` (EMU → dp at 96dpi-ish scale), decode with sampling, inline as its own block.
   - Tables `w:tbl`: rows/cells (`w:gridSpan` for colspan; `w:vMerge` treat continue cells as empty), cell paragraphs (recursive blocks), cell shading, borders as 1dp lines.
   - Headers/footers ignored; footnotes optional (append at end under a divider if `word/footnotes.xml` exists).
2. Render in Compose: `LazyColumn`, content max width 760dp centered, padding 24dp, page-like surface card on wide screens. Paragraph = `Text(AnnotatedString)` with
   SpanStyles; alignment mapping respects RTL (`TextAlign.Start/End/Center/Justify`; `w:bidi` → `LocalLayoutDirection` Rtl for that paragraph). Headings sized from styles
   (fallback H1 26sp, H2 22sp, H3 18sp). Lists with hanging indent marker. Tables via Rows with weights from grid widths, horizontally scrollable if too wide on phones.
   Whole document inside `SelectionContainer` (copy text). Top bar (`ViewerTopBar`): text size −/+ (scale 0.8–1.8, persisted in memory), search (find in document:
   highlights matches, next/prev, scrolls to block), overflow: Share, Open in another app, "Copy all text".
   Parse off main thread with loading state; error state with "Open in another app". `.doc` (legacy) → message + open externally.
3. Outline: if document has headings, a side panel (Expanded) / sheet (Compact) "Outline" listing headings; tap scrolls.

## How — Images (`ImageScreen`)
- Load downsampled bitmap (max ~4096 px) off-thread with EXIF orientation via `BitmapFactory` + manual orientation read (`android.media.ExifInterface` platform class is OK).
- Pinch-zoom (1×–8×), pan with bounds, double-tap to toggle 2.5× at tap point, smooth (use `Modifier.pointerInput` + `detectTransformGestures`, graphicsLayer).
- Top bar actions: Share, Open in another app, **Convert to PDF** (uses `com.daftar.app.pdf.PdfTools.imagesToPdf(ctx, listOf(uri), out)` with `uriFor(ctx,file)`; out = `uniqueFile(dir, name, "pdf")`; toast `saved_to`),
  **Annotate in a new note** (create `<name>.note` next to it: an `InkDoc` with one page sized to the image aspect (width 595pt) containing an `ImageItem` filling the page
  (`ImageItem.encode(bitmap)` downscaled to ≤ 2000px), save with `InkDoc.save`, `Storage.touch()`, then `Nav.replace(Screen.Note(path))`), Rotate 90° (saves the image file).

## Acceptance criteria
1. A typical lab sheet (title, headings, numbered steps, bullet sub-lists, a table, an image, a hyperlink) reads clearly and close to Word.
2. Arabic documents render right-to-left with correct alignment; mixed-direction paragraphs are correct.
3. Text selection/copy works; find-in-document works; zoom works; outline jumps.
4. Large docs (100+ pages) open without freezing (parse in background, lazy list).
5. Image viewer: smooth zoom/pan, correct EXIF rotation, convert to PDF and annotate-in-note work.
6. Corrupt files → error state, no crash. All strings localized (en + ar). Code compiles.

## Test data
Create test files with python-docx (installed) into `docs/testdata/` (English lab sheet with table/list/image; Arabic document).
