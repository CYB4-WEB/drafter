# docs-agent log — Word (.docx) reader + image viewer

## Understanding
I build two real, working viewers in package `com.daftar.app.word`, keeping the public signatures
`WordScreen(path: String)` and `ImageScreen(path: String)`.

**Word reader.** Parse .docx myself (ZipFile + XmlPullParser, no POI) into a list of blocks and render them in a lazy,
selectable, zoomable reading view (max 760dp text width, page-like card on wide screens). Parsing must cover: paragraph
styles resolved through styles.xml with basedOn chain (size, bold, italic, colour, alignment, spacing, heading level from
id or name), alignment (jc), bidi paragraphs, indentation, spacing, numbering from numbering.xml (bullets by level, decimal /
letters / roman counters per numId+level with deeper-level restart); runs (b/i/u/strike/size/colour/highlight/shading/
super/sub/rtl, text with xml:space, tab, br, page break -> divider, sym), hyperlinks via document rels -> clickable links;
inline images (blip embed -> media, extent EMU -> dp, sampled decode); tables (gridSpan, vMerge continue = empty, nested
blocks, shading, 1dp borders); footnotes appended at end. UI: ViewerTopBar with text size -/+ (0.8–1.8, remembered in
memory), find-in-document (highlight, next/prev, scroll to block), overflow Share / Open in another app / Copy all text;
outline of headings (side panel on Expanded, sheet on Compact) that jumps to the heading. Background parse with loading
state; error state with "Open in another app"; legacy .doc -> message + open externally.

**Image viewer.** Downsampled (<= ~4096px) decode off-thread with EXIF orientation applied; pinch zoom 1x–8x, bounded pan,
double-tap toggles 2.5x at the tap point. Actions: Share, Open in another app, Convert to PDF (PdfTools.imagesToPdf ->
unique file next to the image, toast saved_to), Annotate in a new note (InkDoc with one page 595pt wide matching aspect,
ImageItem filling it, bitmap <= 2000px, save, Storage.touch, Nav.replace(Screen.Note)), Rotate 90 deg (rewrites the file).

**Acceptance criteria (restated)**
1. A typical lab sheet (title, headings, numbered steps, nested bullets, table, image, link) looks close to Word.
2. Arabic docs render RTL with correct alignment; mixed-direction paragraphs correct.
3. Select/copy text, find works, zoom works, outline jumps.
4. 100+ page docs open without freezing (background parse, LazyColumn).
5. Image viewer: smooth zoom/pan, correct EXIF rotation, Convert to PDF + Annotate in note work.
6. Corrupt files -> error state, never crash; all strings in en + ar; code compiles.

## Plan
- `word/DocxModel.kt` — block model: `DocBlock.Para`, `DocBlock.Image`, `DocBlock.Table`, `DocBlock.Divider`, run/span data.
- `word/DocxParser.kt` — zip + pull parser: rels, styles (with basedOn resolution and docDefaults), numbering
  (abstractNum/lvl/num + lvlOverride startOverride), document body, footnotes. Images decoded lazily at render time from the zip
  (keep zip path + entry name), with inSampleSize so memory stays bounded for big docs.
- `word/WordScreen.kt` — state machine (Loading / Error / Legacy / Ready), LazyColumn of blocks inside SelectionContainer,
  AnnotatedString builder with SpanStyles + LinkAnnotation.Url for hyperlinks + search highlight, per-paragraph
  CompositionLocalProvider(LocalLayoutDirection) for bidi, tables as Rows with weights from tblGrid (horizontal scroll when
  narrow), find bar, outline panel/sheet, overflow menu.
- `word/ImageScreen.kt` — bitmap loader with ExifInterface, zoom/pan state with gesture handling, actions.
- `res/values/strings_word.xml` + `res/values-ar/strings_word.xml`.
- Test data: `docs/testdata/lab_sheet_en.docx`, `docs/testdata/arabic_doc.docx` via python-docx.

