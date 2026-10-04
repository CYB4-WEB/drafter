package com.daftar.app.ink

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import java.io.File

/**
 * Full drawing editor: top bar, tool bar, page canvas, optional side panel.
 *
 * @param inkFile where the InkDoc JSON is persisted (the .note itself, or a sidecar for PDF/PPTX)
 * @param source  fixed background pages (PDF/slides) or null for a free notebook
 * @param isNote  true for notebooks (pages can be added/removed, paper styles, audio recording)
 * @param extraActions extra top-bar buttons (e.g. PDF tools menu)
 * @param sidePanel optional panel (end side on wide screens, bottom sheet on narrow ones)
 */
@Composable
fun InkEditorScaffold(
    title: String,
    inkFile: File,
    source: PageSource?,
    isNote: Boolean,
    onBack: () -> Unit,
    onRename: ((String) -> Unit)? = null,
    extraActions: @Composable RowScope.(EditorController) -> Unit = {},
    sidePanel: (@Composable (EditorController) -> Unit)? = null,
    sidePanelLabel: String = "",
) {
    InkEditorImpl(title, inkFile, source, isNote, onBack, onRename, extraActions, sidePanel, sidePanelLabel)
}
