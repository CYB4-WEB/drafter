# grades-agent log

# Round 2 (v3 request: GPA / grades tracker + exam countdown widget)

## Understanding
User: "GPA / grades tracker per subject, exam countdown widget — supports A+, A, B and B+ too".

1. **Model**: terms → courses (name, code, credits, optional subject folder = first-level library folder, pass/fail flag,
   either a final letter or weighted assessments). Assessments: name, weight %, score / out of. Course % → letter via the
   active grading scale (US 4.0 with A+ = 4.0 or 4.33, Saudi/Gulf 5.0, Saudi 4.0, fully editable custom scale).
   Term GPA + cumulative GPA weighted by credits, pass/fail excluded. "What do I need on the rest/final for an A / B+?"
   calculator. Stored in `filesDir/grades.json` (tmp + rename), observable (Compose state + version).
2. **GradesScreen**: cumulative GPA (big number + flat Canvas trend of term GPAs), terms with GPA, course cards with letter
   badge colour + subject folder colour, course detail (assessments editing, standing, needed-score), add/edit dialogs,
   phone / tablet / split pane, RTL.
3. **Exam countdown widget** (RemoteViews): next 1–3 Planner exams, "3 d 4 h", subject colour bar, title, date; resizable;
   tap opens the event; refreshed with the planner widget refresh + a non-wakeup RTC alarm when the countdown text changes.
4. **Entry points**: "Grades & GPA" card in Study, tiny GPA stat on Home, "Grade it" action on past exam events.

## Plan (files)
| File | Content |
|---|---|
| `grades/GradesModel.kt` | serializable model, scale presets, calculations (percent, letter, GPA, needed score) |
| `grades/GradeBook.kt` | store: `filesDir/grades.json`, atomic writes on a single IO thread, `version` state, pending "grade this exam" handoff |
| `grades/Grades.kt` | `GradesScreen()` (kept), list/detail adaptive layout |
| `grades/GradesParts.kt` | summary card + trend chart, term section, course card, letter badge, detail pane, calculator |
| `grades/GradesDialogs.kt` | term, course, assessment, scale editor, grade-exam dialogs |
| `grades/GradesEntry.kt` | `GradesEntryCard()` (Study), `GpaHomeStat()` (Home), `GradeItButton` (Planner editor) |
| `widget/ExamWidget.kt` | `ExamWidget` provider + update + countdown + refresh alarm |
| `res/layout/widget_exams.xml`, `res/xml/widget_exams.xml` | widget layout/provider info (reuse widget_bg / widget_bar drawables) |
| `res/values{,-ar}/strings_grades.xml` | en + ar, prefix `grades_` |

Edits outside my files (allowed by the lead's task): `AndroidManifest.xml` (ExamWidget receiver), `study/StudyScreen.kt`
(entry card), `ui/Screens.kt` (tiny GPA stat), `planner/EditEvent.kt` (one "Grade it" button), and one line in
`widget/Widgets.kt` `refreshNow` so the exam widget follows every planner change ("existing planner widget refresh mechanism").

## Progress
- 2026-10-04: Read the brief, DESIGN, planner + study logs, planner store/format/alarms/receivers, widget code + res, Nav,
  MainActivity routing, Storage, study subjects helpers. Wrote Understanding + Plan.
- Model + scales (`GradesModel.kt`), store (`GradeBook.kt`), screen (`Grades.kt`), parts (`GradesParts.kt`),
  dialogs (`GradesDialogs.kt`), entry points (`GradesEntry.kt`), widget (`widget/ExamWidget.kt`, `layout/widget_exams.xml`,
  `xml/widget_exams.xml`), strings en + ar (115 keys each, Arabic plural with all 6 forms). XML well-formedness checked with python.
- Cut off by a usage limit; resumed 2026-10-05 from the lead's WIP checkpoint. Compiled the real tree.
- Compile (real tree, 2026-10-05): **0 errors in grades/, widget/, StudyScreen.kt, Screens.kt, EditEvent.kt** (filtered run).
  The tree still fails on other agents' files (`pdf/PdfTranslate.kt` missing `pdf3_*` strings, `ui/tags/SmartFiltersUi.kt`
  missing `tags_*` strings) — not mine.

## Decisions & limits
- **Scales**: US 4.0 (A+ 97, A 93, A− 90, B+ 87, B 83, B− 80, C+ 77, C 73, C− 70, D+ 67, D 60, F; A+ = 4.0 or 4.33 switch),
  Saudi/Gulf 5.0 (A+ 95 → 5.0 … D 60 → 2.0, F → 1.0 as specified), Saudi 4.0 (A+ 4.0, A 3.75, B+ 3.5 … F 0). Custom scale:
  editable rows (letter, min %, points), starts as a copy of the current preset, validated (unique letters, 0–100, points ≥ 0).
  One active scale for the whole record (changing it recomputes every GPA). Letters compare ignoring case and "−"/"-".
