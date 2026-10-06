# v3.5

## Understanding
- The user wants flashcards gone entirely (Make card from the lasso menu, Review screen, decks, SM-2 scheduling,
  the daily "cards due" reminder + its notification action `ACTION_STUDY_REVIEW`, "cards reviewed" stats) including the
  card data on disk (`filesDir/study/cards.json` + `filesDir/study/img/`), deleted once.
- What stays in Study: the focus (Pomodoro) timer, its notification/alarms/DND, focus stats (minutes, 7-day chart,
  per-subject, streak based on focus days), `StudySettingsSection()` (timer settings only), `StudyLinks`,
  `StudyHomeCard()` / `FocusChip()` (used by the lead's Home / MainActivity) and the subject helpers used by grades.
- Replacement: **AI practice quizzes** built by Gemini from a source = lasso selection image and/or a library file
  (page range via `ai.FileContext.pageCount/parts`) + options (count, question types — several can be selected —,
  difficulty, language auto/ar/en, free-text instructions). Gemini returns strict JSON (`jsonSchema`). MCQ/TF graded
  locally, short/essay graded by Gemini (JSON: score + feedback). Results show explanations for wrong answers;
  quizzes saved as JSON in app-private storage (with source file name + pages), retake / rename / delete from the hub.
- Contracts I provide: `QuizRequests.prepare(file, pages, image, instructions)` (ai-agent's chat chip calls it, then
  `pane.push(Screen.NewQuiz)`), `NewQuizScreen()`, `QuizScreen(id)`. I consume `Gemini.generate(..., jsonSchema)`,
  `Gemini.AiException.Kind`, `AiPrefs.hasKey`, `ai.FileContext` (ai-agent's first deliverable), `LibraryFilePickerDialog`.
- No key → card explaining it + button to `Screen.Settings`. Privacy notice before the first request
  (`AiPrefs.privacyAccepted` / `acceptPrivacy()`). Everything off the main thread, cancellable, with progress.

## Plan
1. Delete `Cards.kt`, `MakeCard.kt`, `Review.kt`; move shared helpers (`StudyDialog`, `atomicWrite`, day helpers,
   study dir) into `StudyLog.kt`/`StudyParts.kt`; strip card bits from `Focus.kt` (daily reminder, `due()` notification,
   `ACTION_STUDY_REVIEW`), `FocusUi.kt` (home card, stats, settings), `StudyParts.kt` (card images, ink pad, due labels).
2. `StudyCleanup.runOnce()` (IO, flag in prefs): delete cards.json(+tmp) and img/, cancel the old daily alarm, its
   notification and channel. Called from the Study hub, the Home card and the boot receiver.
3. `Quiz.kt`: model + `QuizStore` (`filesDir/study/quizzes/<id>.json`, atomic writes, observable version) +
   `QuizAi` (schema, prompt, validation, batch grading) + friendly error mapping.
4. `QuizRequests.kt`: in-process draft (file, pages, selection JPEG — not a Bitmap — instructions, options) and the
   generation job in an app scope (survives pane moves; cancel button).
5. `NewQuiz.kt` (form), `QuizScreen.kt` (one-at-a-time or list mode, check, submit, results, retake),
   `StudyScreen.kt` (hub: New quiz button, saved quizzes list with open/retake/rename/delete, focus + stats).
6. Strings en/ar in `strings_study.xml`, remove card strings. Compile with `FILTER=study/ tools/compile.sh`.

## Progress
- Deleted `study/Cards.kt`, `study/MakeCard.kt`, `study/Review.kt` (Flashcard model, SM-2, card store, image cache,
  Make/Edit card dialogs, Review screen, ink pad). `StudyDialog`, `atomicWrite`, day helpers and `studyDir` moved to
  `StudyLog.kt` / `StudyParts.kt`.
- `Focus.kt`: removed `ACTION_STUDY_REVIEW`, the daily "cards due" reminder (prefs, alarm, receiver branch, `due()`
  notification, `study_daily` channel). Boot receiver re-arms the timer and runs the cleanup.
- `StudyLog.kt`: focus log only (old `reviews` field is ignored when reading — `json` has `ignoreUnknownKeys`).
  `StudyCleanup.runOnce()` deletes `filesDir/study/cards.json(.tmp)` + `filesDir/study/img/`, cancels the old daily
  alarm (rc 7411), its notification (7403) and channel, removes `daily`/`dailyAt` prefs; flag `cardsRemoved` in the
  "study" prefs → runs once. Called (on IO) from the Study hub, the Home card and the boot receiver.
- `FocusUi.kt`: stats tile "Cards reviewed" → "Focus blocks"; streak = focus days; Home card shows quizzes + "New quiz";
  `StudySettingsSection()` keeps only the timer row.
- New: `Quiz.kt` (model, `QuizStore` = `filesDir/study/quizzes/<id>.json` atomic, `QuizAi` strict schemas for
  generate + batch grading, validation, `quizErrorText()`), `QuizRequests.kt` (draft + generation job in an app
  scope, cancellable, stages), `QuizUi.kt` (no-key card, error card, privacy notice), `NewQuiz.kt`, `QuizScreen.kt`,
  `StudyScreen.kt` (hub).
- Strings: card strings removed, `quiz_*` + `study_blocks` added in en + ar (`strings_study.xml`, XML validated,
  every used key present in both files and no unused keys left).
- `MakeFlashcardDialog`: the lead already removed the call from `ink/InkEditorImpl.kt`, so **no stub** was left.

## Decisions & limits
- `QuizRequests.prepare(file, pages, image, instructions)`: **`pages` is 0-based inclusive** (same as `FileContext`);
  null = whole file. The image is copied and JPEG-encoded in the background (≤1600 px) — the caller keeps ownership
  and may recycle its bitmap right after the call. Only the JPEG bytes stay in memory (no Bitmap).
- The hub's "New quiz" calls `prepare(null, null, null)` (fresh draft) — count/types/difficulty/language are kept
  from the previous quiz as user preferences (in memory).
- No source + instructions only → Gemini writes a topic quiz from general knowledge ("10 questions about photosynthesis").
- Question types are multi-select chips (MCQ, TF, short, essay). MCQ may have several correct answers when the user
  asks ("select all that apply") → checkboxes, all-or-nothing grading.
- Short/essay are graded by Gemini (score 0–100 + feedback, JSON). "Check" grades one question; "Finish" grades
  all unchecked ones locally + one batched Gemini call; blank answers score 0 without a request.
- One-at-a-time mode is default on phones, list mode on wider screens; toggle in the top bar (not persisted).
- Privacy notice uses the shared `AiPrefs.privacyAccepted` flag (shown once for chat or quiz, whichever comes first).
- "Ask AI about this question" (optional) not implemented.
- Compile: full `tools/compile.sh` → **BUILD OK** (with ai-agent's files in place). ai-agent's `AiPanel` calls
  `QuizRequests.prepare(session.file, page..page, bmp, instructions)` then recycles `bmp` — safe (I copy it first).

## Requests to lead
- `AndroidManifest.xml` line ~116 comment still says "daily cards-due reminder": cosmetic, could become
  `<!-- focus timer phase ends / notification actions / re-arm after reboot -->`. Receivers stay as they are.
- No stub of `MakeFlashcardDialog` was needed (the call in `ink/InkEditorImpl.kt` is already gone).

## Self-check
| Requirement | Status |
|---|---|
| Flashcards removed from `study/**` (Cards, MakeCard, Review, decks/SRS, card stats) | PASS |
| Review reminders / `ACTION_STUDY_REVIEW` / daily reminder settings removed | PASS |
| Card data on disk deleted once (cards.json, img/, old alarm, notification, channel, prefs) | PASS (`StudyCleanup.runOnce`) |
| Focus timer, focus stats, `StudySettingsSection()` (timer only), `StudyLinks`, `StudyHomeCard`, `FocusChip` kept | PASS |
| `QuizRequests.prepare(...)`, `NewQuizScreen()`, `QuizScreen(id)` per contract | PASS |
| New quiz: selection image and/or library file (`LibraryFilePickerDialog`) + page range via `FileContext.pageCount` | PASS |
| Count, types (multi-select), difficulty, language auto/ar/en, free-text instructions | PASS |
| Generate with progress (reading / writing / saving) + cancel; survives rotation / pane moves | PASS |
| Strict JSON schema via `Gemini.generate(jsonSchema=…)`, output validated | PASS |
| Take quiz one-at-a-time or list; MCQ/TF checked locally; short/essay graded by Gemini (score + feedback JSON) | PASS |
| Results summary with explanations, correct/model answers, mistakes-only filter, retake, attempt history | PASS |
| Saved as JSON in app-private storage incl. source file name + pages | PASS |
| Study hub: prominent New quiz, saved list (open, retake, rename, delete, open source) + focus timer + stats | PASS |
| No API key → card with button to `Screen.Settings`; friendly errors for every `AiException` kind | PASS |
| Off the main thread (file reading, JSON I/O, JPEG encode, network) | PASS |
| en + ar strings, unused card strings removed | PASS |
| "Ask AI about this question" (optional) | not done |
| No API key written anywhere | PASS |
| Not tested on a device (no emulator) | — |

# v3.6

## Understanding
New quiz sources: any mix of the selection image + several library files + whole folders (recursive, every
AI-readable file, hidden / `.`-prefixed entries skipped). Each row: name, kind, page count (folders: file count),
single files get an optional page range, remove per row. Generation reads every source within the shared caps
(~120k chars, ≤20 images in total), split fairly across files, text preferred when there are many files, file names
given to Gemini so questions can cite their source, per-file reading progress. All source names saved in the quiz
JSON; old quizzes (single `sourcePath/sourceName/pages`) still load. `QuizRequests.prepare(...)` keeps its signature.

## Plan
- `QuizRequests`: `sources` list (`QuizSource(file, folder, whole, from, to)`) replaces the single file; `prepare()`
  maps its `file/pages` to one source. Reading progress = (index, total, name).
- `QuizContext.kt` (study/): expands folders, then builds parts with a budget: pass 1 text only (`FileContext.pages(…,
  images = false)`), char budget water-filled across files; pass 2 images water-filled across visual files (with
  many files only pages that have little text get images), rendered one page at a time and freed immediately.
- `Quiz.sources: List<QuizSourceInfo>` (default empty → old files decode); helper falls back to the old fields.
- `FolderPickerDialog` in study/ (built on `Storage.list`). NewQuiz source list UI. Strings en + ar.
