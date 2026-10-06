# countdown-agent log

# Round 2

## Understanding
The user loves the iOS app "Exam Countdown": a filtered list of big colour cards with a darker end block ("1 / day to go"),
a full-screen live countdown (days · hours · mins · secs, "until Tuesday, October 6, 2026") with share-as-image, and a
first-run hint. Daftar must get the same quality on top of the existing Planner (no second data store):

1. `Screen.Countdowns`: filter dropdown (All / type / subject folder), Past N / Future N toggle, search, + (EditEvent with type
   preset), flat 16dp colour cards (type colour or linked subject colour), type icon, title, subtitle, full localized date+time,
   end block ("1 day to go", "5 hours to go", "Today", "Done", "2 days ago"); weekly classes = next occurrence; RTL mirrors;
   one-time hint bubble.
2. `Screen.Countdown(eventId, occStart)`: the reference layout in Daftar style, ticking every second only while visible;
   Now/Started at zero, "Grade it" after an exam (reuse grades' `GradeItButton`); top bar back / share (card → PNG → share
   sheet) / ⋯ (Edit, Open linked folder, Add to calendar, Delete); swipe to next/previous event; works in a split pane.
3. Home Upcoming section in the same visual language (compact cards, tap → live countdown, See all → Countdowns), 60 s tick
   only while visible.
4. Widgets (RemoteViews, battery-friendly): new single-event Countdown widget (configure activity, default "next exam",
   2×2 → 4×2, Chronometer under 24 h, otherwise refresh only when a label changes); Upcoming + Exam widgets upgraded to
   card rows with the end number block; taps open the live countdown.
5. Planner: "Countdowns" entry in the header + long-press "Show countdown" on events.

## Plan
- `planner/countdown/CountdownModel.kt`: list building (future = one card per event, weekly → next occurrence, done
  assignments kept; past = ended), end-block labels (shared by app + widgets), live parts, full date, subject resolution,
  card colour + readable content colour, per-event style store (colour override + unit), prefs (hint, widget choice),
  draft hand-off (folder preset for "+").
- `planner/countdown/CountdownCards.kt`: big card, compact card, end block, icon ring.
- `planner/countdown/CountdownsScreen.kt`, `LiveCountdown.kt`, `HomeUpcoming.kt`, `CountdownStyleSection.kt` (EditEvent).
- `widget/CountdownWidget.kt` (+ `CountdownWidgetConfigActivity`), layouts/drawables/xml, upgraded `widget_upcoming.xml`
  and `widget_exams.xml`, `Widgets.kt` / `ExamWidget.kt` row rendering.
- Edits: `ui/Nav.kt` (routes + extras), `MainActivity.kt` (Route + widget deep link), `AndroidManifest.xml` (widget receiver +
  configure activity), `ui/Screens.kt` (Home Upcoming section only), `planner/PlannerScreen.kt`, `planner/EditEvent.kt`.
- Strings: `res/values{,-ar}/strings_countdown.xml` (prefix `cd_`).

## Progress
- Read round2b, DESIGN, planner-agent + grades-agent logs, all of planner/ + widget/ + widget res, Nav, Common, MainActivity,
  Home in Screens.kt, GradesEntry. Wrote Understanding + Plan.
- `planner/countdown/`: `CountdownModel.kt` (lists, labels, live parts, dates, subjects, colours, style store, prefs, draft),
  `CountdownCards.kt`, `CountdownsScreen.kt`, `LiveCountdown.kt`, `HomeUpcoming.kt`, `CountdownStyleSection.kt`.
- Routes `Screen.Countdowns` / `Screen.Countdown(eventId, occStart)` + widget extras; Home Upcoming replaced; Planner header
  entry + long-press menus (Agenda/Month rows and Week blocks); EditEvent style section, "Show countdown" action, folder preset.
- Strings en + ar (79 keys each, Arabic plurals zero/one/two/few/many/other; parity checked with python).
- Compile (whole tree) after the app part: BUILD OK. (Interrupted by a usage limit here; resumed from disk.)
- Widgets: `widget/CountdownWidget.kt` (+ `WidgetCards` shared row renderer), `CountdownWidgetConfigActivity.kt`, layouts
  `widget_countdown.xml` / `widget_countdown_small.xml`, upgraded `widget_upcoming.xml` / `widget_exams.xml`, drawables
  `widget_card/block/ring`, `widget_ic_*` vectors, `xml/widget_countdown.xml`, manifest entries. XML well-formedness checked.
- Final compile `:app:processDebugResources :app:compileDebugKotlin`: **BUILD OK**.

## Decisions & limits
- **No new data store.** Everything is computed from `Planner` events. Per-event "countdown style" (colour override +
  Auto/Days/Weeks unit) lives in a small `countdown` SharedPreferences keyed by event id, so `PlanEvent`/`planner.json`
  stay untouched. Stale ids are harmless. The same prefs hold the hint flag and each widget's choice.
- **Lists:** Future has one card per event: weekly events show their next occurrence, in-progress events count as future,
  and ticked assignments stay with a "Done" block. Past holds ended one-off events plus finished weekly series (last
  occurrence), most recent first. Counts on the toggle follow the active filter and search.
