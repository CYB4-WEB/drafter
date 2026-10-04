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
    /** Zoom relative to "fit width", in percent (100 = fit). Observable. */
    val zoomPercent: Int
    fun zoomIn()
    fun zoomOut()
    /** Back to fit-width. */
    fun zoomFit()
    /** Insert a picture on the current page; it floats selected so the user can move/resize it (e.g. a signature). */
    fun addImage(b: Bitmap)
    /** Like [addImage] with a starting width in page points (e.g. a signature ≈ 160pt). */
    fun addImage(b: Bitmap, widthPt: Float)
    /** Go to page [i] and scroll point [yPt] (page points) into view (search matches when zoomed in). */
    fun goToPage(i: Int, yPt: Float)
    /** Re-render the fixed page backgrounds (the source changed what it draws, e.g. search highlights). */
    fun refreshPages()
}
