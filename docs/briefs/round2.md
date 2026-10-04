# Round 2 briefs (read with AGENT_RULES.md, SPEC.md "Round 2", DESIGN.md)

## Rules that changed for Round 2 (all agents)
- **Navigation:** never call `Nav.pop()` / `Nav.open()` from screens. Use `pane.back()`, `pane.open(ctx, file)`, `pane.push(screen)`
  (`com.daftar.app.ui.pane`). This makes every viewer work inside the split workspace. `LocalPaneNav.current.inPane` tells you if you are in a pane.
- **Every viewer header** (Word, PowerPoint, PDF, image) must have: Share, Convert (opens `com.daftar.app.convert.ConvertSheet(file) { }`),
  Open in another app, and **zoom controls** (− / % / + / fit) in addition to pinch. Keep `ViewerTopBar` design; look "native" per type (below), Daftar styling.
- **Low memory:** bitmap caches bounded (LRU by bytes, ≤ 1/8 of `Runtime.maxMemory()` total per screen), release bitmaps on dispose, decode with sampling.
- **Text size:** use MaterialTheme typography / sp units only — the lead adds a global UI text-size setting that scales them.
- Ownership table below is strict. Log in your same log file under a new heading `# Round 2`, same sections (Understanding, Plan, Progress, Decisions, Requests, Self-check).

| Owner | Owns in Round 2 |
|---|---|
| lead | `ink/**`, `ui/Screens.kt`, `ui/Common.kt`, `ui/theme/**`, `data/Prefs.kt`, `res/values*/strings.xml`, `strings_ink.xml` |
| workspace-agent | `MainActivity.kt`, `ui/Nav.kt`, `ui/WebScreen.kt`, `ui/workspace/**`, `ui/LibraryParts.kt`, `data/Storage.kt`, `AndroidManifest.xml`, `strings_workspace.xml` |
| convert-agent | `convert/**`, `strings_convert.xml` |
| slides-agent | `slides/**`, `strings_slides.xml` |
| docs-agent | `word/**`, `strings_word.xml` |
| pdf-agent | `pdf/**`, `strings_pdf.xml` |
| planner-agent | `planner/**`, `widget/**`, widget res, `strings_planner.xml` |

---
## workspace-agent (NEW) — split screen, windows, import, in-app browser, shell
1. **In-app split workspace** (`Screen.Split(first, second, vertical)` → `SplitScreen`): two panes, each hosting any screen via `com.daftar.app.Route(screen)`
   (public in MainActivity). Draggable divider (handle 24dp touch target, min pane 25%, double-tap divider = 50/50), toggle side-by-side ↔ stacked,
   swap panes, close a pane (the other becomes full screen), "Open file here…" per pane (FolderPicker-style file chooser listing library files).
   Each pane has its **own back stack** and provides `LocalPaneNav` (inPane = true) and sets `Nav.activePane` to itself on pointer-down
   (`Modifier.pointerInput(Unit){ awaitPointerEventScope { while(true){ awaitPointerEvent(PointerEventPass.Initial); Nav.activePane = me } } }`);
   when the split closes, `Nav.activePane = RootPaneNav`. Both panes stay fully interactive at the same time (draw on a PDF while reading slides).
   Entry points: in every file's long-press menu "Open side by side" (picks the second file), and a header action in viewers via `pane.push`-compatible API
   — expose `Workspace.openSideBySide(ctx, current: File, other: File)`. Keep pane state when resizing (no recomposition key changes).
   **Drag & drop between panes:** files dragged from the Files list onto a note pane insert a link (use Android `DragAndDropTarget` with `text/uri-list` / ClipData of the path);
   images dragged into a note pane are inserted as images — publish a simple drop API the lead will consume: `object DropBus { val drops: SharedFlow<DropItem> }`
   with `data class DropItem(val target: Any, val file: File?, val text: String?)` and document it in your log.
