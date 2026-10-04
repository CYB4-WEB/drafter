package com.daftar.app.ui.workspace

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.daftar.app.R
import com.daftar.app.ui.LibraryFilePickerDialog
import com.daftar.app.ui.Nav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.screenFor
import java.io.File

/**
 * Entry points of the split workspace and separate windows (owned by workspace-agent; signatures are shared contract).
 */
object Workspace {
    /** Show [first] and [second] side by side in the in-app split workspace (or replace the other pane when already split). */
    fun openSideBySide(ctx: Context, first: File, second: File) {
        val a = screenFor(first); val b = screenFor(second)
        if (a == null || b == null) { openExternally(ctx, if (a == null) first else second); return }
        Nav.push(Screen.Split(a, b))
    }

    /** Open [file] in a separate app window (Samsung multi-window / pop-up view). */
    fun openInNewWindow(ctx: Context, file: File) {
        Nav.open(ctx, file)
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
        ) { picked ->
            current = null
            picked.firstOrNull()?.let { Workspace.openSideBySide(ctx, f, it) }
        }
    }
    return remember { { f: File -> current = f } }
}
