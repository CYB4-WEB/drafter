package com.daftar.app.slides

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Prefs
import com.daftar.app.data.Storage
import com.daftar.app.ink.EditorController
import com.daftar.app.ink.InkEditorScaffold
import com.daftar.app.ui.ConvertButton
import com.daftar.app.ui.LocalPaneNav
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.ViewerMenuItems
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.rememberViewerActions
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import com.daftar.app.ui.widthClassOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.WeakHashMap

// ------------------------------------------------------------------ state holders

private sealed class LoadState {
    data object Loading : LoadState()
    class Error(val msgRes: Int) : LoadState()
    class Ready(val src: PptxSource, val notes: MyNotes, val thumbs: Thumbs) : LoadState()
}

/** Owns the opened source so it is closed even when the screen leaves while parsing is still running. */
private class Holder {
    private var src: PptxSource? = null
    private var disposed = false

    @Synchronized fun adopt(s: PptxSource): Boolean {
        if (disposed) { s.close(); return false }
        src = s; return true
    }

    @Synchronized fun dispose() { disposed = true; src?.close(); src = null }
}

// ------------------------------------------------------------------ screen

@Composable
fun SlidesScreen(path: String) {
    val ctx = LocalContext.current
    val nav = LocalPaneNav.current
    val file = remember(path) { File(path) }
    val labels = Labels(
        stringResource(R.string.slides_chart), stringResource(R.string.slides_diagram),
        stringResource(R.string.slides_object), stringResource(R.string.slides_image),
    )
    // Thumbnails are rendered at about the rail's width in pixels (sharp on the tablet, small on phones).
    val thumbPx = with(LocalDensity.current) { 216.dp.roundToPx() }.coerceIn(200, 480)
    val holder = remember(path) { Holder() }
    var state by remember(path) { mutableStateOf<LoadState>(LoadState.Loading) }

    LaunchedEffect(Unit) { SlidesExport.bind(ctx) }
    LaunchedEffect(path) {
        state = withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext LoadState.Error(R.string.slides_err_missing)
            try {
                val src = PptxSource.open(file, labels)
                if (!holder.adopt(src)) return@withContext LoadState.Loading
                val notes = MyNotes(Storage.sidecar(file, "mynotes.json")).also { it.load() }
                LoadState.Ready(src, notes, Thumbs(src, thumbPx))
            } catch (e: PptxException) {
                LoadState.Error(
                    when (e.kind) {
                        PptxException.Kind.LEGACY -> R.string.slides_err_legacy
                        PptxException.Kind.EMPTY -> R.string.slides_err_empty
                        PptxException.Kind.CORRUPT -> R.string.slides_err_corrupt
                    },
                )
            } catch (_: Throwable) {
                LoadState.Error(R.string.slides_err_corrupt)
            }
        }
    }
    DisposableEffect(path) { onDispose { holder.dispose() } }

    when (val s = state) {
        LoadState.Loading -> Column(Modifier.fillMaxSize().background(D.c.bg)) {
            ViewerTopBar(file.nameWithoutExtension, onBack = { nav.back() })
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = D.c.accent)
                Spacer(Modifier.height(16.dp))
                Text(file.name, style = MaterialTheme.typography.titleMedium, color = D.c.ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.slides_opening), style = MaterialTheme.typography.bodyMedium, color = D.c.muted)
            }
        }
        is LoadState.Error -> Column(Modifier.fillMaxSize().background(D.c.bg)) {
            ViewerTopBar(file.nameWithoutExtension, onBack = { nav.back() })
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = D.c.muted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.slides_err_title), style = MaterialTheme.typography.titleMedium, color = D.c.ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(s.msgRes), style = MaterialTheme.typography.bodyLarge, color = D.c.muted,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp),
                )
                if (file.isFile) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { openExternally(ctx, file) }, shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = D.c.accent, contentColor = D.c.onAccent),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.open_externally))
                    }
                }
            }
        }
        is LoadState.Ready -> SlidesEditor(file, s, onBack = { nav.back() })
    }
}

/**
 * PowerPoint-like editor. The scaffold sees the width class of THIS screen (not of the whole window), so a narrow
 * split pane behaves like a phone even on the tablet:
 * - Expanded: slide rail on the start side (open unless shown in a split pane) + notes pane (Speaker notes / Comments / My notes).
 * - Medium / Compact: no rail; the notes pane starts with a Slides filmstrip tab.
 */
@Composable
private fun SlidesEditor(file: File, s: LoadState.Ready, onBack: () -> Unit) {
    val notes = s.notes
    // Debounced autosave of "My notes" (~600 ms after the last keystroke) + a final save on exit.
    LaunchedEffect(notes) {
        snapshotFlow { notes.version }.drop(1).collectLatest {
            delay(600)
            val data = notes.snapshot()
            withContext(Dispatchers.IO) { notes.save(data) }
        }
    }
    DisposableEffect(notes) {
        onDispose {
            if (notes.version > 0) {
                val data = notes.snapshot()
                Thread { notes.save(data) }.start()
            }
        }
    }
    DisposableEffect(s) { onDispose { s.thumbs.dispose() } }
    KeepScreenOn(Prefs.keepScreenOn)

    val inPane = LocalPaneNav.current.inPane
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = widthClassOf(maxWidth)
        val expanded = width == WidthClass.Expanded
        var tab by rememberSaveable { mutableStateOf(if (expanded) PaneTab.SPEAKER else PaneTab.SLIDES) }
        val railLabel = stringResource(R.string.slides_rail)
        val paneLabel = stringResource(if (expanded) R.string.slides_notes_pane else R.string.slides_slides_and_notes)
        CompositionLocalProvider(LocalWidthClass provides width) {
            InkEditorScaffold(
                title = file.nameWithoutExtension,
                inkFile = Storage.sidecar(file, "ink.json"),
                source = s.src,
                isNote = false,
                onBack = onBack,
                extraActions = { controller -> SlidesHeaderActions(file, s, controller, compact = width == WidthClass.Compact) },
                sidePanel = if (expanded) ({ controller -> SlideRail(s.src.deck, s.thumbs, controller) }) else null,
                sidePanelLabel = railLabel,
                sidePanelAtStart = true,
                sidePanelOpen = !inPane,
                bottomPanel = { controller -> NotesPane(s.src.deck, s.thumbs, notes, controller, tab, { tab = it }, withSlides = !expanded) },
                bottomPanelLabel = paneLabel,
            )
        }
    }
}

