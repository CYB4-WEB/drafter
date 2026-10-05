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