- **Course grade**: either a final letter (letter mode) or computed from assessments. Weights are normalised by their sum
  (a warning shows when they do not add up to 100). Courses with some graded components count in the GPA with their
  current standing (shown as an outlined "in progress" badge); courses with nothing graded, pass/fail courses, 0-credit
  courses and letters not on the scale are excluded.
- **Calculator**: for every letter above F, the average % needed on the ungraded components (named "on the Final" when only
  one is left), or Secured / Out of reach; plus a "locked in even with 0 on the rest" line.
- **Store**: `filesDir/grades.json`, loaded synchronously on first use (small), mutations on main, tmp + rename writes on a
  single IO thread; `GradeBook.version`/`data` are Compose state. Term/course deletes have Undo snackbars.
- **Layout**: two panes (list | detail) when the available width ≥ 760dp (BoxWithConstraints, so it adapts inside split panes),
  otherwise list → detail with back. Trend chart is a flat Canvas line (mirrored in RTL), no gradients/shadows.
- **Widget**: `ExamWidget` (3×2 target, resizable down to 1 row). Rows: subject colour bar (first-level library folder of the
  event's linked folder, else exam red), title, "Tue 09:00 · Hall B", countdown chip ("3 d 4 h", "12 d", "5 h", "< 1 h", "Now").
  Row tap → `EXTRA_EVENT_ID` (opens the event); background tap → Planner. Refresh: hooked into `Widgets.refreshNow` (every planner
  change, boot, planner refresh alarm) + one **non-wakeup RTC** alarm at the exact moment a label changes (hourly at most, daily
  beyond 10 days) + 6 h `updatePeriodMillis` safety net. Alarm cancelled when no widget/exam. Light/dark via `@color` like the others;
  texts set at runtime with `localized()` (in-app language).
- **"Grade it"**: shown in the planner event editor's top bar for non-weekly exams that have ended. It hands the event id to
  `GradeBook.pendingExam` and opens Grades, whose dialog pre-selects the course (same subject folder, else name/code in the title)
  and component (name in title, else first ungraded), or creates a course/term. The assessment stores `eventId` so re-grading edits it.

## Edits outside my own files
- `AndroidManifest.xml`: `.widget.ExamWidget` receiver (APPWIDGET_UPDATE, `@xml/widget_exams`).
- `widget/Widgets.kt` (planner-agent): one line in `refreshNow` → `ExamWidgets.refresh(ctx, list)` ("existing planner widget refresh mechanism").
- `planner/EditEvent.kt`: one line in the top bar → `com.daftar.app.grades.GradeItButton(existing)`.
- `study/StudyScreen.kt`: `com.daftar.app.grades.GradesEntryCard()` before `FocusSection()` in both layouts.
- `ui/Screens.kt`: `com.daftar.app.grades.GpaHomeStat()` after `StudyHomeCard()` in both Home layouts (renders nothing without a GPA).

## Requests to lead
1. Please accept the one-line hook in `widget/Widgets.kt` (planner-agent's file) — without it the exam widget would only update on its own tick.
2. Optional: list the Exam countdown widget in DESIGN.md §5.

## Self-check
| # | Criterion | Status |
|---|---|---|
| 1 | Model: terms/courses (subject link, credits, letter OR assessments), weights+scores, 3 presets + A+ 4.33 option + editable custom scale, term & cumulative GPA by credits, pass/fail excluded, needed-score calculator, `grades.json` atomic + observable | PASS |
| 2 | GradesScreen: cumulative big number + trend chart, terms with GPA, course cards (letter colour + subject colour), detail with assessments editing, standing, calculator, add/edit dialogs, phone/tablet/split pane, RTL | PASS (code-complete; no emulator here) |
| 3 | Exam countdown widget: next 1–3 exams, countdown, colour bar, title, date, resizable, tap opens event, battery-friendly refresh, light/dark | PASS (code-complete; device test by lead) |
| 4 | Entry points: Study "Grades & GPA" card with cumulative GPA, tiny Home GPA stat, "Grade it" on past exams | PASS |
| 5 | Strings en + real Arabic (115 keys each, plurals), typography only (no sp in Compose), no Nav.* calls (pane.push only) | PASS |
| 6 | Compiles | PASS for my files (0 errors); tree fails only in pdf/PdfTranslate.kt and ui/tags/SmartFiltersUi.kt (other agents) |
