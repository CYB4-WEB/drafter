package com.daftar.app.ui.workspace

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.daftar.app.R
import com.daftar.app.WindowActivity
import com.daftar.app.data.Storage
import com.daftar.app.ui.LibraryFilePickerDialog
import com.daftar.app.ui.Nav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.file
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.screenFor
import com.daftar.app.ui.toast
import java.io.File

/**
 * Entry points of the split workspace and separate windows (owned by workspace-agent; signatures are shared contract).
 */
object Workspace {
    /**
     * Show [first] and [second] side by side in the in-app split workspace. When the focused window already shows a split,
     * [second] opens in the pane that does not have focus (and [first] in the focused one if it isn't showing it yet).
     */
    fun openSideBySide(ctx: Context, first: File, second: File) {
        val b = screenFor(second)
        if (b == null) { openExternally(ctx, second); return }
        val ap = Nav.activePane
        val ctl = (ap as? SplitPane)?.controller ?: SplitRegistry.forScreen(HostStacks.current(ap))
        val a = screenFor(first)
        if (!second.isDirectory) Storage.opened(second)
        if (ctl != null) {
            val focused = (ap as? SplitPane)?.takeIf { ctl.closed !== it } ?: ctl.active.let { if (ctl.closed === it) ctl.other(it) else it }
            if (a != null && focused.current.file?.absolutePath != first.absolutePath && focused.current != a) focused.stack.push(a)
            ctl.showIn(ctl.other(focused), b)
            return
        }
        if (a == null) { openExternally(ctx, first); return }
        ap.push(Screen.Split(a, b))
    }

    /** Open [file] in a separate app window (Samsung multi-window / pop-up view). */
    fun openInNewWindow(ctx: Context, file: File) {
        if (screenFor(file) == null) { openExternally(ctx, file); return }
        val i = Intent(ctx, WindowActivity::class.java)
            .putExtra(EXTRA_OPEN_PATH, file.absolutePath)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        if (!file.isDirectory) Storage.opened(file)
        try { ctx.startActivity(i) } catch (_: Exception) { toast(ctx, ctx.getString(R.string.error_generic)) }
    }
}

/**
 * Returns a function that asks the user for a second library file and then opens it next to the given one.
 * Hosts its own picker dialog — call once per screen and invoke the result from a button/menu.
 */
@Composable
fun rememberSplitPicker(): (File) -> Unit {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf<File?>(null) }
    current?.let { f ->
        LibraryFilePickerDialog(
            title = stringResource(R.string.pick_second_file),
            accept = { screenFor(it) != null && it.absolutePath != f.absolutePath },
            multiple = false,
            onDismiss = { current = null },
            start = f.parentFile?.takeIf { it.absolutePath.startsWith(Storage.root.absolutePath) } ?: Storage.root,
        ) { picked ->
            current = null
            picked.firstOrNull()?.let { Workspace.openSideBySide(ctx, f, it) }
        }
    }
    return remember { { f: File -> current = f } }
}
