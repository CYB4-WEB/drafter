# onenote-agent log — Microsoft OneNote (.one / .onepkg) support

# Round 2

## Understanding
User request: "add Microsoft OneNote files support". I own the new package `com.daftar.app.onenote`,
`res/values*/strings_onenote.xml` and `docs/testdata/onenote/`, plus small listed edits elsewhere (Kind, Nav, Route, manifest).

What has to work:
1. **Parser (pure Kotlin/JVM, no Android imports)** for the OneNote 2010+ revision store file format:
   [MS-ONESTORE] header → transaction log (committed node counts) → file node lists (fragment chains) → object space
   manifests → revision manifest lists → revisions (dependency chain, roles, contexts) → object groups with global id
   tables → object declarations → ObjectSpaceObjectPropSet (OID/OSID/ContextID streams + PropertySet) and the file data
   store (FileDataStoreObject, `<ifndf>{guid}` references). On top of it the [MS-ONE] model: section node → page series →
   page object spaces (+ page metadata), page manifest → page node → title / outlines / images / ink / embedded files;
   outline → outline elements (indent levels, lists via NumberListNode, content = rich text / tables / images / ink);
   rich text with runs (TextRunIndex + TextRunFormatting → ParagraphStyleObject: bold, italic, underline, strike, font size,
   colour, highlight, super/subscript), hyperlink field codes (U+FDDF HYPERLINK "url"); tables as rows/cells; positions from
   OffsetFromParentHoriz/Vert (half-inch units); ink (InkContainer → InkDataNode → InkStrokeNode/InkPath (MS-ISF
   multi-byte signed ints) + StrokePropertiesNode (width, colour, dimensions)).
   Robustness: bounds-checked reader, every unknown structure skipped, never crash; detection of encrypted sections
   (ObjectDataEncryptionKeyV2FNDX), `.onetoc2` (table of contents), the cloud/FSSHTTP "alternative packaging" format,
   OneNote 2007 files (best effort with the legacy node types).
2. **.onepkg** = CAB archive: minimal CAB reader (headers, folders, files, CFDATA), uncompressed + MSZIP folders
   (raw deflate per block, previous block as dictionary via `Inflater`). LZX → clear message (unless I can verify a decoder).
3. **Viewer** `OneNoteScreen(path)`: OneNote-like layout in Daftar's design: section/page rail (sections → pages with titles
   + dates, sub-page indentation) on wide screens, bottom sheet on narrow screens / split panes; page canvas on paper with
   items at their positions, selectable text, inline images, drawn ink; header with search in the section, ConvertButton,
   ViewerMenuItems; ZoomControls + pinch zoom. "Import as Daftar note" (current page / all pages → `.note` next to the
   original; TextItems / ImageItems / Strokes); export PDF (PdfDocument) and plain text. Errors → EmptyState + Open in
   another app.
4. **Test data**: python generator for a structurally valid .one + .onepkg (CAB, MSZIP and stored), run the pure parser
   on the JVM with the kotlin compiler from the Gradle cache. Additionally (found during research) real OneNote files are
   available: Apache Tika's public test documents (OneNote 2013/2016 desktop files, one with ink, one Office 365 cloud file).
   I use them only for verification in my scratchpad (not committed, licence/size), and document results honestly.

## Plan
Files (package `com.daftar.app.onenote`):
- `OneModel.kt` — pure output model: OneBook (sections) / OneSection / OnePage / items (outline, image, ink, file) /
  blocks (paragraph with runs, table, image, ink, file) + `OneError` kinds.
- `OneStore.kt` — pure MS-ONESTORE reader (byte reader, GUIDs, file nodes, revisions, objects, property sets, file data).
- `OneDoc.kt` — pure MS-ONE interpretation (section → pages → content), text runs, lists, tables, ink.
- `Cab.kt` — pure CAB/MSZIP extraction for .onepkg (+ loader that turns a path into a OneBook, extracting packages to cache).
- `OneNoteScreen.kt` — viewer UI (rail/sheet, canvas, search, zoom, menus, errors).
- `OneCanvas.kt` — page layout/rendering in Compose (text, images with bounded byte cache, ink paths).
- `OneExport.kt` — import as Daftar note, PDF export (PdfDocument + StaticLayout), plain text.
- `res/values{,-ar}/strings_onenote.xml`.
- `docs/testdata/onenote/make_one.py` (+ generated `sample.one`, `sample.onepkg`, `sample_stored.onepkg`),
  `docs/testdata/onenote/run_parser.sh` (JVM run of the pure parser).
