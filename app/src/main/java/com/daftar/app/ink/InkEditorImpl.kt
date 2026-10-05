package com.daftar.app.ink

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.daftar.app.R
import com.daftar.app.data.Prefs
import com.daftar.app.data.Storage
import com.daftar.app.ui.ConvertButton
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.ViewerMenuItems
import com.daftar.app.ui.rememberViewerActions
import com.daftar.app.ui.TextInputDialog
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.shareFiles
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serialized background saver shared by every open editor: JSON encoding + writing never runs on the main thread, saves
 * of the same file are coalesced (only the newest snapshot is written) and never overlap.
 */
internal object InkSaver {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ink-save").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val latest = ConcurrentHashMap<String, () -> Unit>()

    fun submit(key: String, job: () -> Unit) {
        if (latest.put(key, job) == null) exec.execute { latest.remove(key)?.let { runCatching(it) } }
    }

    /** Waits (bounded) until everything submitted so far is written. */
    fun flush(timeoutMs: Long = 4000) {
        runCatching { exec.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }
}

/** Change counter so unchanged documents are never re-written (plain fields: no Compose state, no recomposition). */
private class SaveState { var changes = 0; var saved = 0; var job: Job? = null }

private class EditorStateImpl(val view: InkView) : EditorController {
    override var currentPage by mutableIntStateOf(0)
    override var pageCount by mutableIntStateOf(0)
    override var zoomPercent by mutableIntStateOf(100)
    override fun goToPage(i: Int) = view.goToPage(i)
    override fun zoomIn() = view.zoomBy(1.25f)
    override fun zoomOut() = view.zoomBy(0.8f)
    override fun zoomFit() = view.zoomToFit()
    /** Called after [addImage] so the toolbar can switch to the lasso (image floats selected). */
    var onImageAdded: (() -> Unit)? = null
    override fun addImage(b: Bitmap) { view.addImage(b); onImageAdded?.invoke() }
    override fun addImage(b: Bitmap, widthPt: Float) { view.addImage(b, widthPt); onImageAdded?.invoke() }
    override fun goToPage(i: Int, yPt: Float) = view.goToPage(i, yPt)
    override fun refreshPages() = view.refreshBackground()
    var saver: (() -> Unit)? = null
    override fun saveNow() { saver?.invoke() }
    override fun doc(): InkDoc = view.doc
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InkEditorImpl(
    title: String, inkFile: File, source: PageSource?, isNote: Boolean, onBack: () -> Unit,
    onRename: ((String) -> Unit)?, extraActions: @Composable RowScope.(EditorController) -> Unit,
    sidePanel: (@Composable (EditorController) -> Unit)?, sidePanelLabel: String,
    sidePanelAtStart: Boolean, sidePanelOpen: Boolean,
    bottomPanel: (@Composable (EditorController) -> Unit)?, bottomPanelLabel: String,
    bottomPanelOpen: Boolean = false,
    onPageChipClick: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = D.c
    val view = remember(inkFile) { InkView(ctx) }
    val ctl = remember(view) { EditorStateImpl(view) }

    var loaded by remember(inkFile) { mutableStateOf(false) }
    val ts = remember { InkPrefs.init(ctx); InkToolState() }
    var whiteboard by remember(inkFile) { mutableStateOf(false) }
    var linkEdit by remember { mutableStateOf<Pair<Int, LinkItem?>?>(null) }   // (page, link) — link null = new
    var linkMenu by remember { mutableStateOf<Triple<Int, LinkItem, Pair<Float, Float>>?>(null) }
    var hasTapes by remember { mutableStateOf(false) }
    /** The document this ink belongs to: the note itself, or the PDF/PPTX next to its `.name.ink.json` sidecar. */
    val hostFile = remember(inkFile) {
        if (isNote) inkFile else File(inkFile.parentFile, inkFile.name.removePrefix(".").removeSuffix(".ink.json"))
    }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var hasSel by remember { mutableStateOf(false) }
    var textReq by remember { mutableStateOf<Triple<Int, Pair<Float, Float>, TextItem?>?>(null) }
    var showMore by remember { mutableStateOf(false) }
    var showPaper by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }   // pages-agent: page manager
    var showColors by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val wideAtStart = LocalWidthClass.current == WidthClass.Expanded
    var showPanel by remember { mutableStateOf(sidePanelOpen && wideAtStart) }
    var showBottom by remember { mutableStateOf(bottomPanelOpen) }
    var showDictation by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var recognized by remember { mutableStateOf<String?>(null) }
    val saveState = remember(inkFile) { SaveState() }
    var textUi by remember { mutableStateOf<TextBoxUi?>(null) }
    var textMenu by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var colorForText by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showPrint by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<Triple<android.graphics.Bitmap, String?, Int>?>(null) }   // flashcard front, text, page
    var mathJob by remember { mutableStateOf<Job?>(null) }
    var mathOn by remember { mutableStateOf(run { InkPrefs.init(ctx); InkPrefs.mathHelper }) }
    var exported by remember { mutableStateOf<List<File>?>(null) }
    var workJob by remember { mutableStateOf<Job?>(null) }
    val wide = LocalWidthClass.current == WidthClass.Expanded
    // Window got narrow (split / pop-up): close the side panel rather than turning it into a sheet nobody asked for.
    LaunchedEffect(wide) { if (!wide) showPanel = false else if (sidePanelOpen) showPanel = true }

    // ---- persistence ----
    // The snapshot is immutable (pages, strokes and items are never mutated after creation), so it is taken on the main
    // thread in O(1) and encoded / written on the saver thread. Nothing is written when nothing changed.
    fun saveAsync() {
        if (!loaded) return
        saveState.job?.cancel(); saveState.job = null
        if (saveState.changes == saveState.saved) return
        saveState.saved = saveState.changes
        val d = view.doc
        val snap = InkDoc(d.pages, d.paperColor, d.recordings, d.infinite)
        val file = inkFile
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        InkSaver.submit(file.absolutePath) {
            if (isNote && !file.exists()) return@submit   // renamed/moved away while open
            val ok = runCatching {
                if (source != null && snap.pages.all { it.isEmpty() } && snap.recordings.isEmpty()) file.delete() else snap.save(file)
            }.isSuccess
            if (isNote && ok) {
                runCatching { com.daftar.app.data.Versions.capture(file) }
                main.post { Storage.touch() }
            }
        }
    }
    /** Saves and waits until the file is written (rename, host screens that read the file right away). */
    fun saveBlocking() { saveAsync(); InkSaver.flush() }
    fun scheduleSave() {
        saveState.changes++
        saveState.job?.cancel()
        saveState.job = scope.launch { delay(800); saveState.job = null; saveAsync() }
    }
    ctl.saver = { saveBlocking() }

