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
- Read notes-agent / notes3-agent round 2 logs, round2b brief, all of `ink/**` (and pages-agent's hook in drawPaper,
  `applyPages`, `PaperTemplates` API, study-agent's `MakeFlashcardDialog` / `StudyLinks`, `Versions`).
- New `InkTiles.kt` (tile cache + `StrokeGrid` spatial index + `IntList`); old single-layer cache removed from InkView.
- `InkModel.kt`: `Stroke.identity()`, shared geometry offset (`gdx/gdy`) in `shifted()`, `withColor/withTime` keep geometry.
- `InkRender.kt`: dot geometry (`StrokeGeom.isDot/dotR`, `drawDot`), offset-aware `drawStroke` / `drawTape`.
- `InkView.kt`: tiles wiring, zoom-settle tracking, growth step ≥ one screen, laser stops when idle, `onPause/onResume`,
  `trimMemory` + `ComponentCallbacks2`, Text tool tap always types where tapped, math candidate detection + answer with
  ✓/× chip, `tidySelection`, `selectionBitmap`, `selectedTexts`, `selectionPage`, `insertSolveAnswer`, `goToPageWhenReady`.
- New `InkAssist.kt`: `Tidy`, `MathAssist` (finds a fresh "=" + its line), `MathEval` (safe parser, Arabic-Indic digits).
- `InkToolbar.kt`: `PenButton` / `StyleChip` own their popup state; non-animated `Popup`; `PenPreviews` bitmap cache
  pre-rendered off the main thread.
- `InkEditorImpl.kt`: `ToolSync`, `RecordingBar`, `PlaybackBar` (tickers only while active, own scope); lifecycle pause /
  resume; selection bar Tidy / Solve / Flashcard (scrolls when narrow); ⋮ Math helper toggle; `Versions.capture` after a
  successful note save; lead items: `PaperTemplates.nextKey` for Add page, `PaperTemplates.stamp` for new notes, old
  `PaperDialog`/`Papers` deleted, `StudyLinks.consumePage(hostFile)` after load, 5-arg `MakeFlashcardDialog(…, page, …)`.
- `EditorServices.kt`: `Handwriting.candidates()` (all candidates of one line). `InkPrefs.mathHelper` (default on).
- Strings: 6 `ink4_` keys en + ar (both parse).
- `MathEval` checked on the JVM (compiled the object alone with the Gradle-bundled Kotlin compiler): `12×7=`→84,
  `3.5+4/2=`→5.5, `(2+3)^2=`→25, `√16=`→4, `١٢×٣=`→٣٦, `2(3+4)=`→14, `-2^2=`→-4, `5/0=`/`7=`/`1+=`/`1+1=2`→none.
- Compile: `FILTER=ink/ tools/compile.sh` and full `tools/compile.sh` → **BUILD OK**.

## Decisions & limits
- **Tiles**: 512 px, content-space grid (page coords − whiteboard shift) so growth keeps every tile; identity tokens
  survive `shifted()` copies, so a left/top growth costs a points copy per stroke (no Path copies) and zero re-renders.
  Budget min(heap/8, 96 MB) incl. previous zoom level; empty tiles keep no bitmap; pool of 6; prefetch ring only when the
  visible set uses < half the budget; if even the visible tiles do not fit (tiny heap), culled vectors are used.
  Fallback vectors are capped at 60 000 points per frame (rest appears when its tile lands, typically next frame).
  New strokes fold into tiles 450 ms after the pen rests (they are clipped vectors on top until then).
- **Growth**: by at least one screen (in points) beyond what is needed, still multiples of 234 pt.
- **Dots**: a stroke whose points span ≤ half the pen width is drawn as one filled dot (circle; square for the
  highlighter) of the pen width at the first pressure — editor live + committed, tiles, thumbnails, PDF/PNG exports.
- **Pen popup**: `Popup` (no enter animation) with cached 76×26 dp bitmaps; opening it changes no editor or canvas state.
- **Math helper**: runs only when the last two pen strokes form an "=" (short, flat, stacked) — no recognition for normal
  writing; 600 ms after pen-up; uses the handwriting model only if already downloaded (offline), handwriting language then
  en-US, every candidate is tried against the parser. Answer = typed text in the stroke colour, Caveat ("hand") font,
  right after the "=" (left of it for right-to-left lines), drawn at ~47 % opacity with a ✓ (keep) / × (remove) chip;
  ignored, it stays (it is a normal saved text box). One undo step. Not inserted if anything was written after the "=".
  Lasso → Solve may download the model (explicit request) and writes "= answer" when there was no "=".
- **Tidy**: rigid moves + one 1-2-1 smoothing pass; rotation ≤ 12°, per-word baseline correction ≤ 0.6 × line height,
  word gaps evened only with ≥ 3 words, line spacing only with ≥ 3 lines; direction from stroke order (Arabic RTL
  anchors the right end). Undo restores the original (lasso lift is the undo point).
- **Flashcard**: selection rendered on paper colour (white for PDF/slides), 2× capped at 1600 px; typed text + handwriting
  recognized only if the model is ready, waiting ≤ 900 ms; source = note, or the PDF/PPTX behind the `.name.ink.json`.
- **Text tool** (lead addendum): verified the normal path never opens a dialog — tap / drag create a box and the native
  editor opens in place with the keyboard (paged notes, whiteboards, PDF, slides); dictation and "insert recognized text"
  insert a typed box directly; `TextBoxDialog` is reachable only from the long-press "Edit text in a window…". Changed: a
  Text-tool tap elsewhere while typing now commits and starts the next box right there (OneNote/whiteboard style; before
  the first tap only ended the edit). The overlay is positioned every frame from the box's page position × zoom (font px
  = size × zoom), and growth shifts the edited box with the content, so 10 %–800 % and left/top growth line up.
