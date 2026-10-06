package com.daftar.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import java.io.File

/** Every destination in the app. Viewers take an absolute file path. */
sealed class Screen {
    data object Home : Screen()
    data class Library(val dir: String) : Screen()
    data object Planner : Screen()
    data object Settings : Screen()
    data object Notes : Screen()
    data class Search(val query: String) : Screen()
    data class Note(val path: String) : Screen()
    data class Pdf(val path: String) : Screen()
    data class Slides(val path: String) : Screen()
    data class Word(val path: String) : Screen()
    data class Image(val path: String) : Screen()
    /** OneNote section (.one) or notebook package (.onepkg). */
    data class OneNote(val path: String) : Screen()
    /** In-app browser / video player for links placed in notes. */
    data class Web(val url: String) : Screen()
    /** Converter hub; [path] preselects a source file. */
    data class Convert(val path: String? = null) : Screen()
    /** Two documents side by side (owned by workspace-agent). */
    data class Split(val first: Screen, val second: Screen, val vertical: Boolean = false) : Screen()
    /** Planner editor; id null = new event, preset type optional. */
    data class EditEvent(val id: Long?, val presetType: Int = -1) : Screen()
    /** Study hub: AI practice quizzes + focus timer (quiz-agent). */
    data object Study : Screen()
    /** Create an AI practice quiz; the source (file / pages / selection image) comes from `study.QuizRequests`. */
    data object NewQuiz : Screen()
    /** Take / review a saved quiz. */
    data class Quiz(val id: String) : Screen()
    /** Recycle bin (files-agent). */
    data object Trash : Screen()
    /** GPA / grades tracker (grades-agent). */
    data object Grades : Screen()
    /** Countdown list built on planner events (countdown-agent). */
    data object Countdowns : Screen()
    /** Live countdown of one event occurrence; occStart 0 = next occurrence (countdown-agent). */
    data class Countdown(val eventId: Long, val occStart: Long = 0L) : Screen()

    val isTopLevel get() = this is Home || this is Planner || this is Settings || this is Notes || this is Search || this is Convert || this is Study || (this is Library && dir == Storage.root.absolutePath)
}

/** Intent extra used by widgets / notifications to deep-link: values below. */
const val EXTRA_ACTION = "daftar.action"
const val ACTION_NEW_NOTE = "new_note"
const val ACTION_PLANNER = "planner"
const val ACTION_IMPORT = "import"
const val ACTION_ADD_EVENT = "add_event"
/** Extra with a PlanEvent id (Long) to open that event's editor. */
const val EXTRA_EVENT_ID = "daftar.event_id"
/** Extras (Long) used by countdown widgets to open the live countdown of an event occurrence. */
const val EXTRA_COUNTDOWN_EVENT = "daftar.countdown_event"
const val EXTRA_COUNTDOWN_START = "daftar.countdown_start"

/**
 * Navigation seen by a screen. In the normal app it is the global [Nav]; inside a split-screen pane
 * the workspace provides its own so back/open act on that pane only. Screens must use
 * `LocalPaneNav.current` instead of calling Nav.pop()/Nav.open() directly.
 */
interface PaneNav {
    fun back()
    fun open(ctx: Context, f: File)
    fun push(s: Screen)
    /** Replace the current screen of this pane (e.g. after a rename). */
    fun replace(s: Screen)
    /** True when the screen is shown inside a split pane (hide redundant chrome, etc.). */
    val inPane: Boolean get() = false
    /** The screens of this pane's back stack, bottom first. */
    val screens: List<Screen> get() = emptyList()
    /** Pop back until [s] is on top (e.g. breadcrumbs). Returns false (and changes nothing) when [s] is not in the stack. */
    fun popTo(s: Screen): Boolean = false
}

object RootPaneNav : PaneNav {
    override fun back() { Nav.pop() }
    override fun open(ctx: Context, f: File) = Nav.open(ctx, f)
    override fun push(s: Screen) = Nav.push(s)
    override fun replace(s: Screen) = Nav.replace(s)
    override val screens: List<Screen> get() = Nav.stack.toList()
    override fun popTo(s: Screen): Boolean {
        val i = Nav.stack.lastIndexOf(s)
        if (i < 0) return false
        while (Nav.stack.lastIndex > i) Nav.stack.removeAt(Nav.stack.lastIndex)
        return true
    }
}

val LocalPaneNav = androidx.compose.runtime.staticCompositionLocalOf<PaneNav> { RootPaneNav }

/**
 * The pane the user last touched (the split workspace updates [Nav.activePane] on pointer-down in a pane).
 * Use `pane.back()` / `pane.open(ctx, file)` / `pane.push(screen)` from any screen code.
 */
val pane: PaneNav get() = Nav.activePane

object Nav {
    /** Observable so the split workspace can outline the pane that has focus. */
    var activePane: PaneNav by androidx.compose.runtime.mutableStateOf(RootPaneNav)
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current get() = stack.last()

    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    /** Switch top-level tab (rail / bottom bar). */
    fun tab(s: Screen) { stack.clear(); stack.add(s) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }

    fun open(ctx: Context, f: File) {
        val s = screenFor(f)
        if (s == null) { openExternally(ctx, f); return }
        if (!f.isDirectory) Storage.opened(f)
        push(s)
    }
}

/** The in-app screen that shows [f], or null when only another app can open it. */
fun screenFor(f: File): Screen? {
    if (f.isDirectory) return Screen.Library(f.absolutePath)
    return when (Storage.kindOf(f)) {
        Kind.NOTE -> Screen.Note(f.absolutePath)
        Kind.PDF -> Screen.Pdf(f.absolutePath)
        Kind.PPTX -> Screen.Slides(f.absolutePath)
        Kind.DOCX, Kind.TEXT -> Screen.Word(f.absolutePath)
        Kind.IMAGE -> Screen.Image(f.absolutePath)
        Kind.ONENOTE -> Screen.OneNote(f.absolutePath)
        else -> null
    }
}

/** File shown by a viewer screen (null for non-file screens). */
val Screen.file: File? get() = when (this) {
    is Screen.Note -> File(path)
    is Screen.Pdf -> File(path)
    is Screen.Slides -> File(path)
    is Screen.Word -> File(path)
    is Screen.Image -> File(path)
    is Screen.OneNote -> File(path)
    else -> null
}

fun uriFor(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

fun mimeOf(f: File): String = when (f.extension.lowercase()) {
    "note" -> "application/octet-stream"
    "one", "onetoc2" -> "application/onenote"
    "onepkg" -> "application/vnd.ms-cab-compressed"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
}

fun openExternally(ctx: Context, f: File) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, f), mimeOf(f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    try { ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.open_with))) }
    catch (_: ActivityNotFoundException) { Toast.makeText(ctx, R.string.no_app_found, Toast.LENGTH_SHORT).show() }
}

fun shareFiles(ctx: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { uriFor(ctx, it) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mimeOf(files[0]))
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.share)))
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
