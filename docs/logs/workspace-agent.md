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
- 2026-10-04 — Read round2b/round2/AGENT_RULES/DESIGN/SPEC and all owned + dependent code; wrote Understanding/Plan.
- `data/Storage.kt`: import helpers `uniqueNamed`, `copyIn` (64 KB buffered, cancellable, removes partial file), `nameWithExt`,
  `treeName`, `treeRootDoc`, `listTree` (DocumentsContract walk, hidden entries skipped, depth-capped), `canDelete`
  (FLAG_SUPPORTS_DELETE), `deleteDocument`, `makeImportedFolder` (default FolderMeta), `writeText`. Existing API untouched.
- `ui/workspace/Import.kt` (new): `Importer` (process scope, IO, observable progress, flag-based cancel that keeps partial results,
  one import at a time, dialogs scoped to the window that started it) + `ImportHost(window)` (Copy/Move chooser with Move disabled
  when the provider can't delete, progress with counts/current name/cancel, report of failed / not-removed items).
- `ui/workspace/Stack.kt` (new): `EntryStack` (keyed back stack shared by panes and windows; saveable UI state kept for covered
  entries, dropped on pop) + `EntryStackContent` + `PaneBackOwner`.
- `ui/workspace/Split.kt` (new): `SplitPane`, `SplitController`, `SplitRegistry`, `HostStacks`, `SplitWorkspace` UI.
- `ui/workspace/DragDrop.kt` (new): `DropData` (contract), `startFileDrag` + drag shadow, `Modifier.fileItemGestures`,
  `Modifier.openDropTarget`.
- `ui/workspace/Windows.kt` (new): `WindowNav`, `EXTRA_OPEN_PATH`; `WindowActivity.kt` (new, package `com.daftar.app`).
- `ui/workspace/Workspace.kt`: real `openSideBySide` / `openInNewWindow`; `rememberSplitPicker` kept (starts in the file's folder).
- `ui/WebScreen.kt`: full in-app browser; `SplitScreen` delegates to `SplitWorkspace`.
- `MainActivity.kt`: dispatchTouchEvent focus reset, focus reclaim from other windows on resume/focus, Convert destination,
  phone More sheet, `DaftarBrand` sidebar header (lead's request), sidebar hidden < 400dp or while split, VIEW/SEND/SEND_MULTIPLE
  through the Importer (Inbox, progress), shared plain text → link opens in browser / text saved as .txt.
- `ui/LibraryParts.kt`: redesigned `FileBadge` (+ optional `ext`) and `FileTile`, `typeLabel`, drag gestures on FileTile /
  FolderTile / EntryRow, long-press menu "Open side by side" + "Open in new window", create sheet "New whiteboard" + "Import
  folder", Files/Folder import sheet, quick keys `whiteboard`, `import`, `import_files`, `import_folder` (lead's request), public
  `newWhiteboard(ctx, dir)`, results open in the screen's own pane (`LocalPaneNav`), `SheetItem` overload with a description.
- `ui/Nav.kt`: `PaneNav.screens` + `PaneNav.popTo(screen)` (default implementations, non-breaking), implemented by RootPaneNav,
  split panes and windows.
- `AndroidManifest.xml`: `WindowActivity` (standard, resizeable, not exported, autoRemoveFromRecents, same configChanges) and a
  SEND_MULTIPLE filter (documents, images, text).
- `res/values{,-ar}/strings_workspace.xml`: all strings incl. Arabic plurals (zero/one/two/few/many/other).
- Compiled with the shared lock many times: **0 errors in my files**. The build still fails only on other agents' in-progress
  files (convert/*, ink/*, pdf/*, word/WordScreen.kt — mostly string resources they haven't added yet).

## Decisions & limits
- **Closing a pane keeps the other pane alive.** Instead of replacing the `Screen.Split` entry with the remaining pane's screen
  (which would recreate that viewer: page/zoom/scroll lost, and a note could be re-read before its pending save lands), the split
  switches to single-pane mode: the remaining pane fills the workspace without border/divider, `inPane` becomes false, its back
  stack keeps working; back at its root removes the Split from the host stack (`Nav.activePane` → host). "Open side by side" from
  that pane re-opens the second pane. Visually identical to "the other pane becomes full screen".
- **"Replace the non-active pane"** pushes the new file onto that pane's stack (back in that pane returns to what it showed).
  When invoked from a Library pane, the long-pressed file also opens in the focused pane so both files end up side by side.
- **Pane back handling:** each pane gets its own `OnBackPressedDispatcher` via `LocalOnBackPressedDispatcherOwner`; the split's
  BackHandler forwards system back to the active pane's dispatcher (screen BackHandlers such as the browser history or Word's find
  bar) and otherwise calls `pane.back()`. So screen BackHandlers in the non-focused pane never steal back presses.
- **Focus:** MainActivity resets `Nav.activePane` only on ACTION_DOWN (not on every move) and pane pointer input only reacts to
  downs, so focus writes happen once per touch. MainActivity does NOT reset focus on its own window-focus changes (dialogs closing
  would wrongly steal focus from a pane); it only takes focus back when it was held by another Daftar window.
- **Drag & drop:** long-press then release = menu; long-press then move (beyond touch slop) = platform drag (ClipData text
  `daftar:file:<path>` + FileProvider URI item, DRAG_FLAG_GLOBAL | GLOBAL_URI_READ, localState = source pane). Panes and windows
  are drop targets that open a dropped file (or http link → in-app browser) there; drops from the same pane are ignored; deeper
  targets (e.g. a note editor inserting links/images) win automatically (Compose gives the drop to the deepest target).
- **Move:** for a folder, when every file copied the picked folder is deleted in one `deleteDocument` call; otherwise only the
  copied files are deleted one by one and failures are listed. Hidden entries (".DS_Store", ".nomedia"…) are not imported (they
  would clash with the library's hidden sidecar naming).
- **Browser:** direct video links (.mp4/.webm/.m4v/.mov/.3gp/.ogv) load in a tiny HTML `<video controls>` page so fullscreen goes
  through `onShowCustomView`; fullscreen view is added to the activity decor view with system bars hidden (back exits it). Dark
  mode: WebView created with a dark `ContextThemeWrapper` (isLightTheme=false) + `isAlgorithmicDarkeningAllowed` on 33+,
  `forceDark` on 29–32. Typed text that isn't an address becomes a Google search. Compact width moves forward/reload/open/share
  into an overflow menu.
- **Split layout:** BoxWithConstraints (width class per pane via `derivedStateOf`, only changes at thresholds) + one
  multi-content `Layout`; fraction/orientation/swap are read in the measure block (relayout only). The split pads for system bars
  itself (consumed, so viewers' top bars don't add status-bar space in the lower pane).
- Import dialogs are global state but shown only by the window that started the import.

## Requests to lead
1. `ui/Screens.kt` LibraryScreen breadcrumbs use `Nav.stack`/`Nav.current` and loop `pane.back()`, which misbehaves inside a split
   pane or window (it can close the pane). Replace the non-root branch with the new pane-aware API:
   ```kotlin
   else { if (!pane.popTo(Screen.Library(f.absolutePath))) pane.push(Screen.Library(f.absolutePath)) }
   ```
   and the root branch `Nav.tab(Screen.Library(root))` with
   `if (LocalPaneNav.current.inPane) pane.popTo(Screen.Library(Storage.root.absolutePath)) || run { pane.push(Screen.Library(Storage.root.absolutePath)); true } else Nav.tab(...)`
   (capture `val inPane = LocalPaneNav.current.inPane` outside the lambda).
2. Planner (planner-agent) still calls `Nav.push(Screen.EditEvent(...))` in PlannerScreen/WeekView/EditEvent; inside a pane or
   window this opens on the main window. Should be `pane.push(...)`.
3. NotesScreen cards use their own `combinedClickableCompat`; to make notes draggable to the other pane like library files, use
   `Modifier.fileItemGestures(n.e.file, onClick = { pane.open(ctx, n.e.file) }, onLongPress = { actions.menu(n.e) })`
   (`com.daftar.app.ui.workspace.fileItemGestures`).
4. Notes-agent / drop targets: the drag source sets `View.startDragAndDrop(..., localState = <source PaneNav>, ...)`; ClipData item 0
   is `daftar:file:<path>`, item 1 (files only) a FileProvider content URI. `DropData.fileOf(text)` / `DropData.urlOf(text)` in
   `ui/workspace/DragDrop.kt` parse it.

## Self-check
1. Split workspace — **PASS**: two panes with own stacks (`SplitPane : PaneNav`, inPane), `LocalPaneNav` per pane, Initial-pass
   pointer-down focus (not consumed), 2dp accent outline; one custom multi-content Layout (switch/swap/resize never recompose
   pane content); divider 24dp touch target, 25 % min, double-tap 50/50, grip pill menu (Swap, Side by side/Stacked, Equal sizes,
   Open file left/right|top/bottom via LibraryFilePickerDialog, Close each pane; names follow RTL); back pops active pane first
   (incl. its screen BackHandlers), back at root/Close closes a pane (other keeps state, see Decisions), activePane → host when the
   split leaves; per-pane `LocalWidthClass`; sidebar hidden; MainActivity.dispatchTouchEvent; `openSideBySide` +
   `rememberSplitPicker`. Pane drop target opens dropped files. Not device-tested (no emulator).
2. Separate windows — **PASS**: `WindowActivity` (standard, resizeable, not exported, same configChanges, autoRemoveFromRecents),
   NEW_DOCUMENT|MULTIPLE_TASK|LAUNCH_ADJACENT(+NEW_TASK), `daftar.open_path`, own `WindowNav` stack, DaftarTheme + own width class,
   back at root = finish(), focus on touch/resume/window focus, reset on destroy, task label = file name (follows navigation),
   multiple windows coexist, main Nav untouched; drop a file onto a window to open it there. Not device-tested.
3. Import copy/move — **PASS**: Files (OpenMultipleDocuments) / Folder (OpenDocumentTree, recursive, structure + empty folders
   kept, default FolderMeta), DocumentsContract only, Copy/Move dialog, move = copy + deleteDocument with report, progress dialog
   (counts, current name, cancel) on IO, "(2)" names, SEND_MULTIPLE + text shares, Home `quick("import")` and create sheet reach
   the choice; lead's `whiteboard` / `import_folder` keys + `newWhiteboard`.
4. In-app browser — **PASS**: JS + DOM storage, progress bar, back/forward/reload|stop, editable URL, open externally, share;
   YouTube/Vimeo pages and direct MP4 play inside with fullscreen; file:// and content:// blocked; mailto:/intent:/market:/tel: to
   the system (intent: with browser_fallback_url); back = history first; algorithmic darkening 33+ (forceDark 29–32); WebView
   destroyed in `onRelease`; works in panes (own back dispatcher, compact top bar).
5. Shell — **PASS**: Convert in sidebar (Transform icon), phone bar Home·Files·Notes·Planner·More (sheet: Search, Convert,
   Settings), `DaftarBrand(28.dp)` header (lead switched from DaftarLogo+Text), sidebar hidden < 400dp or while split.
6. Library — **PASS**: long-press menu side-by-side + new window, create sheet New whiteboard + Import folder, files/folders draggable
   (long-press-and-move; plain long-press still opens the menu), redesigned FileTile/FileBadge (tinted square, outlined document
   with folded corner, coloured type label PDF/DOCX/PPTX/TXT/MD/NOTE…, colours from `kindColor`), FolderGlyph unchanged.
7. Storage helpers — **PASS**: recursive import with unique names + SAF helpers; existing public functions unchanged.
