# Daftar — Product spec (evaluated & upgraded)

## Who / why
University student with a Galaxy Tab S11 Ultra + S Pen. Today their lectures, seminar slides, lab sheets, PDFs and handwritten notes
are scattered across Files, Samsung Notes, PowerPoint and Drive. Daftar = one calm place: **organise → read → annotate → write → plan**.

## Original requirements (user) → status in scope
1. Folder manager: folders with colours, icons, titles; lectures/seminars/labs inside; easy create & manage. → Library + "course template".
2. Easy to use; split-screen ("cut window"); tablet landscape & portrait. → adaptive layout, config changes handled in-place.
3. PPTX: open & read well, add notes, see comments. → custom PPTX renderer + speaker notes + comments + "My notes" + draw on slides.
4. PDF: write/draw on it, PDF→images, images→PDF, extract pages, print page(s), copy text. → PDF viewer + annotation + tools menu.
5. Notability-style notebooks (whiteboard, pages, pen, highlighter, eraser, lasso, shapes, text, images, audio recording synced to ink, paper styles). 
6. Word (DOCX) reader. 
7. Modern, simple design; no flashy gradients / "AI look". → DESIGN.md, user mockup.
8. Arabic + English (+ follow system). → full string resources, RTL.
9. Speech → text using the phone's recogniser. → Android SpeechRecognizer (Samsung/Google engine on device), ar/en.
10. Handwriting → typed text. → ML Kit Digital Ink (en + ar, offline after one model download).
11. S Pen + its side button. → pen-only mode (finger scrolls), side button = eraser or lasso, eraser end supported, pressure.
12. Fonts. → several font families for text boxes (incl. Arabic-friendly).
13. Lightweight. → R8 minified release, no web engine, no heavy office SDK.
14. (added later) Planner/timetable: exams, assignments, meetings, classes, anything — date, description, link; reminders. Notifications.
15. (added later) Home-screen widgets.

## Upgrades proposed by lead (included)
- Course template: creating a subject auto-creates Lectures / Seminars / Labs subfolders with matching icons.
- Home dashboard: Today's planner items, pinned, recent, quick actions (new note, folder, import, images→PDF, add event).
- "Open with"/share-to-Daftar from other apps (WhatsApp, Gmail, Drive) → saved into Inbox and opened.
- Audio recording replay (Notability's signature feature): strokes fade in in sync with the lecture recording; tap ink to jump the audio.
- Global search; pin anything; move/duplicate/share; sort & grid/list views.
- Planner links to a subject folder; assignments can be ticked done; daily morning summary notification.
- Fallback "Open in another app" in every viewer for 100% fidelity when needed.

## Out of scope (honest limits)
- Pixel-perfect PPTX (animations, SmartArt, charts render as best-effort/placeholder). Legacy .ppt/.doc → open externally.
- Cloud sync. (Files live in app storage; share/export any time.)

---
# Round 2 (user feedback, 2026-10-04) — requirements R2.x

R2.1 Notes like OneNote/Notability: **infinite canvas** option (whiteboard that grows in every direction) besides paged notes; a visible **size bar** (stroke-width slider), **pen types** (ballpoint, fountain, pencil, brush, marker), image import, **link items**: web/video URL or a path to a file in the library (PowerPoint, PDF…) placed on the page; tapping opens it **inside Daftar** (files in their viewer, web/video in an in-app browser).
R2.2 Import **folders and files** into the library, choosing **copy or move**.
R2.3 **Converter** for every supported type, both from a **Convert section** and from inside each opened file: PDF→images, images→PDF, PPTX→PDF, PDF→PPTX, DOCX→PDF, PDF→DOCX (text), note→PDF/images, merge/split PDF.
R2.4 Research-driven expansion (Notability, GoodNotes, OneNote, Xodo, Samsung Notes): tape tool (self-quiz), laser pointer, ruler, PDF search/outline/page management/merge, PowerPoint present mode.
R2.5 Planner events can be **added to the device calendar/reminders** after asking the user.
R2.6 Word: wider acceptance (.docx + .txt/.md/.rtf/.csv readers) and a **native-like UI per file type** (Word print-layout pages, PowerPoint slide rail + notes pane + present, PDF reader header) in Daftar's design.
R2.7 **Share** any file from wherever it is open.
R2.8 **Open two files at the same time**: in-app split screen with adjustable divider (side-by-side or stacked), any combination (image+PDF, PDF+PowerPoint, note+PDF…), both fully usable; plus open a file in a **separate window** (Samsung multi-window / pop-up), drag content between.
R2.9 **Low RAM and disk**: bounded caches, release build minified/shrunk.
R2.10 Design review; faster; **bigger, adjustable text/header sizes** in Settings; every Settings option must work.
R2.11 **Zoom in/out** with fingers (pinch), pen, or on-screen buttons.