Outside edits (allowed list): `Kind.ONENOTE` in Storage.kt, kindIcon/kindColor/typeLabel/kindLabel/mimesFor,
`Screen.OneNote` + screenFor + Screen.file in Nav.kt, MainActivity.Route, manifest mime types.

## Progress
- Research: confirmed my MS-ONESTORE / MS-ONE constants against two public implementations (Apache Tika's
  `OneNotePropertyEnum` / `FndStructureConstants`, and the `onenote_parser` crate's ink property ids + MS-ISF multi-byte
  decoding). Downloaded Apache Tika's public OneNote test documents (8 real files, OneNote 2013/2016 + one Office 365 cloud
  file) into my scratchpad for verification only (not committed).
- `OneModel.kt`, `OneStore.kt` (revision store), `OneDoc.kt` (MS-ONE), `Cab.kt`, `OneLoader.kt` written — pure JVM.
  `docs/testdata/onenote/run_parser.sh` compiles them with the Kotlin compiler shipped in the Gradle distribution and runs
  `OneDump.kt`; first run on the real files already produced correct titles, outlines and text.
- Fixes from real files: image nodes carry OCR text in RichEditTextUnicode (now dispatched by JCID first); title offset is
  relative to the title node.
- `make_one.py` generator: sample.one (3 pages incl. sub-page + Arabic RTL page; runs, link field code, bullets, nested
  numbered list, bordered table, PNG, ink, attachment), lab.one, encrypted.one, toc.onetoc2, corrupt.one, sample.onepkg
  (MSZIP) and sample_stored.onepkg — all parse as expected (two bugs found & fixed: Entry identity across CAB reads,
  stale extraction cache marker).
- Android side: `OneImages.kt` (byte-bounded LruCache), `OneLayout.kt` (StaticLayout page layout + drawing for PDF /
  note import), `OneExport.kt` (PDF, text, Daftar note, attachments), `OneCanvas.kt` (Compose page canvas, selectable),
  `OneNoteScreen.kt` (rail/sheet, search, zoom, menus, dialogs, errors), strings en + ar.
