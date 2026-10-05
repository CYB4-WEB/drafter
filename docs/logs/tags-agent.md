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
(see below)
