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

## Progress
1. **Arabic evaluation (done first).** Installed `tesserocr` (pip wheel bundling Tesseract **5.5.1** / Leptonica 1.85 —
   same engine generation as the Android build) on this box, downloaded `tessdata_fast` / `tessdata_best` 4.1.0, rendered
   10 Arabic study sentences with the app's Amiri, Cairo and Tehreer fonts (PIL + raqm shaping) at font sizes equivalent
   to 12 pt @ 300/200/150/100 dpi, clean and "scan-degraded" (0.6° skew, blur, salt-and-pepper, JPEG q55).
   CER = Levenshtein / reference length after NFKC + removing tashkeel. Scripts: scratchpad `ocr_eval.py`, `ocr_pre.py`, `ocr_sel.py`.
2. Dependencies resolved from google() + mavenCentral() only (no JitPack, settings.gradle.kts untouched).
3. Code: `ml/Ml.kt` (public API, same signatures), `MlStore.kt` (Wi-Fi only, Tesseract models: download w/ mirrors,
   progress, SHA-256, delete), `TessEngine.kt` (JavaCPP Tesseract, cancel via CANCEL_FUNC), `LatinEngine.kt`
   (ML Kit v2 thin + Play-services module install with progress), `OcrPipeline.kt` (strips, preprocess, merge,
   reading order), `TranslateEngine.kt` (ML Kit translate + language ID, sentence chunking), `MlSettings.kt`
   (`MlSettingsSection()`), `OcrSheet.kt` (`OcrTextSheet`, `OcrResult.paragraphs()`, `createTextNote`).
4. `word/ImageScreen.kt`: two menu items (Copy text (OCR), Translate) + the sheet; diff is local (state var, 2 items, sheet call).
5. Strings: `values/strings_ml.xml`, `values-ar/strings_ml.xml` (real Arabic).

### Arabic OCR results (Tesseract 5.5, LSTM, `ara` tessdata_fast 1.4 MB unless noted)
| font | 300 dpi clean / scan | 200 dpi clean / scan | 150 dpi clean / scan | 100 dpi clean / scan |
|---|---|---|---|---|
| Amiri (naskh) | 1.7 % / 2.9 % | 1.7 / 5.7 | 2.3 / 5.1 | 2.6 / 7.4 |
| Cairo (sans) | 1.1 / 2.2 | 6.3 / 4.3 | 11.0 / 4.0 | 1.1 / 4.5 |
| Tehreer | 2.5 / 5.9 | 3.5 / 5.9 | 2.0 / 7.7 | 3.1 / 11.1 |
- `tessdata_best` (12.6 MB, ~2.5× slower) is only ~0.5–1.5 points better → **fast** chosen (download size, speed).
- English with `eng` fast: 0–1.2 % CER. Arabic with `ara+eng` combined: 7.9 % vs 2.9 % with `ara` alone → Arabic runs
  with `ara` alone and Latin comes from ML Kit; results are merged by script/overlap.
- Typical errors: dotted-letter confusion (ج/ح, ي/ب, ه/ة), dropped shadda/tashkeel (ignored in CER), 1-2 merged words.
  Real-world expectation: ~2–6 % CER on decent 200–300 dpi scans of printed naskh/sans text; worse for decorative
  fonts, handwriting (not supported), heavy noise, or < 150 dpi. Honest note: synthetic images, not real scans.
