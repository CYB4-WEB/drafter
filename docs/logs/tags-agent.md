# tags-agent

# Round 2

## Understanding
User requests (v3): tags + colour labels on files and folders, smart filters ("Exam week", "To review"), search by tag,
and a no-server replacement for "share a note as a link": a self-contained web page (.html) made from a note.

Owned: `data/Tags.kt` (new), `ui/tags/**` (new), `res/values{,-ar}/strings_tags.xml` (prefix `tags_`).
Allowed hooks (each listed under "Edits outside my files"): `data/Storage.kt` (keep tags in sync on rename / move / duplicate /
delete / restore), `ui/LibraryParts.kt` (long-press menu, tile/row tag marks), `ui/Screens.kt` (Library header chips, Search tag
filters, Notes tag chips, Home "Smart filters" row), `convert/**` for the Note → Web page conversion only.

Library facts that drive the design:
- Library = real folders under `Storage.root`; hidden sidecars `.<name>.*`; `Storage.rename/move/duplicate/delete` are the only
  mutation paths the UI uses. `delete` moves into `Trash` (files-agent) and `Trash.restore` puts the item back (possibly with a
  unique name) and then posts `Storage.repin(...)` on the main thread — the only Storage call a restore makes.
- `Trash.kt` is not mine, so restore is detected through the `Storage.repin` hook.
- Search / Notes / Library / Home are lead-owned screens in `ui/Screens.kt`; I keep hooks to a few lines and put all UI in `ui/tags/`.
- Converter: `Conv` enum + `Engines.run` `when`; badges use `conv.to` (Kind) → a Kind.OTHER target needs an extension label.
- Ink: `InkRender.geom(stroke)` builds the exact stroke geometry (`StrokeGeom` internal `fill` / `chunks` / `chunkW` / `dotR`);
  `Path.approximate()` (API 26 = minSdk) flattens Android paths, so strokes can be exported as true vectors identical to the editor.

## Plan
1. `data/Tags.kt`: `Tag(id, name, color 0..7, key)` (key = built-in, name blank = localized default), `SmartFilter(...)`,
   `filesDir/tags.json` keyed by path relative to the library root, copy-on-write state + `version` for Compose, async atomic save.
   Hooks: `moved`, `copied`, `trashed(f, trashId)` (tags parked with the bin entry), `removed`, `restoredFromBin()` (reattach),
   prune of vanished paths at start. Filter evaluation + `#tag` query parsing + counts in one library walk.
2. `ui/tags/`: label palette, `TagDots` / `TagChips` / `TagMarks` (tiles & rows), `TagPickerDialog` (one or many files; create,
   rename, recolour, delete), `BulkTagDialog` (multi-select in Library), smart filter chips / Home row / editor dialog,
   Search tag filters, Notes tag chips.
3. Hooks in Storage / LibraryParts (menu "Tags…", "Share as web page" for notes, marks on FileTile / FolderTile / EntryRow) /
   Screens (Home row, Library chips + results + multi-select button, Search chips + `#tag`, Notes chips).
4. `convert/NoteHtml.kt` + `Conv.NOTE_HTML`: single .html, inline SVG per page (exact ink vectors, paper via a recording SVG
   canvas, images as base64, text as `foreignObject` with `dir=auto` / `unicode-bidi: plaintext`, links as `<a>`, tapes tap-to-reveal),
   only the used fonts embedded, sticky page navigation (prev/next, counter, keyboard), print CSS. Plus `shareNoteAsWebPage()`
   for menus; request for the note editor's ViewerMenuItems entry.

## Progress
All four items done (the session was cut twice by usage limits; the work on disk was resumed each time).
- `data/Tags.kt`: model, persistence (`filesDir/tags.json`, atomic, one writer thread), default bilingual tags (To review,
  Exam week, Important, Done, Homework: key-based, so the name follows the app language until renamed), 4 built-in filters,
  Storage hooks, filter matching, one-walk counts, `#tag` parser, tag-aware search.
