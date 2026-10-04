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
- Read round2b brief, DESIGN, notes-agent + notes3-agent logs, InkModel, InkRender (drawPaper), InkView (pages, undo,
  layer cache), InkEditorImpl (⋮ menu, PaperDialog, PageChip), NoteExport, EditorServices.exportNoteToPdf,
  LibraryFilePickerDialog, Storage.uniqueFile, Prefs.defaultPaper / Settings paper list.
- New `ink/PaperTemplates.kt`: catalogue (15 keys), labels, `stamp` / `nextKey` / `locale` / `firstDayOfWeek`,
  thread-safe vector drawing of 10 new templates (light/dark, clip-culled, LOD for dense patterns),
  `PaperPreview`, `TemplateGrid`, `TemplateChooserDialog`, `PaperPickerDialog`, `choices()`.
- New `ink/PageManager.kt`: `PageOps` (rotate, scale, portable copy, shift, move, appendToNote), `ThumbCache`
  (byte-bounded LruCache ≤ heap/16, 2 low-priority threads, recycled on dispose), `PageManagerPanel` (full-screen
  dialog: thumbnail grid, tap → page, long-press-drag reorder with auto-scroll, long-press → select, selection bar,
  undo/redo, insert, copy/move to note, extract, delete with confirm; TalkBack custom actions Move earlier/later).
- Strings: 48 `pages_*` keys appended to `values/strings_ink.xml` and `values-ar/strings_ink.xml` (both parse).

### Hook edits in ink4-agent's files (complete list)
1. `InkRender.drawPaper` — one line added after `val paper = page.paper`:
   `if (PaperTemplates.handles(paper)) { PaperTemplates.draw(c, page, dark, clip, pxPerUnit, bounded); return }`
2. `InkView` — new self-contained public method `applyPages(newPages: List<InkPage>, goTo: Int = -1)` inserted between
   `deletePage` and `setPaper` (finishEditing → pushUndo → replace pages → relayout/clamp/updateCurrentPage/changed →
   onPageChanged → optional goToPage). No other InkView line touched.
3. `InkEditorImpl`:
   a. state line `var showPages by remember { mutableStateOf(false) }` after `showPaper`;
   b. ⋮ menu: `DropdownMenuItem` "Pages" (notes, not whiteboards) right after the "Paper style" item;
   c. `PageChip(ctl, onPageChipClick ?: (if (isNote) ({ view.finishEditing(); showPages = true }) else null), …)`
      (PDF keeps its own chip action);
   d. paper dialog call site: `if (showPaper) PaperTemplates.PaperPickerDialog(view, whiteboard, onDismiss = …)`
      replaces `PaperDialog(...) { view.setPaper; Prefs.putPaper }` (same behaviour + new templates), and
      `if (showPages && isNote && !whiteboard) PageManagerPanel(view, inkFile, title, …)` right after it.
   The old private `PaperDialog` / `Papers` are now unused (left in place: not my code — ink4 may delete them).

## Decisions & limits
- **Where it lives**: the manager is a full-screen `Dialog` (works in phone, tablet, split, pop-up; the editor and its
  ink caches stay alive behind it). Notes with pages only — whiteboards have one growing page, PDFs/slides have fixed
  pages, so neither shows "Pages" (the PDF page chip keeps its own action).
- **Undo**: every change to this note is `InkView.applyPages` = exactly one undo step; the panel has Undo/Redo too and
  the editor's undo/redo work after closing it. Copy/move **into another note** writes that file and is not undoable
  there (only the removal from this note, for Move, is undoable here).
- **Gestures**: long-press then drag = reorder (dragged tile follows the finger, target tile outlined, grid auto-scrolls
  near the edges); long-press without moving = select / unselect; tap = open the page (or toggle while selecting).
  Accessible alternatives: Move earlier / later buttons (multi-select, blocks keep order) and TalkBack custom actions.
