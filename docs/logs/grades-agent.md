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
