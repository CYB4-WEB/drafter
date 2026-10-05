package com.daftar.app.convert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import android.view.View
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import com.daftar.app.ink.InkRender
import com.daftar.app.ink.LinkItem
import com.daftar.app.ink.PenStyle
import com.daftar.app.ink.Stroke
import com.daftar.app.ink.StrokeGeom
import com.daftar.app.ink.TextItem
import com.daftar.app.ink.Tool
import com.daftar.app.ui.shareFiles
import com.daftar.app.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.text.DateFormat
import java.util.Date

/**
 * Note → one self-contained web page (tags-agent). Every page becomes an inline SVG: ink strokes as exact vectors (the
 * editor's own geometry from [InkRender.geom], flattened with [Path.approximate]), the paper pattern recorded through
 * [SvgCanvas] from [InkRender.drawPaper], pictures as base64, typed text as HTML (`dir="auto"` per paragraph, so Arabic
 * paragraphs run right-to-left), links as real `<a>` chips, tapes hidden until tapped (self-quiz). Only the fonts the note
 * uses are embedded. A sticky bar gives previous / next and "Page x of n" (keys ← →, PgUp/PgDn too). No server, no
 * scripts from the network — the file opens in any browser and can be sent through the share sheet.
 */
object NoteHtml {
    /** Writes [note] as HTML to [out]. [isActive] is polled between pages; [progress] gets (done, total). Blocking. */
    fun write(ctx: Context, note: File, out: File, isActive: () -> Boolean = { true }, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val doc = InkDoc.load(note)?.takeIf { it.pages.isNotEmpty() } ?: throw ConvertException(R.string.convert_err_failed)
        val title = note.nameWithoutExtension
        val rtl = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val lang = ctx.resources.configuration.locales[0]?.language ?: "en"
        val dark = (Color.red(doc.paperColor) * 299 + Color.green(doc.paperColor) * 587 + Color.blue(doc.paperColor) * 114) / 1000 < 110
        val n = doc.pages.size
        out.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { w ->
            w.write("<!DOCTYPE html>\n<html lang=\"$lang\" dir=\"${if (rtl) "rtl" else "ltr"}\"><head><meta charset=\"utf-8\">")
            w.write("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            w.write("<meta name=\"generator\" content=\"Daftar\"><title>"); w.write(esc(title)); w.write("</title><style>")
            w.write(CSS)
            fonts(ctx, doc, w)
            w.write("</style></head><body>")
            // toolbar
            w.write("<header class=\"bar\"><div class=\"title\" title=\"${esc(title)}\">${esc(title)}</div>")
            if (n > 1) {
                w.write("<nav><button id=\"prev\" aria-label=\"${esc(ctx.getString(R.string.tags_html_prev))}\">$CHEV</button>")
                w.write("<span id=\"count\"></span>")
                w.write("<button id=\"next\" class=\"fwd\" aria-label=\"${esc(ctx.getString(R.string.tags_html_next))}\">$CHEV</button></nav>")
            }
            w.write("</header><main>")
            // shared paper patterns (one per distinct page look), referenced with <use>
            val papers = LinkedHashMap<String, String>()
            val paperDefs = ArrayList<String>()
            progress(0, n)
            for ((i, p) in doc.pages.withIndex()) {
                if (!isActive()) throw CancellationException()
                val r = doc.exportRect(i)
                val paperKey = "${p.paper}|${p.w}|${p.h}|$dark|${if (doc.infinite) "${r.left},${r.top},${r.right},${r.bottom}" else ""}"
                val paperId = papers[paperKey] ?: run {
                    val sb = StringBuilder()
                    val sc = SvgCanvas(sb)
                    runCatching { InkRender.drawPaper(sc, p, dark, clip = r, bounded = !doc.infinite) }
                    sc.flush()
                    val id = "paper${papers.size}"
                    papers[paperKey] = id
                    paperDefs.add("<g id=\"$id\">$sb</g>")  // written into <defs> below
                    id
                }
                val bg = hex(doc.paperColor)
                w.write("<section class=\"page\" id=\"p${i + 1}\" aria-label=\"${esc(ctx.getString(R.string.tags_html_page_of, i + 1, n))}\">")
                w.write("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"${f(r.left)} ${f(r.top)} ${f(r.width())} ${f(r.height())}\" ")
                w.write("width=\"${f(r.width())}\" height=\"${f(r.height())}\" style=\"max-width:${f(r.width() * 1.6f)}px\">")
                w.write("<rect x=\"${f(r.left)}\" y=\"${f(r.top)}\" width=\"${f(r.width())}\" height=\"${f(r.height())}\" fill=\"$bg\"/>")
                w.write("<use href=\"#$paperId\"/>")
                page(w, p)
                w.write("</svg></section>")
                progress(i + 1, n)
            }
            w.write("</main><svg width=\"0\" height=\"0\" style=\"position:absolute\" aria-hidden=\"true\"><defs>")
            for (g in paperDefs) w.write(g)
            w.write("</defs></svg>")
            w.write("<footer>${esc(ctx.getString(R.string.tags_html_footer, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())))}</footer>")
            w.write("<script>var PAGE_OF=\"${js(ctx.getString(R.string.tags_html_page_of, 111111, 222222))}\";")
            w.write(SCRIPT)
            w.write("</script></body></html>\n")
        }
    }

