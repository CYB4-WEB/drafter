# slides-agent log

## Understanding
Build the PowerPoint (.pptx) viewer behind `SlidesScreen(path)` (signature kept). A student must be able to open a lecture deck and read it
close to PowerPoint, see speaker notes and reviewer comments, type their own per-slide notes, and draw on slides with the S Pen.

Required architecture: no Apache POI. `ZipFile` + `XmlPullParser` into a tiny DOM; parse presentation.xml (slide size in EMU, slide order through
rels), slide → layout → master → theme chain, notes slides, comments (legacy + modern) and media. Render each slide onto an Android `Canvas`
in slide points by implementing `ink.PageSource`; the lead's `InkEditorScaffold` provides the canvas, tools and ink persistence
(`Storage.sidecar(file,"ink.json")`). I supply the side panel (Slides thumbnails / Notes / Comments / My notes), an overflow menu
(Open in another app, Share, Export slide notes → .txt next to the file + toast `saved_to`), a loading state and an error state.

Acceptance criteria, in my words:
1. A typical university deck (title slide, bullets, pictures, table, themed background) renders close to PowerPoint: positions, sizes,
   colours, bullets, images.
2. Arabic text is shaped and laid out right-to-left; mixed Arabic/English lines order correctly (Android bidi).
3. Placeholders without their own xfrm take position and text style from the matching layout/master placeholder (idx, then type).
4. Speaker notes and comments (legacy p:cm and modern p188:cm with replies) show for the current slide; "My notes" are saved
   (debounced ~600ms) to `mynotes.json` sidecar and come back after reopening.
5. Drawing works and persists — I pass the correct `inkFile` and a correct `PageSource` (sizes in points, render honours the matrix).
6. Adaptive (any window size); thumbnails render off the UI thread and are cached; corrupt/.ppt files give an error state, never a crash.
7. Every user-visible string is a resource in en + ar. Code compiles.

## Plan
Files (all under `app/src/main/java/com/daftar/app/slides/`):
- `Xml.kt` — `XNode` mini-DOM (local element names, raw attribute names with local-name fallback lookup) built with `android.util.Xml` pull parser.
- `PptxModel.kt` — data classes: `Pptx`, `SlidePart`, `LayoutPart`, `MasterPart`, `Theme`, `Comment`, colour context.
- `PptxParser.kt` — package/rels resolution, theme (colours, fonts, fmtScheme), placeholder indexes, notes text, comments + authors.
- `PptxColors.kt` — colour resolution (srgb/scheme/sys/prst/scrgb/hsl + lumMod/lumOff/tint/shade/alpha/satMod), clrMap.
- `PptxText.kt` — text style inheritance chain (defaultTextStyle → master txStyles → master/layout ph lstStyle → shape lstStyle → pPr/rPr),
  StaticLayout building (spans, bullets, numbering, spacing, rtl/bidi alignment, autofit).
- `PptxRenderer.kt` — background, shapes (preset + custom geometry, fill/line/style refs, rotation/flip), pictures (sampled decode, srcRect,
  LRU bitmap cache), groups (child transform), tables (with built-in Medium Style 2 fallback), chart/diagram placeholders.
- `PptxSource.kt` — `PageSource` implementation (synchronised, closes zip) + thumbnails.
- `SlidesScreen.kt` — loading / error / scaffold + side panel + overflow menu + My notes autosave + export.
- Strings: `res/values/strings_slides.xml`, `res/values-ar/strings_slides.xml`.
- Test decks: `docs/testdata/` generated with python-pptx (en + ar, notes, image, table, comments injected).

## Progress
- 2026-10-04 — Read AGENT_RULES, DESIGN, SPEC, brief and all shared code (PageSource, InkEditor stub, InkModel, Common, Nav, Theme, Storage).
- 2026-10-04 — `Xml.kt`: pull-parser mini-DOM (local element names, qualified attribute names with local fallback, whitespace-preserving `a:t`).
- 2026-10-04 — `PptxParser.kt`: package rels resolution, presentation size/order, slide → layout → master → theme chain, placeholder indexes
  (idx → type → type family), clrMap, txStyles, theme fmtScheme, table styles, speaker notes, legacy comments (with p15 threading replies)
  and modern comments (p188 with replyLst), both author lists; OLE2 (.ppt / protected) detection; typed errors (LEGACY / CORRUPT / EMPTY).