- `ui/tags/TagsUi.kt`: 8 label colours (with names for screen readers), `TagDots` / `MiniTagChip` / `TileTags` / `RowTags`,
  `DotChip`, `TagPickerDialog` (one or many files, tri-state ticks, usage counts, create / rename / recolour / delete with
  confirmation), `TagEditDialog`, `BulkTagDialog` (multi-select).
- `ui/tags/SmartFiltersUi.kt`: `TagSearch` (Search query handling), `rememberSmartResults`, `SmartFilterChips` (Library root,
  Search; long-press = edit; "New filter"), `SearchTagFilters`, `NotesTagChips`, `SmartFiltersSection` (Home cards with live
  counts), `SmartFilterEditor` (name, tags any/all, type, folder, modified today/this week/older, untagged only, AND/OR, live
  match count, delete, restore built-ins).
- `convert/NoteHtml.kt`: `NoteHtml.write` / `NoteHtml.share` + `SvgCanvas`; `Conv.NOTE_HTML` in Catalog/Engines.
- `res/values{,-ar}/strings_tags.xml`: every string, real Arabic, Arabic plurals (zero/one/two/few/many/other).
- Compile: final `tools/compile.sh` = **BUILD OK** (whole app). Earlier runs failed only in other agents' in-progress files
  (`ink/InkStickers.kt`, `word/WordEditor.kt`, `planner/countdown/*`), never in mine.

## Edits outside my files (all marked `// tags-agent hook`)
- `data/Storage.kt`: `Tags.init(ctx)` in `init`; new `isReady()`; `Tags.moved` in `rename` and `move`; `Tags.copied` in
  `duplicate`; `Tags.trashed(f, item.id)` in `delete`; `Tags.removed` in `deleteNow`; `Tags.restoredFromBin()` in `repin`.
- `ui/LibraryParts.kt`: `TileTags` on FolderTile (both variants) and FileTile; `RowTags` in EntryRow (dots when compact,
  chips otherwise); `Actions.tagsFor` + `TagPickerDialog` host; long-press menu "Tags…" (subtitle lists current tags) and,
  for notes, "Share as web page".
- `ui/Screens.kt`: Home → `SmartFiltersSection()` under quick actions. Library → `smart` state (rememberSaveable), smart
  filter chips at the root (results replace the listing, rows then show the parent folder, own empty state), "Tag several
  items" header button + `BulkTagDialog`. Notes → tag chips after the subject chips, plus dots on note cards. Search → state
  keyed by `query`; `smart:<id>` query preselects a filter (Home cards); `#tag` parsing, tag chips and smart chips through
  `TagSearch.run`; autofocus skipped when a filter is preselected.
- `convert/Catalog.kt`: `Conv.toExt` (new optional parameter), `NOTE_HTML`, Language icon. `convert/Engines.kt`:
  `noteToHtml` (temp file, cancel, cleanup). `convert/Parts.kt`: badge passes `conv.toExt` (shows "HTML").

## Decisions & limits
- **Keys are library-relative paths**, so tags survive a moved app-data directory. Tags of paths that vanished outside
  the app are pruned at start, off the main thread.
- **Delete / restore:** `Trash.kt` is not mine. On delete, the item's tags (and those of everything inside a folder) are
  moved into a record keyed by the bin entry id. `Trash.restore` always ends with `Storage.repin`, so `repin` calls
  `restoredFromBin()`, which reattaches every record whose entry has left the bin. The tags go back to the original path,
  or to the untagged "Name (n)" copy when the name was taken in the meantime. Limit: if one item is deleted forever while
  another item is restored in the same session, and a new untagged file has meanwhile been created at the first item's
  path, that new file could get the old tags. This is very unlikely. A precise one-line hook would remove the risk (Request 2).
- **Moving or renaming a folder also updates smart filters that point at it.** Deleting a tag removes it everywhere,
  including from filters and bin records.
