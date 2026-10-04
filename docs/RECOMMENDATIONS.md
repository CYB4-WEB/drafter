# Daftar — what's left and what I'd add (for your approval)

Nothing below is built yet. Tick what you want and I'll schedule it.

## A. Still open from your requests
| # | Item | Status |
|---|---|---|
| A1 | Typing directly on the note paper (move / resize / font bar) | being built now (notes3-agent) |
| A2 | Note export: images, Word, text, "share page as image" + note Print with range | being built now (notes3-agent) |
| A3 | Smoothness pass (ink bitmap cache, fewer recompositions, off-thread saving) | being built now + final pass by me |
| A4 | OneNote `.one` / `.onepkg` viewer + "Import as Daftar note" | being built now (onenote-agent) — best effort: OneNote's format is proprietary; text, images and layout should work, exotic content may not |
| A5 | Ruler / straightedge in notes | not done (clashed with pinch-zoom; needs device testing) |
| A6 | Everything new needs a real test on the Tab S11 Ultra (S Pen, drag & drop between panes, multi-window, calendar) | needs you |

## B. What similar apps have and Daftar doesn't (research: GoodNotes 6, Notability, Samsung Notes, OneNote, Xodo, MyStudyLife, Anki/Quizlet)

### High value for a student, no internet/AI needed — my recommendation: do these
1. **Flashcards from notes + spaced repetition** (Notability "Learn", Anki/Quizlet). Lasso any ink/text → "Make flashcard" (front/back); a Review screen with spaced repetition (SM-2 schedule), per subject folder; review reminder in the planner.
2. **Focus / Pomodoro timer** (MyStudyLife). 25/5 timer linked to a subject, counts study time per subject, weekly chart on Home; optional Do-Not-Disturb while running.
3. **Document scanner** (Samsung Notes / Xodo / OneNote Lens). Camera → auto crop/deskew → multi-page PDF into a folder. Uses Google's on-device ML Kit Document Scanner (small, no internet after first use).
4. **Text recognition (OCR) for scanned PDFs and photos** so search and "copy text" work on scans (on-device ML Kit, Latin + Arabic is limited — Arabic OCR quality is the weak point, I'd test it first).
5. **Note page manager**: page thumbnails grid, reorder / duplicate / move pages between notes, page templates (lined, grid, Cornell, planner week, music staff, graph).
6. **Handwriting cleanup / straighten** (Samsung Note Assist, GoodNotes): lasso → "Tidy" aligns lines and smooths strokes.
7. **Math helper** (GoodNotes Math Assist, offline part): write `12×7=` and the answer appears; recognised via the existing handwriting engine.
8. **Recycle bin** (30 days) and **note version history** (restore yesterday's version) — protects students from accidental deletes.
9. **Backup & restore**: one-tap export of the whole library (zip) to Drive/USB/another app and import back; optional auto-weekly backup.
10. **Tags & colour labels on files** + smart filters ("Exam week", "To review").
11. **App lock** (fingerprint/face) for private folders.
12. **Audio recording transcript**: after recording a lecture, transcribe it (Android's on-device speech recognition can only listen live, so this would transcribe *while* recording into a side transcript, synced with the ink).

### AI features competitors now advertise (need an online AI service → cost/privacy decision)
13. Summaries, auto-generated quizzes/flashcards from a PDF or note (Notability Learn, GoodNotes "Ask").
14. "Ask my notes" chat across a subject folder.
15. PDF overlay translation (Samsung) — Arabic ⇄ English for English lecture slides.
I'd only add these if you're happy with an online service (or Samsung's Galaxy AI on the tablet, which some features can hand off to).

### Nice to have
16. Stickers / stamps (checkmarks, arrows, "important"), shape library (arrows, tables).
17. Two-page view for PDFs/notes on the 14.6" screen in landscape.
18. Reading mode for PDFs (reflow text), night paper (dark pages).
19. GPA / grades tracker per subject, exam countdown widget.
20. Share a note as a link (needs a server) — not recommended (no cloud by design).

## C. My opinion on priorities
1. First, test this build on the tablet and send me what feels wrong (writing, lag, layout) — fixing real-device issues beats new features.
2. Then B1 (flashcards + spaced repetition), B3 (scanner), B5 (page manager), B8 (recycle bin) and B9 (backup): these give the biggest jump for a student and stay light and offline.
3. B2 (focus timer) and B6/B7 next. AI (B13–15) only if you decide on an online service.
