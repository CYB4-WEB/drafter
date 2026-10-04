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