- 2026-10-04 — `PptxColors.kt`: srgb/scheme/sys/prst/scrgb/hsl colours, lumMod/lumOff/satMod/hue/tint/shade/alpha/gray/inv, clrMap +
  clrMapOvr; fills (none/solid/gradient/picture/pattern/group).
- 2026-10-04 — `PptxText.kt`: style chain defaultTextStyle → master title/body/other style → master ph lstStyle → layout ph lstStyle →
  shape style fontRef → shape lstStyle → pPr/rPr; StaticLayout per paragraph at 4 px/pt with spans (size, typeface by name heuristics,
  b/i/u/strike, colour, highlight, super/subscript, caps, hyperlink colour), bullets (buChar incl. Wingdings/Symbol mapping, buAutoNum with
  counters, buNone, buClr, buSz), marL/indent hanging indents, line/paragraph spacing, autofit scale, RTL direction + absolute alignment.
- 2026-10-04 — `PptxRenderer.kt`: background chain (bgPr / bgRef via theme styles), master/layout/slide shape order with showMasterSp,
  placeholder inheritance of xfrm/geometry/fill/line/bodyPr, ~45 preset geometries + custGeom paths, line dashes and arrow heads,
  rotation/flip, pictures (sampled decode, srcRect incl. negative crops, alpha, grayscale, geometry clip, EMF/WMF → placeholder),
  groups (child offset/extent transform), tables (grid, spans/merges, auto row heights, tcPr fills/borders/margins/anchor, table styles from
  tableStyles.xml + built-in Medium Style 2 fallback), SmartArt via its cached drawing part (fallback "Diagram"), charts → "Chart" box,
  OLE → preview picture or "Object" box, mc:AlternateContent → Fallback. Per-shape try/catch.
- 2026-10-04 — `PptxSource.kt`: `PageSource` (synchronised render, pt → px scale from matrix, idempotent close) + `thumbnail()`.
- 2026-10-04 — `SlidesScreen.kt`: loading / error (Open in another app) / InkEditorScaffold with side panel (Slides thumbnails, Notes,
  Comments, My notes) and overflow menu (Open in another app, Share, Export slide notes). My notes autosave (collectLatest + 600 ms) and save
  on exit; source closed on dispose even if parsing is still running.
- 2026-10-04 — strings_slides.xml (en + ar). Compile: `BUILD SUCCESSFUL` (forced `--rerun`), no errors or warnings in slides files.
- 2026-10-04 — Test decks in `docs/testdata/`: `lecture_en.pptx`, `lecture_ar.pptx` (title, bullets with levels, picture + caption on a
  custom background, styled table, preset shapes, group, connector; speaker notes; legacy comment + reply on slide 2; modern comment + reply
  on slide 3), `corrupt.pptx`, `legacy.ppt`; generator `make_slides_testdata.py`. Walked the generated XML through the code paths
  (placeholder `<p:spPr/>` → layout xfrm; table style {5C22544A…} absent from tableStyles.xml → built-in fallback; autoshape
  fillRef/lnRef/fontRef → theme; rels targets `../comments/...` resolve to `ppt/comments/...`).
- 2026-10-04 — Review fixes: guarded `ImageCache` sample loop against zero/NaN target sizes; clamped hanging-indent margins to layout width.

## Decisions & limits
- Gradients are drawn as real linear/radial gradients (more faithful than "first stop"); slide content is not subject to the app's
  no-gradient UI rule. Path gradients are approximated radially.
- Text is laid out at 4 px per point and drawn scaled, so font metrics are not distorted by small point sizes. Layouts are cached per
  (txBody, slide, box size) in an LRU of 600 blocks; images in an LRU bounded to min(maxMemory/6, 96 MB), decoded with inSampleSize for the
  current zoom (a sharper cached copy is reused). One lock serialises page renders and thumbnail renders.
- Fonts: Office font names are mapped to Android families (serif / mono / light / condensed / medium / black / sans). Arabic runs use the
  `cs` font when present; glyphs come from the system Arabic fallback (shaping and bidi by Android).
- Direction: `rtl="1"` → RTL paragraph; `rtl="0"` → LTR; missing → first strong character. Per the brief, `rtl="1"` with `algn="l"` renders
  right-aligned. Margins and bullets mirror in RTL paragraphs.
- When a body has `normAutofit` without a saved `fontScale` (generated decks), text shrinks in steps until it fits, like PowerPoint on open.
- Not rendered: charts (placeholder "Chart", out of scope per SPEC), SmartArt without a saved drawing ("Diagram"), EMF/WMF/SVG-only media
  ("Image" box), video, 3D, effects (shadow/glow/reflection), WordArt warps, text columns, kerning. Group flips also mirror inner text (rare).
