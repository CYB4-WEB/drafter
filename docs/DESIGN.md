# Daftar (دفتر) — UI Design System

Target: Samsung Galaxy Tab S11 Ultra (14.6", 2960×1848, S Pen), also phones. Android 8+ (minSdk 26).
**Visual reference: the user's mockup ("Daftar — Your study files, organised"). It overrides anything below.**
Personality: **calm study desk**. Paper-like neutrals, one quiet blue accent, colour comes only from the
student's own folders. No gradients, no glow, no glassmorphism, no emoji-as-icons, no "AI sparkle".

## 1. Tokens (source of truth: `ui/theme/Theme.kt`)

| Token | Light | Dark | Use |
|---|---|---|---|
| `bg` | #F7F6F2 | #14161A | app background ("Paper") |
| `surface` | #FFFFFF | #1C1F24 | cards, sheets, toolbars |
| `surfaceAlt` | #E9E7E1 | #262A31 | chips, inputs, tile backgrounds |
| `line` | #E4E2DC | #2E333B | 1dp borders / dividers |
| `ink` | #1F2937 | #E8EAED | primary text |
| `muted` | #6B7280 | #9CA3AF | secondary text, icons |
| `accent` | #3B82F6 | #60A5FA | FAB, selection pill (accent@12%), links |
| `danger` | #B3261E | #F2B8B5 | delete |

Folder palette (12, flat): Coral #F26D5B, Orange #F59E42, Sand #F2C94C, Lime #9BC53D, Green #4CC38A, Teal #2EB5B0,
Blue #3B82F6, Indigo #5B6EE8, Purple #8B6CE0, Pink #E56BA6, Brown #B08968, Slate #7C8796.
Folder glyph = solid two-tone folder shape (back tab darker, front lighter) in folder colour, white subject icon on front (`ui/FolderGlyph`).
Grid tile = surface card, 48dp glyph, title, "12 items" muted. Phone = list row: 36dp glyph, title, count, chevron.

Event type colours (planner): Exam = Red, Assignment = Amber, Meeting = Indigo, Class = Blue, Other = Slate.

## 2. Type & shape
- Font: system default (Samsung One UI / Roboto; Arabic falls back to system Arabic). No custom UI font.
- Scale: Display 28/34 semibold (screen titles on tablet), Title 20/26 semibold, Body 15/22, Label 13/18 medium, Caption 12/16.
- Radius: cards 16dp, chips/buttons 12dp, dialogs 24dp. Elevation: none — use 1dp `line` borders.
- Spacing grid 4dp; screen gutter 16dp (phone) / 24dp (tablet); card padding 16dp.
- Touch targets ≥ 48dp. Icons: Material Symbols Rounded (material-icons-extended `Icons.Rounded.*`), 24dp, `muted` unless active.

## 3. Adaptive layout (all screens)
Width classes from `LocalWindowWidth` (Compact < 600dp, Medium 600–840, Expanded > 840).
- **Medium/Expanded (tablet, any orientation)**: permanent labelled sidebar (240dp: "Daftar", Home, Files, Notes, Planner, Search, Settings;
  selected = accent icon+text on accent@12% pill). Viewers are full-screen and use side panels.
- **Compact (phone / narrow split)**: bottom bar Home · Files · Notes · Planner · More. Folder grids become list rows.
- Round blue FAB "+" bottom-end on Home, Files, Notes, Planner.
Must work in split-screen and pop-up window at any size; never assume orientation. Activity handles config changes itself (no state loss).
RTL: everything mirrors automatically in Arabic — use `start/end`, never `left/right`; directional icons use `AutoMirrored`.

## 4. Screens
0. **Notes** — all notebooks across library; filter chips (All + subject root folders); cards: title, preview line, date end-aligned.
   **Search** — field + clear, chips All/Folders/Files/Notes; rows: type badge, name, "PDF · Cyber Security · 3d ago".
   File rows everywhere: 32dp rounded type badge (PDF red, Word blue, Note blue, Slides orange), name, "Folder · 2h ago", chevron.
1. **Home** — greeting + date; search field; Quick actions row (New note, New folder, Import, Images→PDF, Add event);
   "Today" strip (next 3 planner items); "Subjects" grid (root folders, colour tiles); "Pinned"; "Recent" list.
2. **Library / Folder** — breadcrumb bar; grid (adaptive 168dp) or list toggle; sort menu; FAB "+" → sheet (Note, Folder, Import files, Images→PDF).
   Long-press → action sheet: Open, Rename, Appearance (folders), Move, Duplicate, Share, Pin, Delete.
   Folder creation dialog: title, 12 colour swatches, icon grid (~24 study icons), "Add Lectures / Seminars / Labs" toggle (default ON at root).
3. **Note editor (Notability-style)** — top bar: back, title (tap to rename), undo/redo, record, more. Tool bar (floating, centered, surface + line border):
   Pen, Highlighter, Eraser, Lasso, Text, Shape, Hand | colour dots (6 + custom) | width presets (3) | Insert (image, page, dictation). Pages scroll vertically.
   Lasso selection shows a small floating menu: Convert to text, Colour, Duplicate, Delete.
4. **PDF viewer** — same canvas & toolbar; top bar extra: page x/y, thumbnails panel, "Tools" menu: Export annotated PDF, Share, Print (all/current), Copy text,
   Extract pages, Pages → images, Go to page.
5. **Slides (PPTX)** — slide canvas (annotatable) + side panel (Expanded) / bottom sheet (Compact) with tabs: Thumbnails | Speaker notes | Comments | My notes.
6. **Word (DOCX)** — reading view, max text width 760dp centered, selectable text, zoom −/+, Open externally.
7. **Planner** — tabs: Agenda (upcoming grouped by day), Week timetable (7 columns × hours, class blocks coloured), Month (optional dots).
   Add/Edit event sheet: type chips (Exam, Assignment, Meeting, Class, Other), title, date, start/end time, repeat weekly (classes), location, link, description,
   reminders (chips: at time, 10 min, 1 h, 1 day, 1 week), linked folder (optional).
8. **Settings** — Language (System/English/العربية), Theme, Pen-only drawing, S Pen button action (Eraser/Lasso), default paper, handwriting language,
   dictation language, daily summary notification, storage info.

## 5. Widgets
- **Upcoming** (4×2, resizable): header + "+" chip; up to 4 rows with colour bar, title, "Tue 10:00 · Exam".
- **Quick actions** (4×1): New note · Planner · Import.

## 6. Motion & feedback
Standard Material motion only (150–250ms fades/slides). Snackbars for undoable deletes. Empty states: one muted icon + one line + one action button.