- **End block** uses calendar days like the reference: tomorrow reads "1 day to go" even 13 h ahead. Later today it shows
  "5 hours to go" or "12 mins to go", and all-day events today show "Today". The live screen shows the exact duration.
- **Card colour:** style override first, then the linked subject folder colour (first-level library folder), then the type
  colour. Text and icons switch between white and ink by luminance, so Sand/Lime cards stay readable. The block is a flat
  16 % black shade, with no gradients or shadows.
- **Live screen:** 1 s `rememberTickingNow` (lifecycle STARTED only). The pager list is built once per planner change, so
  pages never jump while you watch. Prev/next buttons and "2 of 7" sit below the card for keyboard and S Pen. Share records
  the card with a `GraphicsLayer` (on a paper margin, so there are no transparent corners). It writes a PNG to
  `cacheDir/countdown` (only the latest is kept) and shares it with a text line. "Add to calendar" uses planner-agent's
  permission-free `insertViaApp`, or "Open in calendar app" when a phone copy exists. Delete stays on the screen when other
  pages remain.
- **Widgets / battery:** no `updatePeriodMillis` on the new widget. More than 24 h out, it shows big whole days with a small
  "4 hours" line, refreshed by one non-wakeup RTC alarm when the hour count drops. Under 24 h it uses a count-down
  `Chronometer` animated by the launcher, with no app wakeups. The Upcoming widget block uses calendar labels
  ("3 days", "10:00 Today", "Now") that change only at midnight, start or end. Its refresh alarm now also fires at item
  start. The Exam widget keeps grades-agent's hourly non-wakeup tick, with "3 days" / "5 hours" / "<1 hour" / "Now".
- Widget tap → live countdown via `EXTRA_COUNTDOWN_EVENT` / `EXTRA_COUNTDOWN_START`. MainActivity opens it on the Planner tab.
  An empty Countdown widget opens "Add event".
- Widget type icons are hand-written vector drawables (Material paths). Exam uses the pencil glyph in widgets
  (`Quiz` in the app).
- The configure activity sets `configuration_optional|reconfigurable` (API 31+), so the default "next exam" works without
  setup. Older launchers show the picker once.

## Edits outside planner/countdown + widget (all allowed by the task, listed for the lead)
- `ui/Nav.kt`: `Screen.Countdowns`, `Screen.Countdown(eventId, occStart = 0)`, `EXTRA_COUNTDOWN_EVENT/START`.
- `MainActivity.kt`: two `Route` branches + widget deep link in `handleIntent`.
- `AndroidManifest.xml`: `.widget.CountdownWidget` receiver + `.widget.CountdownWidgetConfigActivity` (APPWIDGET_CONFIGURE).
- `ui/Screens.kt`: Home calls `HomeUpcomingSection()`. Removed the old `UpcomingSection`, `Countdown`, `whenLabel`,
  `DateUtilsCompat`, `eventColor`, the `upcoming` val and 3 imports that became unused. The strings `countdown_*` in
  strings.xml are now unused.
- `planner/PlannerScreen.kt`: header "Countdowns" button, `EventRow` long-press menu (`EventLongPressMenu`).
  `planner/WeekView.kt`: the same long-press on timed blocks (one small edit).
- `planner/EditEvent.kt`: "Countdown style" section, "Show countdown" top-bar action, folder preset from `CountdownDraft`.
- `widget/Widgets.kt`, `widget/ExamWidget.kt`: card-row rendering, CountdownWidgets refresh hook. The exam widget's unused
  private `colorFor` was removed; `WidgetCards.colorOf` gives the same subject→exam colour (plus the style override).

## Requests to lead
1. Optional cleanup: `countdown_now/dh/hm/m/left` in `values{,-ar}/strings.xml` are no longer referenced.
2. Optional: list the Countdown widget and the Countdowns screen in DESIGN.md §4/§5.
3. Device test wanted: the Chronometer count-down in the launcher, the share sheet image, and the configure flow on One UI.

## Self-check
1. Countdowns screen (filter All/types/subjects, Past/Future counts, search, + with type preset (+ subject folder),
   flat 16dp colour cards with type icon, title, subtitle, full localized date+time, end block variants, weekly next
   occurrence, RTL mirroring via start/end, one-time hint bubble): **PASS** (code-complete; no emulator).
2. Live countdown (reference layout, 1 s tick only while visible, Started/Now, Finished + Grade it for exams, share image,
   ⋯ Edit/Open folder/Add to calendar/Mark done/Delete, swipe next/previous, split pane via BoxWithConstraints): **PASS**.
3. Home Upcoming redesign (compact cards with end block, tap → live, See all → Countdowns, 60 s tick only while visible):
   **PASS**.
4. Widgets: (a) single-event Countdown widget with configure activity, default next exam, 2×2 small / ≥ 3 cells wide
   layout, colour card, big days + hours line, Chronometer under 24 h, non-wakeup refresh only on label change, tap → live
   countdown: **PASS** (device test pending). (b) Upcoming + Exam widgets in card style with end number block, tap → live
   countdown: **PASS**.
5. Planner: header Countdowns entry; long-press "Show countdown" in Agenda, Month list and Week blocks: **PASS**.
6. Strings en + real Arabic, typography only in Compose (sp only in widget XML, as in existing widgets), pane.* navigation,
   compiles: **PASS** (whole tree BUILD OK).