- Hidden slides are still shown (dimmed thumbnail) because students may want to read them.
- Export writes `<deck> - notes.txt` (unique name) next to the deck: per slide title, speaker notes, my notes and comments.
- Comment dates without a timezone are treated as UTC and shown in the device locale.

## Requests to lead
- None required. Integration notes:
  - `PptxSource.close()` is idempotent; `SlidesScreen` closes it on dispose, so it is safe if the scaffold closes its `source` too.
  - `sidePanel` content fills the size it is given and scrolls itself; it works as an end panel and as a bottom sheet.
  - `extraActions` contributes one MoreVert `IconButton` with a DropdownMenu.

## Self-check
1. Deck looks close to PowerPoint — PASS by code review against the generated decks (I did not run a device, per rules): positions from
   xfrm + inheritance, theme colours and backgrounds, master bullets with hanging indents, pictures with crops, styled tables, preset shapes,
   groups. Charts are placeholders (out of scope per SPEC).
2. Arabic shaped and RTL, mixed lines correct — PASS: per-paragraph StaticLayout with explicit RTL/LTR direction; Android bidi + shaping;
   mirrored bullets and margins.
3. Placeholder inheritance — PASS: slide ph → layout (idx, type, family) → master (family); xfrm, geometry, fill, line, bodyPr and
   lstStyles are inherited. The generated decks use empty `<p:spPr/>` placeholders that depend on this.
4. Notes and comments per slide; My notes persist — PASS: notes from the notes slide body; legacy (threaded replies) and modern
   (replyLst) comments with author, date and resolved state; My notes JSON at `Storage.sidecar(file,"mynotes.json")`, 600 ms debounced
   autosave + save on exit, loaded on open.
5. Drawing persists — PASS (depends on scaffold): `inkFile = Storage.sidecar(file, "ink.json")`; `PageSource` reports slide size in points
   and renders through the page → pixel matrix (zoomed tiles clip naturally). The scaffold was still a stub while I worked.
6. Adaptive, thumbnails non-blocking, corrupt → error — PASS: no orientation assumptions; parsing on IO with progress UI; thumbnails on one
   background lane, LRU-cached (160); .ppt/OLE2 → legacy message, broken zip/missing parts → corrupt, zero slides → empty, missing file →
   message, each with "Open in another app" when the file exists. Exception guards per shape and per render.
7. Strings en + ar; compiles — PASS: `res/values/strings_slides.xml` + `res/values-ar/strings_slides.xml` (same keys); build succeeds.

---
# Round 2