    // ================================================================================ page content

    private fun page(w: Writer, p: InkPage) {
        for (im in p.images) {
            val data = im.data.filter { it != '\n' && it != '\r' }
            val mime = when {
                data.startsWith("/9j/") -> "image/jpeg"
                data.startsWith("R0lG") -> "image/gif"
                data.startsWith("UklG") -> "image/webp"
                else -> "image/png"
            }
            w.write("<image x=\"${f(im.x)}\" y=\"${f(im.y)}\" width=\"${f(im.w)}\" height=\"${f(im.h)}\" preserveAspectRatio=\"none\" href=\"data:$mime;base64,")
            w.write(data); w.write("\"/>")
        }
        for (s in p.strokes) if (!s.isTape) stroke(w, s)
        for (t in p.texts) text(w, t)
        for (l in p.links) link(w, l)
        for (s in p.strokes) if (s.isTape) tape(w, s)
    }

    private fun alphaOf(s: Stroke): Float {
        val style = if (s.tool == Tool.PEN) s.style else -1
        val base = if (style == PenStyle.MARKER) 255 else Color.alpha(s.color)
        return (if (style == PenStyle.PENCIL) base * 0.85f else base.toFloat()) / 255f
    }

    private fun stroke(w: Writer, s: Stroke) {
        val g = runCatching { InkRender.geom(s) }.getOrNull() ?: return
        val col = hex(s.color)
        val op = alphaOf(s)
        val tr = if (s.gdx != 0f || s.gdy != 0f) " transform=\"translate(${f(s.gdx)} ${f(s.gdy)})\"" else ""
        val opa = if (op < 0.999f) " opacity=\"${f(op)}\"" else ""
        if (g.dotR > 0f) {
            if (s.tool == Tool.HIGHLIGHTER) w.write("<rect x=\"${f(g.dotX - g.dotR)}\" y=\"${f(g.dotY - g.dotR)}\" width=\"${f(g.dotR * 2)}\" height=\"${f(g.dotR * 2)}\" fill=\"$col\"$opa$tr/>")
            else w.write("<circle cx=\"${f(g.dotX)}\" cy=\"${f(g.dotY)}\" r=\"${f(g.dotR)}\" fill=\"$col\"$opa$tr/>")
            return
        }
        val fill = g.fill
        if (fill != null) {
            val d = pathData(fill)
            if (d.isNotEmpty()) w.write("<path d=\"$d\" fill=\"$col\" fill-rule=\"nonzero\"$opa$tr/>")
            return
        }
        val cap = when {
            s.tool == Tool.HIGHLIGHTER -> "square"
            s.tool == Tool.PEN && s.style == PenStyle.MARKER -> "butt"
            else -> "round"
        }
        w.write("<g fill=\"none\" stroke=\"$col\" stroke-linecap=\"$cap\" stroke-linejoin=\"round\"$opa$tr>")
        for (i in 0 until g.nChunks) {
            val path = g.chunks[i] ?: continue
            val d = pathData(path)
            if (d.isNotEmpty()) w.write("<path d=\"$d\" stroke-width=\"${f(g.chunkW[i])}\"/>")
        }
        w.write("</g>")
    }

