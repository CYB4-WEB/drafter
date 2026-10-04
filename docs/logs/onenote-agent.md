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
