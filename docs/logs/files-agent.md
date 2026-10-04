# files-agent

# Round 2

## Understanding
Four features on top of the library: ML Kit document scanner, folder pictures, a 30-day recycle bin, and note version
history. Owned files: `data/Storage.kt`, `data/Versions.kt`, `data/Trash.kt` (new), `ui/LibraryParts.kt`, `ui/TrashScreen.kt`,
`ui/files/**` (new), `res/values*/strings_files.xml`. Small hooks in the lead's `ui/Screens.kt` are listed below.

## Plan / Progress (all done)
1. **Scanner**: `ui/files/Scanner.kt`. `GmsDocumentScanning` (SCANNER_MODE_FULL, gallery import, page limit 50, PDF + JPEG)
   started from the Activity (`getStartScanIntent(activity)`, then `StartIntentSenderForResult` with `IntentSenderRequest`).
   The PDF is copied on IO into the target folder as "Scan 4 Oct 15.30.pdf" (unique name), then opened in the same pane.
   If there is no PDF it is built from the page JPEGs (`PdfTools.imagesToPdf`). The target folder is `rememberSaveable`
   so it survives process death while the scanner is open. Errors are shown as toasts: no Play services
   (`GoogleApiAvailability`), module still downloading (`MlKitException.UNAVAILABLE`), unsupported, and any other failure
   with its message. How you start it: `actions.quick("scan", dir)`. With dir = null it asks with FolderPickerDialog first.
   Also in the "New…" sheet.
2. **Folder pictures**: `ui/files/Covers.kt`. In FolderDialog (Appearance / New folder) there is a "Picture" section:
   photo picker, then Wide 16:9 / Square chips, a preview with the colour accent and icon chip, Change and Remove picture.
   Decoding is sampled, EXIF orientation is applied, the image is centre-cropped and scaled to ≤ 512 px, then saved as JPEG 85
   to `<folder>/.cover.jpg` (temp file + rename) on a background executor. Tiles get a 16:9 picture header, a 3dp
   folder-colour line and a folder-colour icon chip overlapping the edge. Rows (`FolderThumb`) get a rounded thumbnail with
   a folder-colour ring. Thumbnails sit in an `LruCache` sized by bytes (≤ 1/32 heap, max 12 MB, RGB_565), keyed by
   path+mtime+size and decoded on `Dispatchers.IO`. `peek()` lets a tile show its picture straight away when it recomposes.
3. **Recycle bin**: `data/Trash.kt`. Bin location: `<external app files>/.trash/<id>/data/<name>` (+ sidecars) and
   `.../versions/`. It is outside `Library/`, so search, stats, pickers and Notes never see it. It is on the same volume,
   so delete and restore are renames. `index.json` holds the id, name, original path, deleted time, kind and pinned paths.
   `Storage.delete(f)` now moves to the bin and returns the `TrashItem` (or null, in which case nothing was deleted). It
   removes recents and pins. `Storage.deleteNow(f)` is new and deletes for good. Restore puts the item back in the original
   folder (recreated if missing), with a unique name on a clash, moves the sidecars and version history back, and re-pins.
   An auto-purge of items older than 30 days runs once per app start on a daemon thread from `Storage.init`, which also
   cleans stray entries from crashes. TrashScreen shows a type badge or folder glyph, the name, "Kind · original folder"
   and "Deleted 3 days ago · 27 days left" (red when ≤ 3 days, Arabic plurals). Actions: Restore (icon and menu), Delete
   forever (confirm), Empty bin (confirm). The delete confirmation now reads "Move to recycle bin". After a delete, an Undo
   bar (non-focusable Popup, bottom centre, 6 s) appears in ActionsHost.
4. **Version history**: `data/Versions.kt` + `ui/files/VersionHistory.kt`. Snapshots are gzip copies of the note JSON in
   `<external app files>/.versions/<path relative to Library>/v<created>_<bytes>.gz`. That folder mirrors the library tree,
   so `Storage.rename` and `Storage.move` call `Versions.moved(from, to)` and one directory rename covers notes and whole
   folders. Deleting a note or folder takes its version folder into the trash entry, and restoring brings it back.
   `capture()` is debounced (15 s after the last save, a single one-shot task on one daemon thread, no periodic wakeups).
   Within a 10-minute window the newest snapshot is overwritten with the latest state; after 10 minutes a new one starts.
   Retention is 30 snapshots / 30 days, and the newest is always kept. Dialog: list (date/time, relative time, size, page
   count parsed lazily on a single-lane dispatcher and cached) and a page-1 preview (720 px, `InkRender.drawPaper` +
   `drawPageContent`, whiteboards cropped with `exportRect`). Side by side when ≥ 640dp, stacked otherwise. Restore asks
   first and saves the current content as a version beforehand. Save as copy creates "Name (version 4 Oct 15.30).note" next
   to the note and opens it. The dialog is opened from "Version history" in the note's long-press menu.