    private fun tape(w: Writer, s: Stroke) {
        val g = runCatching { InkRender.geom(s) }.getOrNull() ?: return
        val path = g.fill ?: return
        val d = pathData(path)
        if (d.isEmpty()) return
        val tr = if (s.gdx != 0f || s.gdy != 0f) " transform=\"translate(${f(s.gdx)} ${f(s.gdy)})\"" else ""
        val border = Color.rgb((Color.red(s.color) * 0.78f).toInt(), (Color.green(s.color) * 0.78f).toInt(), (Color.blue(s.color) * 0.78f).toInt())
        w.write("<g class=\"tape\"$tr><path class=\"tf\" d=\"$d\" fill=\"${hex(s.color)}\"/>")
        w.write("<path d=\"$d\" fill=\"none\" stroke=\"${hex(border)}\" stroke-opacity=\".55\" stroke-width=\".7\"/></g>")
    }

    private fun fontCss(key: String) = when (key) {
        "serif" -> "Georgia,'Times New Roman',serif"
        "mono" -> "ui-monospace,Menlo,Consolas,monospace"
        "cairo" -> "'Cairo',system-ui,sans-serif"
        "amiri" -> "'Amiri',serif"
        "tehreer" -> "'Tehreer',serif"
        "hand" -> "'Caveat',cursive"
        else -> "system-ui,-apple-system,'Segoe UI',Roboto,'Noto Sans','Noto Sans Arabic',Tahoma,sans-serif"
    }

    private fun text(w: Writer, t: TextItem) {
        if (t.text.isEmpty()) return
        val h = runCatching { InkRender.layout(t).height.toFloat() }.getOrDefault(t.size * 1.4f * (t.text.count { it == '\n' } + 1))
        val align = when (t.align) { TextItem.ALIGN_CENTER -> "center"; TextItem.ALIGN_END -> "end"; else -> "start" }
        w.write("<foreignObject x=\"${f(t.x)}\" y=\"${f(t.y)}\" width=\"${f(t.w.coerceAtLeast(20f))}\" height=\"${f(h * 1.25f + 4f)}\">")
        w.write("<div xmlns=\"http://www.w3.org/1999/xhtml\" class=\"t\" style=\"font-size:${f(t.size)}px;color:${hex(t.color)};")
        w.write("font-family:${fontCss(t.font)};text-align:$align;${if (t.bold) "font-weight:700;" else ""}")
        if (Color.alpha(t.color) < 255) w.write("opacity:${f(Color.alpha(t.color) / 255f)};")
        w.write("\">")
        for (para in t.text.split('\n')) {
            w.write("<p dir=\"auto\">"); w.write(if (para.isEmpty()) "<br/>" else esc(para)); w.write("</p>")
        }
        w.write("</div></foreignObject>")
    }