- Outside edits applied (see list below); compile shows no errors in my files (other agents' files currently fail:
  study/*, ink/PaperTemplates.kt, LibraryParts FolderThumb — not mine).
- LZX: implemented `Lzx.kt` (verbatim / aligned / uncompressed blocks, pretree delta lengths, R0-R2, E8 translation,
  frame realignment) and wired it into `Cab.kt`, so `.onepkg` packages work whatever compression OneNote used
  (stored, MSZIP, LZX; only Quantum is refused with a message).
- Ink placement confirmed against one2html (the renderer built on onenote_parser): first point absolute, the rest are
  deltas, HIMETRIC units, relative to the InkDataNode's InkBoundingBox origin; generator updated with a non-zero origin.
- `OneConvert.kt`: public facade (toPdf / toText / toNote) for the converter hub.
- Pre-2010 files: if the header allows pre-2010 readers and nothing could be read → "old OneNote version" message.
- `docs/testdata/onenote/expected_output.txt`: parser dump of every synthetic test file (regression reference).
- Final `tools/compile.sh`: **BUILD OK** (whole app, all agents' code).

## Verification (what was checked against what)
Parser core compiled and run on the JVM (`docs/testdata/onenote/run_parser.sh`, Kotlin 2.0.21 compiler from the Gradle
distribution; the parser files have no Android imports).

| Input | Source | Result |
|---|---|---|
| testOneNote.one, testOneNote1/2/3/4.one, testOneNote2016.one, testOneNoteEmbeddedWordDoc.one | **real OneNote 2013/2016 files** (Apache Tika test documents, scratchpad only) | all sections parse: 9 pages, titles, dates, outlines at their offsets, 303 paragraphs with bold/size/colour runs, 9 tables (incl. nested tables), 60 images (PNG, size + alt text + file name, OCR text kept out of the text flow), 6 hyperlinks from field codes, bullets/numbered list labels, 1 embedded .docx attachment (11 791 bytes) |
| testOneNoteFromOffice365.one | real Office 365 (cloud / FSSHTTP packaging) | reported as CLOUD → UI explains how to export |
| sample.one / lab.one | my generator (spec-based) | every feature round-trips: runs (b/i/u/colour/highlight/size), link, bullets + nested numbering, `1)` style, bordered table + column widths, PNG, ink (2 strokes, colour, width, bbox origin), attachment, sub-page level 2, Arabic RTL page with right alignment |
| sample.onepkg (MSZIP), sample_stored.onepkg | my generator | 2 sections ("Lectures", "Labs › Lab 1"), .onetoc2 and recycle bin skipped, extracted bytes identical to the inputs |
| encrypted.one / toc.onetoc2 / corrupt.one | my generator | ENCRYPTED / TOC / CORRUPT, no crash |
| libmspack `mszip_lzx_qtm.cab`, `normal_2files_1folder.cab` | real cabinets (libmspack test suite) | MSZIP + LZX text files decode correctly; Quantum → PACKAGE_COMPRESSION |
| libmspack `large-files-cab.cab` → inner `large-files.cab` | real cabinets | outer LZX-21 (449 frames) extracts a valid cabinet; inner MSZIP, **LZX-15 and LZX-21 2 GB streams all decode to the published MD5** d64bf04a56027b97ac17d751aba2d291 |
| 30 malformed cabinets (libmspack bugs/, bad_*, partial_*, CVE cases) | real | every one fails with a clean OneException, no hang / crash |

Not verifiable here: the Compose UI on a device (no emulator), and ink from a real OneNote file (none of the
available samples contains ink; the ink path follows two independent implementations, see above).

## Decisions & limits
- **Format scope**: OneNote 2010–2016 / OneNote for Windows desktop `.one` files and `.onepkg` exports. The cloud
  packaging (files synced from OneDrive/SharePoint, Office 365 downloads) and `.onetoc2` are detected and explained
  (out of scope as briefed). OneNote 2007 node types are parsed best-effort; otherwise "old version" message.
- **Revisions**: current revision = last default-context revision with role 1 (revision role declarations honoured),
  objects resolved through the dependency chain. Conflict pages and version-history spaces are skipped.
- **Expected fidelity on real files**: text, titles, dates, page order, sub-page levels, run formatting, links,
  lists, tables, pictures and attachments: high (verified). Positions: outlines/images at their stored offsets
  (half-inch units) — matches OneNote's layout; outline heights come from our own text layout, so long notes may
  overlap neighbours slightly differently than in OneNote. Ink: positioned/coloured per spec + reference
  implementations, unverified on real ink. Not rendered: note tags (to-do checkboxes, stars), math equations
  (shown as their text if any), audio/video recordings, printouts as EMF/WMF/TIFF (placeholder with alt text),
  page background colours / rule lines, section colours (they live in the .onetoc2; the rail uses Daftar's palette).
- **Sections order in a .onepkg** is alphabetical (top level first): the real order is in the package's .onetoc2.
- **Memory**: sections are memory-mapped; the model keeps only text and blob references (file + offset);
  pictures decode on demand with sampling into a byte-bounded LruCache (1/8 heap), recycled on dispose.
  Packages are extracted once into `cacheDir/onenote/pkg_*` (last 3 kept).
- **Import as Daftar note**: one TextItem per paragraph (list label prefixed; size, colour, bold of the first run),
  ImageItems for pictures (≤ 2000 px), Strokes for ink and table borders; note pages grow to fit the content.
  The note text engine has no per-run styling, so mixed formatting inside a paragraph is flattened.
- **PDF export**: one PDF page per OneNote page sized to the content (pages longer than 14 000 pt are split).
- Attachments: tapping saves the file next to the notebook and opens it in Daftar (or another app).
- `.onetoc2` is also mapped to `Kind.ONENOTE` so users who pick it get the explanation instead of "no app".

## Edits outside my package (all small)
- `data/Storage.kt`: `Kind.ONENOTE`; `kindOf` maps one / onepkg / onetoc2; `import()` maps the OneNote MIME types to `.one`.
- `ui/Common.kt`: `kindIcon` (MenuBook) and `kindColor` (#7719AA) for ONENOTE.
- `ui/LibraryParts.kt`: `typeLabel` → "ONE", `kindLabel` → `R.string.kind_onenote` (defined in strings_onenote.xml).
- `ui/Nav.kt`: `Screen.OneNote(path)`, `screenFor`, `Screen.file`, `mimeOf` (one/onetoc2 → application/onenote,
  onepkg → application/vnd.ms-cab-compressed) so "Open in another app"/Share target OneNote.
- `MainActivity.kt`: `Route` → `OneNoteScreen` (panes and windows reuse `Route` via `workspace/Stack.kt`).
- `AndroidManifest.xml`: application/onenote, application/msonenote, application/x-onenote in the VIEW/SEND filter.

## Requests to lead
1. **convert-agent**: add OneNote conversions to the catalog so the Convert button/sheet offers them for `Kind.ONENOTE`
   (today `Conv.forKind(ONENOTE)` is empty; my viewer menu already has Export to PDF / text / Import as note):
   ```kotlin
   ONE_PDF(Group.NOTES, Kind.ONENOTE, Kind.PDF, R.string.one_export_pdf, R.string.one_import_desc),   // engine: OneConvert.toPdf(ctx, src, out, progress, cancelled)
   ONE_TXT(Group.NOTES, Kind.ONENOTE, Kind.TEXT, R.string.one_export_text, R.string.one_import_desc), // engine: OneConvert.toText(ctx, src, out, cancelled)
   ONE_NOTE(Group.NOTES, Kind.ONENOTE, Kind.NOTE, R.string.one_import_note, R.string.one_import_desc) // engine: OneConvert.toNote(ctx, src, out, cancelled)
   ```
   and `mimesFor(Kind.ONENOTE) = arrayOf("application/onenote", "application/msonenote", "application/x-onenote", "application/vnd.ms-cab-compressed", "application/octet-stream")`.
2. Lead: `docs/testdata/onenote/*.one/.onepkg` are tiny generated files (≤ 29 KB) — fine to commit. The real Tika
   samples were used from my scratchpad only (Apache-2.0, could be added under docs/testdata if wanted).

## Self-check
| Criterion | Status | Notes |
|---|---|---|
| .one parser: header → file node lists → object spaces → revisions → object groups → property sets | PASS | transaction log, fragment chains, dependency chains, global id tables, OID/OSID/ContextID streams |
| Page title, outlines, rich text (bold/italic/size/colour/highlight/underline/strike/super/sub) | PASS | verified on real files |
| Lists / bullets | PASS | bullets, numbers (decimal / roman / letters via format), nested restart; verified on generator + real |
| Tables as rows/cells | PASS | nested tables, column widths, borders flag |
| Embedded images (file data store) | PASS | real PNGs; EMF/WMF → placeholder with alt text |
| Positions (outline offsets) | PASS | half-inch → pt; title placement relative to title node |
| Ink | PARTIAL | implemented per spec + 2 reference implementations, verified only on generated data |
| Robust: never crash, unknown skipped, encrypted message | PASS | 30 malformed CABs + truncated/encrypted/TOC/cloud files handled |
| .onepkg CAB (stored, MSZIP; LZX) + section list | PASS | LZX verified on 2 GB reference streams; Quantum → message |
| Cloud format detected and explained | PASS | real Office 365 sample |
| Viewer: rail (wide) / sheet (narrow, split pane via LocalWidthClass) | PASS (code) | not run on a device |
| Canvas: positioned content, selectable text, images, ink | PASS (code) | SelectionContainer over the page |
| Header: search in section, ConvertButton, ViewerMenuItems, ZoomControls + pinch | PASS (code) | search highlights + next/prev + per-page counts in the rail |
| Import as Daftar note (page / all) | PASS (code) | TextItems / ImageItems / Strokes |
| Export PDF + plain text | PASS (code) | background job with progress + cancel, "Open" afterwards |
| Errors → EmptyState + Open in another app | PASS | per error kind, en + ar |
| Strings en + real Arabic | PASS | strings_onenote.xml (values, values-ar) |
| Test data + honest verification notes | PASS | make_one.py, run_parser.sh, OneDump.kt, expected_output.txt |
| Compiles | PASS | `tools/compile.sh` → BUILD OK |
