# Brief: pdf-agent — PDF viewer, annotation export and PDF tools

## Goal
The student opens lecture PDFs, writes/draws on them with the S Pen (the shared ink editor does the drawing), and gets the classic tools:
export annotated PDF, share, print all or one page, copy text, extract pages to a new PDF, convert pages to images, convert images to a PDF.

## You own
- `app/src/main/java/com/daftar/app/pdf/**` (replace stubs `PdfScreen.kt`, `PdfTools.kt`; add `PdfSource.kt`, dialogs, etc.)
- `res/values/strings_pdf.xml`, `res/values-ar/strings_pdf.xml`
- log: `docs/logs/pdf-agent.md`

## Public contracts (keep)
- `@Composable fun PdfScreen(path: String)` (package `com.daftar.app.pdf`).
- `object PdfTools { fun imagesToPdf(ctx: Context, images: List<Uri>, out: File): File? }` — used by Home/Library quick action (called off main thread). Add more functions freely.

## How
1. **`PdfSource : PageSource`** using `android.graphics.pdf.PdfRenderer` on a `ParcelFileDescriptor`. `pageSize(i)` in points = `page.width/height` (PdfRenderer reports points).
   `render(i, dest, m)`: open page, `page.render(dest, null, m, RENDER_MODE_FOR_DISPLAY)` (the Matrix lets the editor render zoomed tiles), close page. Synchronize — PdfRenderer allows one open page.
   Cache page sizes at construction. Password-protected / corrupt → throw; screen shows error state with "Open in another app".
2. **`PdfScreen`**: `InkEditorScaffold(title, inkFile = Storage.sidecar(file,"ink.json"), source = PdfSource, isNote = false, onBack = { Nav.pop() }, extraActions = { ctl -> ... }, sidePanel = thumbnails)`.
   - Side panel: page thumbnails (≈200px wide, rendered off-thread, LRU cached), current page highlighted, tap → `ctl.goToPage(i)`.
   - `extraActions`: "Go to page" button (dialog with number) and a **Tools** overflow menu:
     a. **Export annotated PDF** → new file `<name> (annotated).pdf` in the same folder (see 3). Toast `saved_to`. Then `Storage.touch()`.
     b. **Share** → if the ink doc has any content, share the annotated export (in `Storage.cacheDir()`); otherwise share original.
     c. **Print** → dialog: All pages / Current page / Range. Uses `PrintManager` + a `PrintDocumentAdapter` that streams a PDF file (annotated version, or extracted range).
     d. **Copy text** → text of current page (or all) via PDFBox `PDFTextStripper` (`setStartPage/EndPage`, 1-based) off-thread; dialog with `SelectionContainer`
        scrollable text + "Copy all" (ClipboardManager). If empty → message "No selectable text (scanned page)".
     e. **Extract pages** → dialog accepting ranges like `1-3, 5, 8-10` (validate, 1-based) → new PDF `<name> (pages 1-3,5).pdf` via PDFBox (`PDDocument.importPage` or removing pages from a loaded copy). Option checkbox "Include my annotations".
     f. **Pages → images** → choose All / Current / Range and format PNG/JPEG → renders at 2× (≥150 dpi) to folder `<name> images/` next to the PDF (pages named `Page 01.png`). Progress dialog with cancel.
     g. **Open in another app**.
   Every long operation: progress dialog, runs on `Dispatchers.IO`, errors → toast `error_generic` (log stack).
3. **Annotated export (`PdfTools.exportAnnotated(src: File, ink: InkDoc, out: File)`)** — keep the original vector text selectable: load with PDFBox;
   for each page whose `InkPage` is not empty, rasterize ONLY the ink layer (strokes, text boxes, images — use the shared renderer
   `com.daftar.app.ink.InkRender.drawPageContent(canvas, page)` which the lead provides; canvas is in page points, so scale it ×3 for a 3x transparent ARGB bitmap)
   to a PNG, `LosslessFactory.createFromImage`, and draw it over the page with `PDPageContentStream(doc, page, AppendMode.APPEND, true, true)`
   covering the page's **crop box**, honouring `page.rotation` (0/90/180/270). Matrices to map the unit image square to the crop box (x0,y0,W,H):
   rot0 `Matrix(W,0,0,H,x0,y0)`; rot90 `Matrix(0,H,-W,0,x0+W,y0)`; rot180 `Matrix(-W,0,0,-H,x0+W,y0+H)`; rot270 `Matrix(0,-H,W,0,x0,y0+H)`.
   Wrap in `saveGraphicsState/restoreGraphicsState`. Save to `out`.
4. **`imagesToPdf`**: for each Uri decode with sampling (max 2480px long side), EXIF rotation (`androidx.exifinterface` is NOT available — read orientation via `ContentResolver` cursor `MediaStore.Images.ImageColumns.ORIENTATION` when present, else none),
   page = A4 width 595pt, height by aspect (max 842*2), using `android.graphics.pdf.PdfDocument`. Return file or null on failure.
5. Also add `PdfTools.extractText(file, fromPage, toPage)`, `PdfTools.extractPages(src, ranges, out)`, `PdfTools.pagesToImages(...)`, `PdfTools.print(ctx, file, jobName)` — reusable.

## Acceptance criteria
1. PDFs open fast, scroll smoothly (rendering is done by the scaffold using your PageSource); zoomed rendering is sharp (Matrix honoured).
2. Annotated export opens in other readers with original text still selectable and ink positioned exactly (including rotated pages).
3. Print all / current / range works through the Android print dialog.
4. Copy text works for text PDFs including Arabic; scanned PDFs show the friendly message.
5. Extract pages produces a correct new PDF with chosen pages in order; invalid ranges show an inline error.
6. Pages→images writes correctly named images; Images→PDF produces one page per image with correct orientation.
7. Thumbnails panel works; go-to-page works; corrupt/password PDF → error state, no crash.
8. All strings localized (en + ar). Code compiles.
