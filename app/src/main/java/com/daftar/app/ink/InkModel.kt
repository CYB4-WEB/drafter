package com.daftar.app.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.util.Base64
import com.daftar.app.data.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File

object Tool {
    const val PEN = 0
    const val HIGHLIGHTER = 1
    const val ERASER = 2
    const val LASSO = 3
    const val TEXT = 4
    const val SHAPE = 5
    const val HAND = 6
    const val TAPE = 7      // opaque strip that hides content until tapped (self-quiz)
    const val LASER = 8     // fading pointer trail, never saved
}

/** Pen tip styles (GoodNotes / Notability style). */
object PenStyle {
    const val BALL = 0
    const val FOUNTAIN = 1
    const val PENCIL = 2
    const val BRUSH = 3
    const val MARKER = 4
    val all = listOf(BALL, FOUNTAIN, PENCIL, BRUSH, MARKER)
}

/** One ink stroke. Points are (x, y, pressure) triples in page points. Identity-compared on purpose. */
@Serializable
class Stroke(
    val tool: Int,
    val color: Int,
    val width: Float,
    val pts: FloatArray,
    val rec: Int = 0,      // recording id this stroke was drawn during (0 = none)
    val t: Long = -1,      // ms offset into that recording
    val style: Int = PenStyle.BALL,
) {
    @Transient var paths: List<Pair<Path, Float>>? = null
    @Transient var bbox: RectF? = null

    fun bounds(): RectF = bbox ?: RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { r ->
        var i = 0
        while (i < pts.size) { r.union(pts[i], pts[i + 1]); i += 3 }
        r.inset(-width, -width)
        bbox = r
    }

    fun mapped(f: (Float, Float) -> Pair<Float, Float>, widthScale: Float = 1f): Stroke {
        val n = pts.copyOf()
        var i = 0
        while (i < n.size) { val (x, y) = f(n[i], n[i + 1]); n[i] = x; n[i + 1] = y; i += 3 }
        return Stroke(tool, color, width * widthScale, n, rec, t, style)
    }

    fun withColor(c: Int) = Stroke(tool, c, width, pts, rec, t, style)
}

@Serializable
data class TextItem(
    val id: Long,
    val x: Float,
    val y: Float,
    val w: Float,
    val text: String,
    val size: Float = 16f,
    val color: Int = 0xFF1C1B19.toInt(),
    val font: String = "sans",
    val bold: Boolean = false,
) {
    @Transient var layout: StaticLayout? = null
    fun bounds() = RectF(x, y, x + w, y + (layout?.height?.toFloat() ?: (size * 1.4f)))
}

@Serializable
data class ImageItem(val id: Long, val x: Float, val y: Float, val w: Float, val h: Float, val data: String) {
    @Transient var bmp: Bitmap? = null
    fun bitmap(): Bitmap? = bmp ?: runCatching {
        val b = Base64.decode(data, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    }.getOrNull().also { bmp = it }
    fun bounds() = RectF(x, y, x + w, y + h)

    companion object {
        fun encode(b: Bitmap): String {
            val out = ByteArrayOutputStream()
            if (b.hasAlpha()) b.compress(Bitmap.CompressFormat.PNG, 100, out) else b.compress(Bitmap.CompressFormat.JPEG, 88, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}

/** A tappable link on the page: a web/video URL or an absolute path to a library file. */
@Serializable
data class LinkItem(val id: Long, val x: Float, val y: Float, val label: String, val target: String, val w: Float = 160f) {
    val isFile get() = target.startsWith("/")
    fun bounds() = RectF(x, y, x + w, y + LINK_H)
    companion object { const val LINK_H = 30f }
}

@Serializable
data class InkPage(
    val w: Float = 595f,
    val h: Float = 842f,
    val paper: String = "lined",
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val images: List<ImageItem> = emptyList(),
    val links: List<LinkItem> = emptyList(),
) {
    fun isEmpty() = strokes.isEmpty() && texts.isEmpty() && images.isEmpty() && links.isEmpty()
}

@Serializable
data class Recording(val id: Int, val file: String, val duration: Long, val created: Long)

@Serializable
class InkDoc(
    var pages: List<InkPage> = emptyList(),
    var paperColor: Int = 0xFFFFFFFF.toInt(),
    var recordings: List<Recording> = emptyList(),
    /** Whiteboard mode: one page that grows in every direction (OneNote style). */
    var infinite: Boolean = false,
) {
    fun save(f: File) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(this))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        fun load(f: File): InkDoc? = if (!f.exists()) null else runCatching { json.decodeFromString<InkDoc>(f.readText()) }.getOrNull()

        fun newNote(paper: String, pages: Int = 1) = InkDoc(pages = List(pages) { InkPage(paper = paper) })

        fun newWhiteboard(paper: String = "dots") = InkDoc(pages = listOf(InkPage(w = 2400f, h = 1800f, paper = paper)), infinite = true)

        /** Ink layer for a document with fixed pages (PDF/slides): keep strokes, adopt the source sizes. */
        fun forSource(existing: InkDoc?, sizes: List<Pair<Float, Float>>): InkDoc {
            val old = existing?.pages ?: emptyList()
            return InkDoc(
                pages = sizes.mapIndexed { i, (w, h) ->
                    (old.getOrNull(i) ?: InkPage()).copy(w = w, h = h, paper = "none")
                },
                recordings = existing?.recordings ?: emptyList(),
            )
        }
    }
}
