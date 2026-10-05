# slides4-agent log

## Understanding
User request: "be able to add pages into PowerPoint whenever you want (like between slide 1 and 2)".
The PowerPoint viewer (`slides/**`, built by slides-agent) parses the .pptx read-only (`PptxParser` → `PptxSource` →
`InkEditorScaffold`), with ink in `Storage.sidecar(file, "ink.json")` (one `InkPage` per slide, index = slide position)
and the student's typed notes in `Storage.sidecar(file, "mynotes.json")` (`Map<slideIndex, String>`).
I must make the deck editable at the slide level, writing REAL OOXML so the result opens in PowerPoint:
1. Insert a slide at any position (rail "+" between thumbnails, long-press menu "Insert before/after", header menu
   "Insert slide after current"): Blank slide on a chosen deck layout (names from slideLayouts), Note page (white,
   no master graphics, for handwriting), Duplicate current slide. New `ppt/slides/slideN.xml` + its `_rels` (layout rel),
   `[Content_Types]` Override, presentation rel, `p:sldIdLst` entry at the right place (id ≥ 256 unique, rId unique).
2. Delete (confirm), duplicate, move up/down (reorder sldIdLst), hide/unhide (`show="0"`).
3. Optional title typed for a new slide → title placeholder `p:sp` with `p:ph type="title"`.
Every edit: temp zip → validate (structural check + re-parse with `PptxParser`) → atomic replace; all other parts
byte-for-byte; ink sidecar pages and My-notes indices remapped; viewer reloads on the edited slide (mirroring
pdf-agent's `PdfPages.rebuild` + `PdfScreen.editPages`: editor leaves composition first so its dispose-save lands,
then rewrite from disk, then reopen at the focus page).

## Plan
- `slides/PptxEdit.kt` — **pure JVM** (java.util.zip, javax.xml SAX/DOM, regex splicing; no Android imports) so it can
  be compiled and run on the desktop JVM for verification:
  `layouts(file)`, `rewrite(file, op, out): EditResult(plan, focus)` for Insert(Blank layout + title / NotePage /
  Duplicate) / Delete / Move / SetHidden, `check(file)` structural validator (XML well-formed, rel targets exist,
  content types cover parts, sldIdLst ids/rIds unique & resolvable). Untouched entries copied as identical bytes,
  only presentation.xml, its rels, [Content_Types] and the affected slide parts change. Delete drops parts that become
  unreachable (slide, its notes slide, its comments) and their Overrides. Sections (p14:sectionLst) and custom shows
  kept consistent.
- `slides/SlideEdits.kt` — Android side: run rewrite to temp, validate (check + `PptxParser.open` slide count), remap ink
  (`InkDoc`) + My notes, swap in (pptx, then ink, then notes).
- `slides/SlideEditUi.kt` — Insert dialog (Blank w/ layout choice + optional title / Note page / Duplicate), slide
  context menu, delete confirm.
- `SlidesPanels.kt` — "+" insert gaps in rail and filmstrip, long-press menus.
- `SlidesScreen.kt` — version/reload flow, header menu items (Insert after current, Duplicate, Move, Hide, Delete).
- `res/values{,-ar}/strings_slides.xml` — `slides4_*` strings (en + ar).
- Verification: compile PptxEdit.kt with the cached kotlin-compiler-embeddable on the JVM, apply ops to
  docs/testdata/lecture_{en,ar}.pptx, check with python (zip, XML, rels, content types, python-pptx open).

## Progress
- 2026-10-05 — Read round2b brief, slides-agent and pdf-agent (Round 2) logs, all of slides/**, ink/InkModel.kt, InkEditor.kt,
  PageSource.kt, InkEditorImpl (save-on-dispose, `InkSaver`), PdfScreen/PdfPages reload pattern.
- `slides/PptxEdit.kt` (pure JVM): package model streaming unchanged entries; `layouts()`, `layoutOf()`, `rewrite()` for
  Insert (Blank on a layout with its placeholders + optional title / Note page / Duplicate) / Delete / Move / SetHidden,
  `check()` validator. Delete drops parts that become unreachable (slide, its notes page, comments, charts + embeddings)
  and their Overrides; custom shows lose deleted slides; p14 sections stay consistent (new/moved slides join the section
  of the slide before them). Duplicate copies the slide bytes, copies notes page / charts / diagrams / tags / embeddings
  (retargeting the notes page's back-link), shares layout + media, drops comments (and any `ext` that pointed at them).
- `slides/SlideEdits.kt`: temp → `PptxEdit.check` (only problems the edit introduced) + `PptxParser.open` slide count →
  rename; ink (`InkDoc`, one page per slide; duplicate copies the source slide's ink, new slides empty) and My notes
  remapped and swapped in after the deck.
- `slides/SlideEditUi.kt`: `SlideEditState`, Insert dialog (Blank slide with layout chips named from the deck's
  slideLayouts + optional title / Note page / Duplicate slide N), slide menu items, delete confirmation, "+" gap button.
- `SlidesPanels.kt`: "+" between every thumbnail (and after the last) in the rail and the filmstrip; long-press on a
  thumbnail → Insert before/after, Duplicate, Move up/down, Hide/Show, Delete. `MyNotes.frozen`.
- `SlidesScreen.kt`: edit flow (freeze My notes → editor leaves composition → `InkSaver.flush()` → rewrite on IO,
  NonCancellable → reload via `version` key → go to the edited slide); header "Insert slide after current" (AddBox) on
  non-compact widths; the header menu now starts with the current slide's actions (Insert after current, Duplicate,
  Move, Hide/Show, Delete).
- Strings: 29 `slides4_*` keys in `values/` and `values-ar/strings_slides.xml`.
- JVM verification: compiled `PptxEdit.kt` + a test driver with the cached kotlin-compiler-embeddable 2.1.21 and ran it on
  `docs/testdata/lecture_en.pptx`, `lecture_ar.pptx` and a python-pptx deck with a chart, notes, 2 sections and a custom
  show (11 single ops per deck + a 6-step chain). python3 check of every output: all XML parses, every internal rel target
  exists, content types cover every part, no orphan Overrides, sldIdLst ids/rIds unique ≥ 256, python-pptx opens each
  file with the expected slide order/titles/layouts/hidden flag. Changed parts per op = exactly presentation.xml (+ its
  rels + [Content_Types] when parts are added/removed) or the one slide (hide); all other parts byte-identical.
