# study-agent

# Round 2

## Understanding
Flashcards made from notes (lasso → "Make flashcard") with SM-2 spaced repetition, a Quizlet/Anki-style Review screen,
a Pomodoro focus timer with study time per subject, study stats, a Home card and an optional daily "cards due" reminder.
Decks = subject folders (first folder under the library root), "" = General.

## Plan / files (all new unless noted)
| File | Content |
|---|---|
| `study/Study.kt` (stubs replaced, 3 signatures kept) | entry points; `StudyLinks` (open source at page) |
| `study/Cards.kt` | `Flashcard` model, `Sm2` scheduler, `Flashcards` store (`filesDir/study/cards.json`, images in `filesDir/study/img/`), `ImageCache` |
| `study/StudyLog.kt` | focus sessions + daily review counts (`filesDir/study/log.json`), stats helpers (minutes per subject, streak) |
| `study/Focus.kt` | `FocusTimer`, `StudyPrefs`, alarms, `StudyReceiver` / `StudyBootReceiver`, notifications, DND |
| `study/MakeCard.kt` | `MakeFlashcardDialog` (+ overload with `page`), `CardEditorDialog`, side editor, dialog shell |
| `study/StudyParts.kt` | subjects/deck helpers, `CardImage`, `InkPad` (Compose strokes → bitmap), interval/due labels |
| `study/StudyScreen.kt` | Study hub: review-all banner, search, deck grid, deck detail list (edit/delete/reset/open source), New card FAB |
| `study/Review.kt` | `ReviewScreen`: flip animation, grades with SM-2 hints, swipe, keys, summary, "Open in note" |
| `study/FocusUi.kt` | Focus section, timer settings dialog, stats (Canvas charts), `StudyHomeCard`, `FocusChip`, `StudySettingsSection` |
| `res/values{,-ar}/strings_study.xml` | en + ar, prefix `study_` |

### Edits outside study/ (allowed by the brief)
- `ui/Screens.kt` HomeScreen: `com.daftar.app.study.StudyHomeCard()` right after `UpcomingSection(upcoming)` in both layouts (2 lines).
- `AndroidManifest.xml`: `ACCESS_NOTIFICATION_POLICY` permission (optional DND), `.study.StudyReceiver` (not exported) and
  `.study.StudyBootReceiver` (exported, system broadcasts only: BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED).
- DaftarApp.kt: **not touched** — study notification channels are created lazily by `StudyNotify`.

## Decisions & limits
- **Store**: Compose `mutableStateListOf` + `version`; mutations on main thread; writes coalesced on one background thread,
  atomic (temp file + fsync + rename). Lazy-loaded on first access (Storage.appCtx), so receivers work in a cold process.
- **SM-2** (Anki-style 4 buttons): Again → relearn in 10 min, reps 0, ease −0.2, lapse++; Hard → ×1.2 (min +1 d), ease −0.15;
  Good → 1 d, 6 d, then ×ease; Easy → 4 d, 10 d, then ×ease×1.3, ease +0.15. Ease ≥ 1.3. Due dates land at local midnight.
  Again cards are re-queued at the end of the current session. Buttons show the next interval ("10m", "6d", "1.5mo").
- **Timer, lowest battery**: no foreground service, no ticking in the background. State = wall-clock timestamps in
  SharedPreferences (survives process death). Phase end = one exact alarm (falls back to inexact `setAndAllowWhileIdle` when
  exact alarms are not allowed). The ongoing notification uses the system chronometer countdown (`setChronometerCountDown`)
  so the remaining time is drawn by System UI with zero app wakeups; actions Pause/Resume, Skip, Stop. UI ticks 1 s only while
  visible (`rememberTickingNow`, lifecycle STARTED) and ends the phase itself if it reaches zero on screen (idempotent with the alarm).
