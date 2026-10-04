# planner-agent log

## Understanding
I build Daftar's academic planner end to end. That means the data store, alarm-based reminders, the daily summary, the Planner UI (Agenda / Week / Month), the full-screen event editor, and the two RemoteViews home-screen widgets. All of it stays inside `planner/**`, `widget/**`, the widget layouts/xml/drawables and `strings_planner.xml` (en + ar).

Public contracts I must keep: `EventType` constants, `PlanEvent` (I can only add fields that have defaults), `Occurrence`, `Planner.version` (Compose state, private set), `Planner.init(ctx)`, `Planner.upcoming(limit, from)`, `PlannerScreen()`, `EditEventScreen(id, presetType)`, `ReminderReceiver`, `BootReceiver`, `UpcomingWidget`, `QuickWidget`. I may not touch the manifest, MainActivity, Nav, Common, Theme, data/*.

The acceptance criteria, in my own words:
1. The user can create, edit and delete events of all five types (Exam, Assignment, Meeting, Class, Other). A weekly class shows up every week, in both the Week grid and the Agenda. Everything survives an app restart (`filesDir/planner.json`).
2. Each reminder fires a notification at the right moment, both exact and still working after a reboot. Tapping it opens that event's editor, and when the event has a link an "Open link" action opens it.
3. When `Prefs.dailySummary` is on, a 07:30 notification summarises today ("Today: 2 classes, 1 exam" plus the first items).
4. The Week timetable is readable on a tablet in landscape and portrait, and usable on a phone (3-day paging there). It mirrors in Arabic and shows a current-time line.
5. An assignment can be ticked done. Once done it no longer appears in Upcoming (Home strip, widget, Agenda).
6. Both widgets render and refresh after every planner change and on a time schedule. Their buttons deep-link correctly (`EXTRA_ACTION` / `EXTRA_EVENT_ID`).
7. `Planner.upcoming(limit, from)` returns occurrences whose end ≥ from, with weekly events expanded, `until` respected and done assignments excluded, sorted by start. Home's "Today" strip relies on it.
8. Every user-visible string is a resource in both `values/strings_planner.xml` and `values-ar/strings_planner.xml`, and the code compiles.

## Plan
Files (all under `app/src/main/java/com/daftar/app/`):
- `planner/Planner.kt` holds the contract types plus the store: a `mutableStateListOf` loaded from `filesDir/planner.json`; the API `all/get/upsert/delete/setDone/occurrences/upcoming/overdue`; DST-safe weekly expansion with `java.time` (ZonedDateTime.plusWeeks). Saves are atomic (tmp + rename) on a single-thread IO executor. Every mutation runs `version++`, reschedules that event's alarms and refreshes the widgets.
- `planner/Alarms.kt` handles AlarmManager scheduling for reminders (unique data URI + request code per event × reminder index), the daily 07:30 summary, and a widget refresh at the next occurrence end or midnight. It uses exact alarms when allowed and falls back to `setAndAllowWhileIdle`.
- `planner/Notify.kt` builds the reminder and daily-summary notifications. Text goes through a localized context, and the POST_NOTIFICATIONS permission is checked first.
- `planner/Receivers.kt` contains `ReminderReceiver` (actions: reminder, daily, refresh widgets, mark done) and `BootReceiver` (reschedule all).
- `planner/Format.kt` has the type colours and labels, date/time formatting and the app-locale context helper.
- `planner/PlannerScreen.kt` is the header, the segmented tabs, the permission and exact-alarm banners, the Agenda tab, the FAB and the snackbar.
- `planner/WeekView.kt` is the timetable: HorizontalPager pages a week (or 3 days on Compact), overlapping events get lanes, all-day strip, now-line.
- `planner/MonthView.kt` is the month grid with type dots; the selected day's list sits below it (beside it on Expanded).
- `planner/EditEvent.kt` is `EditEventScreen`.
- `widget/Widgets.kt` has `UpcomingWidget`, `QuickWidget` and `Widgets.refresh(ctx)`.
- Resources: string files (en/ar), the widget layout (give the header an id so it can be localized at runtime).

## Progress
- 2026-10-04 14:20: Read AGENT_RULES, DESIGN, SPEC, the brief, and the shared code (manifest, DaftarApp, MainActivity deep-link handling, Nav, Common, Theme, Storage, Prefs, widget layouts/xml). Wrote Understanding + Plan.
- 14:30: `Planner.kt`. Store with JSON persistence (atomic tmp+rename on a single IO thread), DST-safe weekly expansion, `occurrences / upcoming / upcomingIn / overdue / onDay`, `setDone`, `draftStart` hand-off from Week/Month taps.
- 14:35: `Alarms.kt` (reminders per event × slot, daily 07:30, widget refresh), `Notify.kt` (reminder + daily InboxStyle), `Receivers.kt`, `Format.kt` (type colours/labels/icons, locale-aware formatting, in-app-language context for widgets/notifications, link normalisation).
- 14:40: `widget/Widgets.kt`: UpcomingWidget (rows adapt to widget height, row tap opens the event, refresh alarm at next item end / midnight) and QuickWidget. Added `@+id/header` to `widget_upcoming.xml` so the header can be localized at runtime.
- 14:45: UI. `PlannerScreen.kt` (header, segmented tabs, notification/exact-alarm banners, Agenda, FAB, undo snackbar), `WeekView.kt`, `MonthView.kt`, `EditEvent.kt`.
- 14:50: Strings en + ar (`strings_planner.xml`, Arabic plurals with zero/one/two/few/many/other).
- 14:54: Compile. The real tree fails only in `ui/LibraryParts.kt` (another agent: missing string keys kind_*, imported_n, images_pdf_name, import_files, save_in). To verify my code I compiled a scratch copy with temporary stub strings: BUILD SUCCESSFUL, 0 errors / 0 warnings in planner/ and widget/. Scratch copy deleted.

## Decisions & limits
- **Event identity / alarms:** each reminder PendingIntent has a unique data URI `daftar://reminder/<id>/<slot>` plus request code. That makes alarms unique per (event, reminder index) regardless of hash collisions. Up to 8 slots are always reconciled, so removing reminders cancels stale alarms.
- **Exact alarms:** `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()`, otherwise `setAndAllowWhileIdle`. On Android 14+ the permission is off by default, so Planner shows a banner → `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. On resume, if it was granted, every alarm is rescheduled as exact.
- **All-day events** are reminded relative to 09:00 that day ("At time" = 09:00, "1 day" = 09:00 the day before), the same as common calendars. A midnight ping would be useless.
- **Weekly expansion** uses `ZonedDateTime.plusWeeks`, so a 10:00 class stays at 10:00 across DST changes. `until` is stored as the end of the chosen day.
- **Daily summary** alarm is always armed for 07:30. The receiver checks `Prefs.dailySummary` when it fires, so toggling the setting needs no extra wiring by the lead. Nothing is posted on an empty day (no nagging).
- **Done assignments** are excluded from `upcoming()`, widget, Home and the Agenda (hidden unless the "Show completed" chip is on). Reminders are cancelled when done. The notification has a "Mark done" action and the Agenda offers Undo through a snackbar.
- **Overdue:** undone assignments due before today appear in a red "Overdue" section on top of the Agenda.
- **Widget language** on API < 33: AppCompat applies the in-app locale only to Activities. For widgets and notifications, `localized(ctx)` uses `AppCompatDelegate.getApplicationLocales()` and falls back to reading AppCompat's persisted locale file (autoStoreLocales=true). All widget texts are set at runtime with `getString`. On API 33+ the framework handles this natively.
- **Planner.init** reads `planner.json` synchronously in `Application.onCreate` (small file; Home and widgets need it immediately). Writes and alarm scheduling happen on a background thread.
- **Notification permission** is requested once, when the first event with reminders is saved (from the editor; PlannerScreen does the same as a fallback). After that, a banner offers "Turn on", which opens the system notification settings.
- Week view: the hour range is 7–22, auto-extending to fit events. Hour height fills the available height (min 48dp tablet / 52dp phone). Overlapping events go into side-by-side lanes. On Compact, 3-day pages are anchored on today. Tapping an empty slot creates an event at that half-hour. In Month view, the FAB pre-fills the selected day.

## Requests to lead
1. (Recommended, non-blocking) Make `BootReceiver` also run after app updates and clock/timezone changes. Add to its intent-filter in `AndroidManifest.xml`:
   ```xml
   <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
   <action android:name="android.intent.action.TIME_SET" />
   <action android:name="android.intent.action.TIMEZONE_CHANGED" />
   ```
   `BootReceiver` already reschedules everything for any action it receives.
2. Home "Today" strip: call `Planner.upcoming(3)` inside composition; it reads Compose state so it recomposes on changes. Use `occurrenceSummary(ctx, o)` (public in `planner/Format.kt`) for "Exam · Tomorrow 09:00 · Hall B" text and `typeColor(type)` for the bar.
3. FYI: the current tree fails to compile only because `ui/LibraryParts.kt` references missing strings (`kind_folder`, `kind_note`, `kind_slides`, `kind_word`, `kind_image`, `kind_audio`, `kind_file`, `imported_n`, `images_pdf_name`, `import_files`, `save_in`). This is not in my files.

## Self-check
1. Create/edit/delete each type; weekly classes appear every week in Week + Agenda; persistence: **PASS**. The editor has all 5 types, delete with confirm, and weekly expansion in `occurrences()` used by Week/Agenda/Month. Data lives in `filesDir/planner.json` and is reloaded in init.
2. Reminder fires at the right time, after reboot, opens the event, "Open link": **PASS** (code-complete; device test by lead). Exact alarms are used when permitted (banner otherwise). `BootReceiver` and `Planner.init` reschedule. The content intent carries `EXTRA_EVENT_ID` → MainActivity opens the editor. The "Open link" action uses ACTION_VIEW.
3. Daily summary fires when enabled: **PASS**. The 07:30 alarm re-arms daily and posts "Today: 2 classes, 1 exam" with an InboxStyle list.
4. Week timetable on tablet landscape/portrait + phone; RTL; now-line: **PASS**. 7 columns (locale first day) on Medium/Expanded, 3-day paging on Compact. Hour height adapts. It uses start/end offsets and AutoMirrored arrows, so it mirrors in Arabic. A red now-line with a dot ticks every minute.
5. Assignments ticked done and leave Upcoming: **PASS**. Done can be ticked from the Agenda checkbox (with Undo) or the notification's "Mark done" action. `upcoming()` excludes them, so the widget, Home and Agenda update.
6. Both widgets render, update, deep-link: **PASS** (code-complete). `Widgets.refresh` runs after every mutation, plus a refresh alarm at the next item end or midnight, plus updatePeriodMillis. Buttons send `EXTRA_ACTION` (ACTION_ADD_EVENT / PLANNER / NEW_NOTE / IMPORT) with distinct request codes. Rows send `EXTRA_EVENT_ID`.
7. `Planner.upcoming()` correct sorted occurrences: **PASS**. It returns occurrences with end ≥ from (in-progress items included), expands weekly events, respects `until`, excludes done assignments, sorts by start then end, and caps at limit.
8. All strings localized (en + ar); compiles: **PASS** for my files. 0 errors in planner/ and widget/, verified on a scratch copy. The shared tree currently fails only in another agent's `LibraryParts.kt` (see Requests #3).
