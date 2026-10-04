package com.daftar.app.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.text.StaticLayout
import android.util.Base64
import com.daftar.app.data.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
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

/**
 * One ink stroke. Points are (x, y, pressure) triples in page points. Identity-compared on purpose.
 * A [Tool.TAPE] stroke has exactly two points (start, end) and [width] = tape thickness.
 */
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
    /** Render geometry for this stroke's pen style (built once by [InkRender], then reused every frame). */
    @Transient var geom: StrokeGeom? = null
    @Transient var bbox: RectF? = null
    /** Tape only: shown see-through in the editor (self-quiz). Never saved, never exported. */
    @Transient var revealed: Boolean = false
    /**
     * Offset of [geom] relative to [pts]: a whiteboard growth shift shares the already built geometry instead of copying
     * every Path; [InkRender] draws the geometry translated by (gdx, gdy).
     */
    @Transient var gdx: Float = 0f
    @Transient var gdy: Float = 0f
    /** Stable identity shared by shifted copies of this stroke (render caches survive whiteboard growth). */
    @Transient private var ident: Any? = null

    /** Identity token: the same for this stroke and every [shifted] copy of it. Main thread only. */
    fun identity(): Any = ident ?: Any().also { ident = it }

    val isTape get() = tool == Tool.TAPE

    fun bounds(): RectF = bbox ?: RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { r ->
        var i = 0
        while (i < pts.size) { r.union(pts[i], pts[i + 1]); i += 3 }
        // the widest pen styles (brush, fountain) reach ~1.7 × width; tapes reach width / 2
        val pad = if (isTape) width * 0.6f else width
        r.inset(-pad, -pad)
        bbox = r
    }

    fun mapped(f: (Float, Float) -> Pair<Float, Float>, widthScale: Float = 1f): Stroke {
        val n = pts.copyOf()
        var i = 0
        while (i < n.size) { val (x, y) = f(n[i], n[i + 1]); n[i] = x; n[i + 1] = y; i += 3 }
        return Stroke(tool, color, width * widthScale, n, rec, t, style)
    }

    /** Same stroke moved by (dx, dy). Keeps the cached geometry (offset copy), so big whiteboards shift cheaply. */
    fun shifted(dx: Float, dy: Float): Stroke {
        val n = pts.copyOf()
        var i = 0
        while (i < n.size) { n[i] += dx; n[i + 1] += dy; i += 3 }
        val id = identity()
        return Stroke(tool, color, width, n, rec, t, style).also { s ->
            val g = geom
            if (g != null && g.finished) { s.geom = g; s.gdx = gdx + dx; s.gdy = gdy + dy }
            bbox?.let { b -> s.bbox = RectF(b).apply { offset(dx, dy) } }
            s.revealed = revealed
            s.ident = id
        }
    }

    /** Same points in another colour (geometry does not depend on colour, so it is shared). */
    fun withColor(c: Int) = Stroke(tool, c, width, pts, rec, t, style).also { it.geom = geom; it.gdx = gdx; it.gdy = gdy; it.bbox = bbox }

    fun withTime(recId: Int, time: Long) = Stroke(tool, color, width, pts, recId, time, style).also { it.geom = geom; it.gdx = gdx; it.gdy = gdy; it.bbox = bbox }
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
    /** Paragraph alignment: [ALIGN_START] (follows the text direction, Arabic → right), [ALIGN_CENTER], [ALIGN_END]. */
    val align: Int = ALIGN_START,
) {
    @Transient var layout: StaticLayout? = null
    companion object {
        const val ALIGN_START = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_END = 2
    }
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

/**
 * A tappable link on the page: a web/video URL or an absolute path to a library file.
 * Drawn as a chip [w] × [h] page points (resizable with the lasso; [h] defaults to [LINK_H]).
 */
@Serializable
data class LinkItem(
    val id: Long,
    val x: Float,
    val y: Float,
    val label: String,
    val target: String,
    val w: Float = 160f,
    val h: Float = LINK_H,
) {
    /** Ellipsized label cache for the chip (render-only). */
    @Transient var shown: CharSequence? = null
    @Transient var shownFor: Float = -1f
    /** Render cache: 0 file badge, 1 video, 2 web (-1 = not computed); badge colour + tag for files. */
    @Transient var iconKind: Int = -1
    @Transient var badgeColor: Int = 0
    @Transient var badgeTag: String = ""

    val isFile get() = target.startsWith("/")
    val isVideo get() = !isFile && isVideoUrl(target)
    fun bounds() = RectF(x, y, x + w, y + h)
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h

    companion object {
        const val LINK_H = 30f

        /** Label shown when the user gives none: file name, or the URL without scheme / "www." / trailing slash. */
        fun defaultLabel(target: String): String {
            if (target.startsWith("/")) return File(target).nameWithoutExtension
            val s = target.substringAfter("://").removePrefix("www.").trimEnd('/')
            return if (s.length > 60) s.take(57) + "…" else s
        }

        /** "youtube.com/x" → "https://youtube.com/x"; returns null when [raw] is not a usable web address. */
        fun normalizeUrl(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty() || t.contains(' ')) return null
            val u = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
            val host = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            return if (host.contains('.') && !host.startsWith('.') && !host.endsWith('.')) u else null
        }

        fun isVideoUrl(u: String): Boolean {
            val s = u.lowercase()
            val host = s.substringAfter("://").substringBefore('/').removePrefix("www.").removePrefix("m.")
            val path = s.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
            return host == "youtube.com" || host == "youtu.be" || host == "music.youtube.com" || host.endsWith(".youtube.com") ||
                host == "vimeo.com" || host == "player.vimeo.com" ||
                path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mov")
        }
    }
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
    /** Whiteboards only: total amount the content was shifted right/down while the board grew left/top. */
    val shiftX: Float = 0f,
    val shiftY: Float = 0f,
) {
    fun isEmpty() = strokes.isEmpty() && texts.isEmpty() && images.isEmpty() && links.isEmpty()

    /** All content moved by (dx, dy) (whiteboard growth). Render caches are carried over. */
    fun shifted(dx: Float, dy: Float): InkPage = copy(
        strokes = strokes.map { it.shifted(dx, dy) },
        texts = texts.map { t -> t.copy(x = t.x + dx, y = t.y + dy).also { it.layout = t.layout } },
        images = images.map { i -> i.copy(x = i.x + dx, y = i.y + dy).also { it.bmp = i.bmp } },
        links = links.map { l -> l.copy(x = l.x + dx, y = l.y + dy).also { it.shown = l.shown; it.shownFor = l.shownFor } },
        shiftX = shiftX + dx, shiftY = shiftY + dy,
    )

    /** Union of everything on the page (text boxes measured), or null when the page is empty. */
    fun contentBounds(): RectF? {
        if (isEmpty()) return null
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in strokes) r.union(s.bounds())
        for (t in texts) { InkRender.layout(t); r.union(t.bounds()) }
        for (i in images) r.union(i.bounds())
        for (l in links) r.union(l.bounds())
        return r
    }
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
    /** Writes atomically (temp file + rename), streaming the JSON so a big document never exists twice as a String. */
    @OptIn(ExperimentalSerializationApi::class)
    fun save(f: File) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().buffered(64 * 1024).use { json.encodeToStream(this, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /**
     * The part of page [i] worth exporting (page points): the whole page for paged notes; for a whiteboard the content
     * bounds plus a margin (a blank board gives a landscape A4-sized area). Use it to crop note → PDF / images.
     */
    fun exportRect(i: Int): RectF {
        val p = pages[i]
        if (!infinite) return RectF(0f, 0f, p.w, p.h)
        val b = p.contentBounds() ?: return RectF(0f, 0f, minOf(p.w, 842f), minOf(p.h, 595f))
        b.inset(-EXPORT_MARGIN, -EXPORT_MARGIN)
        return b
    }

    companion object {
        /** Whiteboard export margin around the content, in points. */
        const val EXPORT_MARGIN = 36f

        fun load(f: File): InkDoc? = if (!f.exists()) null else runCatching { json.decodeFromString<InkDoc>(f.readText()) }.getOrNull()

        fun newNote(paper: String, pages: Int = 1) = InkDoc(pages = List(pages) { InkPage(paper = paper) })

        fun newWhiteboard(paper: String = "dots") = InkDoc(pages = listOf(InkPage(w = 2340f, h = 1638f, paper = paper)), infinite = true)

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
