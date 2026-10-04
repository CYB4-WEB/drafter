# Brief: planner-agent — Timetable, events, reminders, notifications, widgets

## Goal
The student keeps their whole academic schedule in Daftar: weekly class timetable, exams, assignments (with done tick), meetings, and anything else —
each with date/time, description, link (Zoom/Teams/Drive URL), location, optional linked subject folder — and gets **reliable reminder notifications**.
Home-screen **widgets** show what's next and give one-tap shortcuts.

## You own
- `app/src/main/java/com/daftar/app/planner/**` (replace stubs `Planner.kt`, `PlannerScreen.kt`, `Receivers.kt`; add more files)
- `app/src/main/java/com/daftar/app/widget/**` (replace stub `Widgets.kt`)
- `res/layout/widget_upcoming.xml`, `res/layout/widget_quick.xml`, `res/xml/widget_upcoming.xml`, `res/xml/widget_quick.xml` (existing — you may edit), any new `res/drawable/widget_*`
- `res/values/strings_planner.xml`, `res/values-ar/strings_planner.xml`
- log: `docs/logs/planner-agent.md`

## Public contracts (keep exactly — Home screen & manifest use them)
- In `planner/Planner.kt`: `object EventType {EXAM=0, ASSIGNMENT=1, MEETING=2, CLASS=3, OTHER=4}`, `@Serializable data class PlanEvent(...)` with the existing fields
  (you may ADD fields with defaults, not remove/rename), `data class Occurrence(event, start, end)`,
  `object Planner { var version (Compose state, private set); fun init(ctx); fun upcoming(limit, from = now): List<Occurrence> }`.
- `@Composable fun PlannerScreen()` and `@Composable fun EditEventScreen(id: Long?, presetType: Int)` (package `com.daftar.app.planner`).
- Receivers `planner.ReminderReceiver`, `planner.BootReceiver`; widgets `widget.UpcomingWidget`, `widget.QuickWidget` (already declared in the manifest).
- Deep-link constants in `ui/Nav.kt`: `EXTRA_ACTION` with `ACTION_NEW_NOTE / ACTION_PLANNER / ACTION_IMPORT / ACTION_ADD_EVENT`, and `EXTRA_EVENT_ID` (Long). MainActivity handles them.
- Notification channels exist: `DaftarApp.CH_REMINDERS`, `DaftarApp.CH_DAILY`. Small icon: `R.drawable.ic_notify`.

## How
1. **Storage**: `filesDir/planner.json` (list of PlanEvent) via `com.daftar.app.data.json`; in-memory `mutableStateListOf`; every mutation: save, `version++`,
   reschedule alarms for that event, refresh widgets (`AppWidgetManager.updateAppWidget` via a `Widgets.refresh(ctx)` helper).
   API: `all()`, `get(id)`, `upsert(e)`, `delete(id)`, `setDone(id, done)`, `occurrences(from, to)` (expand weekly events, respecting `until`), `upcoming(limit, from)`
   (occurrences whose end ≥ from, sorted; exclude done assignments).
2. **Reminders**: `AlarmManager`. For each event × reminder minutes → next occurrence trigger time in the future. Use `setExactAndAllowWhileIdle` when
   `canScheduleExactAlarms()` (API 31+) else `setAndAllowWhileIdle`. Unique request code per (event id, reminder index). PendingIntent FLAG_IMMUTABLE.
   Weekly events: after firing, schedule the next week's. `BootReceiver` (BOOT_COMPLETED) and `Planner.init` reschedule everything.
   `ReminderReceiver` posts a notification: title = event title, text = "Exam · Tomorrow 09:00 · Hall B" style, BigText with description,
   content intent → MainActivity with `EXTRA_EVENT_ID`; if `link` not blank → action button "Open link" (ACTION_VIEW Uri). Category by type colour (`setColor`).
   Check POST_NOTIFICATIONS permission (API 33+): PlannerScreen asks once with `rememberLauncherForActivityResult(RequestPermission)` when the first reminder is created;
   if exact alarms not allowed on API 31+, show a one-line banner with a button opening `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
3. **Daily summary**: if `Prefs.dailySummary`, an alarm at 07:30 local each day posts "Today: 2 classes, 1 exam" with the first items (channel CH_DAILY). Reschedule daily.
4. **PlannerScreen** (top-level tab; the app shell draws the sidebar/bottom bar around it — you draw only content). Header "Planner" + segmented tabs:
   - **Agenda**: upcoming occurrences grouped by day ("Today", "Tomorrow", "Mon 12 Oct"), rows: colour bar by type, title, time range, type label, location, link icon
     (tap opens link), checkbox for assignments. Overdue undone assignments section on top. Empty state with "Add event".
   - **Week** (timetable): 7 day columns (locale first day of week; in Arabic RTL columns mirror automatically) × hours 7:00–22:00 (auto-extend to fit), events as coloured
     blocks positioned by time (overlaps side-by-side), current-time line, prev/next week arrows, "This week". On Compact width show 3 days with horizontal paging.
   - **Month**: grid with dots per type; tap day → that day's list below.
   - FAB "+" (accent, round) → `Nav.push(Screen.EditEvent(null))`. Tap event → `Nav.push(Screen.EditEvent(id))`.
5. **EditEventScreen** (full screen, `ViewerTopBar` with Save): type chips (Exam/Assignment/Meeting/Class/Other, colours from DESIGN.md: Exam Red(coral),
   Assignment Amber(sand), Meeting Indigo, Class Blue, Other Slate — use `FolderPalette` entries), title (required), all-day switch, date (DatePickerDialog M3),
   start/end time (TimePicker M3, 24h per locale), "Repeats weekly" switch (default ON for Class) + optional until date, location, link (URL keyboard; validate; prefix https:// if missing),
   description (multi-line), reminders chips multi-select: At time(0), 10 min, 30 min, 1 h(60), 1 day(1440), 1 week(10080); linked subject folder (dropdown of
   `Storage.root` subfolders; show folder colour) + "Open folder" button when set. Delete button (confirm) when editing. `presetType` ≥0 preselects the type.
6. **Widgets** (RemoteViews, no Glance):
   - `UpcomingWidget`: header "Upcoming" + "+ Add" chip (→ MainActivity `EXTRA_ACTION=ACTION_ADD_EVENT`), up to 4 rows (`row0..3`, `bar0..3`, `title0..3`, `sub0..3` ids exist in layout),
     bar tinted by type (`setInt(id,"setColorFilter",color)`), sub = "Tue 10:00 · Exam". Empty text when none. Whole widget click → planner. Refresh on every planner change,
     on `ACTION_TIME_TICK`-free schedule: `updatePeriodMillis` + an alarm at the next occurrence end to refresh.
   - `QuickWidget`: three chips `q_note`, `q_planner`, `q_import` → MainActivity with `EXTRA_ACTION` = ACTION_NEW_NOTE / ACTION_PLANNER / ACTION_IMPORT (distinct request codes).
   - Widget text must follow app language (use `context.getString`).

## Acceptance criteria
1. Create/edit/delete each type; weekly classes appear every week in Week view and Agenda; data persists across restarts.
2. Reminder notification fires at the right time (incl. after reboot), opens the event; "Open link" works.
3. Daily summary fires when enabled.
4. Week timetable readable on tablet landscape and portrait, usable on phone; Arabic mirrors correctly; current-time line.
5. Assignments can be ticked done and leave Upcoming.
6. Both widgets render, update after changes, and their buttons deep-link correctly.
7. `Planner.upcoming()` returns correct sorted occurrences for the Home "Today" strip.
8. All strings localized (en + ar). Code compiles.
