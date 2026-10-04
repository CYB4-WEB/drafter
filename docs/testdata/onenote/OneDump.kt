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
    if (args.firstOrNull() == "--cabprefix") {
        // decodes the first N bytes of every folder and prints their MD5 (cross-checks MSZIP vs LZX output)
        val f = File(args[1]); val max = args[2].toLong()
        val arc = Cab.read(f)
        java.io.RandomAccessFile(f, "r").use { r ->
            for ((fi, folder) in arc.folders.withIndex()) {
                val type = folder.type and 0xF
                r.seek(folder.dataStart)
                val blocks = ArrayList<LongArray>()
                for (b in 0 until folder.blocks) {
                    val hdr = ByteArray(8); r.readFully(hdr)
                    val cb = (hdr[4].toInt() and 0xFF) or ((hdr[5].toInt() and 0xFF) shl 8)
                    val cu = (hdr[6].toInt() and 0xFF) or ((hdr[7].toInt() and 0xFF) shl 8)
                    r.seek(r.filePointer + arc.dataReserve)
                    blocks.add(longArrayOf(r.filePointer, cb.toLong(), cu.toLong())); r.seek(r.filePointer + cb)
                }
                fun payload(b: LongArray): ByteArray { val d = ByteArray(b[1].toInt()); r.seek(b[0]); r.readFully(d); return d }
                var next = 0
                val lzx = if (type == 3) Lzx((folder.type shr 8) and 0x1F) { blocks.getOrNull(next++)?.let { payload(it) } } else null
                val md = java.security.MessageDigest.getInstance("MD5")
                var done = 0L; var hist = ByteArray(0)
                val t0 = System.currentTimeMillis()
                try {
                    for (b in blocks) {
                        if (done >= max) break
                        val out = when (type) {
                            0 -> payload(b)
                            1 -> Cab.mszip(payload(b), b[2].toInt(), hist).also { o -> hist = (hist + o).takeLast(32768).toByteArray() }
                            else -> lzx!!.frame(b[2].toInt())
                        }
                        val n = minOf(out.size.toLong(), max - done).toInt()
                        md.update(out, 0, n); done += n
                    }
                    println("folder $fi type=0x${folder.type.toString(16)} blocks=${folder.blocks} decoded=$done md5=" + md.digest().joinToString("") { "%02x".format(it) } + " in ${System.currentTimeMillis() - t0} ms")
                } catch (e: Exception) { println("folder $fi type=0x${folder.type.toString(16)} FAILED after $done bytes: $e") }
            }
        }
        return
    }
    if (args.firstOrNull() == "--cab") {
        val out = File(args[2]); out.mkdirs()
        val arc = Cab.read(File(args[1]))
        for ((i, e) in arc.entries.withIndex()) {
            print("${e.name} size=${e.size} folder=${e.folder} compression=${arc.compression(e.folder)}: ")
            try {
                val r = Cab.extract(File(args[1]), out, { it.name == e.name })
                val f = r.first().second
                println("OK ${f.length()} bytes md5=" + java.security.MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) })
            } catch (x: Exception) { println("FAILED $x") }
        }
        return
    }
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
