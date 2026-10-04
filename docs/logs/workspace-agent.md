# workspace-agent log

# Round 2

## Understanding
I own the app shell and the "work with several files" features. What I must deliver (round2b overrides round2):

1. **Split workspace** — `Screen.Split(first, second, vertical)` rendered by `SplitScreen`. Two panes, each with its own back
   stack implementing `PaneNav` (`inPane = true`), provided through `LocalPaneNav`; pointer-down in a pane (Initial pass, never
   consumed) makes it `Nav.activePane`; the active pane has a 2dp accent outline. One custom `Layout` so side-by-side ↔ stacked,
   swap and resize only re-place the panes (no recomposition of their content: scroll, zoom, ink survive). Divider: 24dp touch
   target, panes ≥ 25 %, double-tap = 50/50, centred grip pill → menu (Swap, Side by side / Stacked, Open file here… per pane via
   `LibraryFilePickerDialog`, Close each pane). Back at a pane root / Close closes that pane; a split-level BackHandler pops the
   active pane first; when the split goes away `Nav.activePane` returns to the host. Each pane gets `LocalWidthClass` from its
   own width. App sidebar hidden while a split shows. `MainActivity.dispatchTouchEvent` resets `Nav.activePane = RootPaneNav`
   before Compose dispatch. `Workspace.openSideBySide`: already split → the non-active pane shows the new file; else push a Split.
2. **Separate windows** — new `WindowActivity` (standard, resizeable, not exported, same configChanges) started with
   NEW_DOCUMENT | MULTIPLE_TASK | LAUNCH_ADJACENT and extra `daftar.open_path`; own PaneNav stack, DaftarTheme, own width class;
   back at root = finish(); sets `Nav.activePane` on touch / resume / focus, resets on destroy; task label = file name. Several
   windows coexist; main window Nav untouched. `Workspace.openInNewWindow` launches it.
3. **Import files & folders, copy or move** (R2.2) — Import → "Files" (OpenMultipleDocuments) or "Folder" (OpenDocumentTree,
   recursive, structure kept, folders get default FolderMeta) using DocumentsContract only; then Copy / Move dialog (move = copy +
   `DocumentsContract.deleteDocument`, report what could not be removed); progress dialog (counts, current name, cancel) on IO;
   clashes → "name (2)". ACTION_SEND_MULTIPLE + text mime types accepted. Home's `actions.quick("import", dir)` and the create sheet
   reach the Files/Folder choice. Lead addition: `quick("whiteboard")`, `quick("import_folder")`, public `newWhiteboard(ctx, dir)`.
4. **In-app browser** `WebScreen(url)` — WebView (JS, DOM storage), progress bar, top bar (back/forward/reload, editable URL,
   open externally, share); YouTube/Vimeo/MP4 inside with fullscreen via `onShowCustomView`; file:// blocked; mailto:/intent:/
   market:… handed to the system; back = history first, then `pane.back()`; algorithmic darkening on 33+ in dark theme; WebView
   destroyed on dispose; works inside a split pane.
5. **Shell** — Convert destination (Icons.Rounded.Transform, `R.string.convert`, `Screen.Convert()`); phone bar Home · Files ·
   Notes · Planner · More (sheet: Search, Convert, Settings); sidebar header `DaftarLogo(28.dp)` + app name; sidebar hidden when
   the window is < 400dp wide or a split shows.
6. **Library** — long-press menu: "Open side by side" (rememberSplitPicker) + "Open in new window"; create sheet: "New whiteboard",
   "Import folder"; files draggable (ClipData text `daftar:file:<path>`, DRAG_FLAG_GLOBAL | GLOBAL_URI_READ) while a plain
   long-press still opens the menu; redesigned `FileTile`/`FileBadge` (tinted rounded square, outlined document glyph, coloured
   type label PDF/DOCX/PPTX/TXT/MD/NOTE from `kindColor`); `FolderGlyph` unchanged.
7. **Storage helpers** — recursive import with unique names; existing public functions unchanged.

Acceptance (round2): two different files side by side and both usable; divider resizes smoothly; stacked mode; swap/close; back
per pane; new window opens a file in a second Samsung window; folder import copy & move with progress; web links & videos in-app;
Convert tab present.

## Plan
- `ui/workspace/Split.kt` (new): `SplitController` (two `SplitPane`s, orientation, swap, fraction, active pane, single-pane state),
  `SplitPane : PaneNav` (stack of keyed entries, own `OnBackPressedDispatcher` so screen BackHandlers act per pane), registry keyed
  by the `Screen.Split` instance, `SplitScreen` UI = BoxWithConstraints (width class per pane via derivedStateOf) + one
  multi-content `Layout` (pane A, pane B, divider; composition order fixed, only placement changes), divider gestures + menu.
- `ui/WebScreen.kt`: real browser; `SplitScreen` stub delegates to `workspace.SplitWorkspace`.
- `ui/workspace/Windows.kt` (new): `WindowNav` + `WindowActivity` (in `com.daftar.app` package file `WindowActivity.kt`).
- `ui/workspace/Workspace.kt`: real `openSideBySide` / `openInNewWindow`, `rememberSplitPicker` kept.
- `ui/workspace/Import.kt` (new): `Importer` (process-scope coroutine on IO, observable progress, cancel, result report) +
  `ImportHost()` dialogs (progress, report) shown by MainActivity and WindowActivity; Copy/Move chooser dialog.
- `ui/workspace/DragDrop.kt` (new): `Modifier.fileDragSource(file, onLongPress)` (long-press-and-move starts a platform drag,
  long-press-and-release opens the menu), drag shadow, `daftar:file:` parsing, pane/window drop target (opens the file there).
- `data/Storage.kt`: `uniqueNamed`, `copyIn` (cancellable stream copy), `listTree` (DocumentsContract walk), `canDelete`,
  `deleteDocument`, `importFolderDir`; existing API untouched.
- `MainActivity.kt`: dispatchTouchEvent, Convert + More sheet, logo header, sidebar rules, ACTION_SEND_MULTIPLE / shared text via
  Importer, ImportHost.
- `ui/LibraryParts.kt`: redesigned badge/tile, drag sources, menu + create-sheet entries, Files/Folder choice, quick keys.
- `AndroidManifest.xml`: WindowActivity, SEND_MULTIPLE filter.
- `res/values/strings_workspace.xml` + `res/values-ar/strings_workspace.xml`.

## Progress