2. **Separate windows:** "Open in new window" → new task instance (`FLAG_ACTIVITY_NEW_DOCUMENT or FLAG_ACTIVITY_MULTIPLE_TASK or FLAG_ACTIVITY_LAUNCH_ADJACENT`)
   that opens straight into the file (extra `daftar.open_path`); manifest: keep `singleTask` for the launcher but add a second activity alias / `documentLaunchMode="intoExisting"`
   strategy so multiple windows can coexist (test logic carefully; each window has its own Nav stack — make Nav per-activity, e.g. a ViewModel-scoped stack provided via CompositionLocal while
   keeping the `Nav` object API working for the main window).
3. **Import with copy or move:** Import sheet → "Files" (OpenMultipleDocuments) or "Folder" (OpenDocumentTree, recursive copy keeping structure; create folders with default meta);
   then dialog **Copy** / **Move** (move = copy then `DocumentsContract.deleteDocument` / `DocumentFile.delete` when the provider supports it; report items that could not be removed).
   Progress dialog with counts and cancel; runs on IO; name clashes → "(2)". Also accept multiple files shared into the app (ACTION_SEND_MULTIPLE).
4. **In-app browser** `WebScreen(url)`: WebView with JS, top bar (back/forward/reload, URL text, open externally, share). YouTube/Vimeo/MP4 links play inside
   (allow fullscreen video via `WebChromeClient.onShowCustomView`). `file://` not allowed. Destroy WebView on dispose (memory).
5. **Shell:** add a **Convert** destination to the sidebar & phone bar (icon `Icons.Rounded.Transform`, label from strings), route `Screen.Convert`.
   Phone "More" → sheet with Search, Convert, Settings. Keep design. When the activity window is very small (pop-up view < 400dp), hide sidebar.
6. Fix anything in your owned files that the auditor reports (lead will forward).
**Acceptance:** two different files open side by side and both usable; divider resizes smoothly; stacked mode; swap/close; back acts per pane;
new window opens a file in a second Samsung window; import folder copy & move works with progress; web links & videos play in-app; Convert tab present.

## convert-agent (NEW) — Converter hub + per-file convert sheet
- `ConvertScreen(path)`: grid of conversion cards grouped "From PDF / From images / From PowerPoint / From Word / From notes / PDF tools" with clear icons;
  choose source(s) (library file picker; images via photo picker; multiple PDFs for merge), options (pages range, image format & quality, DPI), output folder
  (default = source folder), run with progress + cancel, result row with Open / Share / Show in folder. Recent conversions list (in memory is fine).
- `ConvertSheet(file, onDismiss)`: ModalBottomSheet listing only the conversions valid for that file type, same engine, toasts `saved_to` and offers Open.
- Engines (put in `convert/Engines.kt`, all off main thread, cancellable via coroutine `isActive` checks):
  PDF→images (use `com.daftar.app.pdf.PdfTools.pagesToImages` if suitable), images→PDF (`PdfTools.imagesToPdf`), PDF→PPTX (write a minimal valid PPTX zip yourself: one slide per page with the page rendered as a picture filling the slide, 16:9 or page aspect),
  images→PPTX, PDF→DOCX (text per page via `PdfTools.extractText`, write with `com.daftar.app.word.DocxExport.writeDocx`; page breaks between pages), PPTX→PDF / PPTX→images (`com.daftar.app.slides.SlidesExport`),
  DOCX→PDF (`DocxExport.toPdf`), note→PDF (`com.daftar.app.ink.exportNoteToPdf(InkDoc.load(f)!!, out)`), note→images (render pages with `InkRender.drawPaper + drawPageContent` at 2×),
  merge PDFs, split PDF (by ranges / every N pages), TXT/MD→PDF (paginate text with StaticLayout, RTL aware). Output names: `<name>.pdf`, `<name> (images)/Page 01.png`, unique via `Storage.uniqueFile`.
  If an engine owned by another agent still returns false/empty (stub), show a clear error — don't reimplement theirs.
**Acceptance:** every listed conversion produces a valid file that opens in Daftar and in other apps; hub and sheet both work; progress/cancel; Arabic text survives TXT→PDF and PDF→DOCX.