- Sessions are recorded when a focus block ends, is skipped or stopped (≥ 1 min), with subject, start and minutes.
- Auto-start next phase (default on); long break every N (default 4); 25/5/15 defaults, all configurable.
- **DND**: off by default; turning it on first explains and asks, then opens the system "Do Not Disturb access" page.
  While a focus block runs → priority-only; restored afterwards only if Daftar changed it.
- **"Lasso the answer later"**: card saved with `backPending`; it shows "Answer needed". When the user later lassos in the
  same source and taps "Make flashcard", the dialog offers "Use this selection as the answer for …" → fills the back. No ink changes needed.
- Card images are shown on a paper-coloured box in both themes (ink is dark on transparent).
- Image cache: one LruCache bounded by bytes (heap/16), trimmed when Study/Review leave; decode with inSampleSize.
- Deep links from study notifications use `EXTRA_ACTION` = `study` / `study_review`. MainActivity routes unknown actions
  to Home → `StudyHomeCard` picks them up and opens Study / Review (works now; see request 2 for the clean route).
- Floating chip: `FocusChip()` is ready but an app-wide overlay needs AppShell (MainActivity) → request 1. Until then the
  ongoing notification is the always-visible timer, as the brief allows.

## Requests to lead
1. **Floating focus chip on every screen** (MainActivity/AppShell, workspace-agent owned): overlay
   `com.daftar.app.study.FocusChip(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp))`
   inside the root Box of AppShell (it renders nothing when the timer is idle or Study is showing).
2. **Notification deep links** (MainActivity.handleIntent, optional — current workaround works):
   ```kotlin
   "study" -> Nav.tab(Screen.Study)
   "study_review" -> { Nav.tab(Screen.Study); Nav.push(Screen.Review(null)) }
   ```
3. **Settings**: embed `com.daftar.app.study.StudySettingsSection()` in SettingsScreen (e.g. after `PlannerSettingsSection()`).
4. **Open card source at its page** (ink / pdf owners): when a Note/PDF screen has loaded file `f`, call
   `com.daftar.app.study.StudyLinks.consumePage(f)?.let { controller.goToPage(it) }`. Until then "Open in note" opens the file at its last position.
5. **ink4-agent**: call `MakeFlashcardDialog(source, front, recognizedText, page = <0-based page>, onDismiss)` (overload with page)
   so cards remember their page; the 4-argument version stays valid (page unknown).

## Self-check
| # | Criterion | Status |
|---|---|---|
| 1 | Store: cards.json + img/, all fields incl. SM-2, atomic background writes, observable | PASS |
| 2 | MakeFlashcardDialog: front image + editable text, back type/draw/lasso-later, deck defaults to subject, Save toast, Save & make another | PASS |
| 3 | StudyScreen: deck grid by subject (colour/icon), due counts, Review all due, per-deck list (edit/delete/reset/open source), New card, search, focus + stats | PASS |
| 4 | ReviewScreen: flip animation, tap/Space, 4 grades with interval hints, summary, swipes, keys 1–4, Open in note, phone/tablet/pane | PASS ("at page" needs request 4) |
| 5 | Focus timer: configurable 25/5/15 ×4, subject picker, start/pause/skip/stop, alarm + chronometer notification with actions, no background ticking, optional DND (asks first), sessions recorded | PASS; floating chip on every screen PARTIAL (request 1; notification covers it) |
| 6 | Stats: minutes per subject today/week (Canvas bars), 7-day chart, streak, cards reviewed | PASS |
| 7 | StudyHomeCard: due count + Review, focus minutes today, start/pause focus | PASS |
| 8 | Daily cards-due reminder, off by default, in StudySettingsSection (time picker) | PASS (needs request 3 to be visible) |
| – | en + ar strings, flat design, RTL (charts mirror), compiles | PASS (see Progress) |

## Progress
- All files written. Full `tools/compile.sh` → BUILD OK (an earlier run failed only on ink/InkView.kt MathAssist visibility, owned by another agent; it is fixed now).