- **Smart filter logic:** there are two groups, the tag rule (any or all of the chosen tags) and the other rules (type AND
  folder AND date AND untagged), joined with AND or OR. So "tag Exam week OR (modified this week in subject X)" can be built
  exactly. The "Exam week" built-in is seeded as tag-only: with "OR this week" and no subject it would duplicate "Recently
  edited". The user can add the subject and the date in the editor. "This week" means the last 7 days (rolling). Date rules
  apply to files only (a folder's mtime is not meaningful). Results are sorted newest first, capped at 500.
- **Built-in filters can be edited and deleted.** "Restore built-in filters" brings back any missing ones.
- **`#tag` search:** a full name may contain spaces (`#Exam week`). Spellings such as `#exam_week` and `#examweek` also
  work, and so does a prefix (`#exa`). Each `#token` is ANDed. A token that matches no tag gives no results. Tag chips in
  Search are ANDed too.
- **Multi-select:** the "Tag several items" header button opens a checklist of the current listing (folder or filter
  result), then the tag picker for the selected items. This is a dialog, not an in-grid selection mode: that would need the
  lead's grid and gestures reworked.
- **Web page:**
  - Strokes use the editor's own `StrokeGeom` (internal fields, read-only), flattened with `Path.approximate(0.2)`, so the
    shape matches the app exactly, including the fountain, brush and pencil outlines.
  - The pencil grain texture becomes plain 85 % opacity.
  - Paper patterns, including every PaperTemplates layout, are recorded once per distinct page look through `SvgCanvas` and
    reused with `<use>`.
  - Typed text is HTML inside `foreignObject`, with `dir="auto"` per paragraph. The browser wraps it, so line breaks can
    differ slightly from Android's.
  - Only the fonts the note uses are embedded (Cairo, Amiri, Tehreer, Caveat: 260 to 600 KB each).
  - Library-file links become non-clickable chips (the recipient has no such file). Web links are real `<a>`.
  - Tapes are hidden and toggle on tap.
  - The bar direction and the "Page x of y" text follow the app language. The page also has a print stylesheet.
  - `NoteHtml.share` writes into `cache/work/webpage/` (FileProvider `cache-path`) and opens the share sheet.
- Not device-tested (no emulator). The HTML generator was not run on a device.

## Requests to lead
1. **Note editor menu → "Share as web page".** `ui/ViewerActions.kt`, in `ViewerMenuItems`, right after the Share item:
   ```kotlin
   if (com.daftar.app.data.Storage.kindOf(a.file) == com.daftar.app.data.Kind.NOTE) DropdownMenuItem(
       { Text(stringResource(R.string.tags_share_web)) },
       { close(); printScope.launch { com.daftar.app.convert.NoteHtml.share(ctx, a.file) } },
       leadingIcon = { Icon(Icons.Rounded.Language, null, tint = c.muted) })
   ```
   (import `androidx.compose.material.icons.rounded.Language`; `printScope` is the existing Main scope in that file). The
   library long-press menu and the converter already offer it.
2. Optional (files-agent's `data/Trash.kt`): for exact restores, pass the target to tags. Add
   `fun restoredTo(trashId: String, target: File)` to `Tags` (I can write it), and call it in `Trash.restore` right after
   `write(read().filter { it.id != item.id })`. The current `repin`-based reconciliation already covers the normal cases.

## Self-check
1. Tags & colour labels: **PASS**. Multiple tags per item, 8 colours, 5 editable bilingual defaults, `filesDir/tags.json` by
   path, kept in sync on rename / move / duplicate / delete / restore, "Tags…" picker with create / rename / recolour / delete,
   dots on rows and chips on tiles (and Notes cards). Multi-select is **PARTIAL**: it is a selection dialog, not an in-grid
   selection mode.
2. Smart filters: **PASS**. Tags + type + folder + modified date (today / this week / older) + untagged, AND/OR between tags
   and the rest. Built-ins: To review, Exam week, Recently edited, Untagged notes. User filters are editable (built-ins too).
   Home "Smart filters" row with counts, chips at the Library root and in Search, results use FileTile / FolderTile / EntryRow.
3. Search by tag: **PASS**. `#tagname` (also multi-word, `_`, prefix) and tag chips in Search.
4. Note → Web page: **PASS** for the code (converter entry, a single self-contained .html with vector SVG pages, base64
   images, RTL-aware text, page navigation, share via the share sheet from the long-press menu). The note-editor menu entry is
   **PARTIAL**: it waits on Request 1. Neither the HTML nor the rest of the app has been run on a device or in a browser.