    // ---- recording / playback ----
    val recorder = remember { Recorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<Recording?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    // transcript-agent: live lecture transcript + karaoke panel
    val transcript = remember { TranscriptSession(ctx) }
    val playClock = remember { PlayClock() }
    var recPaused by remember { mutableStateOf(false) }
    var curRecId by remember { mutableIntStateOf(0) }
    var stripOpen by remember { mutableStateOf(TranscriptPrefs.stripOpen(ctx)) }
    var trPanelOpen by remember { mutableStateOf(true) }
    var trDialog by remember { mutableStateOf<Recording?>(null) }

    fun stopPlayback() {
        player?.release(); player = null; playing = null; isPlaying = false
        view.playRec = 0; view.invalidate()
    }

    fun startRecording() {
        stopPlayback()
        val id = (view.doc.recordings.maxOfOrNull { it.id } ?: 0) + 1
        val f = Storage.sidecar(inkFile, "rec$id.m4a")
        runCatching { recorder.start(f, live = LiveTranscription.isSupported(ctx)) }.onFailure { toast(ctx, ctx.getString(R.string.error_generic)); return }
        view.recId = id; view.recClockStart = SystemClock.elapsedRealtime(); view.recOffset = 0
        curRecId = id; recPaused = false
        // transcript times use the stroke clock, so transcript, audio and ink replay share one timeline
        transcript.begin(recorder, id, Prefs.speechLang) {
            if (recPaused) view.recOffset else SystemClock.elapsedRealtime() - view.recClockStart + view.recOffset
        }
        recording = true
    }

    fun pauseRecording() {
        if (!recorder.active || recPaused) return
        transcript.pauseRec()
        recorder.pause()
        view.recOffset += SystemClock.elapsedRealtime() - view.recClockStart
        view.recId = 0          // ink drawn during the pause is not tied to the audio
        recPaused = true
    }

    fun resumeRecording() {
        if (!recPaused) return
        recorder.resume()
        view.recClockStart = SystemClock.elapsedRealtime()
        view.recId = curRecId
        recPaused = false
        transcript.resumeRec()
    }

    fun stopRecording() {
        val id = curRecId
        // final words arrive asynchronously (≤ 1.5 s): they replace the transcript stored below
        transcript.finish { segs ->
            if (view.doc.recordings.any { it.id == id }) {
                view.doc.recordings = view.doc.recordings.map { if (it.id == id) it.copy(transcript = segs) else it }
                if (playing?.id == id) playing = view.doc.recordings.firstOrNull { it.id == id }
                scheduleSave()
            }
        }
        recorder.stop()
        val f = recorder.file
        view.recId = 0; curRecId = 0; recPaused = false
        recording = false
        if (f != null && f.exists()) {
            view.doc.recordings = view.doc.recordings + Recording(id, f.name, audioDuration(f), System.currentTimeMillis(), transcript.state.snapshot())
            scheduleSave()
        }
    }

    fun play(r: Recording, from: Long = 0) {
        stopPlayback()
        val f = File(inkFile.parentFile, r.file)
        val p = runCatching { MediaPlayer().apply { setDataSource(f.absolutePath); prepare() } }.getOrNull() ?: return
        p.seekTo(from.toInt()); p.start()
        p.setOnCompletionListener { isPlaying = false }
        player = p; playing = r; isPlaying = true
        view.playRec = r.id
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else toast(ctx, ctx.getString(R.string.ink_mic_permission))
    }
    val dictPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) showDictation = true else toast(ctx, ctx.getString(R.string.ink_mic_permission))
    }
    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED


    // ---- image insert ----
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val b = withContext(Dispatchers.IO) { loadBitmap(ctx, uri, 2000) }
            if (b != null) { view.addImage(b); ts.selectTool(Tool.LASSO); view.tool = Tool.LASSO } else toast(ctx, ctx.getString(R.string.error_generic))
        }
    }

    // ---- load ----
    LaunchedEffect(inkFile) {
        val d = withContext(Dispatchers.IO) {
            val existing = InkDoc.load(inkFile)
            if (source != null) InkDoc.forSource(existing, (0 until source.pageCount).map { source.pageSize(it) })
            else existing ?: InkDoc.newNote(PaperTemplates.stamp(Prefs.defaultPaper))
        }
        view.setDocument(d, source)
        com.daftar.app.study.StudyLinks.consumePage(hostFile)?.let { pg -> view.goToPageWhenReady(pg) }
        whiteboard = d.infinite
        hasTapes = view.hasTapes()
        ctl.pageCount = d.pages.size
        saveState.changes = 0; saveState.saved = 0
        loaded = true
    }

    /**
     * Recognizes [strokes] as a calculation and solves it: (answer, the user wrote "="). Tries the handwriting language,
     * then English digits; candidates beyond the first are tried too. [download] = may fetch the model (explicit Solve).
     */
    suspend fun solveInk(strokes: List<Stroke>, requireEquals: Boolean, download: Boolean): Pair<String, Boolean>? {
        if (strokes.isEmpty()) return null
        val langs = listOf(Prefs.inkLang, "en-US").distinct()
        for ((k, lang) in langs.withIndex()) {
            if (!Handwriting.isReady(lang)) {
                if (!download || k > 0) continue
                busy = ctx.getString(R.string.ink_downloading_model)
                try { Handwriting.download(lang) } finally { busy = null }
            }
            for (t in Handwriting.candidates(lang, strokes)) {
                val a = MathEval.answerFor(t, requireEquals) ?: continue
                return a to t.contains('=')
            }
        }
        return null
    }

    view.listener = remember(view) {
        object : InkView.Listener {
            override fun onChanged() { ctl.pageCount = view.doc.pages.size; scheduleSave() }
            override fun onLinkOpen(link: LinkItem) { view.finishEditing(); saveAsync(); openLink(ctx, link) }
            override fun onLinkMenu(page: Int, link: LinkItem, x: Float, y: Float) { linkMenu = Triple(page, link, x to y) }
            override fun onDropFailed() { toast(ctx, ctx.getString(R.string.ink_drop_failed)) }
            override fun onPageChanged(current: Int, count: Int) { ctl.currentPage = current; ctl.pageCount = count }
            override fun onTextRequest(page: Int, x: Float, y: Float, existing: TextItem?) { textReq = Triple(page, x to y, existing) }
            override fun onSelectionChanged(active: Boolean) { hasSel = active }
            override fun onSeek(rec: Int, t: Long) {
                view.doc.recordings.firstOrNull { it.id == rec }?.let { r ->
                    if (playing?.id == r.id && player != null) { player!!.seekTo(t.toInt()); if (!isPlaying) { player!!.start(); isPlaying = true } } else play(r, t)
                }
            }
            override fun onUndoStateChanged(u: Boolean, r: Boolean) { canUndo = u; canRedo = r }
            override fun onZoomChanged(percent: Int) { ctl.zoomPercent = percent }
            override fun onTextBox(ui: TextBoxUi?) { textUi = ui; if (ui == null) textMenu = null }
            override fun onTextMenu(page: Int, item: TextItem, x: Float, y: Float) { textMenu = x to y }
            override fun onMathCandidate(page: Int, line: MathAssist.Line) {
                mathJob?.cancel()
                mathJob = scope.launch {
                    // offline only: never downloads a model for the automatic helper
                    val ans = runCatching { solveInk(line.strokes, requireEquals = true, download = false)?.first }.getOrNull() ?: return@launch
                    view.insertMathAnswer(page, line, ans)
                }
            }
        }
    }
    ctl.onImageAdded = { ts.selectTool(Tool.LASSO); view.tool = Tool.LASSO }

    // keep the view in sync with toolbar & settings (in its own scope: a tool change does not recompose the editor)
    ToolSync(view, ts, c.bg.toArgb())
    remember(view) { view.mathHelper = InkPrefs.mathHelper; view.textFont = InkPrefs.textFont; view.textSize = InkPrefs.textSize; view.textBold = InkPrefs.textBold; true }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(inkFile) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { view.finishEditing(); saveAsync(); mathJob?.cancel(); view.onPause() }
                Lifecycle.Event.ON_RESUME -> view.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mathJob?.cancel()
            transcript.finishNow()
            if (recorder.active) stopRecording()
            transcript.release()
            stopPlayback()
            view.finishEditing()
            saveAsync()
            view.release()
            source?.close()
        }
    }

    fun recognize(strokes: List<Stroke>, replace: Boolean) {
        if (strokes.isEmpty()) { toast(ctx, ctx.getString(R.string.ink_no_handwriting)); return }
        scope.launch {
            val lang = Prefs.inkLang
            try {
                if (!Handwriting.isReady(lang)) { busy = ctx.getString(R.string.ink_downloading_model); Handwriting.download(lang) }
                busy = ctx.getString(R.string.ink_recognizing)
                val text = Handwriting.recognize(lang, strokes)
                busy = null
                if (text.isBlank()) toast(ctx, ctx.getString(R.string.ink_no_handwriting))
                else if (replace) view.replaceSelectionWithText(text) else recognized = text
            } catch (e: Exception) {
                busy = null
                toast(ctx, ctx.getString(R.string.ink_model_failed))
            }
        }
    }

    fun solveSelection() {
        val strokes = view.selectedStrokes()
        if (strokes.isEmpty()) { toast(ctx, ctx.getString(R.string.ink4_no_math)); return }
        scope.launch {
            busy = ctx.getString(R.string.ink_recognizing)
            val r = try { solveInk(strokes, requireEquals = false, download = true) } catch (e: Exception) { null } finally { busy = null }
            if (r == null) toast(ctx, ctx.getString(R.string.ink4_no_math)) else view.insertSolveAnswer(r.first, r.second)
        }
    }

    /** Lasso → flashcard: image of the selection + (optional, quick, offline) recognized text, then the study dialog. */
    fun makeFlashcard() {
        val bmp = view.selectionBitmap() ?: return
        val page = view.selectionPage.coerceAtLeast(0)
        val typed = view.selectedTexts()
        val strokes = view.selectedStrokes()
        scope.launch {
            val lang = Prefs.inkLang
            val hw = if (strokes.isEmpty()) null else runCatching {
                kotlinx.coroutines.withTimeoutOrNull(900) { if (Handwriting.isReady(lang)) Handwriting.recognize(lang, strokes) else null }
            }.getOrNull()
            val text = (typed + listOfNotNull(hw?.takeIf { it.isNotBlank() })).joinToString("\n").ifBlank { null }
            card = Triple(bmp, text, page)
        }
    }

    val actions = if (isNote) rememberViewerActions(hostFile) else null

    // ---- export / print (notes) ----
    /** Immutable copy of the document for background work (pages and items are never mutated in place). */
    fun snapshot(): InkDoc { view.finishEditing(); val d = view.doc; return InkDoc(d.pages, d.paperColor, d.recordings, d.infinite) }
    val baseName = inkFile.nameWithoutExtension
    val outDir = inkFile.parentFile ?: Storage.root

    /** Runs [work] off the main thread with a cancellable progress bar; [done] gets the files written (null = failed). */
    fun runWork(work: suspend ((String) -> Unit) -> List<File>?, done: (List<File>?) -> Unit) {
        workJob?.cancel()
        busy = ctx.getString(R.string.ink_exporting)
        workJob = scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { work { msg -> scope.launch(Dispatchers.Main) { if (workJob?.isActive == true) busy = msg } } }
            } catch (e: kotlinx.coroutines.CancellationException) { busy = null; workJob = null; throw e }
            catch (e: Throwable) { null }
            busy = null; workJob = null
            done(r)
        }
    }
    fun finished(files: List<File>?) {
        if (files == null) { toast(ctx, ctx.getString(R.string.error_generic)); return }
        if (files.isEmpty()) return
        Storage.touch()
        exported = files
    }
    fun exportPdf() {
        val d = snapshot()
        runWork({ val out = Storage.uniqueFile(outDir, baseName, "pdf"); exportNoteToPdf(d, out); listOf(out) }, ::finished)
    }
    /** transcript-agent: "<host name> transcript.txt" next to the note, with the open/share bar. */
    fun exportTranscript(r: Recording) {
        val segs = view.doc.recordings.firstOrNull { it.id == r.id }?.transcript ?: r.transcript
        if (segs.isEmpty()) return
        val n = view.doc.recordings.indexOfFirst { it.id == r.id } + 1
        val head = ctx.getString(R.string.tr_recording_title, title, n.coerceAtLeast(1))
        val name = ctx.getString(R.string.tr_export_name, hostFile.nameWithoutExtension)
        runWork({ val out = Storage.uniqueFile(outDir, name, "txt"); out.writeText(TranscriptText.export(head, segs)); listOf(out) }, ::finished)
    }
    fun exportImages(png: Boolean, all: Boolean) {
        val d = snapshot()
        val pages = if (all) d.pages.indices.toList() else listOf(ctl.currentPage.coerceIn(0, d.pages.size - 1))
        runWork({ msg -> NoteExport.exportImages(d, pages, outDir, baseName, png) { k, n -> if (n > 1) msg(ctx.getString(R.string.ink_exporting_page, k, n)) } }, ::finished)
    }
    fun sharePageImage() {
        val d = snapshot()
        val i = ctl.currentPage.coerceIn(0, d.pages.size - 1)
        runWork({
            val name = if (d.pages.size > 1) "$baseName - ${i + 1}" else baseName
            val f = File(Storage.cacheDir(), Storage.sanitize(name).ifBlank { "page" } + ".png")
            val b = NoteExport.renderPage(d, i)
            try { NoteExport.writeBitmap(b, f, true) } finally { b.recycle() }
            listOf(f)
        }) { files -> if (files != null) shareFiles(ctx, files) else toast(ctx, ctx.getString(R.string.error_generic)) }
    }
    fun exportText(docx: Boolean) {
        val d = snapshot()
        val lang = Prefs.inkLang
        runWork({ msg ->
            val (pages, hwMissing) = NoteExport.pageTexts(d, lang) { k, n -> msg(ctx.getString(R.string.ink_recognizing_page, k, n)) }
            if (hwMissing) scope.launch(Dispatchers.Main) { toast(ctx, ctx.getString(R.string.ink_export_no_hw)) }
            if (pages.all { it.isEmpty() }) { scope.launch(Dispatchers.Main) { toast(ctx, ctx.getString(R.string.ink_export_empty)) }; return@runWork emptyList() }
            val out = Storage.uniqueFile(outDir, baseName, if (docx) "docx" else "txt")
            if (docx) { if (!com.daftar.app.word.DocxExport.writeDocx(NoteExport.docxParagraphs(pages), out, baseName)) return@runWork null }
            else out.writeText(NoteExport.plainText(pages))
            listOf(out)
        }, ::finished)
    }
    fun printPages(pages: List<Int>) {
        val act = ctx.findActivityOrNull() ?: run { toast(ctx, ctx.getString(R.string.error_generic)); return }
        val d = snapshot()
        val sub = InkDoc(pages.filter { it in d.pages.indices }.map { d.pages[it] }, d.paperColor, emptyList(), d.infinite)
        if (sub.pages.isEmpty()) return
        runWork({ val f = File(Storage.cacheDir(), "print-" + Storage.sanitize(baseName).ifBlank { "note" } + ".pdf"); exportNoteToPdf(sub, f); listOf(f) }) { files ->
            val f = files?.firstOrNull()
            if (f == null) toast(ctx, ctx.getString(R.string.error_generic))
            else runCatching { com.daftar.app.pdf.PdfTools.print(act, f, title) }.onFailure { toast(ctx, ctx.getString(R.string.error_generic)) }
        }
    }

    androidx.activity.compose.BackHandler(enabled = textUi != null) { view.finishEditing() }

    // =========================== UI ===========================
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        ViewerTopBar(title, onBack = { view.finishEditing(); saveAsync(); onBack() },
            onTitleClick = if (onRename != null) ({ showRename = true }) else null) {
            IconButton(onClick = { view.undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.ink_undo), tint = if (canUndo) c.ink else c.line) }
            IconButton(onClick = { view.redo() }, enabled = canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.ink_redo), tint = if (canRedo) c.ink else c.line) }
            if (isNote) {
                IconButton(onClick = {
                    if (recording) stopRecording() else if (hasMic()) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Icon(if (recording) Icons.Rounded.StopCircle else Icons.Rounded.Mic, stringResource(R.string.ink_record), tint = if (recording) c.danger else c.ink)
                }
                if (view.doc.recordings.isNotEmpty() || playing != null) {
                    IconButton(onClick = { showRecordings = true }) { Icon(Icons.Rounded.GraphicEq, stringResource(R.string.ink_recordings), tint = if (playing != null) c.accent else c.ink) }
                }
            }
            if (actions != null) ConvertButton(actions)
            extraActions(ctl)
            if (bottomPanel != null) IconButton(onClick = { showBottom = !showBottom }) {
                Icon(Icons.Rounded.ViewAgenda, bottomPanelLabel, tint = if (showBottom) c.accent else c.ink)
            }
            if (sidePanel != null) IconButton(onClick = { showPanel = !showPanel }) {
                Icon(Icons.AutoMirrored.Rounded.ViewSidebar, sidePanelLabel, tint = if (showPanel) c.accent else c.ink)
            }
            Box {
                IconButton(onClick = { showMore = true; hasTapes = view.hasTapes() }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = c.ink) }
                DropdownMenu(showMore, { showMore = false }) {
                    if (isNote && !whiteboard) {
                        DropdownMenuItem({ Text(stringResource(R.string.ink_add_page)) }, { showMore = false; view.addPage(ctl.currentPage, view.doc.pages.getOrNull(ctl.currentPage)?.paper?.let { PaperTemplates.nextKey(it) } ?: PaperTemplates.stamp(Prefs.defaultPaper)) }, leadingIcon = { Icon(Icons.Rounded.NoteAdd, null) })
                    }
                    if (isNote) DropdownMenuItem({ Text(stringResource(R.string.ink_paper)) }, { showMore = false; showPaper = true }, leadingIcon = { Icon(Icons.Rounded.GridOn, null) })
                    if (isNote && !whiteboard) DropdownMenuItem({ Text(stringResource(R.string.pages_title)) }, { showMore = false; view.finishEditing(); showPages = true }, leadingIcon = { Icon(Icons.Rounded.AutoAwesomeMosaic, null) })
                    if (isNote && !whiteboard && ctl.pageCount > 1) {
                        DropdownMenuItem({ Text(stringResource(R.string.ink_delete_page)) }, { showMore = false; view.deletePage(ctl.currentPage) }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) })
                    }
                    DropdownMenuItem({ Text(stringResource(if (whiteboard) R.string.ink_clear_board else R.string.ink_clear_page)) }, { showMore = false; view.clearPage(ctl.currentPage) }, leadingIcon = { Icon(Icons.Rounded.LayersClear, null) })
                    DropdownMenuItem({ Text(stringResource(if (whiteboard) R.string.ink_recognize_board else R.string.ink_recognize_page)) }, { showMore = false; recognize(view.pageStrokes(ctl.currentPage), false) }, leadingIcon = { Icon(Icons.Rounded.TextFields, null) })
                    if (hasTapes) {
                        DropdownMenuItem({ Text(stringResource(R.string.ink_tapes_reveal)) }, { showMore = false; view.setAllTapes(true) }, leadingIcon = { Icon(Icons.Rounded.Visibility, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.ink_tapes_hide)) }, { showMore = false; view.setAllTapes(false) }, leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) })
                    }
                    if (isNote) {
                        DropdownMenuItem({ Text(stringResource(R.string.ink_export)) }, { showMore = false; showExport = true },
                            leadingIcon = { Icon(Icons.Rounded.IosShare, null) }, trailingIcon = { Icon(Icons.Rounded.ChevronRight, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.ink_print)) }, {
                            showMore = false
                            if (whiteboard || ctl.pageCount <= 1) printPages(listOf(0)) else showPrint = true
                        }, leadingIcon = { Icon(Icons.Rounded.Print, null) })
                    }
                    DropdownMenuItem(
                        { Text(stringResource(R.string.ink4_math_helper)) },
                        { mathOn = !mathOn; InkPrefs.mathHelper = mathOn; view.mathHelper = mathOn; showMore = false },
                        leadingIcon = { Icon(Icons.Rounded.Calculate, null) },
                        trailingIcon = { Checkbox(checked = mathOn, onCheckedChange = null) },
                    )
                    DropdownMenuItem(
                        { Text(stringResource(if (Prefs.penOnly) R.string.ink_finger_draw_off else R.string.ink_finger_draw_on)) },
                        { showMore = false; Prefs.putPenOnly(!Prefs.penOnly) },
                        leadingIcon = { Icon(Icons.Rounded.TouchApp, null) },
                    )
                    if (actions != null) {
                        HorizontalDivider(color = c.line)
                        ViewerMenuItems(actions, close = { showMore = false }, onShare = {
                            val d = snapshot()
                            runWork({ val out = File(Storage.cacheDir(), Storage.sanitize(baseName).ifBlank { "note" } + ".pdf"); exportNoteToPdf(d, out); listOf(out) }) { files ->
                                if (files != null) shareFiles(ctx, files) else toast(ctx, ctx.getString(R.string.error_generic))
                            }
                        }, showPrint = false)
                    }
                }
                if (isNote) DropdownMenu(showExport, { showExport = false }) {
                    Text(stringResource(R.string.ink_export), style = MaterialTheme.typography.labelMedium, color = c.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    @Composable
                    fun Item(label: Int, icon: ImageVector, action: () -> Unit) =
                        DropdownMenuItem({ Text(stringResource(label)) }, { showExport = false; action() }, leadingIcon = { Icon(icon, null) })
                    Item(R.string.ink_export_pdf_short, Icons.Rounded.PictureAsPdf) { exportPdf() }
                    val multi = !whiteboard && ctl.pageCount > 1
                    if (multi) {
                        Item(R.string.ink_export_png_page, Icons.Rounded.Image) { exportImages(png = true, all = false) }
                        Item(R.string.ink_export_png_all, Icons.Rounded.Collections) { exportImages(png = true, all = true) }
                        Item(R.string.ink_export_jpg_page, Icons.Rounded.Image) { exportImages(png = false, all = false) }
                        Item(R.string.ink_export_jpg_all, Icons.Rounded.Collections) { exportImages(png = false, all = true) }
                    } else {
                        Item(R.string.ink_export_png_board, Icons.Rounded.Image) { exportImages(png = true, all = true) }
                        Item(R.string.ink_export_jpg_board, Icons.Rounded.Image) { exportImages(png = false, all = true) }
                    }
                    Item(R.string.ink_share_page_image, Icons.Rounded.Share) { sharePageImage() }
                    Item(R.string.ink_export_docx, Icons.Rounded.Description) { exportText(docx = true) }
                    Item(R.string.ink_export_txt, Icons.AutoMirrored.Rounded.Notes) { exportText(docx = false) }
                }
            }
        }

        // ---- toolbar ----
        InkToolbar(
            st = ts,
            showAddPage = isNote && !whiteboard,
            onToolChanged = { view.finishEditing() },
            onMoreColors = { showColors = true },
            onImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onLink = { linkEdit = ctl.currentPage to null },
            onDictate = { if (hasMic()) showDictation = true else dictPermission.launch(Manifest.permission.RECORD_AUDIO) },
            onAddPage = { view.addPage(ctl.currentPage, view.doc.pages.getOrNull(ctl.currentPage)?.paper?.let { PaperTemplates.nextKey(it) } ?: PaperTemplates.stamp(Prefs.defaultPaper)) },
        )

        if (recording) LectureRecordingBar(view, recPaused, transcript, stripOpen,
            onStripToggle = { stripOpen = !stripOpen; TranscriptPrefs.setStripOpen(ctx, stripOpen) },
            onPause = { pauseRecording() }, onResume = { resumeRecording() }, onStop = { stopRecording() },
            onLang = { code -> Prefs.putSpeechLang(code); transcript.setLang(code) })
        playing?.let { r ->
            val hasTr = r.transcript.isNotEmpty()
            LecturePlaybackBar(r, player, isPlaying, view, playClock, hasTr, trPanelOpen, onTranscriptToggle = { trPanelOpen = !trPanelOpen },
                onToggle = { val p = player; if (p != null) { if (isPlaying) { p.pause(); isPlaying = false } else { p.start(); isPlaying = true } } },
                onClose = { stopPlayback() })
            if (hasTr && trPanelOpen) {
                TranscriptPanel(r.transcript, playClock, follow = true,
                    onSeek = { t -> view.listener?.onSeek(r.id, t) },
                    onInsert = { text -> view.addTextAtCenter(text, 16f, ts.penColor, "sans") },
                    onExport = { exportTranscript(r) },
                    modifier = Modifier.fillMaxWidth().background(c.surface).padding(top = 4.dp),
                    listMaxHeight = if (wide) 200.dp else 128.dp)
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (sidePanel != null && showPanel && wide && sidePanelAtStart) {
                Box(Modifier.width(260.dp).fillMaxHeight().background(c.surface)) {
                    Box(Modifier.padding(end = 1.dp)) { sidePanel(ctl) }
                    Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(c.line))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // the canvas and the on-canvas text editor share one FrameLayout (the editor is positioned by the view)
                val host = remember(view) {
                    android.widget.FrameLayout(ctx).apply {
                        clipChildren = true
                        addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
                        addView(view.textOverlay.edit)
                    }
                }
                AndroidView({ host }, Modifier.fillMaxSize().clipToBounds())
                if (loaded) ZoomPill(ctl, Modifier.align(Alignment.BottomStart).padding(16.dp))
                if (!loaded) CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)

                if (hasSel) SelectionBar(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 8.dp, end = 8.dp),
                    onText = { recognize(view.selectedStrokes(), true) },
                    onTidy = { if (!view.tidySelection()) toast(ctx, ctx.getString(R.string.ink4_nothing_to_tidy)) },
                    onSolve = { solveSelection() },
                    onFlashcard = { makeFlashcard() },
                    onColor = { col -> view.recolorSelection(col) },
                    onDuplicate = { view.duplicateSelection() },
                    onDelete = { view.deleteSelection() },
                    onDone = { view.commitSelection() },
                )
                textUi?.let { ui ->
                    TextFormatBar(
                        Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 8.dp, end = 8.dp), ui,
                        onFormat = { f ->
                            view.formatText { t -> f(t).also { n -> InkPrefs.textFont = n.font; InkPrefs.textSize = n.size; InkPrefs.textBold = n.bold
                                view.textFont = n.font; view.textSize = n.size; view.textBold = n.bold } }
                        },
                        onMoreColors = { colorForText = true; showColors = true },
                        onDuplicate = { view.duplicateText() },
                        onDelete = { view.deleteSelectedText() },
                        onDone = { view.finishEditing() },
                    )
                }
                if (!whiteboard) PageChip(ctl, onPageChipClick ?: (if (isNote) ({ view.finishEditing(); showPages = true }) else null), Modifier.align(Alignment.BottomEnd).padding(16.dp))
                busy?.let { msg ->
                    Row(Modifier.align(Alignment.BottomCenter).padding(24.dp).background(c.surface, RoundedCornerShape(12.dp))
                        .border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                        Spacer(Modifier.width(10.dp)); Text(msg, color = c.ink, modifier = Modifier.padding(vertical = 8.dp))
                        if (workJob != null) TextButton(onClick = { workJob?.cancel(); workJob = null; busy = null }) { Text(stringResource(R.string.cancel)) }
                        else Spacer(Modifier.width(12.dp))
                    }
                }
                exported?.let { files ->
                    LaunchedEffect(files) { delay(8000); if (exported === files) exported = null }
                    ExportResultBar(Modifier.align(Alignment.BottomCenter).padding(24.dp), files,
                        onOpen = { f -> exported = null; view.finishEditing(); saveAsync(); com.daftar.app.ui.pane.open(ctx, f) },
                        onShare = { fs -> exported = null; shareFiles(ctx, fs) },
                        onDismiss = { exported = null })
                }
                textMenu?.let { (x, y) ->
                    TextBoxMenu(x, y, onDismiss = { textMenu = null },
                        onEdit = { textMenu = null; view.editSelectedText() },
                        onEditDialog = { textMenu = null; view.editSelectedTextInDialog() },
                        onDuplicate = { textMenu = null; view.duplicateText() },
                        onDelete = { textMenu = null; view.deleteSelectedText() })
                }
                linkMenu?.let { (page, link, pos) ->
                    LinkMenu(pos.first, pos.second, link, onDismiss = { linkMenu = null },
                        onOpen = { linkMenu = null; view.finishEditing(); saveAsync(); openLink(ctx, link) },
                        onSideBySide = { linkMenu = null; view.finishEditing(); saveBlocking(); openLinkSideBySide(ctx, link, hostFile) },
                        onEdit = { linkMenu = null; linkEdit = page to link },
                        onDelete = { linkMenu = null; view.deleteLink(page, link.id) },
                    )
                }
            }
            if (bottomPanel != null && showBottom) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.34f).background(c.surface)) { bottomPanel(ctl) }
            }
            }
            if (sidePanel != null && showPanel && wide && !sidePanelAtStart) {
                Box(Modifier.width(320.dp).fillMaxHeight().background(c.surface)) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                    Box(Modifier.padding(start = 1.dp)) { sidePanel(ctl) }
                }
            }
        }
    }

    if (sidePanel != null && showPanel && !wide) {
        ModalBottomSheet(onDismissRequest = { showPanel = false }, containerColor = c.surface) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.6f)) { sidePanel(ctl) }
        }
    }

    textReq?.let { (page, pos, existing) ->
        TextBoxDialog(existing, ts.penColor,
            onDismiss = { textReq = null },
            onDelete = { existing?.let { view.deleteText(page, it.id) }; textReq = null },
            onSave = { text, size, font, bold, color ->
                val p = view.doc.pages[page]
                val item = existing?.copy(text = text, size = size, font = font, bold = bold, color = color)
                    ?: TextItem(System.nanoTime(), pos.first, pos.second, (if (whiteboard) 360f else (p.w - pos.first - 24f).coerceAtLeast(140f)), text, size, color, font, bold)
                view.upsertText(page, item)
                textReq = null
            })
    }

    if (showPaper) PaperTemplates.PaperPickerDialog(view, whiteboard, onDismiss = { showPaper = false })
    if (showPages && isNote && !whiteboard) PageManagerPanel(view, inkFile, title, onDismiss = { showPages = false },
        onOpenFile = { f -> saveAsync(); com.daftar.app.ui.pane.open(ctx, f) })

    if (showColors) ColorGridDialog(onDismiss = { showColors = false; colorForText = false }) { col ->
        if (colorForText && textUi != null) view.formatText { it.copy(color = col) }
        else { ts.pickColor(col); view.finishEditing() }
        showColors = false; colorForText = false
    }

    if (showPrint) PrintDialog(ctl.pageCount, ctl.currentPage, onDismiss = { showPrint = false }) { pages ->
        showPrint = false; printPages(pages)
    }

    if (showRename && onRename != null) TextInputDialog(stringResource(R.string.rename), title, stringResource(R.string.save), { showRename = false }) {
        view.finishEditing(); saveBlocking(); showRename = false; onRename(it)
    }

    if (showDictation) DictationDialog(onDismiss = { showDictation = false }) { text ->
        showDictation = false
        if (text.isNotBlank()) view.addTextAtCenter(text, 16f, ts.penColor, "sans")
    }

    if (showRecordings) AlertDialog(
        onDismissRequest = { showRecordings = false },
        title = { Text(stringResource(R.string.ink_recordings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.ink_replay_hint), color = c.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                view.doc.recordings.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth().clickable { play(r); showRecordings = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayCircle, null, tint = c.accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ink_recording_n, i + 1), color = c.ink)
                            Text(fmtTime(r.duration) + " · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(r.created),
                                color = c.muted, style = MaterialTheme.typography.bodySmall)
                            if (r.transcript.isNotEmpty()) Text(transcriptPreview(r.transcript), color = c.ink, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                        if (r.transcript.isNotEmpty()) IconButton(onClick = { trDialog = r; showRecordings = false }) {
                            Icon(Icons.Rounded.Subtitles, stringResource(R.string.tr_transcript), tint = c.accent)
                        }
                        IconButton(onClick = {
                            if (playing?.id == r.id) stopPlayback()
                            File(inkFile.parentFile, r.file).delete()
                            view.doc.recordings = view.doc.recordings.filterNot { it.id == r.id }
                            scheduleSave()
                        }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = c.muted) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showRecordings = false }) { Text(stringResource(R.string.close)) } },
    )

    trDialog?.let { r0 ->
        val r = view.doc.recordings.firstOrNull { it.id == r0.id } ?: r0
        val n = view.doc.recordings.indexOfFirst { it.id == r.id } + 1
        TranscriptDialog(ctx.getString(R.string.tr_recording_title, title, n.coerceAtLeast(1)), r.transcript, playClock, follow = playing?.id == r.id,
            onSeek = { t -> trDialog = null; view.listener?.onSeek(r.id, t) },
            onInsert = { text -> trDialog = null; view.addTextAtCenter(text, 16f, ts.penColor, "sans") },
            onExport = { trDialog = null; exportTranscript(r) },
            onDismiss = { trDialog = null })
    }

    card?.let { (front, text, page) ->
        com.daftar.app.study.MakeFlashcardDialog(source = hostFile, front = front, recognizedText = text, page = page, onDismiss = { card = null })
    }

    linkEdit?.let { (page, existing) ->
        LinkDialog(existing, exclude = hostFile, onDismiss = { linkEdit = null }) { target, label ->
            linkEdit = null
            if (existing == null) { view.addLink(target, label); ts.selectTool(Tool.LASSO); view.tool = Tool.LASSO }
            else view.updateLink(page, existing.id, target, label)
        }
    }

    recognized?.let { text ->
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { recognized = null },
            title = { Text(stringResource(R.string.ink_recognized_text)) },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(text, color = c.ink) } },
            confirmButton = {
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(text)); toast(ctx, ctx.getString(R.string.copied)); recognized = null }) { Text(stringResource(R.string.copy)) }
            },
            dismissButton = {
                TextButton(onClick = { view.addTextAtCenter(text, 16f, ts.penColor, "sans"); recognized = null }) { Text(stringResource(R.string.ink_insert_as_text)) }
            },
        )
    }
}