    private fun link(w: Writer, l: LinkItem) {
        val k = l.h / LinkItem.LINK_H
        val href = if (!l.isFile && (l.target.startsWith("http://", true) || l.target.startsWith("https://", true))) l.target else null
        if (href != null) w.write("<a href=\"${esc(href)}\" target=\"_blank\" rel=\"noopener\">")
        w.write("<g class=\"link\"><title>${esc(if (l.isFile) File(l.target).name else l.target)}</title>")
        w.write("<rect x=\"${f(l.x)}\" y=\"${f(l.y)}\" width=\"${f(l.w)}\" height=\"${f(l.h)}\" rx=\"${f(9 * k)}\" fill=\"#fff\" stroke=\"#D5D1C8\" stroke-width=\"${f(k)}\"/>")
        val s = l.h - 8f * k
        val ix = l.x + 4f * k; val iy = l.y + 4f * k
        when {
            l.isFile -> {
                val (col, tag) = InkRender.fileBadge(l.target)
                w.write("<rect x=\"${f(ix)}\" y=\"${f(iy)}\" width=\"${f(s)}\" height=\"${f(s)}\" rx=\"${f(5 * k)}\" fill=\"${hex(col)}\"/>")
                w.write("<text x=\"${f(ix + s / 2)}\" y=\"${f(iy + s / 2)}\" font-size=\"${f((if (tag.length >= 4) 6.2f else 7.6f) * k)}\" font-weight=\"700\" fill=\"#fff\" text-anchor=\"middle\" dominant-baseline=\"central\" font-family=\"sans-serif\">${esc(tag)}</text>")
            }
            l.isVideo -> {
                w.write("<rect x=\"${f(ix)}\" y=\"${f(iy)}\" width=\"${f(s)}\" height=\"${f(s)}\" rx=\"${f(6 * k)}\" fill=\"#E5484D\"/>")
                val cx = ix + s / 2 + k; val cy = iy + s / 2; val h = s * 0.22f
                w.write("<path d=\"M${f(cx - h * 0.85f)} ${f(cy - h)}L${f(cx + h * 1.05f)} ${f(cy)}L${f(cx - h * 0.85f)} ${f(cy + h)}Z\" fill=\"#fff\"/>")
            }
            else -> {
                val cx = ix + s / 2; val cy = iy + s / 2; val rad = s * 0.4f
                w.write("<g fill=\"none\" stroke=\"#3B82F6\" stroke-width=\"${f(1.5f * k)}\"><circle cx=\"${f(cx)}\" cy=\"${f(cy)}\" r=\"${f(rad)}\"/>")
                w.write("<ellipse cx=\"${f(cx)}\" cy=\"${f(cy)}\" rx=\"${f(rad * 0.45f)}\" ry=\"${f(rad)}\"/>")
                w.write("<path d=\"M${f(cx - rad)} ${f(cy)}H${f(cx + rad)}\"/></g>")
            }
        }
        val tx = ix + s + 7f * k
        val avail = l.x + l.w - 8f * k - tx
        if (avail > 4f) {
            w.write("<foreignObject x=\"${f(tx)}\" y=\"${f(l.y)}\" width=\"${f(avail)}\" height=\"${f(l.h)}\">")
            w.write("<div xmlns=\"http://www.w3.org/1999/xhtml\" class=\"ll\" dir=\"auto\" style=\"font-size:${f(12 * k)}px;line-height:${f(l.h)}px\">")
            w.write(esc(l.label)); w.write("</div></foreignObject>")
        }
        w.write("</g>")
        if (href != null) w.write("</a>")
    }

    // ================================================================================ fonts

    private fun fonts(ctx: Context, doc: InkDoc, w: Writer) {
        val used = doc.pages.flatMap { p -> p.texts.map { it.font } }.toSet()
        val files = listOf(
            Triple("cairo", "Cairo", R.font.cairo), Triple("amiri", "Amiri", R.font.amiri),
            Triple("tehreer", "Tehreer", R.font.tehreer), Triple("hand", "Caveat", R.font.caveat),
        )
        for ((key, family, res) in files) {
            if (key !in used) continue
            val bytes = runCatching { ctx.resources.openRawResource(res).use { it.readBytes() } }.getOrNull() ?: continue
            w.write("@font-face{font-family:'$family';src:url(data:font/ttf;base64,")
            w.write(Base64.encodeToString(bytes, Base64.NO_WRAP))
            w.write(") format('truetype');font-display:swap}")
        }
    }

    // ================================================================================ helpers

    /** SVG path data of an Android path (flattened to ≤ 0.2 pt error; a moveTo is a repeated fraction). */
    internal fun pathData(path: Path, m: Matrix? = null): String {
        val a = runCatching { path.approximate(0.2f) }.getOrNull() ?: return ""
        if (a.size < 3) return ""
        val sb = StringBuilder(a.size * 4)
        val pt = FloatArray(2)
        var prev = Float.NaN
        var i = 0
        while (i + 2 < a.size) {
            pt[0] = a[i + 1]; pt[1] = a[i + 2]
            m?.mapPoints(pt)
            sb.append(if (i == 0 || a[i] == prev) 'M' else 'L').append(f(pt[0])).append(' ').append(f(pt[1]))
            prev = a[i]
            i += 3
        }
        return sb.toString()
    }

