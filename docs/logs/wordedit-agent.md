# wordedit-agent log — Word editing (v3)

## Understanding
User request (v3): "besides opening Word, support editing it — changing the text in it, like the basics of Word".
I own `word/**` and `res/values*/strings_word.xml` (prefix `word_`). The reader built by docs-agent (DocxParser → DocxDoc
model → Paginator/print + read layouts, DocxExport, text readers) stays the default; I add an **Edit mode** on top of it.

What has to exist when I'm done:
1. **Edit mode** in `WordScreen` (Edit button in the header). Editable: .docx, .txt, .md and new documents. .rtf/.csv/.doc
   stay read-only with "Save as .docx to edit". Per-paragraph rich text editing in the document flow (paper / reflow),
   RTL/Arabic aware, type/delete/select/copy/cut/paste, undo/redo. A compact Word-like ribbon (B/I/U/S, size −/+/picker,
   font family incl. Cairo/Amiri/Tehreer, text colour, highlight, paragraph style Normal/Title/H1–H3/Quote, alignment,
   RTL/LTR, bullets/numbering, indent/outdent, insert image/page break/horizontal line/simple table with editable cells),
   find & replace, status bar "Page 2 of 5 · 1,240 words".
2. **Saving that preserves the document**: write back into the original package; only changed body elements of
   word/document.xml are regenerated, everything else (untouched paragraphs, tables, images, headers/footers, styles,
   numbering, sectPr) is copied byte-for-byte. Numbering definitions / styles / media parts + relationships + content
   types are added only when needed. Temp file + validation + atomic rename, ".bak" kept until success, autosave every
   30 s + on pause/exit, "Saved" indicator. Validated with python3 and re-parsed with DocxParser. txt/md save as text.
3. `fun newWordDocument(ctx: Context, dir: File): File` — minimal valid .docx, opens in edit mode.
4. Narrow widths (split pane / phone): ribbon collapses into an overflow; keyboard never covers the caret.

## Plan
- `XmlLite.kt` — tiny offset-preserving XML tokenizer/DOM (raw slices of the source), entity decode/escape, schema-order
  helpers for `w:rPr` / `w:pPr` children (Word rejects out-of-order properties).
- `EditModel.kt` — immutable editing model: `EditDoc` (blocks + interned run formats + atoms), `EPara` (text + per-char
  format index, paragraph props, raw XML while untouched), `ETable` (rows/cells of EPara, raw pieces kept), `EObject`
  (preserved, non-editable: pictures, page breaks, rules, locked content, hidden body markers). Fields, drawings, footnote
  refs, equations inside a paragraph become **atoms** (U+FFFC + raw run XML) so the text around them stays editable.
- `DocxEdit.kt` — loader (document.xml → EditDoc, classification editable / locked), writer (paragraph/run XML with
  managed rPr/pPr merged into the original property bags in schema order), package saver (zip rewrite, styles/numbering/
  rels/content-type additions, temp + validate + atomic replace + .bak), text/markdown load+save, newWordDocument,
  "save as .docx" conversion from the read-only formats.
- `DocxParser.kt` — internal `DocxStyleSession`: reuses the parser's style/numbering resolution so the editor shows
  exactly what is saved (pPr/rPr XML → resolved look, list markers in document order, preview of preserved fragments).
  Also: run font family (`RunFmt.font`) for Cairo/Amiri/Tehreer/serif/mono in reader, print view and PDF; empty
  paragraph with a bottom border → horizontal rule.
- `EditorState.kt` — undo/redo (snapshot stack, typing coalesced), focus + selection, formatting commands, split/merge
  paragraphs (Enter / Backspace at start via a zero-width sentinel), paste with newlines, find & replace, autosave.
