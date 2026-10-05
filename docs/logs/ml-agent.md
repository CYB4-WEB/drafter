# ml-agent log

# Round 2 (v3 requests)

## Understanding
- User: "OCR for scanned PDFs and photos so search and copy-text work on scans; Arabic quality is the weak point — test it
  first" and "Arabic ⇄ English translation over a PDF" (pdf3-agent builds the PDF UI on my API in `ml/Ml.kt`).
- I own `ml/**`, `res/values*/strings_ml.xml`; may add dependencies to `app/build.gradle.kts`; allowed local edit of
  `word/ImageScreen.kt` (Copy text (OCR) / Translate sheet). Keep every public signature of `Ocr`, `Translator`,
  `OcrLine`, `OcrResult`. Everything on-device; never throws; cancellable; boxes in input pixels; RTL flag + reading order.
- Repositories are restricted to google() + mavenCentral() (FAIL_ON_PROJECT_REPOS).

## Plan
1. Evaluate Arabic options *before* coding: check which Tesseract builds resolve from Maven Central / Google Maven,
   measure Arabic CER on rendered scan-like images with the same Tesseract version + tessdata_fast model.
2. `Ocr`: ML Kit v2 (Latin, thin) + Tesseract 5 LSTM (`ara`, downloaded to filesDir with SHA-256). Preprocess
   (grayscale + contrast stretch, upscale tiny inputs), horizontal strips with overlap for huge images, merge by script
   and overlap, column-aware reading order (RTL rows right→left), cancel via coroutine + Tesseract CANCEL_FUNC.
3. `Translator`: ML Kit Translate + Language ID; model manager (download with progress, Wi-Fi only option, delete,
   list); sentence-boundary splitting, batch keeps order, never throws.
4. `MlSettingsSection()` composable; image viewer "Copy text (OCR)" + "Translate" bottom sheet.
5. Strings en + ar, compile, log.
