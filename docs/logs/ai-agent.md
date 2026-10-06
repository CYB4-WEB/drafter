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