- **Battery**: laser re-posts frames only while a trail is fading; pen held still = static dot, no loop. Recording /
  playback tickers live in their bars (only while recording / playing). ON_PAUSE: tile thread queue cleared, settle /
  zoom / long-press / math callbacks removed, fling stopped. Trim memory (UI hidden / running low): tiles, PDF tiles and
  far PDF page bitmaps freed; lighter levels drop the previous zoom level. Dispose: `release()` frees everything.
- Not done: paper pattern caching (PaperTemplates is pages-agent's; dots are LOD-limited already).
- No device/emulator: verified by compilation, code reading and the JVM run of the parser only.

## Requests to lead
1. study-agent: `Study.kt` still has a 4-arg stub `MakeFlashcardDialog(source, front, recognizedText, onDismiss)` next to the
   real 5-arg one in `MakeCard.kt`; I call the 5-arg one with named args (`page = …`). Please delete the stub when convenient.
2. Please test on the Tab: a 3000+ stroke whiteboard pan/zoom; a dot with each pen style; ✓/× chip; Tidy on Arabic lines.

## Self-check
1. Whiteboard lag — **PASS (not device-measured)**: tile cache of the visible region, spatial grid culling, bounded
   memory, background rendering, cheap growth; paged notes use the same tiles.
2. Pen-type popup — **PASS**: cached previews, no animation, isolated state, no canvas/cache work on open.
3. Dots — **PASS**: explicit dot geometry for all tools/styles in live view, committed ink, tiles and exports.
4. Tidy — **PASS (heuristic, not device-tested)**.
5. Math helper — **PASS** (auto + Solve + ⋮ toggle persisted; offline only for auto).
6. Flashcard — **PASS** (notes, whiteboards, PDFs, slides; depends on study-agent's dialog).
7. Versions hook — **PASS** (saver thread, notes only, after a successful save).
8. Battery/CPU — **PASS**: no idle loops/polling, pause stops work, trim-memory + dispose release caches.
- Lead addenda (text on canvas, PaperTemplates hooks, PaperDialog removal, StudyLinks, Versions) — **PASS**.
- Signatures (`InkEditorScaffold`, `EditorController`, `exportNoteToPdf`, `drawPaper`, `drawPageContent`, `newWhiteboard`),
  `.note` compatibility (no serialized field changed), `isPen()` mouse rule, `penOnly` rule — kept.
