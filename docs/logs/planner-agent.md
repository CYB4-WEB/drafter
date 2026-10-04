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

# Round 2

## Understanding
SPEC R2.5: the user wants "connect adding an event to the device's app reminder **after asking the user**". Planner events must reach the phone's calendar (Samsung Calendar / Google Calendar). Those apps then alert too. Nothing is added without consent. I own `planner/**`, `widget/**`, widget res and `strings_planner.xml` (en + real Arabic). The manifest already has READ/WRITE_CALENDAR, and BootReceiver now also gets MY_PACKAGE_REPLACED / TIME_SET / TIMEZONE_CHANGED.

Acceptance criteria (my wording):
1. **Ask after adding.** After saving a NEW event, if the choice is still "ask", a friendly dialog asks "Also add this to your phone's calendar? Samsung Calendar / Google Calendar will remind you too." Buttons: Add / Not now, plus remembering the choice ("Always do this" / "Don't ask again"). The choice is stored as ask / always / never. Add explains why calendar access is needed, then requests READ+WRITE_CALENDAR and inserts. If permission is denied it falls back to `Intent.ACTION_INSERT` on `CalendarContract.Events` (no permission; the user confirms in the calendar app).
2. **Calendar sync.** Target calendar = visible + writable calendars (account + colour shown), defaulting to the primary. Insert, update and delete go through `CalendarContract.Events`. Each copy carries: title; description including the link; location; start/end; all-day as UTC midnight dates; weekly classes as `RRULE:FREQ=WEEKLY;UNTIL=…` with DURATION; and EVENT_TIMEZONE. `CalendarContract.Reminders` (METHOD_ALERT) match the event's reminder minutes. The device event id is stored on PlanEvent in a new defaulted, backward-compatible field. If the copy was deleted in the calendar app, the next update re-inserts it and a delete is a no-op. Edits and deletes in Daftar update the device copy.
3. **EditEventScreen.** An "Also add to my phone's calendar" switch, defaulting from the remembered choice. Synced events get an "Open in calendar app" action.
4. **Planner menu.** "Sync with device calendar" (toggle + calendar picker). "Import from calendar" (upcoming device events, multi-select, copied with a type guess: Class for weekly, keyword guesses, else Other). "Export .ics" (share all or upcoming events as valid RFC 5545: UTC times, no VTIMEZONE, RRULE for weekly, CRLF, escaping, 75-octet folding).
5. **`@Composable fun PlannerSettingsSection()`** for the lead to embed in Settings. It shows the calendar choice (Ask / Always / Never), the target calendar, and the morning-summary switch (Prefs.dailySummary / Prefs.putDaily). It must look like Settings' SettingsGroup / SwitchRow / ChoiceRow (replicated, since those are private).
6. Navigation uses `pane.back()` / `pane.open()` / `pane.push()` only (no Nav.push/pop from my screens). Text sizes come from typography/sp only.
7. Every string is in en + ar. My files compile clean with `tools/compile.sh`.

## Plan
- `planner/Planner.kt`: new PlanEvent fields `calendarSync: Boolean = false` (user wants a phone copy), `deviceEventId: Long = 0`, `deviceCalendarId: Long = 0`, `importKey: String = ""` (dedupes imports). The store owns the link fields: `upsert` keeps the stored link, so stale editor copies cannot clobber it. After each change the IO thread mirrors to the device calendar: push when calendar-relevant fields changed, remove on delete or when the switch is turned off. It writes the new link back via `setLink` without re-triggering sync. The IO thread keeps an id→deviceId cache so quick successive edits never duplicate.
- `planner/DeviceCalendar.kt` (new): `CalendarPrefs` (mode ask/always/never, target calendar id); `DeviceCalendar` (permission check, list calendars, primary resolution, push/update/delete with reminders, exists, open-in-calendar intent, ACTION_INSERT fallback intent, read upcoming instances for import, type guess, link extraction).
- `planner/Ics.kt` (new): RFC 5545 writer (UTC DTSTART/DTEND or VALUE=DATE, RRULE weekly with UNTIL, VALARM per reminder, escaping, octet folding, CRLF) plus the share helper.
- `planner/CalendarUi.kt` (new): `rememberCalendarAccess()` (rationale dialog → system prompt → callback granted/denied); the ask-after-save dialog; `CalendarPickerDialog`; `CalendarSyncDialog` (planner menu); `ImportCalendarDialog`; `ExportIcsDialog`; `PlannerSettingsSection()`.
- `planner/EditEvent.kt`: the switch, "Open in calendar app", and the post-save flow (ask dialog → permission → push or fallback → notification-permission ask → `pane.back()`). Uses `pane.*` everywhere.
- `planner/PlannerScreen.kt`: header overflow menu (Sync / Import / Export). `Nav.push` → `pane.push`. Same in WeekView/MonthView.
- Strings en + ar.