/** Pushes toolbar state and settings into the canvas. Its own recomposition scope: tool / colour / width changes
 *  re-run only this (no editor recomposition, no canvas work). */
@Composable
private fun ToolSync(view: InkView, ts: InkToolState, bg: Int) {
    view.tool = ts.tool; view.penColor = ts.penColor; view.penWidth = ts.penWidth; view.penStyle = ts.penStyle
    view.hlColor = ts.hlColor; view.hlWidth = ts.hlWidth; view.eraserRadiusDp = ts.eraserRadius
    view.tapeColor = ts.tapeColor; view.tapeWidth = ts.tapeWidth
    view.shapeColor = ts.penColor
    view.keepScreenOn = Prefs.keepScreenOn
    // "Pen only" is enforced only once this device has shown it has a stylus; otherwise fingers must be able to write.
    view.penOnly = Prefs.penOnly && Prefs.stylusSeen
    view.stylusButtonTool = if (Prefs.stylusButton == 1) Tool.LASSO else Tool.ERASER
    view.bgColor = bg
    view.textColor = ts.penColor
    view.textHint = stringResource(R.string.ink_text_hint)
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun loadBitmap(ctx: android.content.Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            val w = info.size.width; val h = info.size.height
            val s = maxOf(w, h).toFloat() / maxSide
            if (s > 1f) dec.setTargetSize((w / s).toInt(), (h / s).toInt())
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        @Suppress("DEPRECATION")
        val b = MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
        val s = maxOf(b.width, b.height).toFloat() / maxSide
        if (s > 1f) Bitmap.createScaledBitmap(b, (b.width / s).toInt(), (b.height / s).toInt(), true) else b
    }
}.getOrNull()