- `WordEditor.kt` — edit UI: paper (page width + margins, zoom) or reflow layout, BasicTextField per paragraph with a
  VisualTransformation for run formatting/atoms/find hits, list markers, tables with editable cells, objects with
  select + delete, ribbon (one row, horizontal scroll; collapses into an overflow sheet below 600 dp), status bar,
  imePadding + bring-into-view, insert dialogs (table size), photo picker.
- `WordScreen.kt` — Edit button / Done, read-only formats → "Save as .docx to edit", reload the reader after saving.
- Strings en + ar. Verification: JVM harness (kotlinc + kxml2) for loader/saver round trips on generated test docx
  files, python3 zip/XML/relationship checks, `FILTER=word/ tools/compile.sh`.

## Progress
- (Resumed twice after usage-limit cut-offs; all partial work was on disk.)
- `DocxModel.kt` / `DocxParser.kt`: `RunFmt.font` (serif / mono / cairo / amiri / tehreer from w:rFonts, `DocxParser.fontKeyOf`);
  an empty paragraph with a bottom border reads as a horizontal rule (Divider); `Ctx` decoupled from ZipFile; new internal
  `DocxParser.StyleSession` (paraLook / runFmt / markers in document order / parseFragment / styleIdFor / kindOf /
  addStyles / addNumbering) so the editor resolves styles exactly like the reader.
- `Fonts.kt`: `WordFonts` (bundled Cairo/Amiri/Tehreer for Compose + Typeface for StaticLayout/PDF, `FaceSpan`); used by
  `buildText` (reader) and `TextEngine.spanned` (print layout + PDF), initialised from WordScreen.
- `XmlLite.kt`: offset-preserving XML reader (start/openEnd/innerEnd/end per element, shallow mode for huge bodies),
  entity decode/escape, CT_RPr / CT_PPr schema order lists.
- `EditModel.kt`: immutable blocks (EPara / ETable / EObject), interned run formats (CFmt), atoms, PProps, package
  additions with snapshot/commit, word count.
- `DocxEdit.kt`: loader (body split, paragraph/table classification, fields/pictures/notes/equations as atoms, bookmarks
  kept, tracked changes/comments/content controls → locked objects, sectPr hidden), `DocxWriter` (pPr/rPr merged into the
  original property bags, schema order, direction-split runs with w:rtl, hyperlinks regrouped, tables rebuilt from kept
  tblPr/tblGrid/trPr/tcPr), saver (zip rewrite, styles/numbering/rels/content-type insertion, new numbering.xml when
  missing, media parts, temp `.name.saving` → validate (XML parse of every touched part + `DocxParser.parse`) → `.name.bak`
  → rename → delete bak), text/markdown load+save (BOM + CRLF + trailing newline kept), style / list / table / picture /
  page-break / rule factories, `fromModel` (rtf/csv/doc → docx).