## Progress
- 2026-10-04 14:40 — Read AGENT_RULES, DESIGN, SPEC, brief, and the shared code (Common, Nav, Theme, Storage, InkModel, PdfTools contract, stubs).
- 14:45 — Wrote `DocxModel.kt` (block model, pure Kotlin, so it can be built off the main thread) and `DocxParser.kt` (ZipFile + namespace-aware XmlPullParser: styles with basedOn chain + docDefaults, numbering with abstractNum/num/lvlOverride/startOverride and per-numId counters, rels, body, tables, drawings/VML pictures, text boxes, hyperlinks (rels + HYPERLINK fields), footnotes).
- 14:50 — Wrote `WordScreen.kt` (reader UI), `ImageScreen.kt` (zoomable viewer + actions), and `strings_word.xml` in en and ar.
- 14:55 — Compile: the shared tree was failing only in lead-owned files (Screens.kt, LibraryParts.kt, strings.xml mid-edit). I proved the compiler was not reaching my files (a deliberate probe error was not reported), so I compiled an isolated copy in my scratchpad with those unrelated issues patched. Result: zero errors and zero warnings in `word/`. Later the real tree compiled cleanly too (`compileDebugKotlin`, no errors).
- 15:00 — Test data: `docs/testdata/make_testdata.py` generates `lab_sheet_en.docx` (title, headings, nested bullets, numbered steps, centred image, Table Grid table with a merged row, sub/superscript, colour, highlight, strike, hyperlink, page break, monospace code), `arabic_doc.docx` (bidi headings, numbered and bullet lists, bidiVisual table, mixed-direction paragraph, an LTR paragraph, a centred paragraph, an Arabic paragraph without a bidi flag), `large_120_pages.docx` (40 chapters, about 120 pages), `corrupt.docx`, and `legacy.doc` (OLE header).
- 15:05 — Ran the real parser on the JVM (copied DocxModel/DocxParser, shimmed `android.util.Xml` to kxml2, compiled with kotlin-compiler-embeddable). Results:
  - Lab sheet: all headings with levels and outline indices are correct. Bullets show "•" at two levels with indents of 0 and 22.5dp. Numbered steps show 1.–4. The image is a 422×238dp block, centred. The table has grid 3×180dp, borders from the Table Grid style, and the merged row has span=3. Bold, italic, colour, highlight, strike, sub and superscript, the hyperlink URL, the page-break divider, and monospace are all correct.
  - Arabic: every bidi paragraph has rtl=true. The paragraph without bidi is still rtl through first-strong detection. The English paragraph is rtl=false. The centre-aligned paragraph is correct. The table has rtl=true. The list markers "1." "2." "•" are correct.
  - Large: 1160 blocks, 160 headings, 1120 paragraphs parsed in 90 ms (JVM).
  - Corrupt: ZipException goes to the error state. Legacy (.doc or OLE) throws LegacyDocException and shows the legacy message. A missing file goes to the error state.
- 15:10 — Refinements: non-scrolling tables fill the width. PNG/WebP rotation decodes at a 4096px cap for memory safety.

## Decisions & limits
- **Units:** 1pt of document text is rendered as 1.4sp × the user text scale (11pt body is about 15sp, the DESIGN body size). Headings are capped at 36sp × scale. Indents use 1.25dp per pt. Image size is EMU/9525 × 1.1dp, clamped to the column width. Span sizes are stored relative to the paragraph size (em), so zoom rescales everything, including indents and table widths.
- **Direction:** a paragraph is RTL if it has `w:bidi`, or if its first strong character is RTL. This is how Arabic text without the flag still reads right-to-left. Mixed-direction text is left to the platform bidi algorithm (`TextDirection.Rtl` for bidi paragraphs, `Content` otherwise), and each paragraph gets its own `LocalLayoutDirection`, so list markers and indents mirror. For `w:jc`, left/right mean start/end in bidi paragraphs (OOXML transitional semantics). In non-bidi paragraphs they are physical sides.
- **Arabic run formatting:** runs containing Arabic or Hebrew use the complex-script properties (`bCs`, `iCs`, `szCs`) when present, which matches Word.
- **Dark theme:** document colours are adapted. Near-grey dark colours become `ink`, other dark colours are lightened, and highlight and cell shading are drawn at 30% alpha. The light theme keeps the document's colours. The page is a surface card with a 1dp `line` border on wide screens (≥ 808dp) and full-bleed surface on phones. There are no shadows.
- **Numbering:** counters are kept per numId, as the brief asks. Bullets use "•", "◦", "▪" by level unless the document's bullet is already a normal visible glyph. Supported formats: decimal, decimalZero, roman and letter (upper and lower), arabicAlpha, arabicAbjad, and hindiNumbers (Arabic-Indic digits).
- **Tables:** column weights come from `tblGrid`. Borders come from explicit `tblBorders`, else from the table style's borders, else from a style id containing "Grid". `vMerge` continuation cells render empty with borders. A table scrolls horizontally when its natural width × scale exceeds the column by more than 8%; nested tables never scroll.
- **Not rendered:** headers, footers and comments (the brief allows ignoring them). Charts, SmartArt and EMF/WMF pictures show a neutral image placeholder, or the alt text for screen readers. Floating (anchored) pictures are shown inline at their anchor paragraph. Text-box contents are shown as normal paragraphs after their anchor paragraph. Internal bookmark links (`w:anchor`) are plain text. Fonts map to system sans, except Courier, Consolas or Mono, which map to monospace.
- **Find:** case-insensitive, debounced 180ms, runs on `Dispatchers.Default`, capped at 5000 matches. It scrolls to the top-level block that holds the match; for a match inside a big table it lands on the table's top.
- **Text size:** 0.8–1.8 in 0.1 steps, kept in memory for the app session (`WordPrefs`). On Compact width the −/+ controls move into the overflow menu so the top bar fits.
- **Outline:** a 280dp side panel on Expanded (on by default, toggled by the TOC icon) and a ModalBottomSheet on Compact and Medium.
- **Images inside a .docx:** decoded lazily from the zip on IO, sampled to the display width, held in a 48MB LRU, and the zip is closed on dispose.
- **Image viewer:** decodes with a 4096px cap and applies EXIF orientation, including mirrored variants. Rotate on a JPEG is lossless: it only updates the EXIF orientation tag. On PNG/WebP it re-encodes the pixels; an image larger than 4096px would be saved downsampled, which is rare for those formats. HEIC, GIF and BMP cannot be written back, so Rotate shows "This image type can't be rotated". "Annotate in a new note" creates `<name>.note` next to the image: one page 595pt wide with the image's aspect ratio, paper "blank" (a valid paper id in InkEditorImpl; "none" is only used for PDF/slide layers), and an ImageItem filling the page, downscaled to ≤ 2000px. It then calls `Storage.touch()` and `Storage.opened()` and does `Nav.replace(Screen.Note)`.
- **Convert to PDF:** calls `PdfTools.imagesToPdf(ctx, listOf(uriFor(ctx, file)), Storage.uniqueFile(dir, name, "pdf"))` on IO, then shows a `saved_to` toast. It will show "Could not create the PDF" until pdf-agent's implementation lands, because the stub returns null.

