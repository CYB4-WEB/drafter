# Brief: slides-agent — PowerPoint (.pptx) viewer with notes, comments and annotation

## Goal
A student opens a lecture `.pptx` and reads it comfortably, like in PowerPoint: correct layout, text, images, backgrounds, Arabic text.
They can see the speaker notes and the reviewers' comments, write their own typed notes per slide, and draw/handwrite on slides with the S Pen.

## You own
- `app/src/main/java/com/daftar/app/slides/**` (replace the stub `SlidesScreen.kt`; add e.g. `Xml.kt`, `PptxParser.kt`, `PptxRenderer.kt`, `PptxSource.kt`)
- `res/values/strings_slides.xml`, `res/values-ar/strings_slides.xml`
- log: `docs/logs/slides-agent.md`

## Public contract (keep)
`@Composable fun SlidesScreen(path: String)` in package `com.daftar.app.slides` — `path` is an absolute `.pptx` path.

## How (required architecture)
1. **Parsing** (no Apache POI — too heavy and needs AWT): open with `java.util.zip.ZipFile`; parse XML with `XmlPullParser` (namespace-aware off is fine; match on local names) into a tiny DOM.
   - `ppt/presentation.xml`: `p:sldSz cx/cy` (EMU; 12700 EMU = 1pt), `p:sldIdLst` order → `ppt/_rels/presentation.xml.rels` targets.
   - Each slide's `_rels`: layout → its master; notesSlide; comments; images (`r:embed` → `ppt/media/...`).
   - Theme (`ppt/theme/theme1.xml` via master rels): `a:clrScheme` (dk1, lt1, dk2, lt2, accent1–6, hlink) and fonts (major/minor latin + `a:cs`/`a:ea`).
2. **Rendering** onto an Android `Canvas` in slide points (implement `com.daftar.app.ink.PageSource`):
   - Draw order: background (slide → layout → master; `p:bg/p:bgPr` solidFill / blipFill image / `p:bgRef` → approximate with theme lt1) →
     master non-placeholder shapes (unless `showMasterSp="0"`) → layout non-placeholder shapes → slide shapes.
   - Shapes `p:sp`: `a:xfrm` (off/ext, `rot`, `flipH/V`); placeholder shapes without xfrm inherit position & text styles from the layout/master
     placeholder with the same `idx` (or `type`: title/ctrTitle/body/subTitle...). `a:prstGeom` at least: rect, roundRect, ellipse, line, triangle,
     rightArrow, chevron; other presets → rect. Fill: solidFill (srgbClr / schemeClr / sysClr with lumMod/lumOff/tint/shade/alpha), noFill, gradFill → first stop.
     Outline `a:ln` width + colour.
   - Text `p:txBody`: `a:bodyPr` insets (default 91440/45720 EMU), anchor t/ctr/b, `normAutofit fontScale`, wrap; paragraphs `a:p` with `a:pPr algn`,
     `lvl`, `marL/indent`, bullets (`buChar`, `buAutoNum` → "1.", `buNone`; body placeholders get "•" by default from master `p:bodyStyle`),
     line spacing; runs `a:r/a:rPr` sz (1/100 pt), b, i, u, strike, solidFill colour, `a:latin/a:cs` typeface (map to `Typeface.SANS_SERIF/SERIF/MONOSPACE`
     by name heuristics), `a:br`, `a:fld` text. Default sizes from master `p:txStyles` (titleStyle/bodyStyle/otherStyle lvlNpPr defRPr) → fallback 18pt.
     Use `StaticLayout` with spans; let Android bidi handle Arabic (paragraphs whose `a:pPr rtl="1"` → ALIGN_OPPOSITE when algn is l, i.e. right aligned).
   - Pictures `p:pic`: decode media with `BitmapFactory` using `inSampleSize` to the target size; honour `a:srcRect` crop. EMF/WMF/SVG → draw a light placeholder box.
   - Groups `p:grpSp`: apply `a:grpSpPr/a:xfrm` child offset/extent transform recursively.
   - Tables `p:graphicFrame` → `a:tbl`: grid columns widths, rows heights, cell text, cell fill, borders (thin `line` colour default).
   - Charts / SmartArt (`graphicFrame` with chart/dgm) → light rounded placeholder labelled with the string "Chart"/"Diagram".
   - Cache parsed model; rendering for a page must be fast enough for scrolling (target < 150ms per slide on a tablet).
3. **Notes & comments**:
   - Speaker notes: notesSlide → body placeholder text (paragraphs).
   - Comments: legacy `ppt/comments/commentN.xml` (`p:cm` with `p:text`, `authorId` → `ppt/commentAuthors.xml` name, `dt`) AND modern
     `ppt/comments/modernComment_*.xml` (`p188:cm` → `p188:txBody` text, replies `p188:reply`, `authorId` → `ppt/authors.xml`). Show author, date, text, replies.
   - "My notes": student's typed notes per slide, saved as JSON at `Storage.sidecar(file, "mynotes.json")` (map slideIndex → text), autosave (debounced ~600ms).
4. **Screen** (`SlidesScreen`): use `InkEditorScaffold(title, inkFile = Storage.sidecar(file,"ink.json"), source = PptxSource, isNote = false, onBack = { Nav.pop() }, extraActions, sidePanel, sidePanelLabel)`.
   The scaffold gives the zoomable vertical page canvas + drawing tools + saving; you supply:
   - `sidePanel(controller)`: tabs **Slides** (thumbnails list, current highlighted, tap → `controller.goToPage(i)`), **Notes** (speaker notes of `controller.currentPage`),
     **Comments** (for current slide; empty state), **My notes** (multi-line text field for current slide). Thumbnails rendered off-thread, small (≈240px wide), cached.
   - `extraActions`: overflow menu with "Open in another app" (`openExternally`) and "Share" (`shareFiles`), and "Export slide notes" (writes a `.txt` next to the file
     containing per-slide speaker notes + my notes, then toast `saved_to`).
   - Loading state while parsing (centered progress + file name); error state (message + "Open in another app") for corrupt/unsupported (e.g. `.ppt`).

## Acceptance criteria
1. A normal university deck (title slide, bullet slides, images, a table, theme background) opens and looks close to PowerPoint: positions, sizes, colours, bullets, images.
2. Arabic slide text renders correctly shaped and right-to-left; mixed Arabic/English lines are correct.
3. Placeholder inheritance works (titles positioned from layout when slide has no xfrm).
4. Speaker notes and comments (legacy + modern) are visible per current slide; "My notes" persist after closing/reopening.
5. Drawing on slides works and persists (handled by scaffold — you must pass the right `inkFile` and a correct `PageSource`).
6. Works in landscape/portrait/split-screen; thumbnails don't freeze the UI; corrupt file → error state, no crash.
7. All strings localized (en + ar). Code compiles.

## Test data
Generate your own test decks with python-pptx (installed: `python -c "import pptx"`) into `docs/testdata/` (e.g. English + Arabic deck with notes, image, table)
and parse them in a quick JVM-free sanity check by reading your code paths carefully; the lead will run them on the emulator.