// =====================================================================================
// Toolbar & dialogs
// =====================================================================================

@Composable
private fun SelectionBar(
    modifier: Modifier, onText: () -> Unit, onTidy: () -> Unit, onSolve: () -> Unit, onFlashcard: () -> Unit,
    onColor: (Int) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit,
) {
    val c = D.c
    var colors by remember { mutableStateOf(false) }
    Row(
        modifier.background(c.surface, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState()).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onText) { Icon(Icons.Rounded.Spellcheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ink_convert_to_text)) }
        TextButton(onClick = onTidy) { Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ink4_tidy)) }
        TextButton(onClick = onSolve) { Icon(Icons.Rounded.Calculate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ink4_solve)) }
        TextButton(onClick = onFlashcard) { Icon(Icons.Rounded.Style, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ink4_flashcard)) }
        Box {
            IconButton(onClick = { colors = true }) { Icon(Icons.Rounded.Palette, stringResource(R.string.color), tint = c.ink) }
            DropdownMenu(colors, { colors = false }) {
                Row(Modifier.padding(8.dp)) {
                    (PenColors + listOf(0xFFFFFFFF.toInt())).forEach { col ->
                        Box(Modifier.padding(3.dp).size(28.dp).clip(CircleShape).background(Color(col)).border(1.dp, c.line, CircleShape)
                            .clickable { onColor(col); colors = false })
                    }
                }
            }
        }
        IconButton(onClick = onDuplicate) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.duplicate), tint = c.ink) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = c.danger) }
        IconButton(onClick = onDone) { Icon(Icons.Rounded.Check, stringResource(R.string.done), tint = c.accent) }
    }
}