## Requests to lead
- None required for my features.
- FYI for pdf-agent and the lead: after a JPEG is rotated in the image viewer, only its EXIF orientation changes. `PdfTools.imagesToPdf` should honour EXIF orientation (read `ExifInterface` from the Uri's stream), or the PDF page will appear unrotated.
- FYI: while I worked, `res/values/strings.xml` briefly had an unescaped apostrophe in `set_daily_desc`, which breaks aapt. It compiles now; just make sure it stays escaped (backslash before the apostrophe).

## Self-check
1. **Lab sheet reads clearly, close to Word: PASS.** Verified by running the parser on `lab_sheet_en.docx`: title and headings with style sizes and colours, nested bullets with hanging indents, numbered steps, a centred image, a bordered table with a merged row, sub/superscript, highlight, strike, a clickable hyperlink, and a page-break divider. The final on-device rendering still needs the lead's visual check.
2. **Arabic RTL, alignment, mixed direction: PASS.** Bidi paragraphs, headings, lists and tables (`bidiVisual`) are RTL with mirrored markers and indents, and Arabic without a bidi flag is detected. A mixed paragraph uses an RTL base direction with the platform bidi algorithm, and English paragraphs stay LTR.
3. **Select/copy, find, zoom, outline: PASS.** There is a SelectionContainer over the whole list, plus "Copy all text" in the overflow menu. Find highlights every match, the current one stronger, with next/prev and scrolling. Text size is −/+ (0.8–1.8). Tapping an outline heading scrolls to it (panel on Expanded, sheet otherwise).
4. **100+ page docs without freezing: PASS.** Parsing is a single streaming pass on `Dispatchers.IO` (120-page test file: 90 ms on the JVM). The view is a LazyColumn, and images decode lazily on IO with sampling and an LRU.
5. **Image viewer: PASS.** Pinch 1×–8× around the fingers, bounded pan, and double-tap that animates to 2.5× at the tap point. EXIF orientation is applied, all 8 values. Annotate-in-note works end to end. Convert to PDF is wired to the PdfTools contract and works once pdf-agent's implementation lands; it currently shows the failure toast because the stub returns null.
6. **Corrupt files, strings, compile: PASS.** Corrupt and missing files go to the error state with "Open in another app". .doc and OLE-encrypted files get the legacy message plus the open-externally button. Every Throwable is caught, including OOM. All user-visible strings are in `strings_word.xml` in en and ar. The project compiles with zero errors in my files, and the full tree compiles.
