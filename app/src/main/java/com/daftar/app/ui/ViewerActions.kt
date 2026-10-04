package com.daftar.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.daftar.app.R
import com.daftar.app.convert.ConvertSheet
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.workspace.Workspace
import com.daftar.app.ui.workspace.rememberSplitPicker
import java.io.File

/**
 * The actions every viewer header offers for its file: Share, Convert, Open side by side, Open in a new window,
 * Open in another app. Create once per viewer with [rememberViewerActions]; it also hosts the convert sheet and the
 * side-by-side file picker.
 */
class ViewerActions internal constructor(val file: File) {
    internal var showConvert by mutableStateOf(false)
    internal var pickSecond: (File) -> Unit = {}

    fun convert() { showConvert = true }
    fun openSideBySide() = pickSecond(file)
}

@Composable
fun rememberViewerActions(file: File): ViewerActions {
    val a = remember(file.absolutePath) { ViewerActions(file) }
    a.pickSecond = rememberSplitPicker()
    if (a.showConvert) ConvertSheet(file) { a.showConvert = false }
    return a
}

/** "Convert" header button. */
@Composable
fun ConvertButton(a: ViewerActions) {
    IconButton(onClick = { a.convert() }) { Icon(Icons.Rounded.Transform, stringResource(R.string.convert), tint = D.c.ink) }
}

/**
 * Standard overflow-menu entries; put inside a DropdownMenu. [close] dismisses the menu.
 * [onShare] overrides plain sharing (e.g. the PDF viewer shares the annotated copy).
 */
@Composable
fun ViewerMenuItems(a: ViewerActions, close: () -> Unit, onShare: (() -> Unit)? = null, showConvert: Boolean = true) {
    val ctx = LocalContext.current
    val c = D.c
    DropdownMenuItem({ Text(stringResource(R.string.share)) }, { close(); if (onShare != null) onShare() else shareFiles(ctx, listOf(a.file)) },
        leadingIcon = { Icon(Icons.Rounded.Share, null, tint = c.muted) })
    if (showConvert) DropdownMenuItem({ Text(stringResource(R.string.convert)) }, { close(); a.convert() },
        leadingIcon = { Icon(Icons.Rounded.Transform, null, tint = c.muted) })
    DropdownMenuItem({ Text(stringResource(R.string.open_side_by_side)) }, { close(); a.openSideBySide() },
        leadingIcon = { Icon(Icons.Rounded.VerticalSplit, null, tint = c.muted) })
    DropdownMenuItem({ Text(stringResource(R.string.open_new_window)) }, { close(); Workspace.openInNewWindow(ctx, a.file) },
        leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null, tint = c.muted) })
    DropdownMenuItem({ Text(stringResource(R.string.open_externally)) }, { close(); openExternally(ctx, a.file) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.muted) })
}
