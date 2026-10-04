# Round 2 (resumed) — shared brief for every agent

Round 2 was cut off by a usage limit before any agent wrote code. This brief replaces `round2.md` where they differ
(new machine, new compile command, shared API added by the lead). Read `round2.md` too for the original wording.

## Environment (Linux cloud box — NOT the Windows path in older docs)
- Project root: `/home/user/drafter` (git repo, branch `claude/sleepy-feynman-suvcts`). Android SDK at `/opt/android-sdk`.
- **Compile:** `tools/compile.sh` (from the root). It serializes builds between agents with a lock (one Gradle at a time,
  4 CPUs / 15 GB RAM shared by everyone), prints errors, and ends with `BUILD OK` / `BUILD FAILED`.
  Waiting for the lock is normal. Do NOT run `./gradlew` directly, do NOT run `gradlew --stop` or `clean`.
  Compile when you have a meaningful batch of changes (not after every line).
  If errors are only in files another agent owns, note it and continue; your files must be clean.
- **Git:** do NOT commit, push, stash, checkout, reset or revert. The lead commits. Never touch files outside your ownership.
- No emulator here. No network installs of new dependencies — if you truly need one, request it in your log.
- python3 is available for checking generated files (zip structure, XML well-formedness).

## Ownership (strict)
| Owner | Owns |
|---|---|
| lead | `ui/Screens.kt`, `ui/Common.kt`, `ui/theme/**`, `ui/ViewerActions.kt`, `ui/FilePicker.kt`, `data/Prefs.kt`, `res/values*/strings.xml`, launcher/logo resources, `app/build.gradle.kts`, docs (except your log) |
| notes-agent | `ink/**`, `res/values*/strings_ink.xml` |
| workspace-agent | `MainActivity.kt`, `ui/Nav.kt`, `ui/WebScreen.kt`, `ui/workspace/**` (new files OK; keep `Workspace.kt` signatures), `ui/LibraryParts.kt`, `data/Storage.kt`, `AndroidManifest.xml`, new activities, `res/values*/strings_workspace.xml` |
| convert-agent | `convert/**`, `res/values*/strings_convert.xml` |
| slides-agent | `slides/**`, `res/values*/strings_slides.xml` |
| docs-agent | `word/**`, `res/values*/strings_word.xml` |
| pdf-agent | `pdf/**`, `res/values*/strings_pdf.xml` |
| planner-agent | `planner/**`, `widget/**`, widget res (`res/layout/widget_*`, `res/xml/widget_*`, `res/drawable/widget_*`), `res/values*/strings_planner.xml` |

Need a change in a file you don't own? Write the exact snippet under "## Requests to lead" in your log. Work around it meanwhile.

## Shared API added by the lead (use it — don't duplicate)
- **Navigation inside panes:** never call `Nav.pop()/Nav.open()/Nav.replace()` from screens. Use `pane.back()`, `pane.open(ctx, file)`,
  `pane.push(screen)`, `pane.replace(screen)` (`com.daftar.app.ui.pane`). `LocalPaneNav.current.inPane` = shown inside a split pane.
  `screenFor(file): Screen?` maps a file to its viewer screen; `Screen.file` gives a viewer screen's file.
- **Kinds:** `Kind.TEXT` (txt, md, markdown, rtf, csv, tsv, log) and `.doc` (Kind.DOCX) both open `Screen.Word(path)`.
- **Viewer actions** (`ui/ViewerActions.kt`): `val actions = rememberViewerActions(file)` (hosts the convert sheet + split picker),
  `ConvertButton(actions)` for the header, and `ViewerMenuItems(actions, close = { menu = false }, onShare = null)` inside your
  DropdownMenu → Share, Convert, Open side by side, Open in new window, Open in another app. Every viewer must offer these.
- **Zoom pill:** `ZoomControls(percent, onOut, onIn, onFit, modifier)` in `ui/Common.kt` — same look in every viewer (bottom-start, 16dp).
- **Ink editor** (`InkEditorScaffold`): new params `sidePanelAtStart`, `sidePanelOpen`, `bottomPanel`, `bottomPanelLabel`.
  `EditorController` now has `zoomPercent`, `zoomIn()`, `zoomOut()`, `zoomFit()`, `addImage(bitmap)`. The editor already shows
  the zoom pill for PDF/slides/notes — viewers built on the scaffold get zoom for free.
- **Library file picker:** `LibraryFilePickerDialog(title, accept = { f -> … }, multiple, onDismiss, start = Storage.root) { files -> }`.
- **Workspace contract** (`ui/workspace/Workspace.kt`): `Workspace.openSideBySide(ctx, first, second)`, `Workspace.openInNewWindow(ctx, file)`,
  `rememberSplitPicker(): (File) -> Unit`.
- **Prefs:** `Prefs.textScale` (applied globally via density — just use typography/sp), `Prefs.largeControls`, `Prefs.linksInApp`,
  `Prefs.keepScreenOn`.
- **Drag & drop contract** (between panes / windows): drag sources put a `ClipData` whose first item is plain text
  `daftar:file:<absolute path>` (library file) or a URL / plain text, and use `View.DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`
  so it also works across Samsung windows. Drop targets accept: `daftar:file:` (image files → inserted as image, others → link),
  `http(s)://` (link), other text (text box), content URIs of images.

## Quality bar (unchanged, plus)
- Real, working, compiling code. No TODOs or placeholders. Arabic + English strings (real Arabic) for every user-visible text.
- Works in landscape/portrait, split screen, Samsung pop-up windows, and **inside a split pane** (narrow width: collapse panels).
- Low memory: bitmap caches bounded by bytes (≤ 1/8 of `Runtime.getRuntime().maxMemory()` per screen), recycle on dispose,
  decode with sampling. Heavy work off the main thread with progress and cancel.
- Text sizes: typography / sp only (the global text-size setting scales them).
- Design: DESIGN.md — flat surfaces, 1dp line borders, 16dp cards, accent only for actions/selection, no gradients/shadows.
  File type colours (new, from the user's icon sheet): PDF #EF4444, Word #3B82F6, PowerPoint #F97316, Text #10B981.

## Log
`docs/logs/<agent>.md`, new heading `# Round 2`: Understanding, Plan, Progress, Decisions & limits, Requests to lead, Self-check
(every acceptance criterion PASS / PARTIAL / FAIL with reason). Final message: files changed, criteria status, requests.