// ------------------------------------------------------------------ header

/** Present, Convert and the "Share and export" menu (its own icon; the scaffold already has a ⋮ menu). */
@Composable
private fun SlidesHeaderActions(file: File, s: LoadState.Ready, controller: EditorController, compact: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = D.c
    val actions = rememberViewerActions(file)
    var presentFrom by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }

    if (compact) {
        IconButton(onClick = { presentFrom = controller.currentPage }) {
            Icon(Icons.Rounded.Slideshow, stringResource(R.string.slides_present_from_current), tint = c.accent)
        }
    } else {
        Row(
            Modifier.padding(horizontal = 4.dp).height(36.dp).clip(RoundedCornerShape(12.dp)).background(c.accent)
                .clickable(onClickLabel = stringResource(R.string.slides_present_from_current), role = Role.Button) { presentFrom = controller.currentPage }
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.slides_present), color = c.onAccent, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        ConvertButton(actions)
    }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.IosShare, stringResource(R.string.slides_file_menu), tint = c.ink) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            ViewerMenuItems(actions, close = { menu = false }, showConvert = compact)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.slides_export_notes)) },
                leadingIcon = { Icon(Icons.Rounded.FileDownload, null, tint = c.muted) },
                onClick = {
                    menu = false
                    val mine = s.notes.snapshot()
                    scope.launch {
                        val out = withContext(Dispatchers.IO) { runCatching { exportNotes(ctx, file, s.src.deck, mine) }.getOrNull() }
                        if (out != null) { Storage.touch(); toast(ctx, ctx.getString(R.string.saved_to, out.name)) }
                        else toast(ctx, ctx.getString(R.string.error_generic))
                    }
                },
            )
        }
    }

    presentFrom?.let { from ->
        PresentMode(
            src = s.src, thumbs = s.thumbs, start = from,
            inkPage = { i -> controller.doc().pages.getOrNull(i) },
        ) { last ->
            presentFrom = null
            controller.goToPage(last)
        }
    }
}

/** Writes "<deck> - notes.txt" next to the deck: per slide, speaker notes, my notes and comments. */
private fun exportNotes(ctx: Context, file: File, deck: Pptx, mine: Map<Int, String>): File {
    val out = Storage.uniqueFile(file.parentFile!!, ctx.getString(R.string.slides_export_file, file.nameWithoutExtension), "txt")
    val sb = StringBuilder()
    sb.append(file.nameWithoutExtension).append('\n').append("=".repeat(file.nameWithoutExtension.length.coerceIn(3, 60))).append("\n\n")
    for (slide in deck.slides) {
        val n = slide.index + 1
        val head = if (slide.title.isNotBlank()) ctx.getString(R.string.slides_slide_n_title, n, slide.title) else ctx.getString(R.string.slides_slide_n, n)
        sb.append(head).append('\n').append("-".repeat(head.length.coerceIn(3, 60))).append('\n')
        if (slide.notes.isNotBlank()) {
            sb.append(ctx.getString(R.string.slides_speaker_notes)).append(":\n").append(slide.notes.trim()).append("\n\n")
        }
        mine[slide.index]?.takeIf { it.isNotBlank() }?.let {
            sb.append(ctx.getString(R.string.slides_tab_mynotes)).append(":\n").append(it.trim()).append("\n\n")
        }
        if (slide.comments.isNotEmpty()) {
            sb.append(ctx.getString(R.string.slides_comments_header)).append(":\n")
            for (c in slide.comments) {
                sb.append("- ").append(c.author.ifBlank { ctx.getString(R.string.slides_unknown_author) }).append(": ").append(c.text).append('\n')
                for (r in c.replies) sb.append("    - ").append(r.author.ifBlank { ctx.getString(R.string.slides_unknown_author) }).append(": ").append(r.text).append('\n')
            }
            sb.append('\n')
        }
        sb.append('\n')
    }
    val tmp = File(out.parentFile, ".${out.name}.part")
    tmp.writeText(sb.toString())
    if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    return out
}

// ------------------------------------------------------------------ keep screen on (Settings option)

/** Honours Settings → "Keep the screen on" while the deck is open (counted per view, several decks can be open). */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        if (on) ScreenOn.acquire(view)
        onDispose { if (on) ScreenOn.release(view) }
    }
}

private object ScreenOn {
    private val counts = WeakHashMap<View, Int>()

    fun acquire(v: View) {
        counts[v] = (counts[v] ?: 0) + 1
        v.keepScreenOn = true
    }

    fun release(v: View) {
        val n = (counts[v] ?: 1) - 1
        if (n <= 0) { counts.remove(v); v.keepScreenOn = false } else counts[v] = n
    }
}