- **Preprocessing findings**: 2× upscaling *hurt* Tesseract on average (6.5 % → 11.8 % mean CER over 30 cases; it
  amplifies noise); choosing x1/x2 by mean confidence did not beat plain x1. A 3×3 median filter destroyed i'jam dots
  on clean text (up to 97 % CER). So the pipeline only does grayscale + 1 %/99 % contrast stretch, and upscales 2× only
  tiny inputs (< 1000 px longest side, for ML Kit's ≥ 16 px glyph need). Tesseract line confidence is unreliable
  (real lines at 7–11), so filtering uses script fraction + confidence together.

## Decisions & limits
- **Latin OCR: ML Kit v2 thin (Play services)** `play-services-mlkit-text-recognition:19.0.1` (~80 KB AAR) instead of
  bundled (~4 MB/ABI): the app already relies on Play services (document scanner); the module is fetched through
  `ModuleInstallClient` with byte progress. Without Play services, `prepare` falls back to Tesseract `eng` (4.1 MB download).
- **Arabic OCR: Tesseract 5.5.0 via `org.bytedeco:tesseract:5.5.0-1.5.11` (Maven Central)**. `cz.adaptech.tesseract4android`
  is JitPack-only (and its 4.9.0 JitPack build exposes no AAR), `com.rmtheis:tess-two` (Central) is Tesseract 3.05 with no
  LSTM (poor Arabic). So no new repository was needed. Models: tessdata_fast tag 4.1.0 from raw.githubusercontent.com
  with jsDelivr as mirror, pinned SHA-256 + exact size, into `filesDir/ml/tessdata`; ar (1.4 MB), fa, ur, en fallback.
- Huge images: horizontal strips of 2400 working px with 200 px overlap (a line keeps the strip whose own band holds
  its centre); width capped at 4000 px. Memory per strip ≤ ~4000×2400×5 bytes.
- Merge: overlapping ML Kit / Tesseract lines → higher of conf×scriptFraction wins; a small Latin box inside an Arabic
  line is dropped (the Arabic line already contains it). Reading order: vertical gutter detection (columns), spanning
  lines split sections, rows top→bottom, RTL rows right→left, RTL pages read the right column first.
- Cancellation: coroutine cancel sets a flag polled by Tesseract's CANCEL_FUNC (between words) + checks between strips.
- Translation: ML Kit gives no download progress → estimated progress (asymptotic to 95 %, 1 at completion), documented.
  `prepare(x, x)` downloads one model (used by Settings). Language ID uses the thin Play-services variant; without it
  `detectLanguage` falls back to script detection. `Translator.translate(from = "und"/"auto")` detects the source.
- Limits: no device here → engine integration untested at runtime (compile-only); JavaCPP .so files are 4 KB-aligned
  (fine on Tab S11 Ultra's 4 KB kernel; a 16 KB-page device would need newer builds); no handwriting; vertical text not handled.

### Dependencies added (app/build.gradle.kts) and APK impact (uncompressed .so, as packaged by default)
| dependency | size |
|---|---|
| `com.google.android.gms:play-services-mlkit-text-recognition:19.0.1` | ~0.1 MB (model via Play services) |
| `com.google.android.gms:play-services-mlkit-language-id:17.0.0` | ~0.25 MB |
| `com.google.mlkit:translate:17.0.3` | `libtranslate_jni.so` 16.4 MB arm64 + 17.4 MB x86_64 (+ ~0.6 MB classes) |
| `org.bytedeco:tesseract/leptonica/javacpp` android-arm64 | 13.3 MB .so (tesseract 3.8 + jnitesseract 0.6 + leptonica 4.7 + jnileptonica 3.8 + jnijavacpp 0.4) |
| same, android-x86_64 | debug builds only (`debugImplementation`), ~14 MB |
Release APK: ≈ +48 MB uncompressed (≈ +20 MB if .so are compressed). Language models are downloaded, never bundled.
Also added `packaging.resources.excludes` for headers/pkgconfig/cmake/CLI binary shipped inside the JavaCPP jars.

## Requests to lead
1. Embed the section in Settings (ui/Screens.kt, next to `StudySettingsSection()`):
   `com.daftar.app.ml.MlSettingsSection()`
2. R8 keep rules for release (`app/proguard-rules.pro`) — JavaCPP uses JNI + reflection:
   ```
   -keep class org.bytedeco.javacpp.** { *; }
   -keep class org.bytedeco.tesseract.** { *; }
   -keep class org.bytedeco.leptonica.** { *; }
   -dontwarn org.bytedeco.**
   -dontwarn java.awt.**
   ```
   (ML Kit / Play services ship their own consumer rules.) Without them OCR of Arabic silently returns nothing in release.
3. APK size options (your call): `packaging { jniLibs { useLegacyPackaging = true } }` compresses the .so (~-25 MB
   download, extracted at install), and/or drop x86_64 from release `abiFilters` (saves 17.4 MB of translate natives).
4. pdf3-agent: render scanned pages at ~200–300 dpi equivalent before `Ocr.recognize`; use `OcrResult.paragraphs()`
   for translation input; `OcrTextSheet(source, translate, noteDir, noteName, onNote, onDismiss)` is reusable.
