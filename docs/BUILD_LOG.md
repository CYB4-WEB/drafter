# Daftar — Master build log (lead)

## 2026-10-04
- Evaluated requirements → `docs/SPEC.md` (upgrades: course template, home dashboard, share-to-Daftar inbox, audio-synced ink replay, search/pin, planner links, daily summary).
- Target device clarified: "Samsung S11 Ultra" = Galaxy Tab S11 Ultra (no phone exists with that name).
- Stack chosen: native Kotlin + Jetpack Compose, custom ink engine (Canvas), PdfRenderer + PDFBox-android, own PPTX/DOCX parsers, ML Kit Digital Ink,
  Android SpeechRecognizer (phone's engine), AlarmManager reminders, RemoteViews widgets. Release = R8 minified.
- Design: `docs/DESIGN.md`, updated to the user's mockup (logo, palette #F7F6F2/#E9E7E1/#1F2937/#6B7280/#3B82F6, labelled sidebar, folder tiles, recent rows, round FAB).
- Foundation by lead: Gradle (AGP 8.13, Kotlin 2.1.21), manifest, theme, Nav, Common UI, Storage, Prefs, ink model + InkRender, PageSource/EditorController contracts,
  stubs for every agent area. Base compiles.
- Emulator AVD `TabS11` (2960×1848 @320dpi, API 35) created & booting.
- Agents launched in parallel with briefs in `docs/briefs/` and rules in `docs/AGENT_RULES.md`:
  slides-agent (PPTX), pdf-agent (PDF + tools), planner-agent (planner, reminders, notifications, widgets), docs-agent (DOCX + images).
- Lead builds in parallel: ink engine (InkView), editor (tools, S Pen, lasso, shapes, text, images, audio record/replay, dictation, handwriting→text),
  Home, Files, Notes, Search, Settings, folder dialogs.

## 2026-10-04 (round 2, resumed in the cloud)
- Round 2 was cut off by the usage limit before any agent wrote code (verified from the uploaded agent transcripts: only stubs + the
  note data model existed). Code moved to GitHub (`cyb4-web/drafter`); build output and placeholder files removed, `.gitignore` added.
- Linux build box: Android SDK 36 installed at /opt/android-sdk; `tools/compile.sh` serializes Gradle between parallel agents
  (4 CPU / 15 GB); Kotlin compiles in-process to save RAM.
- Lead contracts before relaunch: PaneNav.replace + screenFor() + observable active pane; Kind.TEXT (txt/md/rtf/csv/log) and .doc → Word viewer;
  EditorController zoom API + addImage; editor start/bottom panels + floating zoom pill; ViewerActions (share/convert/side-by-side/new window/
  open with); LibraryFilePickerDialog; Workspace contract; Prefs text scale / larger buttons / links in app / keep screen on (theme scales all sp);
  calendar permissions + text mime types; brand logo (DaftarLogo + adaptive launcher icon from the user's SVG) and icon-sheet file colours.
- Agents relaunched with `docs/briefs/round2b.md`: notes, workspace, convert, slides, docs, pdf, planner.
- Lead: Settings (text size, larger buttons, keep screen on, links in app, clear cache), Home quick actions (whiteboard, convert),
  Notes screen (whiteboard entry, cached summaries).
