// JVM test driver for the pure OneNote parser core (not part of the app build).
// Usage: see run_parser.sh. Prints sections, pages, outlines, runs, tables, images, ink, attachments.
import com.daftar.app.onenote.*
import java.io.File

fun dumpBlocks(blocks: List<OneBlock>, pad: String, sb: StringBuilder) {
    for (b in blocks) when (b) {
        is OnePara -> {
            sb.append(pad).append("  ".repeat(b.indent)).append("P")
            if (b.list != null) sb.append(" [").append(b.list).append("]")
            if (b.style != null) sb.append(" {").append(b.style).append("}")
            if (b.align != 0) sb.append(" align=").append(b.align)
            sb.append(": ")
            for (r in b.runs) {
                val f = buildString {
                    if (r.bold) append("b"); if (r.italic) append("i"); if (r.underline) append("u"); if (r.strike) append("s")
                    if (r.size > 0) append(r.size.toInt()); if (r.color != null) append("#%06X".format(r.color!! and 0xFFFFFF))
                    if (r.highlight != null) append("^%06X".format(r.highlight!! and 0xFFFFFF))
                    if (r.link != null) append(" ->").append(r.link)
                }
                sb.append("«").append(r.text.replace("\n", "⏎")).append("»")
                if (f.isNotEmpty()) sb.append("(").append(f).append(")")
            }
            sb.append('\n')
        }
        is OneTable -> {
            sb.append(pad).append("TABLE ${b.rows.size}x${b.rows.maxOfOrNull { it.size }} widths=${b.columnWidths.map { it.toInt() }} borders=${b.borders}\n")
            for ((ri, r) in b.rows.withIndex()) for ((ci, c) in r.withIndex()) {
                sb.append(pad).append("  cell[$ri,$ci]\n"); dumpBlocks(c, "$pad    ", sb)
            }
        }
        is OneImageBlock -> sb.append(pad).append("IMG ${img(b.image)}\n")
        is OneInkBlock -> sb.append(pad).append("INK ${ink(b.ink)}\n")
        is OneFileBlock -> sb.append(pad).append("FILE ${b.file.name} ${b.file.data?.length}\n")
    }
}
fun img(i: OneImage) = "${i.widthPt.toInt()}x${i.heightPt.toInt()}pt type=${i.data?.imageType()} bytes=${i.data?.length} alt=${i.alt} name=${i.name}"
fun ink(k: OneInk) = "strokes=${k.strokes.size} bounds=${k.bounds.map { it.toInt() }} first=${k.strokes.first().let { s -> "w=%.2f c=%08X n=%d p0=(%.1f,%.1f)".format(s.width, s.color, s.pts.size / 2, s.pts[0], s.pts[1]) }}"

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--jcids") {
        for (a in args.drop(1)) {
            val st = OneStore.open(File(a))
            val hist = java.util.TreeMap<String, Int>()
            for (sp in st.spaces.values) {
                val f = sp.javaClass.getDeclaredField("revisions"); f.isAccessible = true
                @Suppress("UNCHECKED_CAST") val revs = f.get(sp) as Map<Any, Revision>
                for (r in revs.values) for (d in r.objects.values) { val k = "%08X".format(d.jcid); hist[k] = (hist[k] ?: 0) + 1 }
            }
            println("$a: spaces=${st.spaces.size} root=${st.rootGosid} $hist")
        }
        return
    }
    for (a in args) {
        val f = File(a)
        println("==== ${f.name}")
        try {
            val book = OneLoader.load(f, File(System.getProperty("java.io.tmpdir"), "onedump"))
            for (s in book.sections) {
                println("SECTION '${s.name}' pages=${s.pages.size} error=${s.error}")
                for (p in s.pages) {
                    println(" PAGE '${p.title}' level=${p.level} created=${java.util.Date(p.created)} size=${p.widthPt.toInt()}x${p.heightPt.toInt()} title@${p.titleX.toInt()},${p.titleY.toInt()} items=${p.items.size}")
                    val sb = StringBuilder()
                    for (it in p.items) when (it) {
                        is OneOutline -> { sb.append("  OUTLINE @${it.x.toInt()},${it.y.toInt()} w=${it.width.toInt()}\n"); dumpBlocks(it.blocks, "   ", sb) }
                        is OneImageItem -> sb.append("  IMAGE @${it.x.toInt()},${it.y.toInt()} ${img(it.image)}\n")
                        is OneInkItem -> sb.append("  INK @${it.x.toInt()},${it.y.toInt()} ${ink(it.ink)}\n")
                        is OneFileItem -> sb.append("  FILE @${it.x.toInt()},${it.y.toInt()} ${it.file.name} ${it.file.data?.length}\n")
                    }
                    print(sb)
                }
                if (s.source != null && System.getenv("WARN") != null) OneReader.warnings(s.source!!).take(20).forEach { println("  warn: $it") }
            }
        } catch (e: OneException) {
            println("ERROR ${e.kind}: ${e.message}")
        }
    }
}