## Understanding
Resumed on the Linux box (compile with `tools/compile.sh`; shared API from round2b: `LocalPaneNav`/`pane`, `ViewerActions`,
scaffold params `sidePanelAtStart / sidePanelOpen / bottomPanel / bottomPanelLabel`, controller zoom). My Round 2 work:
1. **PowerPoint-like layout** in Daftar's design. Wide: permanent start-side rail of numbered slide thumbnails (current one outlined),
   slide canvas in the centre (the scaffold's InkView), collapsible notes pane under the slide with tabs Speaker notes / Comments /
   My notes. Narrow screens and split panes: the rail collapses and everything still looks right.
2. **Present mode**: full-screen immersive slideshow from the current slide. System bars hidden (WindowInsetsControllerCompat) and
   restored; next/previous by swipe, tap, arrow keys, Page Up/Down, volume keys; slide counter; exit button; laser pointer (fading
   red trail for the pen, or the finger after a long press); next slide pre-rendered; at most ~3 screen-size bitmaps; screen kept on.
3. **Header**: Present button, `ConvertButton(rememberViewerActions(file))`, my own menu (distinct icon, not MoreVert) with
   `ViewerMenuItems(actions, close)` + "Export notes". No extra zoom control (the scaffold's pill covers it).
4. **`SlidesExport`** (signatures kept, convert-agent calls it): `toPdf` = one PdfDocument page per slide at slide size in points,
   drawn through my renderer so text/shapes stay vector, temp file then rename; `toImages` = `Slide 01.png` … with bounded bitmap
   memory, recycled. Never throw (false / empty list), safe off the main thread.
5. **Low memory**: thumbnail cache bounded by bytes, renderer image cache bounded by bytes (together ≤ 1/8 of maxMemory).
6. Works inside a split pane and in a second window (links to a .pptx in a note open it via `pane.open`).

Acceptance (mine): R2-1 rail + notes pane on wide; R2-2 collapses correctly when narrow / in a pane; R2-3 present mode as above;
R2-4 header actions; R2-5 PDF export vector + atomic; R2-6 image export bounded + named; R2-7 byte-bounded caches;
R2-8 split pane / second window safe; R2-9 en + ar strings, compiles clean.

## Plan
- `SlidesScreen.kt`: loading / error / editor. Editor wraps the scaffold in `BoxWithConstraints` and provides `LocalWidthClass` from
  its *own* width (so a narrow split pane behaves like a phone even on a wide tablet). Expanded → `sidePanel` = rail at start, open
  unless in a pane. Medium/Compact → no side panel (avoids a sheet popping up when the window shrinks with the rail open, and keeps
  the header from overflowing on 360 dp phones); the notes pane gains a first "Slides" tab with a horizontal filmstrip.
  Back via `LocalPaneNav.current.back()`. Header: Present (accent pill on wide, icon on compact), Convert (not on compact — it is in
  the menu there), menu with `Icons.Rounded.IosShare` ("Share and export").
- `SlidesPanels.kt`: byte-bounded `Thumbs`, rail, filmstrip, notes pane (tabs, speaker notes, comments, my notes), IME handling for
  the My notes field (the pane slides up above the keyboard while the field is focused).
- `SlidesPresent.kt`: full-screen `Dialog` (works from a split pane and a second window), dialog window → black, cutout edges, bars
  hidden + transient-by-swipe, FLAG_KEEP_SCREEN_ON. `Frames` = ≤ 3 reusable bitmaps (current, next, previous) rendered by one
  never-cancelled worker; hidden slides skipped; end-of-show screen; ink annotations drawn over the slide; laser + pen hover dot.
- `SlidesExport.kt`: `toPdf` with PdfDocument batches (flush to a temp part when the images drawn reach a memory budget; several
  parts are merged with PdfBox using temp-file memory); `toImages` with one reused bitmap. Interrupt-aware.
- `PptxRenderer.kt` / `PptxSource.kt`: image cache ≤ maxMemory/16, recycle only on close (PDF pages keep references until written),
  drawn-image accounting for export, `drawVector()` for PDF canvases with an image detail scale.
- Strings en + ar for every new label.

## Progress
- 2026-10-04 — Read round2b, AGENT_RULES, DESIGN, SPEC (Round 2), round2 brief, my log, my slides code, InkEditor/InkEditorImpl,
  PageSource, ViewerActions, Common, Nav, Workspace, Prefs, MainActivity, InkRender/InkModel. Verified APIs in the local Gradle cache
  (DialogProperties.decorFitsSystemWindows, DialogWindowProvider, Compose `Key.*`, PdfBox `PDFMergerUtility` + `MemoryUsageSetting`).
- 2026-10-04 — `PptxRenderer.ImageCache`: bound = maxMemory/16 (≤ 64 MB) by `allocationByteCount`; evicted bitmaps are not recycled
  (PDF pages keep references until written), `clear(recycle = true)` only from `PptxSource.close()`. Opt-in drawn-picture accounting
  (`markImages` / `imageBytesSinceMark`) for export batching — off for the viewer so nothing is pinned.
- 2026-10-04 — `PptxSource`: `drawVector(i, canvas, detailPxPerPt)` for PDF canvases, `isClosed`, recycle on close, save/restore
  around every draw.
- 2026-10-04 — `SlidesExport.toPdf` (PdfDocument batches flushed to hidden part files when the pictures they hold pass maxMemory/8
  or 40 pages; parts merged with PdfBox `PDFMergerUtility` + temp-file memory; atomic rename) and `toImages` (one reused bitmap,
  ≤ 16 MP, `Slide 01.png`, `.part` + rename, recycled; unique names; everything deleted on failure). Both interrupt-aware, never throw.
- 2026-10-04 — `SlidesPanels.kt`: byte-bounded `Thumbs` (maxMemory/16, recycled on dispose), `SlideRail` (numbers, outlined
  current, hidden-slide badge, follows the current slide), `SlideStrip` filmstrip, `NotesPane` (chips Slides? / Speaker notes /
  Comments (n) / My notes, compact empty states, keyboard lift for My notes).
- 2026-10-04 — `SlidesPresent.kt`: dialog-window slideshow, immersive bars, keep-screen-on flag, ≤ 3 pooled frames + never-cancelled
  render worker (current → next → previous), thumbnail placeholder while rendering, ink overlay, laser trail + S Pen hover dot,
  swipe / tap zones (mirrored in RTL) / pen tap, keys (arrows, Page Up/Down, Space, Enter, N/P, volume, media, Home/End,
  number+Enter, B/W blank screens, Esc), mouse wheel, hidden slides skipped, end-of-show screen, auto-hiding chrome, first-run hint.
- 2026-10-04 — `SlidesScreen.kt`: pane-aware width class, rail (Expanded, open unless in a pane) / filmstrip tab (narrower),
  Present pill (icon on Compact) + Convert + "Share and export" menu (IosShare icon) with `ViewerMenuItems` + Export slide notes,
  `LocalPaneNav` back, Settings "keep screen on" honoured. Strings en + ar rewritten (38 keys each, unused ones removed).
- 2026-10-04 — Compile: the capped error output is filled by convert-agent's missing `strings_convert` keys (`convert/*`), so my
  files' results are hidden; re-checking once their strings land.
- 2026-10-04 — Optional `SlidesExport.toPdf(src, out, progress, isCancelled)` / `toImages(src, outDir, png, scale, progress, isCancelled)`
  overloads (originals unchanged) so the converter can show per-slide progress and cancel. Part files moved to `Storage.cacheDir()`.
- 2026-10-04 — Filmstrip numbers moved onto the thumbnails (short panes on landscape phones); pane caption hidden on narrow widths.
- 2026-10-04 — `tools/compile.sh` → **BUILD OK** (whole module; no errors in slides files at any point once the other agents' errors
  stopped hiding the end of the capped output).

## Decisions & limits
- **Width class from my own constraints.** The editor is wrapped in `BoxWithConstraints` and provides `LocalWidthClass` for the
  scaffold, so a half-width split pane on the tablet behaves like a phone. Expanded (> 840 dp) → rail at the start, open unless the
  deck is in a split pane (`LocalPaneNav.current.inPane`; toggle still available). Medium/Compact → no side panel at all; the notes
  pane gets a first "Slides" filmstrip tab instead. Reasons: (1) the scaffold keeps `showPanel` when the window shrinks and would
  pop a ModalBottomSheet uninvited; (2) on 360–384 dp phones one more header icon pushes the scaffold's ⋮ off the bar.
  Header on Compact: Present icon + "Share and export" menu (Convert lives in that menu there); wider: Present pill + Convert + menu.
- **Menu icon** = `Icons.Rounded.IosShare` ("Share and export"): Share, Convert (Compact only), Open side by side, Open in new window,
  Open in another app (`ViewerMenuItems`) + Export slide notes. No extra zoom control (the scaffold's pill is used).
- **Notes pane** starts collapsed (the scaffold owns its open state; PowerPoint also starts with notes hidden). Because the editor
  does not resize for the keyboard, the pane slides up above the IME while "My notes" is focused (measured from the window, so it
  also works in pop-up windows); it becomes a no-op if the scaffold later applies `imePadding()`.
- **Present mode** runs in a full-screen `Dialog` window, so it covers the whole app window from a split pane or a second window.
  In Samsung pop-up / freeform windows Android does not hide system bars for a windowed app; the show then fills the pop-up window.
  Navigation: tap end 70 % = next, start 30 % = previous; swipe; both mirror in RTL (like the AutoMirrored chevrons). Hardware keys
  stay physical (→/↓/Page Down/Space/Enter/N/volume-down/media-next = next; ←/↑/Page Up/Backspace/P/volume-up/media-previous =
  previous) because clickers and the S Pen remote send those. Extras: Home/End, number + Enter, B/. black and W/, white screen, Esc,
  mouse wheel. Hidden slides are skipped (PowerPoint behaviour); the viewer and exports still include them. After the last slide an
  "End of slide show" screen; one more "next" exits. Exit returns the editor to the last presented slide.
- **Laser**: pen / eraser end draws a 700 ms fading trail; a quick pen tap still advances; fingers get the laser after a long press
  (haptic tick); S Pen hover shows the dot. The student's ink annotations are drawn over the slide in present mode (main thread,
  separate layer from the per-frame laser layer, because `InkRender` shares Paint objects with the editor).
- **Present memory**: ≤ 3 bitmaps (current, next, previous) pooled and reused, each ≤ maxMemory/16 (scaled down if the screen-size
  frame would exceed it), rendered by one worker whose renders are never cancelled half-way; a cached thumbnail is shown scaled up
  for the instant before a frame is ready. All recycled when the show closes.
- **Caches**: thumbnails ≤ maxMemory/16, renderer pictures ≤ maxMemory/16 (→ 1/8 per deck screen). Evicted thumbnails are not
  recycled (a list item may still draw them); everything is recycled on dispose/close.
- **PDF export**: Android's `PdfDocument` holds every page (and the pictures it references) in memory until `writeTo`, so pages
  are written in parts whenever the pictures held pass maxMemory/8 or 40 pages, and the parts are merged with PdfBox using temp
  files. A normal deck is one part (no merge). Pictures are decoded at 2 px/pt (~144 dpi); Android's PDF backend embeds them
  losslessly, so photo-heavy decks give larger PDFs than PowerPoint's JPEG ones. Text and shapes are vector. Ink is not exported
  (the converter outputs the deck itself). Charts remain placeholders as in the viewer.
- **Labels without a Context** (converter used before any deck was opened): `SlidesExport.bind()` from the screen, else
  `ActivityThread.currentApplication()` by reflection, else captionless placeholder boxes (never untranslated text).
- **Settings → keep screen on** is honoured while a deck is open (`View.keepScreenOn` with a per-view counter so two decks in two
  panes don't switch it off for each other). Present mode always keeps the screen on (dialog window flag).

## Requests to lead
1. (notes-agent / `ink/InkEditorImpl.kt`) Add `.imePadding()` to the scaffold's root `Column(Modifier.fillMaxSize().background(c.bg))`
   so bottom panels and the canvas sit above the on-screen keyboard. My pane already lifts itself; it adapts automatically.
2. (notes-agent) When the window stops being wide, close the side panel instead of turning it into a sheet:
   `LaunchedEffect(wide) { if (!wide) showPanel = false else if (sidePanelOpen) showPanel = true }`
   (I avoid the problem by passing `sidePanel` only when Expanded.)
3. (notes-agent, optional) `bottomPanelOpen: Boolean = false` parameter for the initial state of the bottom panel.
4. (workspace-agent / `data/Storage.kt`) Expose the application context (e.g. `val appContext: Context get() = appCtx`), so
   `SlidesExport` can drop its reflection fallback for localised placeholder labels.
5. (convert-agent, FYI) `SlidesExport.toPdf(src, out, progress = { done, total -> }, isCancelled = { !isActive })` and the matching
   `toImages(..., progress, isCancelled)` overloads report per-slide progress and stop on cancel; the 2-arg / default calls you use
   today keep working unchanged.

## Self-check (Round 2)
Not run on a device (no emulator on this box); verified by compile + code review.
- R2-1 Wide layout (start rail with numbered thumbnails, current outlined; centre canvas; collapsible notes pane with Speaker notes /
  Comments / My notes) — PASS.
- R2-2 Narrow screens / split panes collapse correctly — PASS: pane-aware width class; rail closed in panes; filmstrip tab when not
  Expanded; header fits 360 dp; short panes handled (badged filmstrip, compact empty states); keyboard lift for My notes.
- R2-3 Present mode — PASS: immersive (bars hidden via WindowInsetsControllerCompat, restored on exit), swipe/tap/keys/volume,
  counter + exit + prev/next chrome, laser (pen trail, long-press finger, hover dot), next slide pre-rendered, ≤ 3 frames,
  screen kept on. Limit: no bar hiding inside Samsung pop-up windows (platform).
- R2-4 Header: Present, `ConvertButton(rememberViewerActions(file))`, distinct-icon menu with `ViewerMenuItems` + Export notes; no
  duplicate zoom — PASS.
- R2-5 `SlidesExport.toPdf`: vector pages at slide size in points, temp + rename, bounded memory, never throws, off-main — PASS.
- R2-6 `SlidesExport.toImages`: `Slide 01.png` … (zero-padded), one reused bitmap ≤ 16 MP, recycled, never throws — PASS.
- R2-7 Byte-bounded caches (thumbnails and renderer pictures, ≤ 1/8 maxMemory together) — PASS.
- R2-8 Works in a split pane and a second window (LocalPaneNav back, dialog on the hosting window, no global screen state) — PASS.
- R2-9 en + ar strings (38 keys each, real Arabic), `tools/compile.sh` BUILD OK — PASS.