    /** Compact number: at most two decimals, no trailing zeros, locale independent. */
    internal fun f(v: Float): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        val r = Math.round(v * 100.0)
        val neg = r < 0
        val a = kotlin.math.abs(r)
        val ip = a / 100; val fp = (a % 100).toInt()
        val sb = StringBuilder()
        if (neg && (ip != 0L || fp != 0)) sb.append('-')
        sb.append(ip)
        if (fp != 0) { sb.append('.'); if (fp < 10) sb.append('0'); sb.append(if (fp % 10 == 0) fp / 10 else fp) }
        return sb.toString()
    }

    internal fun hex(c: Int): String {
        val v = c and 0xFFFFFF
        val h = "0123456789ABCDEF"
        val sb = StringBuilder("#")
        for (s in 20 downTo 0 step 4) sb.append(h[(v shr s) and 0xF])
        return sb.toString()
    }

    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) when (ch) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;"); '\'' -> sb.append("&#39;")
            else -> if (ch < ' ' && ch != '\t') sb.append(' ') else sb.append(ch)
        }
        return sb.toString()
    }

    private fun js(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c")

    // ================================================================================ share sheet

    /**
     * Builds `<note>.html` in the cache (previous shared pages are removed) and opens the share sheet — WhatsApp, e-mail,
     * Drive… The recipient opens it in any browser. Shows a toast on failure. Suspends while the page is written.
     */
    suspend fun share(ctx: Context, note: File) {
        val out = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(Storage.cacheDir(), "webpage").apply { deleteRecursively(); mkdirs() }
                val f = File(dir, Storage.sanitize(note.nameWithoutExtension).ifBlank { "note" } + ".html")
                write(ctx, note, f)
                f
            }.getOrNull()
        }
        if (out == null) toast(ctx, ctx.getString(R.string.tags_html_failed)) else shareFiles(ctx, listOf(out))
    }

    private const val CHEV = "<svg class=\"chev\" viewBox=\"0 0 24 24\" width=\"22\" height=\"22\" aria-hidden=\"true\"><path d=\"M15 5l-7 7 7 7\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>"

    private val CSS = """
        :root{color-scheme:light dark;--bg:#F7F6F2;--surface:#fff;--line:#E4E2DC;--ink:#1F2937;--muted:#6B7280;--accent:#3B82F6}
        @media (prefers-color-scheme:dark){:root{--bg:#14161A;--surface:#1C1F24;--line:#2E333B;--ink:#E8EAED;--muted:#9CA3AF;--accent:#60A5FA}}
        *{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--ink);font-family:system-ui,-apple-system,'Segoe UI',Roboto,'Noto Sans Arabic',Tahoma,sans-serif}
        .bar{position:sticky;top:0;z-index:5;display:flex;align-items:center;gap:12px;padding:10px 16px;background:var(--surface);border-bottom:1px solid var(--line)}
        .title{flex:1;min-width:0;font-weight:600;font-size:17px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
        nav{display:flex;align-items:center;gap:4px}nav button{width:44px;height:44px;border:0;border-radius:12px;background:transparent;color:var(--ink);cursor:pointer;display:grid;place-items:center}
        nav button:hover{background:var(--line)}nav button:disabled{opacity:.35;cursor:default;background:transparent}
        .fwd .chev{transform:scaleX(-1)}[dir=rtl] .chev{transform:scaleX(-1)}[dir=rtl] .fwd .chev{transform:none}
        #count{font-size:14px;color:var(--muted);min-width:7em;text-align:center;font-variant-numeric:tabular-nums}
        main{padding:16px;display:flex;flex-direction:column;align-items:center;gap:16px}
        .page{width:100%;display:flex;justify-content:center;scroll-margin-top:72px}
        .page>svg{display:block;width:100%;height:auto;border:1px solid var(--line);border-radius:4px;background:#fff}
        .t{margin:0;line-height:1.2;white-space:pre-wrap;overflow-wrap:break-word}.t p{margin:0;unicode-bidi:plaintext}
        .ll{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#1F2937;font-family:system-ui,sans-serif}
        .tape{cursor:pointer}.tape.rev .tf{fill-opacity:.16}
        footer{text-align:center;color:var(--muted);font-size:12px;padding:8px 16px 24px}
        @media print{.bar,footer{display:none}main{padding:0;gap:0}.page{break-after:page}.page>svg{border:0;max-width:100%!important}}
    """.trimIndent().replace("\n", "")

    private val SCRIPT = """
        (function(){var pages=[].slice.call(document.querySelectorAll('.page')),cur=0,c=document.getElementById('count'),
        pv=document.getElementById('prev'),nx=document.getElementById('next');
        function show(){if(!c)return;c.textContent=PAGE_OF.replace('111111',cur+1).replace('222222',pages.length);pv.disabled=cur<=0;nx.disabled=cur>=pages.length-1;}
        function go(i){i=Math.max(0,Math.min(pages.length-1,i));pages[i].scrollIntoView({behavior:'smooth',block:'start'});cur=i;show();}
        if(pv){pv.onclick=function(){go(cur-1)};nx.onclick=function(){go(cur+1)};}
        function track(){var best=0,bd=1e9;for(var i=0;i<pages.length;i++){var r=pages[i].getBoundingClientRect(),d=Math.abs(r.top-70);if(r.bottom>80&&d<bd){bd=d;best=i;}}if(best!==cur){cur=best;show();}}
        window.addEventListener('scroll',function(){window.requestAnimationFrame(track)},{passive:true});
        document.addEventListener('keydown',function(e){var rtl=document.dir==='rtl';
        if(e.key==='PageDown'||e.key===(rtl?'ArrowLeft':'ArrowRight')){go(cur+1);e.preventDefault();}
        else if(e.key==='PageUp'||e.key===(rtl?'ArrowRight':'ArrowLeft')){go(cur-1);e.preventDefault();}});
        document.querySelectorAll('.tape').forEach(function(t){t.addEventListener('click',function(){t.classList.toggle('rev')})});
        var h=location.hash.match(/^#p(\d+)$/);if(h)cur=Math.max(0,Math.min(pages.length-1,h[1]-1));show();})();
    """.trimIndent().replace("\n", "")
}

