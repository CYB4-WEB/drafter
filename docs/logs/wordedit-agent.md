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