## Progress
- 15:00: Resumed on the Linux box. Re-read round2b, AGENT_RULES, DESIGN, SPEC R2.5, round2, my log, all of planner/ + widget/, ui/Nav.kt, ui/Common.kt, data/Prefs.kt and Settings in ui/Screens.kt. Wrote Understanding + Plan.
- 15:10: `Planner.kt`. New PlanEvent fields with defaults (`calendarSync`, `deviceEventId`, `deviceCalendarId`, `importKey`), so old planner.json loads unchanged. The store now owns the link fields (`upsert` keeps the stored link). The phone-copy mirror runs on the existing ordered IO thread, with an id cache so quick edits never insert twice. New API: `setCalendarSync(ids, on)`, `addAll`, `syncPending()`, `reconcileDevice(ctx)`.
- 15:25: `DeviceCalendar.kt`. `CalendarPrefs` (ask/always/never + target calendar, Compose-observable); listing of visible + writable calendars with primary resolution; insert/update/delete Events with reminders; existence check; ACTION_INSERT fallback; ACTION_VIEW "open in calendar"; reading upcoming Instances for import (simple weekly series → one weekly item per weekday); type guess (EN + AR keywords); link extraction; HTML descriptions → plain text.
- 15:35: `Ics.kt`. RFC 5545 writer + share sheet (`text/calendar`, FileProvider cache path).
- 15:45: `CalendarUi.kt`: explain-then-ask permission helper, ask-after-save dialog, calendar list/picker, sync dialog, import dialog, export dialog, `PlannerSettingsSection()`. `EditEvent.kt`: switch + "Open in calendar app" + post-save flow. `PlannerScreen.kt`: overflow menu, resume sync/reconcile. All `Nav.push` → `pane.push`, and "Open folder" → `pane.open`.
- 15:55: Strings en + ar (143 keys each, parity checked with python, XML well-formed).
- 16:00: Compiled. The real tree's output was capped at 150 lines by other agents' errors, so I verified on a scratch copy: `git archive 4d7d83e` (the lead's round-2 base with the shared contracts) plus my current files, built with that copy's `tools/compile.sh` (same lock): **BUILD OK, 0 errors, 0 warnings** in planner/ + widget/. Scratch copy deleted.
- 16:20: Real tree recompiled: **0 errors in planner/ and widget/**. The remaining 5 errors are in `convert/Convert.kt` (1) and `word/WordScreen.kt` (4), owned by other agents.

## Decisions & limits
- **The per-event switch is the source of truth.** `PlanEvent.calendarSync` says whether an event has a phone copy. The remembered choice only sets the default for new events: Always = switch on; Never = off; Ask = off, and the dialog appears after saving a new event if the user didn't touch the switch. The planner-menu toggle maps to Always (on) / Never (off). Settings offers all three.
- **"Remember my choice".** The ask dialog has one checkbox, "Remember my choice". When ticked, the buttons read "Always do this" / "Don't ask again", so the choice is stored as always / never. Unticked, they read Add / Not now. That covers all four requested actions in two buttons.
- **Permission flow.** An explanation dialog comes first, then Android's prompt for READ+WRITE_CALENDAR. If Android denies, the editor falls back to `Intent.ACTION_INSERT` on `Events.CONTENT_URI`, pre-filled with title, description + link, location, begin/end, all-day and RRULE. That copy can't be tracked, so the event's switch goes back off and a later grant cannot create a duplicate. Once Android stops showing its prompt (asked before and no rationale), the dialog offers "Open settings" (plus "Use calendar app" in the add flow).
- **Phone copy format.** Timed events use DTSTART/DTEND with EVENT_TIMEZONE = device zone. All-day events use UTC-midnight DTSTART/DTEND with EVENT_TIMEZONE = UTC. Weekly events drop DTEND and get DURATION (`P<sec>S` / `P<n>D`) and `RRULE:FREQ=WEEKLY[;UNTIL=…];BYDAY=XX`. UNTIL is a UTC date-time for timed events and a DATE for all-day ones; BYDAY is the start's weekday, added so calendar apps show "every Monday". Description = description + link (unless already included). AVAILABILITY_BUSY, HAS_ALARM.
- **Reminders.** `CalendarContract.Reminders` with METHOD_ALERT, one per reminder minute (max 5, the usual calendar limit). All-day events: Daftar reminds relative to 09:00 but calendars count back from midnight, so I use m − 540 (e.g. "1 day before" = 900 min = 09:00 the day before). Same-day reminders (at time / 10 min / 30 min / 1 h) cannot be expressed that way. Those stay with Daftar's own notification rather than buzzing at midnight.
- **Both apps notify.** For synced events, Daftar's reminders keep firing (they carry "Open link" / "Mark done"), and the calendar app alerts at the same moments. This matches the requested copy "Samsung Calendar / Google Calendar will remind you **too**". If the lead prefers one notification, `Alarms.scheduleEvent` can skip events with `deviceEventId != 0` (a one-line change). I didn't do it unasked.
- **Copy deleted in the calendar app.** When the Planner screen resumes or the editor opens, a reconcile pass (one `_ID IN (…)` query, DELETED=0) clears the link and turns that event's switch off. This respects the user's deletion; turning the switch back on re-adds the copy. If a save races the deletion (copy missing at update time), the push re-inserts it. Deleting in Daftar when the copy is already gone is a no-op.
- **Edits / deletes.** `upsert` mirrors only when calendar-visible fields changed (title, times, all-day, weekly, until, location, link, description, reminders), when the switch flips, or when no copy exists yet. Ticking an assignment done doesn't touch the calendar. `delete` removes the copy. Updates never move a copy to another calendar: changing the target calendar affects new copies only, and the picker says so.
- **Import.** Uses `Instances` in the chosen range (2 weeks / 1 month / 3 months / 6 months) from visible calendars and excludes Daftar's own copies. Simple weekly series (FREQ=WEEKLY, interval 1, plain BYDAY) become weekly Daftar events, one per weekday, with `until` from UNTIL or COUNT. Other recurrences are listed per occurrence. The type is guessed (exam / assignment / meeting / class keywords in EN + AR; weekly → Class; else Other) and the user can tap the type chip to change it. Imports are copies (not linked) with `importKey`, so they show as "Already in Daftar" next time. Daftar reminders on imports default off (the calendar already reminds), with a switch to turn them on.
- **.ics export.** UTC DATE-TIMEs, so no VTIMEZONE is needed, as briefed. Limit: in DST regions a weekly UTC RRULE drifts one hour after a DST change; Saudi/Gulf have no DST and the phone copies themselves use the local zone. All-day = VALUE=DATE. No DTEND when the length is zero (RFC requires DTEND > DTSTART). VALARM TRIGGER relative to DTSTART; for all-day events that means positive offsets for same-day 09:00-based reminders. TEXT escaping, 75-octet UTF-8-safe folding, CRLF, UID `daftar-<id>@com.daftar.app`. Shared via ACTION_SEND `text/calendar` from `cacheDir/ics/` (FileProvider cache-path).
- **Settings section.** `PlannerSettingsSection()` draws its own `SectionTitle(R.string.planner)` + card, with replicas of SettingsGroup / SwitchRow / ChoiceRow and a value row. Turning the morning summary on also re-arms the alarms and asks for POST_NOTIFICATIONS on API 33+ if it is missing.

## Requests to lead
1. **Embed the Settings section** (ui/Screens.kt, SettingsScreen). Replace
   ```kotlin
   SettingsGroup(stringResource(R.string.planner)) {
       SwitchRow(Icons.Rounded.WbSunny, stringResource(R.string.set_daily), stringResource(R.string.set_daily_desc), Prefs.dailySummary) {
           Prefs.putDaily(it)
       }
   }
   ```
   with
   ```kotlin
   com.daftar.app.planner.PlannerSettingsSection()
   ```
   (It draws the "Planner" section title + card itself. `set_daily` / `set_daily_desc` are then unused.)
2. (Optional, consistency) Home in ui/Screens.kt still uses `Nav.push(Screen.EditEvent(...))` for the quick action and the Today strip. `pane.push(...)` would match the round-2 rule.
3. (Tooling, optional) `tools/compile.sh` caps output at 150 lines, so one agent's many errors can hide another's. A filter would help, e.g. `FILTER=planner/ tools/compile.sh` printing `grep -e "$FILTER" -e FAILURE` on the log.
4. Decide whether synced events should notify from Daftar too (current: yes, see Decisions).

## Self-check
1. Ask after adding (dialog, Add / Not now, remember always/never, explain → permission → insert; denied → ACTION_INSERT fallback): **PASS**. `EditEvent.save()` → `AddToCalendarDialog` → `rememberCalendarAccess()` → `Planner.setCalendarSync` / `DeviceCalendar.insertViaApp`. The choice is stored in `CalendarPrefs.mode`. Code-complete; device test by the lead.
2. Calendar sync (target calendar visible + writable with account + colour, primary default; insert/update/delete with title, description + link, location, start/end, all-day UTC, weekly RRULE with UNTIL, EVENT_TIMEZONE; Reminders METHOD_ALERT; device id stored with a default; deleted-in-app handling; edits/deletes update the copy): **PASS**. The only caveat is that same-day all-day reminders cannot be expressed in calendar minutes (Decisions).
3. EditEventScreen switch (default from the remembered choice) + "Open in calendar app": **PASS**. The switch subtitle shows which calendar holds the copy; the open action only appears while the copy exists.
4. Planner menu: Sync (toggle + calendar picker + add existing / remove copies), Import (range, multi-select, select all, type guess editable, reminders option, no duplicates), Export .ics (upcoming / all, RFC 5545 UTC, RRULE weekly, VALARM, share): **PASS**.
5. `PlannerSettingsSection()` (Ask / Always / Never, target calendar, morning summary via Prefs.dailySummary / putDaily, Settings look replicated): **PASS**. The lead must embed it (Request 1).
6. `pane.back()` / `pane.open()` / `pane.push()` only (no Nav.push/pop in planner/ or widget/); typography/sp only (no `sp` / `fontSize` in my files): **PASS** (grep-verified).
7. Strings en + real Arabic for every new text (143 keys each, plurals with zero/one/two/few/many/other in Arabic); compiles: **PASS**. 0 errors / 0 warnings in planner/ + widget/ on the clean scratch build, and 0 errors in my files in the real tree (the remaining errors are in convert/ and word/).