- `EditorState.kt`: undo/redo (200 snapshots, typing coalesced 1.5 s), focus/selection/pending format, Enter split
  (heading → Normal next, empty list item leaves the list, Markdown list/quote continuation), Backspace at start (leave list,
  outdent, merge, delete previous object), Delete at end, char/para formatting, lists (join list above / restart at 1),
  indent (ilvl for lists, 0.5" steps otherwise), Markdown syntax commands, insert blocks, find / replace / replace all,
  save + detached save on dispose (app-wide scope + mutex).
- `WordEditor.kt`: editor UI — ribbon (one scrolling row; < 600 dp: undo/redo/style/B/I/U/bullets/insert + "More formatting"
  bottom sheet with every control), paper layout (white sheet at page width, margins, zoom) or reflow layout, a
  `BasicTextField` per paragraph with a zero-width sentinel (soft-keyboard Backspace at start), `VisualTransformation`
  for run formatting / atoms / find hits (offset mapping), list markers, editable table cells, objects (select → delete;
  locked content shows a lock + "Kept as is"), find & replace bar, status bar "Page X of Y · N words" + Saved/Saving/Edited
  + zoom, image import (photo picker, ≤ 2400 px JPEG/PNG), table size dialog, imePadding + bring-into-view of the caret,
  hardware keys (Shift+Enter line break, arrows across paragraphs, Ctrl+Z/Y/B/I/U/S/F/H). `newWordDocument(ctx, dir)`.
- `WordScreen.kt`: Edit button (not for .log), edit mode chrome (Find & replace, overflow: print/read layout, Save now,
  ViewerMenuItems; Done), autosave every 30 s + ON_PAUSE + on dispose, Back/Done = save then reload the reader;
  read-only formats → dialog "Save as .docx to edit" → new `<name>.docx` next to the original, opened in edit mode.
- Strings: 66 new `word_*` keys, en + real Arabic (96 each).
- `FILTER=word/ tools/compile.sh` / full `tools/compile.sh`: **BUILD OK** (word/ classes verified rebuilt).
- JVM verification (scratchpad harness: kotlinc 2.1.21 + kxml2 for android.util.Xml, stubs for BitmapFactory /
  TextDecode / WordFonts): python-built test.docx (Heading1, bold+themeColor run, hyperlink, PAGE field, bookmark, Arabic bidi
  paragraph, picture, tracked insertion, 2×1 table, header part, sectPr) → load classified as
  `P, P(…link#…), P(ar), O:IMAGE, O:LOCKED, T1, P, O:HIDDEN`; no-op save; edits (append bold/Cairo/14pt/red/highlighted run +
  centre, bullet on the Arabic paragraph, new Quote style, numbered list, page break, rule, new RTL 2×3 table with a cell
  edited, inserted picture, existing table cell edited) saved twice; re-parsed with DocxParser: heading kept, "added" span
  bold 14pt cairo, link still https://example.com, field result "7" kept, Quote centred, "1." marker, bullet on Arabic,
  PageBreak, Divider, both tables, both pictures, tracked text kept; reload in the editor gives the same block kinds.
  python3 (`check.py`): zip OK, every XML part parses, header/media/_rels/.rels byte-identical, untouched paragraphs /
  w:ins / drawing / sectPr verbatim in document.xml, every non-external relationship target exists and every r:id/r:embed
  resolves, numbering.xml created + Override added, exactly 1 Quote style and 2 abstractNums after two saves (no
  duplicates), every w:rPr in CT_RPr order. Markdown: CRLF + trailing newline preserved, line added.

## Decisions & limits
- **Fidelity model:** only paragraphs/tables the user changed are regenerated; everything else is copied verbatim. In a
  changed paragraph, run properties the editor doesn't manage (themeColor, lang, spacing, kern, rStyle …) are kept from the
  original rPr; managed ones (b, i, u, strike, sz, color, highlight, rFonts, rtl) are replaced only when their value changed.
  The paragraph's w14:paraId / rsid attributes stay on the first half of a split; run rsids are dropped.
- **Preserved but not editable (shown read-only with a lock, deletable):** paragraphs with tracked changes (ins/del/move,
  pPrChange), comments, content controls (sdt, e.g. TOC), smart tags/customXml, fields spanning paragraphs; tables with
  nested tables, merged-cell content outside paragraphs or other non-paragraph cell content. Text boxes / floating shapes,
  inline pictures, fields (PAGE, HYPERLINK, …), footnote references, equations and symbols inside an editable paragraph are
  **atoms**: kept byte-for-byte, shown as their result text or a symbol, can be deleted but not edited. Headers/footers,
  footnotes, comments, settings and section properties are never touched.
- Bookmarks of an edited paragraph move to its start/end; proofErr / lastRenderedPageBreak marks are dropped.
- Selection, copy/cut/paste are per paragraph (each paragraph is its own text field); paste is plain text (newlines split
  paragraphs, formatting of the caret applies). No cross-paragraph selection.
- Edit view is a continuous sheet (paper: page width + margins at zoom; reflow on phones / by choice); page boundaries are
  not drawn, the status bar computes "Page X of Y" with the shared Paginator in the background.
- Lists: one shared bullet definition, each new numbered list restarts at 1 (startOverride); existing lists keep their numIds.
  Paragraph styles Normal/Title/Heading 1–3/Quote are resolved by id/name and added to styles.xml only when missing.
- Tables: cell text editing + new tables; no row/column insert/delete or merge.
- Pictures: re-encoded to ≤ 2400 px JPEG (PNG when transparent), inserted inline centred at 96 dpi fitted to the column.
- Only transitional documents that bind `w:` on the root are editable (strict OOXML → "opens read-only" message).
- .txt/.md: one paragraph per line, ≤ 4 MB; saved as UTF-8 (original BOM/UTF-16 kept; legacy 1256/1252 files become UTF-8).
  Markdown ribbon writes Markdown syntax (**bold**, *italic*, ~~strike~~, <u>u</u>, #, >, -, 1., ---, tables, `![](…)`
  with the picture copied into `<name>_files/`). .log/.rtf/.csv/.tsv/.doc → "Save as .docx to edit" (text, run formatting,
  headings, alignment, direction, lists, tables, rules, page breaks; RTF pictures are not carried over; CSV ≤ 2000 rows).
- No emulator here: the Compose editor UI is compile-verified only; the model/saver are verified on the JVM.

## Requests to lead
- **New Word document** (create sheet + Home quick action), exact call:
  ```kotlin
  val f = com.daftar.app.word.newWordDocument(ctx, dir)   // dir = current folder (Storage.root on Home); throws IOException on failure
  pane.open(ctx, f)                                          // WordScreen opens it straight in edit mode
  ```
  Suggested label/icon: `R.string.word_new_doc_name` is the file name ("Document"/"مستند"); for the menu entry please add
  your own string, e.g. "New Word document" / "مستند Word جديد", with `Icons.Rounded.Description` tinted Word blue #3B82F6.
- Hidden `.name.bak` / `.name.saving` files can appear next to a document for a moment while saving; if the library
  lists dot-files, please hide names starting with "." (they're deleted after each successful save).
- Optional DESIGN.md §4.6: "Word — Edit button: ribbon (style, font, size, B/I/U/S, colour, highlight, alignment, direction,
  lists, indent, insert picture/table/page break/line), find & replace, status bar page · words, autosave".

## Self-check
- 1 Edit mode (button, reader default, docx/txt/md/new; rtf/csv/doc → Save as .docx): **PASS**.
- 1 Rich text per paragraph, RTL aware, type/delete/select/copy/cut/paste, undo/redo: **PARTIAL** — all present, but
  selection/copy/paste are within one paragraph (no cross-paragraph selection); on-device feel untested (no emulator).
- 1 Ribbon (B/I/U/S, size −/+/picker, font family incl. Cairo/Amiri/Tehreer, colour, highlight, styles, alignment, RTL/LTR,
  bullets/numbering, indent/outdent, insert image/page break/line/table with editable cells): **PASS**.
- 1 Find & replace: **PASS**. Status bar "Page 2 of 5 · 1,240 words": **PASS**.
- 2 Preserving save (only changed body elements rewritten, other parts byte-for-byte, numbering/images/rels added when
  needed, temp + atomic replace + .bak, autosave 30 s + pause/exit, Saved indicator, python + DocxParser validation):
  **PASS** (verified on the JVM + python; Word itself not available here).
- 2 .txt/.md save as plain text (md keeps syntax): **PASS**.
- 3 `newWordDocument(ctx, dir)` opening in edit mode: **PASS** (lead wiring requested above).
- 4 Ribbon collapses on narrow widths / split pane; keyboard never covers the caret (imePadding + bring-into-view):
  **PASS (code) / device check pending**.
- Fidelity limits documented above. Strings en + ar: **PASS**. Compiles: **PASS** (BUILD OK).
