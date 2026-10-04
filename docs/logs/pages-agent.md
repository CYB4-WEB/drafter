# pages-agent log

# Round 2 (page manager + paper templates)

## Understanding
User request: "Note page manager: page thumbnails, reorder or move pages, and more page templates (week planner, graph)".
I own `ink/PageManager.kt` and `ink/PaperTemplates.kt` (new) and the `pages_` strings in `res/values*/strings_ink.xml`.
ink4-agent owns the rest of `ink/**` and edits it in parallel → my edits there are minimal hooks only:
one delegation in `InkRender.drawPaper`, the menu entry + PageManager invocation (+ paper dialog swap) in `InkEditorImpl`,
small self-contained public page operations in `InkView`.

1. **Page manager** (paged notes only, not whiteboards / PDFs): full-screen panel from ⋮ "Pages" and from the page chip.
   Grid of thumbnails (paper + content), rendered off the main thread, cached in a byte-bounded LRU (≤ heap/8).
   Tap = go to page; long-press-drag reorder + Move earlier/later buttons; multi-select → delete (confirm), duplicate,
   change paper template, rotate portrait↔landscape (swap w/h, content kept); move/copy to another library `.note`
   (LibraryFilePickerDialog, load → append → save that InkDoc); insert before/after with template chooser; extract
   selected pages to a new note. All edits in this note are one undo step each via `InkView.applyPages(...)`.
2. **Templates** (vector, clipped to the visible area, light/dark): graph (5 mm / 1 cm), isometric dots, week planner
   (locale day names, locale first day of week, notes cell), day planner (07–22), month calendar, to-do, music staff,
   lined wide / narrow, storyboard. Cornell stays in InkRender. Keys are strings in `InkPage.paper`; old keys untouched;
   dated planners store their date in the key (`week:2026-10-05`, `day:2026-10-04`, `month:2026-10`).
3. Paper dialog lists the new templates (with real mini previews); Settings default paper keeps working.

## Plan
- `PaperTemplates.kt`: catalogue (key, label, tiling-or-page-layout), `stamp(base)` → dated key, `nextKey(key)` (next
  week/day/month for inserted pages), `handles(paper)`, `draw(...)` called from `InkRender.drawPaper` for new keys
  (ThreadLocal paints → safe from thumbnail threads), `PaperPreview` composable (real render into a small bitmap),
  `TemplateChooserDialog`, `PaperPickerDialog` (replaces the editor's PaperDialog call site).
- `PageManager.kt`: `PageManagerPanel(view, title, noteFile, onDismiss, onSaveNow)`; `ThumbCache` (LruCache by bytes,
  keyed by page identity + paper colour, single background executor, recycled on dispose).
- Hooks: `InkRender.drawPaper` first line delegates; `InkView.applyPages(newPages, goTo)`; `InkEditorImpl` ⋮ "Pages",
  chip click (notes), panel invocation, paper dialog call site.

## Progress
(see below)