/** Zoom pill reading the zoom state in its own scope: pinch-zoom recomposes only this, not the whole editor. */
@Composable
private fun ZoomPill(ctl: EditorController, modifier: Modifier) {
    com.daftar.app.ui.ZoomControls(ctl.zoomPercent, onOut = { ctl.zoomOut() }, onIn = { ctl.zoomIn() }, onFit = { ctl.zoomFit() }, modifier = modifier)
}

/** "page x / y" chip; reads the page state in its own scope (scrolling recomposes only this). */
@Composable
private fun PageChip(ctl: EditorController, onClick: (() -> Unit)?, modifier: Modifier) {
    val c = D.c
    if (ctl.pageCount <= 0) return
    Text(
        stringResource(R.string.page_of, ctl.currentPage + 1, ctl.pageCount),
        style = MaterialTheme.typography.labelMedium, color = c.muted,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface, RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

internal fun fontFamilyOf(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cairo" -> FontFamily(Font(R.font.cairo))
    "amiri" -> FontFamily(Font(R.font.amiri))
    "tehreer" -> FontFamily(Font(R.font.tehreer))
    "hand" -> FontFamily(Font(R.font.caveat))
    else -> FontFamily.SansSerif
}

internal val FontLabels = mapOf("sans" to "Sans", "serif" to "Serif", "mono" to "Mono", "cairo" to "Cairo القاهرة", "amiri" to "Amiri أميري", "tehreer" to "Tehreer تحرير", "hand" to "Handwriting")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextBoxDialog(existing: TextItem?, defaultColor: Int, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (String, Float, String, Boolean, Int) -> Unit) {
    val c = D.c
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var size by remember { mutableFloatStateOf(existing?.size ?: 16f) }
    var font by remember { mutableStateOf(existing?.font ?: "sans") }
    var bold by remember { mutableStateOf(existing?.bold ?: false) }
    var color by remember { mutableIntStateOf(existing?.color ?: defaultColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ink_text_box)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3,
                    placeholder = { Text(stringResource(R.string.ink_text_hint)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontFamilyOf(font), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.ink_font), style = MaterialTheme.typography.labelMedium, color = c.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    InkRender.fonts.forEach { f ->
                        val sel = f == font
                        Text(FontLabels[f] ?: f, fontFamily = fontFamilyOf(f), color = if (sel) c.accent else c.ink,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) c.accent.copy(alpha = 0.12f) else c.surfaceAlt)
                                .clickable { font = f }.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ink_size, size.toInt()), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.width(72.dp))
                    Slider(size, { size = it }, valueRange = 10f..48f, modifier = Modifier.weight(1f))
                    IconToggleButton(bold, { bold = it }) { Icon(Icons.Rounded.FormatBold, null, tint = if (bold) c.accent else c.muted) }
                }
                Row {
                    PenColors.forEach { col ->
                        Box(Modifier.padding(3.dp).size(28.dp).clip(CircleShape).border(2.dp, if (col == color) c.accent else Color.Transparent, CircleShape)
                            .clickable { color = col }.padding(3.dp).clip(CircleShape).background(Color(col)))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onSave(text, size, font, bold, color) else onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = c.danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorGridDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ink_more_colors)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (PenColors + MoreColors).forEach { col ->
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(col)).border(1.dp, D.c.line, CircleShape).clickable { onPick(col) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DictationDialog(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = D.c
    var finalText by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf(Prefs.speechLang) }
    val dict = remember {
        Dictation(ctx, onText = { f, p -> finalText = f; partial = p }, onState = { on, err ->
            listening = on
            if (err != null) toast(ctx, ctx.getString(R.string.ink_speech_error, err))
        })
    }
    DisposableEffect(Unit) {
        if (!dict.available) toast(ctx, ctx.getString(R.string.ink_no_speech_service))
        else { dict.lang = lang; dict.start() }
        onDispose { dict.stop() }
    }
    AlertDialog(
        onDismissRequest = { dict.stop(); onDismiss() },
        title = { Text(stringResource(R.string.ink_dictate)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ar-SA" to "العربية", "en-US" to "English").forEach { (code, label) ->
                        com.daftar.app.ui.Chip(label, lang == code, {
                            if (lang != code) { lang = code; Prefs.putSpeechLang(code); dict.stop(); dict.lang = code; dict.start() }
                        })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).background(c.surfaceAlt, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    val shown = (finalText + " " + partial).trim()
                    Text(shown.ifEmpty { stringResource(R.string.ink_speak_now) }, color = if (shown.isEmpty()) c.muted else c.ink)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (listening) c.danger else c.line))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (listening) R.string.ink_listening else R.string.ink_paused), color = c.muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { if (listening) dict.stop() else dict.start() }) {
                        Text(stringResource(if (listening) R.string.ink_pause else R.string.ink_resume))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { dict.stop(); onInsert((finalText + " " + partial).trim()) }) { Text(stringResource(R.string.ink_insert)) } },
        dismissButton = { TextButton(onClick = { dict.stop(); onDismiss() }) { Text(stringResource(R.string.cancel)) } },
    )
}
