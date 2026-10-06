# v3.5

## Understanding
The user wants Gemini inside Daftar, with the user's own key:
- **Select → AI**: in the ink editor (notes, whiteboard, PDF, slides) the lasso bar gets an AI button. The selection
  image (+ recognized text if any) goes to Gemini in a chat panel at the `end` side (bottom sheet when narrow). Quick
  chips Explain · Answer · Solve step by step · Translate · Make questions, or free text; follow-ups keep context.
- **Context escalation**: the model may call `request_pages(from,to,reason)` (1-based for the model) or
  `request_full_file(reason)`. The app asks the user (Allow these pages / Allow whole file / Deny, + "Always allow for
  this file", remembered per file) and only sends what was allowed (text + capped page images), then continues
  automatically (≤ 4 tool rounds).
- **Chats persist per file** (app-private), "New chat" clears. Selection images are saved as small JPEGs next to the
  chat JSON, not kept in RAM.
- **Settings → AI**: key pasted by the user, stored only in `AiPrefs` (never in code/docs/logs). Test key → model list
  → pick model. Privacy text, key-restriction help (package + signing SHA-1 with copy, API restriction), AI Studio link.
  One-time privacy gate before the first request.
- Friendly localized errors for every `AiException.Kind`; NO_KEY offers "Open Settings".

Contracts I provide (package `com.daftar.app.ai`): `FileContext.pageCount/pages/parts`, `PageContent`,
`rememberAiSession(file)`, `AiSession{open, startFromSelection, show, close}`, `AiPanel(session, modifier)`,
`AiSheetHost(session)`, `AiSettingsSection()`. I consume `study.QuizRequests.prepare(file, pages, image, instructions)`
+ `pane.push(Screen.NewQuiz)` from quiz-agent. Lead wires the panel into `InkEditorImpl` / `Screens`.

## Plan
1. `ai/FileContext.kt`: kind dispatch via `Storage.kindOf` (+ `.ppt` → slides, gives 0 pages if legacy).
   PDF: `PdfTools.extractText` per page (OCR sidecar fallback), `PdfSource.renderFit` + ink sidecar overlay.
   Note: `InkDoc.load` + `NoteExport.renderPage` (scaled to ≤1600 px) + typed text boxes. PPTX: `PptxParser` +
   `PptxSource.thumbnail` + shape text/notes. DOCX/TEXT: `DocLoader.load().plainText()` chunked into ~3000-char pages.
   OneNote: `OneLoader.load` → one page per OnePage. Image: decode sampled. Small LRU-ish cache of text-chunked docs.
   Caps in `parts()`: 20 images, ~120k chars. Compile, note "FileContext ready".
2. `AiChatStore.kt` (persistence: `filesDir/ai_chats/<sha1(path)>.json` + `.jpg`), `AiSession.kt` (state, request
   loop, tools, permission state, cancel), `AiMarkdown.kt` (renderer), `AiPanel.kt` (panel, sheet host, dialogs,
   privacy gate, errors), `AiSettings.kt` (settings card).
3. Strings `strings_ai.xml` (en + ar). Compile with `FILTER=ai/`.
4. Self-check + Requests to lead.

## Progress
- **FileContext ready** — `ai/FileContext.kt` (`FileContext.pageCount/pages/parts`, `PageContent`, plus helpers
  `FileContext.isVisual(file)`, `FileContext.kind(file)`, caps `MAX_IMAGES=20`, `MAX_CHARS=120000`, `MAX_SIDE=1600`)
  compiles clean (`FILTER=ai/`). PDF: PDFBox text per page → OCR sidecar fallback; images via PdfRenderer + ink
  sidecar overlay. Notes: typed text boxes + `NoteExport.renderPage`. PPTX: title + shape text + speaker notes +
  thumbnail with ink. DOCX/DOC/TXT/MD/RTF/CSV and OneNote: plain text chunked into ~3000-char pages (one-entry cache).
- Chat built: `AiChatStore.kt` (per-file JSON `filesDir/ai_chats/<sha1(path)>.json` + `<sha1>_<id>.jpg` selection
  images; "always allow" flag stored in the same JSON), `AiSession.kt` (`rememberAiSession`, `AiSession`, request loop,
  tools, permission + privacy gates, cancel/retry, New chat), `AiMarkdown.kt` (headings, lists, bold/italic, `code`,
  code blocks, quotes, rules; `$…$`/`$$…$$` delimiters stripped, common TeX commands → symbols), `AiPanel.kt`
  (`AiPanel`, `AiSheetHost`, permission / privacy / new-chat dialogs, error card), `AiSettings.kt` (`AiSettingsSection`).
- `Gemini.kt`: `Part.Call` gained an optional `signature` (thoughtSignature round-tripped — newer models reject tool
  turns without it); `certSha1` is now `internal` (shown in Settings). `AiPrefs.resetPrivacy()` added. Public
  signatures unchanged.
- Full `tools/compile.sh` → BUILD OK (whole app, with lead's wiring in InkEditorImpl / Screens and quiz-agent's study/).

## Decisions & limits
- `startFromSelection` **takes ownership of the bitmap** (JPEG-encoded off the main thread, then recycled). Only the
  JPEG bytes + a ≤480 px preview stay in memory; on send the JPEG is written next to the chat JSON.
- Follow-ups keep context: in-memory history holds each question (+ its selection JPEG), the *text* of allowed pages
  and the answer (page images are sent once, not kept). After reopening, history is rebuilt from disk (allowed pages
  re-read as text only, ≤ 120k chars).
- Tool rounds ≤ 4; after that the model must answer without tools. `request_pages` args are 1-based, clamped to
  1..pageCount, swapped if reversed; sent via `FileContext.parts` (≤ 20 images, ~120k chars). If the cap cuts the
  range, the function result tells the model which pages it actually got.
- Notes in the chat ("Shared pages 3–5", "You declined…") are stored as codes and localized at display time.
- Chats are keyed by absolute path: renaming/moving a file starts a fresh chat (old one stays orphaned in app-private
  storage). Acceptable for now.
- Make questions: `QuizRequests.prepare(file, page..page, selectionImage, draftText)` with the pending attachment or
  the latest selection in the chat (pages = null → whole file when there is no selection), then
  `LocalPaneNav.current.push(Screen.NewQuiz)`; the AI panel closes.
- NO_KEY / BAD_KEY → "Open Settings → AI" (`LocalPaneNav.current.push(Screen.Settings)`). NO_KEY is detected before any
  network call. The privacy gate runs before the first request only (Settings can show it again).
- No API key appears in code, resources, logs or docs.

## Requests to lead
1. **PDF / slides selection image has no page content.** `InkView.selectionBitmap()` draws only the selected ink on
   white, so lassoing a printed question in a PDF/slide sends only the circle. Please draw the page source under it
   (in `ink/InkView.kt`, `selectionBitmap`, right after `c.drawColor(paper)` and before `c.scale(k, k)`):
   ```kotlin
   source?.let { src ->
       val pi = selectionPage
       if (pi in 0 until src.pageCount) {
           val m = android.graphics.Matrix().apply { setTranslate(-b.left, -b.top); postScale(k, k) }
           src.render(pi, bmp, m)   // page points → bitmap pixels (assumes ink page size == source page size)
       }
   }
   ```
   (If ink coordinates are scaled vs. the source page, pre-scale by `pw / ip.w`.) Until then the system prompt tells
   the model to call `request_pages` for the selection's page when the image only shows marks.
2. Wiring already present in `InkEditorImpl` / `Screens` matches the contract — nothing else needed. Do not recycle
   the bitmap passed to `ai.startFromSelection` (the session does).

## Self-check
| Requirement | Status |
|---|---|
| `FileContext` + `PageContent` per contract (PDF, notes/whiteboards, PPTX, DOCX/DOC, TXT/MD/RTF/CSV, images, OneNote; ~3000-char text pages; ≤1600 px images; caps 20 images / ~120k chars; bounded memory) | PASS (legacy `.ppt` → 0 pages: no reader exists in the app) |
| FileContext first, compiled, noted | PASS |
| `rememberAiSession`, `AiSession{open,startFromSelection,show,close}`, `AiPanel`, `AiSheetHost` | PASS |
| Chat persisted per file (app-private JSON + small JPEG), reopening shows it, New chat clears | PASS |
| Quick chips Explain · Answer · Solve step by step · Translate · Make questions (→ QuizRequests + NewQuiz) | PASS |
| Free text, follow-ups keep context | PASS |
| Progress + cancel (Stop), retry | PASS |
| Markdown rendering, selectable answers, Copy per message, `$…$` readable | PASS |
| Friendly localized errors for every kind; NO_KEY/BAD_KEY → Open Settings | PASS |
| `request_pages` / `request_full_file` tools, 1-based conversion, permission dialog (pages / whole / deny + always allow per file), loop ≤ 4 rounds | PASS |
| System prompt: file name, kind, page count, selection page, answer in user's language | PASS |
| One-time privacy gate before first request | PASS |
| `AiSettingsSection`: masked key (paste/show/clear/save), Test key → models → pick model, privacy text, restriction help with package + SHA-1 (copy), API restriction, AI Studio link | PASS |
| `strings_ai.xml` en + real Arabic (80 keys, identical sets) | PASS |
| Compiles | PASS (`tools/compile.sh` BUILD OK) |
| Selection image contains the printed PDF/slide content | PARTIAL — needs lead change in `InkView` (Request 1) |
| Runtime test on device | NOT DONE — no emulator here |