/**
 * A [Canvas] that records simple drawing (lines, points, rects, rounded rects, circles, ovals, paths, text) as SVG
 * elements into [out] — used for the paper patterns, so every template exports as vectors. Uses the canvas matrix
 * (translate / scale); clips are ignored (the page itself bounds the drawing).
 */
internal class SvgCanvas(private val out: StringBuilder) : Canvas() {
    private val m = Matrix()
    private val pt = FloatArray(2)
    private var key: String? = null
    private val d = StringBuilder()
    private val f = NoteHtml::f

    private fun matrix(): Matrix { @Suppress("DEPRECATION") getMatrix(m); return m }
    private fun mx(x: Float, y: Float): FloatArray { pt[0] = x; pt[1] = y; matrix().mapPoints(pt); return pt }
    private fun scale() = matrix().mapRadius(1f)

    private fun paintAttrs(p: Paint, stroke: Boolean): String {
        val col = NoteHtml.hex(p.color)
        val op = p.alpha / 255f
        val o = if (op < 0.999f) (if (stroke) " stroke-opacity=\"${f(op)}\"" else " fill-opacity=\"${f(op)}\"") else ""
        return if (stroke) " fill=\"none\" stroke=\"$col\" stroke-width=\"${f((if (p.strokeWidth <= 0f) 0.5f else p.strokeWidth) * scale())}\"$o"
        else " fill=\"$col\"$o"
    }

    private fun capOf(p: Paint) = when (p.strokeCap) { Paint.Cap.ROUND -> "round"; Paint.Cap.SQUARE -> "square"; else -> "butt" }

    /** Appends to the batched stroke path for this paint (lines and points share one <path> per look). */
    private fun batch(p: Paint, cap: String = capOf(p)): StringBuilder {
        val k = paintAttrs(p, true) + " stroke-linecap=\"$cap\"" + if (p.pathEffect != null) " stroke-dasharray=\"${f(3 * scale())} ${f(3 * scale())}\"" else ""
        if (k != key) { flush(); key = k }
        return d
    }

    fun flush() {
        val k = key ?: return
        if (d.isNotEmpty()) out.append("<path d=\"").append(d).append("\"").append(k).append("/>")
        d.setLength(0); key = null
    }

