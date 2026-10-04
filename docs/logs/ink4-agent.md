# ink4-agent — Round 2 (ink performance, dots, Tidy, Math helper, Flashcard, battery)

## Understanding
I own `ink/**` (except `PageManager.kt` / `PaperTemplates.kt`, pages-agent) and `strings_ink.xml` (my keys `ink4_`).
User reports and asks, after testing on a device:
1. Whiteboard lags once it gets big. Causes found in code: (a) every left/top growth called `InkPage.shifted()` on the UI
   thread, copying every stroke's points **and** `Path` geometry, which also broke the identity check of the ink layer so
   the whole visible ink was re-drawn as vectors and re-rasterised; growth steps were small (≈2 × 234 pt) so this repeated
   while panning; (b) the single "visible area + overscan" layer stopped covering the view after a short pan / any zoom,
   and every uncovered frame fell back to drawing *all* visible strokes as vectors on the UI thread (thousands at low
   zoom); (c) erase/undo/lasso invalidated the whole layer. Paged notes share (b)/(c).
2. Pen-type popup is laggy: `DropdownMenu` (animated popup window) rendering live sample strokes, and all toolbar state was
   read in the body of `InkEditorImpl`, so every tool/style change recomposed the whole editor.
3. A dot (tap) is not shown: a 1-point stroke was a 0.01 pt line (dropped by the GPU stroker at some zooms, nothing at all
   with the marker's BUTT cap), and the brush tapered a dot down to ~2 % of its width.
4. Tidy (lasso): group into lines, straighten baselines, even spacing, light smoothing, undoable.
5. Math helper: offline, after "=" is written, answer as faded typed text; ⋮ toggle; lasso → Solve.
6. Lasso → Flashcard → `MakeFlashcardDialog(source, front bitmap, recognizedText, onDismiss)`.
7. `Versions.capture(noteFile)` after each successful note save on the saver thread.
8. Battery: no idle animation loops, no idle polling, stop work on pause, release caches on trim memory / dispose.
Lead addendum: Text tool must type directly on the canvas (whiteboard + pages + PDF/slides), never a modal dialog except
"Edit text in a window…"; overlay positioned right at any zoom and after left/top growth.

## Plan
- New `InkTiles.kt`: tile cache for committed ink — 512 px tiles of the visible region (+ a prefetch ring when the
  budget allows) at the settled zoom, keyed in *content space* (page coords minus the whiteboard shift) so growth never
  invalidates them; LRU, ≤ min(heap/8, 96 MB), bitmap pool, one background thread with a main-thread priority queue
  (visible first, stale requests dropped). Reconcile per stroke-list change: appended strokes → only touched tiles keep
  an old list and draw the tail as clipped vectors; erase/undo/move → only tiles intersecting the changed bounds are
  dirty. Missing tiles: previous zoom level scaled, else clipped vectors from a grid spatial index with a per-frame point
  budget. Replaces the old `InkLayer`.
- `InkModel.kt`: `Stroke.shifted()` shares the geometry with an offset (no Path copies) and keeps a stable identity token.
- `InkRender.kt`: geometry offset in `drawStroke`; explicit dot geometry (round, pen width, all styles, live + saved).
- `InkView.kt`: bigger growth steps; tiles wiring; laser loop stops; pause / trim memory; Tidy, Solve, flashcard bitmap,
  math answer insertion with a pending ✓/× chip; Text tool taps always start typing where tapped.
- New `InkAssist.kt`: Tidy algorithm, math line detection, safe expression parser.
- `InkToolbar.kt`: pen button / style chip own their popup state; non-animated `Popup` with cached preview bitmaps.
- `InkEditorImpl.kt`: tool sync + recording/playback tickers in small composables; Flashcard/Solve/Tidy in the selection
  bar; Math helper toggle; math detection 600 ms after pen-up; Versions hook; lifecycle pause/resume; trim memory.
- `InkPrefs.kt`: `mathHelper` (default on). Strings en + ar.

## Progress
(see below)