- **Thumbnails**: keyed by the page instance (pages are immutable — any edit creates a new instance, so stale
  thumbnails are impossible), paper colour and a 32 px width bucket; rendered on 2 min-priority threads; LruCache
  bounded by bytes at heap/16 (the editor's own ink layers keep their heap/8); evicted bitmaps are left to GC (never
  recycled while possibly on screen), everything is recycled when the panel closes.
- **Rotate**: swaps w/h; content keeps its coordinates and is shrunk proportionally (around the page origin) only if it
  would otherwise fall off the new page — nothing is ever cut.
- **Copy/move to note**: `LibraryFilePickerDialog` (only `.note`, never this note) → on IO: flush pending saves,
  `InkDoc.load` → append → atomic `save`. Whiteboard targets are refused. Copied strokes drop their audio-recording link
  (recording ids belong to the source note). Limitation: if the target note is open in another pane at the same time,
  that editor's next save overwrites the append (no cross-editor registry exists).
- **Extract**: copies (does not remove) the selected pages into a new note next to this one (name prompt,
  `Storage.uniqueFile`), result bar with Open. Delete afterwards if a real "cut" is wanted (undoable).
- **Insert**: before / after the single selected page, or after the last page (top "+"); template chooser with real
  previews and portrait / landscape; size follows the reference page. Inserting a dated planner next to the same planner
  continues the dates (next / previous week, day, month); otherwise the planner gets today's date.
- **Templates**: graph 5 mm minor + 1 cm major (true mm at 72 pt/in), isometric 5 mm triangular dots, lined wide
  (34 pt) / narrow (18 pt) with the same margins as Lined, music staves (7 pt gaps, every 72 pt), to-do (checkbox
  + line every 32 pt, checkbox on the start side), storyboard (16:9 frames + 3 caption lines, 2×3 portrait / 3×2
  landscape, numbered in reading order), week (range title, 7 day boxes + Notes box, 2×4 portrait / 4×2 landscape),
  day (date title, 07:00–22:00 rows with dashed half hours, time column at start, Notes column at end), month (title,
  weekday header row, 5–6 week rows, day numbers, outside days shaded). Labels use the app language (AppCompat app
  locale, device region for week rules), localized digits/time format, and the locale's first day of week (Saturday /
  Sunday / Monday). RTL locales mirror the layouts. A week key stores its first day, so the order never changes later.
  Planners/boxes are page layouts; on whiteboards only the tiling patterns are offered.
- **Paper dialog**: now a preview grid (`PaperTemplates.PaperPickerDialog`) with "Apply to all pages"; applying a planner
  to all pages gives consecutive weeks/days/months (one undo step). The chosen base key becomes the default paper, as
  before.

## Requests to lead
1. **Settings default paper** (`ui/Screens.kt` ~line 578) lists only the 5 old papers. Replace the list with:
   `com.daftar.app.ink.PaperTemplates.choices(), Prefs.defaultPaper) { Prefs.putPaper(it) }`
   (`choices()` is `@Composable` and returns `(key, localized label)` for all 15 templates.) Unknown keys already work
   for new notes; this only makes them pickable / highlighted in Settings.
2. **New notes with a planner default** — to get dates on the first page, create notes with
   `InkDoc.newNote(com.daftar.app.ink.PaperTemplates.stamp(Prefs.defaultPaper))` in `ui/LibraryParts.kt:291`
   (workspace-agent) and `ink/InkEditorImpl.kt` load fallback (ink4-agent). Without it the planner is drawn undated.
3. **ink4-agent** (FYI): ⋮ "Add page" / toolbar add-page could use `PaperTemplates.nextKey(refPage.paper)` so a new
   page after "week:2026-10-05" is the next week; the private `PaperDialog` + `Papers` in InkEditorImpl are now unused
   and can be deleted.
4. Please check on the Tab: drag-reorder feel, thumbnails of image-heavy pages, Arabic week/month headers.