    private fun element(s: String) { flush(); out.append(s) }

    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        val b = batch(paint)
        val a = mx(startX, startY); b.append('M').append(f(a[0])).append(' ').append(f(a[1]))
        val e = mx(stopX, stopY); b.append('L').append(f(e[0])).append(' ').append(f(e[1]))
    }

    override fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        var i = offset
        while (i + 3 < offset + count) { drawLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], paint); i += 4 }
    }

    override fun drawLines(pts: FloatArray, paint: Paint) = drawLines(pts, 0, pts.size, paint)

    override fun drawPoints(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        val b = batch(paint, if (paint.strokeCap == Paint.Cap.ROUND) "round" else "square")
        var i = offset
        while (i + 1 < offset + count) {
            val a = mx(pts[i], pts[i + 1])
            b.append('M').append(f(a[0])).append(' ').append(f(a[1])).append("h0")
            i += 2
        }
    }

    override fun drawPoints(pts: FloatArray, paint: Paint) = drawPoints(pts, 0, pts.size, paint)

    private fun rectEl(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: Paint) {
        val a = mx(l, t); val x0 = a[0]; val y0 = a[1]
        val e = mx(r, b); val x1 = e[0]; val y1 = e[1]
        val s = scale()
        val stroke = paint.style == Paint.Style.STROKE
        val round = if (rx > 0f || ry > 0f) " rx=\"${f(rx * s)}\" ry=\"${f(ry * s)}\"" else ""
        element("<rect x=\"${f(minOf(x0, x1))}\" y=\"${f(minOf(y0, y1))}\" width=\"${f(kotlin.math.abs(x1 - x0))}\" height=\"${f(kotlin.math.abs(y1 - y0))}\"$round${paintAttrs(paint, stroke)}/>")
        if (paint.style == Paint.Style.FILL_AND_STROKE) element("<rect x=\"${f(minOf(x0, x1))}\" y=\"${f(minOf(y0, y1))}\" width=\"${f(kotlin.math.abs(x1 - x0))}\" height=\"${f(kotlin.math.abs(y1 - y0))}\"$round${paintAttrs(paint, true)}/>")
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) = rectEl(left, top, right, bottom, 0f, 0f, paint)
    override fun drawRect(rect: RectF, paint: Paint) = rectEl(rect.left, rect.top, rect.right, rect.bottom, 0f, 0f, paint)
    override fun drawRect(r: Rect, paint: Paint) = rectEl(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), 0f, 0f, paint)
    override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) = rectEl(left, top, right, bottom, rx, ry, paint)
    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) = rectEl(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint)

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        val a = mx(cx, cy)
        element("<circle cx=\"${f(a[0])}\" cy=\"${f(a[1])}\" r=\"${f(radius * scale())}\"${paintAttrs(paint, paint.style == Paint.Style.STROKE)}/>")
    }

    override fun drawOval(oval: RectF, paint: Paint) {
        val a = mx(oval.centerX(), oval.centerY()); val s = scale()
        element("<ellipse cx=\"${f(a[0])}\" cy=\"${f(a[1])}\" rx=\"${f(oval.width() / 2 * s)}\" ry=\"${f(oval.height() / 2 * s)}\"${paintAttrs(paint, paint.style == Paint.Style.STROKE)}/>")
    }

    override fun drawPath(path: Path, paint: Paint) {
        val dd = NoteHtml.pathData(path, matrix())
        if (dd.isEmpty()) return
        val stroke = paint.style == Paint.Style.STROKE
        element("<path d=\"$dd\"${paintAttrs(paint, stroke)}${if (stroke) " stroke-linecap=\"${capOf(paint)}\" stroke-linejoin=\"round\"" else ""}/>")
    }

    private fun textEl(s: String, x: Float, y: Float, paint: Paint) {
        if (s.isBlank()) return
        val a = mx(x, y)
        val anchor = when (paint.textAlign) { Paint.Align.CENTER -> "middle"; Paint.Align.RIGHT -> "end"; else -> "start" }
        val bold = paint.isFakeBoldText || (paint.typeface?.isBold == true)
        element("<text x=\"${f(a[0])}\" y=\"${f(a[1])}\" font-size=\"${f(paint.textSize * scale())}\" text-anchor=\"$anchor\" direction=\"ltr\"" +
            " font-family=\"system-ui,sans-serif\"${if (bold) " font-weight=\"700\"" else ""}${paintAttrs(paint, false)}>${NoteHtml.esc(s)}</text>")
    }

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) = textEl(text, x, y, paint)
    override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) = textEl(text.substring(start, end), x, y, paint)
    override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) = textEl(text.subSequence(start, end).toString(), x, y, paint)
    override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) = textEl(String(text, index, count), x, y, paint)

    override fun drawColor(color: Int) { /* the page background is drawn by the exporter */ }
}