## Hooks in lead-owned `ui/Screens.kt` (each marked `// files-agent hook`)
1. HomeScreen quick actions: `QuickAction(Icons.Rounded.DocumentScanner, stringResource(R.string.files_scan), Color(0xFF0EA5E9)) { actions.quick("scan", null) }` (after Import).
2. LibraryScreen header: `IconButton(onClick = { actions.quick("scan", folder) }) { Icon(Icons.Rounded.DocumentScanner, …) }` (before the folder ⋮ / sort buttons).
3. SettingsScreen › Storage: `TrashSettingsRow()` after the stats row. It shows "N items · size · kept for 30 days" and pushes `Screen.Trash`.

## Decisions & limits
- The bin and versions live next to `Library/` in the **external** app files dir, not in internal `filesDir`. That keeps
  delete and restore as instant renames instead of copying across volumes, and it is still outside the library root.
- Scanner: only the PDF is saved. Page JPEGs are used only as a fallback when there is no PDF (saving loose images too
  would clutter folders; it could become a setting).
- Version restore checks whether the note is open in the main window (directly or as a split's first/second screen) and
  refuses with a message. A note open in a separate window or a navigated pane is not detected. If that editor saves
  later, it overwrites the restored content, but nothing is lost because both states remain as versions.
- If an ActionsHost screen leaves composition, the Undo bar goes with it. The item stays in the bin.
- Not device-tested (no emulator). Kotlin frontend reports no errors in my files. The remaining build errors were in
  `ink/InkView.kt` (notes-agent, in progress).

## Requests to lead
1. **The note editor must call `Versions.capture`.** Nothing calls it yet. In `ink/InkEditorImpl.kt` `saveAsync()`, inside the
   `InkSaver.submit` block after `snap.save(file)` (notes only):
   ```kotlin
   runCatching {
       if (source != null && snap.pages.all { it.isEmpty() } && snap.recordings.isEmpty()) file.delete() else snap.save(file)
   }
   if (isNote) { com.daftar.app.data.Versions.capture(file); main.post { Storage.touch() } }
   ```
   (`capture` is cheap and debounced and may be called from any thread.)
2. Optional: `R.string.delete_confirm` ("cannot be undone") is no longer used by ActionsHost and can be removed or kept.
3. Optional: the LibraryScreen header glyph could use `FolderThumb(Storage.entry(folder), 40.dp)` to show the folder picture.

## Self-check
1. Scanner: ML Kit FULL mode, gallery, 50 pages, PDF+JPEG, IntentSender from Activity, saved to the current folder (Library) or
   a picked folder (Home), "Scan d MMM HH.mm", opened afterwards, clear errors (no Play services / downloading / other). **PASS** (not device-tested).
2. Folder pictures: picker, centred square/16:9 crop, ≤ 512 px JPEG `.cover.jpg`, tile header + colour accent + icon chip,
   row thumbnail, remove option, byte-bounded LruCache, IO decoding. **PASS**
3. Recycle bin: moves with sidecars to a hidden `.trash` outside the library root, index with original path/time/kind; TrashScreen
   with badge, original folder, "deleted N days ago · M days left", Restore (recreates the folder, unique name), Delete forever,
   Empty (confirm); auto-purge on start on a background thread; "Move to recycle bin" text; Undo bar; recents/pins removed and
   pins restored. **PASS**
4. Version history: `capture` with a 10-minute window, keeps the latest, 30 snapshots/30 days, gzip JSON in `.versions/`; menu
   entry, list with time/size/pages, page-1 preview with InkRender, Restore (current saved as a version first), Save as copy;
   follows rename/move; goes into the trash with the note. **PASS**, but **PARTIAL until request 1** (no caller of `capture` yet).
