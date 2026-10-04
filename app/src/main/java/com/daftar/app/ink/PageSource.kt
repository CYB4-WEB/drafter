package com.daftar.app.ink

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Fixed background pages under the ink layer (PDF pages, PPTX slides).
 * Sizes are in points (1/72 inch); ink coordinates use the same space.
 */
interface PageSource {
    val pageCount: Int

    /** Page size in points (width, height), as displayed (rotation applied). */
    fun pageSize(i: Int): Pair<Float, Float>

    /**
     * Draw page [i] into [dest]. [m] maps page points -> bitmap pixels (it may describe a zoomed tile,
     * so content outside the bitmap must simply be clipped). [dest] is pre-filled white.
     * Called on a background thread, one call at a time.
     */
    fun render(i: Int, dest: Bitmap, m: Matrix)

    fun close() {}
}

/** Controller handed to screens hosting [InkEditorScaffold] so they can react to and drive the editor. */
interface EditorController {
    val currentPage: Int          // 0-based, observable (Compose state)
    val pageCount: Int
    fun goToPage(i: Int)
    fun saveNow()
    fun doc(): InkDoc
}
