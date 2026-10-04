# Rules for every build agent (read fully before writing code)

Project: **Daftar (دفتر)** — Android study app for students on Samsung Galaxy Tab S11 Ultra (also phones).
Root: `C:\Users\am204\AndroidStudioProjects\Daftar`. Kotlin + Jetpack Compose (Material3), single module `:app`, package `com.daftar.app`.
Read first: `docs/DESIGN.md` (visual rules — mandatory), `docs/SPEC.md` (product scope), then your own brief in `docs/briefs/`.

## You BUILD working features, not designs
Your output is real, compiling, working Kotlin code that a student can use today. No TODOs, no placeholder screens,
no "would be implemented later", no fake data. If something is genuinely impossible on Android, implement the best
real alternative and write why in your log.

## File ownership (strict)
- Only create/edit files listed under "You own" in your brief. Other agents work in parallel in the same tree.
- Shared files are **read-only** for you: `MainActivity.kt`, `DaftarApp.kt`, `ui/Nav.kt`, `ui/Common.kt`, `ui/theme/Theme.kt`,
  `data/*`, `ink/*`, `AndroidManifest.xml`, `app/build.gradle.kts`, `res/values*/strings.xml`.
  If you need a change there, do NOT edit it — write the exact request in your log under "## Requests to lead".
- Your stub file(s) already exist with the exact public signatures other code calls. **Keep those signatures.**
- Strings: put yours in `res/values/strings_<area>.xml` AND `res/values-ar/strings_<area>.xml` (both, same keys, real Arabic).
  Prefix keys with your area (`slides_`, `word_`, `planner_`, `pdf_`) to avoid clashes. Every user-visible string must be a resource.

## Available shared API (use, don't duplicate)
- Theme: `DaftarTheme`, tokens via `D.c.bg/surface/surfaceAlt/line/ink/muted/accent/danger`, `folderColor(i)`, `FolderPalette`.
- `ui/Common.kt`: `ViewerTopBar`, `SectionTitle`, `EmptyState`, `Chip`, `TextInputDialog`, `ConfirmDialog`, `Modifier.card(D.c)`,
  `LocalWidthClass` (Compact/Medium/Expanded), `kindIcon`, `kindColor`, `studyIcon`.
- `ui/Nav.kt`: `Nav.push/pop/tab/open(ctx,file)`, `Screen.*`, `openExternally`, `shareFiles`, `uriFor`, `toast`, deep-link consts.
- `data/Storage.kt`: library root, `uniqueFile`, `sidecar(file, suffix)`, `touch()`, `opened(file)`, `cacheDir()`.
- `data/Prefs.kt`: settings (read; write via `putX`).
- `ink/*`: `InkEditorScaffold(...)` (full drawing editor with tools), `PageSource`, `EditorController`, `InkDoc/InkPage/Stroke/TextItem/ImageItem`.
  Ink layer for a document file F is stored at `Storage.sidecar(F, "ink.json")`.
- Libraries available: Compose BOM 2025.06, material3, material-icons-extended (`Icons.Rounded.*`, AutoMirrored for directional),
  kotlinx-serialization-json (`com.daftar.app.data.json`), coroutines, `com.tom-roush:pdfbox-android:2.0.27.0` (already initialised in DaftarApp),
  ML Kit digital ink, AndroidX core/appcompat. Do not add dependencies; request them from the lead if essential.

## Quality bar
- Must work in landscape, portrait, split-screen and pop-up windows (no orientation assumptions; use `LocalWidthClass` / BoxWithConstraints).
- Arabic + RTL: use start/end, AutoMirrored icons, and let Compose resolve text direction from content (Arabic files must render right-to-left).
- Heavy work (parsing, rendering, file IO) off the main thread (`Dispatchers.IO` / `Default`); show progress for > 300ms.
- Handle bad/corrupt files gracefully: an error state with "Open in another app" (`openExternally`), never crash.
- Respect DESIGN.md: flat surfaces, 1dp `line` borders, 16dp radius cards, no gradients, no shadows, accent only for actions/selection.
- Memory: recycle/limit bitmaps; downsample large images.

## Build & verify
- Compile with: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:compileDebugKotlin --console=plain -q` (bash, from root).
  Other agents compile too; if a failure is ONLY in files you don't own, note it and continue — don't touch them.
- Fix all errors in your files. Warnings are fine.
- Do not run the emulator or install the app — the lead does integration testing.

## Log (mandatory) — `docs/logs/<your-agent-name>.md`
Append as you go (not only at the end):
1. `## Understanding` — restate in your own words what you must build and the acceptance criteria (before coding).
2. `## Plan` — files + approach.
3. `## Progress` — timestamped entries of what was done.
4. `## Decisions & limits` — trade-offs, anything not fully possible and the alternative chosen.
5. `## Requests to lead` — exact changes needed in shared files (code snippets), or "none".
6. `## Self-check` — go through every acceptance criterion in your brief: PASS / PARTIAL (why) / FAIL.
Finish with a short summary as your final message (files created, criteria status, requests).
