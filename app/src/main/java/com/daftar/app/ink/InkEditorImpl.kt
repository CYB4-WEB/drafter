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

private val Papers = listOf("blank", "lined", "grid", "dots", "cornell")

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
    var showColors by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val wideAtStart = LocalWidthClass.current == WidthClass.Expanded
    var showPanel by remember { mutableStateOf(sidePanelOpen && wideAtStart) }
    var showBottom by remember { mutableStateOf(false) }
    var showDictation by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var recognized by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    val wide = LocalWidthClass.current == WidthClass.Expanded

    // ---- persistence ----
    fun saveBlocking() {
        if (!loaded) return
        if (isNote && !inkFile.exists()) return   // renamed/moved away while open
        val d = view.doc
        val snap = InkDoc(d.pages, d.paperColor, d.recordings, d.infinite)
        runCatching {
            if (source != null && snap.pages.all { it.isEmpty() } && snap.recordings.isEmpty()) inkFile.delete() else snap.save(inkFile)
        }
        dirty = false
    }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(700)
            val d = view.doc
            val snap = InkDoc(d.pages, d.paperColor, d.recordings, d.infinite)
            withContext(Dispatchers.IO) {
                if (isNote && !inkFile.exists()) return@withContext
                runCatching {
                    if (source != null && snap.pages.all { it.isEmpty() } && snap.recordings.isEmpty()) inkFile.delete() else snap.save(inkFile)
                }
            }
            if (isNote) Storage.touch()
            dirty = false
        }
    }
    ctl.saver = { saveBlocking() }

    // ---- recording / playback ----
    val recorder = remember { Recorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recElapsed by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<Recording?>(null) }
    var playPos by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    fun stopPlayback() {
        player?.release(); player = null; playing = null; isPlaying = false
        view.playRec = 0; view.invalidate()
    }

    fun startRecording() {
        stopPlayback()
        val id = (view.doc.recordings.maxOfOrNull { it.id } ?: 0) + 1
        val f = Storage.sidecar(inkFile, "rec$id.m4a")
        runCatching { recorder.start(f) }.onFailure { toast(ctx, ctx.getString(R.string.error_generic)); return }
        view.recId = id; view.recClockStart = SystemClock.elapsedRealtime(); view.recOffset = 0
        recording = true
    }

    fun stopRecording() {
        recorder.stop()
        val f = recorder.file
        val id = view.recId
        view.recId = 0
        recording = false
        if (f != null && f.exists()) {
            view.doc.recordings = view.doc.recordings + Recording(id, f.name, audioDuration(f), System.currentTimeMillis())
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

    LaunchedEffect(recording) {
        while (recording && isActive) { recElapsed = SystemClock.elapsedRealtime() - view.recClockStart; delay(250) }
    }
    LaunchedEffect(player, isPlaying) {
        while (isPlaying && isActive) {
            player?.let { playPos = it.currentPosition.toLong(); view.playPos = playPos }
            delay(80)
        }
    }

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
            else existing ?: InkDoc.newNote(Prefs.defaultPaper)
        }
        view.setDocument(d, source)
        whiteboard = d.infinite
        hasTapes = view.hasTapes()
        ctl.pageCount = d.pages.size
        loaded = true
    }

    view.listener = remember(view) {
        object : InkView.Listener {
            override fun onChanged() { ctl.pageCount = view.doc.pages.size; scheduleSave() }
            override fun onLinkOpen(link: LinkItem) { view.commitSelection(); saveBlocking(); openLink(ctx, link) }
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
        }
    }
    ctl.onImageAdded = { ts.selectTool(Tool.LASSO); view.tool = Tool.LASSO }

    // keep the view in sync with toolbar & settings
    view.tool = ts.tool; view.penColor = ts.penColor; view.penWidth = ts.penWidth; view.penStyle = ts.penStyle
    view.hlColor = ts.hlColor; view.hlWidth = ts.hlWidth; view.eraserRadiusDp = ts.eraserRadius
    view.tapeColor = ts.tapeColor; view.tapeWidth = ts.tapeWidth
    view.shapeColor = ts.penColor
    view.keepScreenOn = Prefs.keepScreenOn
    view.penOnly = Prefs.penOnly
    view.stylusButtonTool = if (Prefs.stylusButton == 1) Tool.LASSO else Tool.ERASER
    view.bgColor = c.bg.toArgb()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(inkFile) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) { view.commitSelection(); saveBlocking() } }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (recorder.active) stopRecording()
            stopPlayback()
            view.commitSelection()
            saveBlocking()
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

    val actions = if (isNote) rememberViewerActions(hostFile) else null

    // =========================== UI ===========================
    Column(Modifier.fillMaxSize().background(c.bg)) {
        ViewerTopBar(title, onBack = { view.commitSelection(); saveBlocking(); onBack() },
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
                        DropdownMenuItem({ Text(stringResource(R.string.ink_add_page)) }, { showMore = false; view.addPage(ctl.currentPage, view.doc.pages.getOrNull(ctl.currentPage)?.paper ?: Prefs.defaultPaper) }, leadingIcon = { Icon(Icons.Rounded.NoteAdd, null) })
                    }
                    if (isNote) DropdownMenuItem({ Text(stringResource(R.string.ink_paper)) }, { showMore = false; showPaper = true }, leadingIcon = { Icon(Icons.Rounded.GridOn, null) })
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
                        DropdownMenuItem({ Text(stringResource(R.string.ink_export_pdf)) }, {
                            showMore = false
                            scope.launch {
                                view.commitSelection(); saveBlocking()
                                val out = Storage.uniqueFile(inkFile.parentFile!!, inkFile.nameWithoutExtension, "pdf")
                                val ok = withContext(Dispatchers.IO) { runCatching { exportNoteToPdf(view.doc, out) }.isSuccess }
                                Storage.touch()
                                toast(ctx, if (ok) ctx.getString(R.string.saved_to, out.name) else ctx.getString(R.string.error_generic))
                            }
                        }, leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) })
                    }
                    DropdownMenuItem(
                        { Text(stringResource(if (Prefs.penOnly) R.string.ink_finger_draw_off else R.string.ink_finger_draw_on)) },
                        { showMore = false; Prefs.putPenOnly(!Prefs.penOnly) },
                        leadingIcon = { Icon(Icons.Rounded.TouchApp, null) },
                    )
                    if (actions != null) {
                        HorizontalDivider(color = c.line)
                        ViewerMenuItems(actions, close = { showMore = false }, onShare = {
                            scope.launch {
                                view.commitSelection(); saveBlocking()
                                val out = File(Storage.cacheDir(), inkFile.nameWithoutExtension + ".pdf")
                                val ok = withContext(Dispatchers.IO) { runCatching { exportNoteToPdf(view.doc, out) }.isSuccess }
                                if (ok) shareFiles(ctx, listOf(out)) else toast(ctx, ctx.getString(R.string.error_generic))
                            }
                        })
                    }
                }
            }
        }

        // ---- toolbar ----
        InkToolbar(
            st = ts,
            showAddPage = isNote && !whiteboard,
            onToolChanged = { view.commitSelection() },
            onMoreColors = { showColors = true },
            onImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onLink = { linkEdit = ctl.currentPage to null },
            onDictate = { if (hasMic()) showDictation = true else dictPermission.launch(Manifest.permission.RECORD_AUDIO) },
            onAddPage = { view.addPage(ctl.currentPage, view.doc.pages.getOrNull(ctl.currentPage)?.paper ?: Prefs.defaultPaper) },
        )

        if (recording) {
            Row(Modifier.fillMaxWidth().background(c.danger.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.danger))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ink_recording_now, fmtTime(recElapsed)), color = c.ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { stopRecording() }) { Text(stringResource(R.string.ink_stop)) }
            }
        }
        playing?.let { r ->
            Row(Modifier.fillMaxWidth().background(c.accent.copy(alpha = 0.08f)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val p = player ?: return@IconButton
                    if (isPlaying) { p.pause(); isPlaying = false } else { p.start(); isPlaying = true }
                }) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = c.accent) }
                Text(fmtTime(playPos), style = MaterialTheme.typography.labelMedium, color = c.ink)
                Slider(
                    value = playPos.toFloat().coerceIn(0f, r.duration.coerceAtLeast(1).toFloat()),
                    onValueChange = { v -> player?.seekTo(v.toInt()); playPos = v.toLong(); view.playPos = playPos },
                    valueRange = 0f..r.duration.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(fmtTime(r.duration), style = MaterialTheme.typography.labelMedium, color = c.muted)
                IconButton(onClick = { stopPlayback() }) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.muted) }
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
                AndroidView({ view }, Modifier.fillMaxSize().clipToBounds())
                if (loaded) com.daftar.app.ui.ZoomControls(
                    ctl.zoomPercent, onOut = { ctl.zoomOut() }, onIn = { ctl.zoomIn() }, onFit = { ctl.zoomFit() },
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                )
                if (!loaded) CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)

                if (hasSel) SelectionBar(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    onText = { recognize(view.selectedStrokes(), true) },
                    onColor = { col -> view.recolorSelection(col) },
                    onDuplicate = { view.duplicateSelection() },
                    onDelete = { view.deleteSelection() },
                    onDone = { view.commitSelection() },
                )
                if (ctl.pageCount > 0 && !whiteboard) Text(
                    stringResource(R.string.page_of, ctl.currentPage + 1, ctl.pageCount),
                    style = MaterialTheme.typography.labelMedium, color = c.muted,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                        .background(c.surface, RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                busy?.let { msg ->
                    Row(Modifier.align(Alignment.BottomCenter).padding(24.dp).background(c.surface, RoundedCornerShape(12.dp))
                        .border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                        Spacer(Modifier.width(10.dp)); Text(msg, color = c.ink)
                    }
                }
                linkMenu?.let { (page, link, pos) ->
                    LinkMenu(pos.first, pos.second, link, onDismiss = { linkMenu = null },
                        onOpen = { linkMenu = null; view.commitSelection(); saveBlocking(); openLink(ctx, link) },
                        onSideBySide = { linkMenu = null; view.commitSelection(); saveBlocking(); openLinkSideBySide(ctx, link, hostFile) },
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

    if (showPaper) PaperDialog(showAllPages = !whiteboard, onDismiss = { showPaper = false }) { paper, all ->
        view.setPaper(paper, all); if (!whiteboard) Prefs.putPaper(paper); showPaper = false
    }

    if (showColors) ColorGridDialog(onDismiss = { showColors = false }) { col ->
        ts.pickColor(col)
        view.commitSelection()
        showColors = false
    }

    if (showRename && onRename != null) TextInputDialog(stringResource(R.string.rename), title, stringResource(R.string.save), { showRename = false }) {
        saveBlocking(); showRename = false; onRename(it)
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
private fun SelectionBar(modifier: Modifier, onText: () -> Unit, onColor: (Int) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit) {
    val c = D.c
    var colors by remember { mutableStateOf(false) }
    Row(
        modifier.background(c.surface, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onText) { Icon(Icons.Rounded.Spellcheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ink_convert_to_text)) }
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

private fun fontFamilyOf(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cairo" -> FontFamily(Font(R.font.cairo))
    "amiri" -> FontFamily(Font(R.font.amiri))
    "tehreer" -> FontFamily(Font(R.font.tehreer))
    "hand" -> FontFamily(Font(R.font.caveat))
    else -> FontFamily.SansSerif
}

private val FontLabels = mapOf("sans" to "Sans", "serif" to "Serif", "mono" to "Mono", "cairo" to "Cairo القاهرة", "amiri" to "Amiri أميري", "tehreer" to "Tehreer تحرير", "hand" to "Handwriting")

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

@Composable
private fun PaperDialog(showAllPages: Boolean, onDismiss: () -> Unit, onPick: (String, Boolean) -> Unit) {
    val c = D.c
    var all by remember { mutableStateOf(false) }
    val labels = mapOf("blank" to R.string.ink_paper_blank, "lined" to R.string.ink_paper_lined, "grid" to R.string.ink_paper_grid,
        "dots" to R.string.ink_paper_dots, "cornell" to R.string.ink_paper_cornell)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ink_paper)) },
        text = {
            Column {
                Papers.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(p, all) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (p) { "lined" -> Icons.Rounded.ViewHeadline; "grid" -> Icons.Rounded.GridOn; "dots" -> Icons.Rounded.MoreHoriz; "cornell" -> Icons.Rounded.ViewQuilt; else -> Icons.Rounded.CropPortrait }, null, tint = c.muted)
                        Spacer(Modifier.width(12.dp)); Text(stringResource(labels[p]!!), color = c.ink)
                    }
                }
                if (showAllPages) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(all, { all = it }); Text(stringResource(R.string.ink_apply_all_pages), color = c.ink)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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
