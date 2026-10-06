package com.daftar.app.word

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/** Saves outlive the screen (save on exit): one app-wide scope, one lock. */
internal object WordSaver {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val lock = Mutex()
}

/** Resolved look of one paragraph for the editor. */
internal class PLook(
    /** Empty-text template with the resolved paragraph properties (size, alignment, direction, indents, spacing). */
    val para: DocBlock.Para,
    val styleId: String?,
    /** Built-in style kind (Normal, Title, Heading1..3, Quote) or null. */
    val kind: String?,
    val numId: String?,
    val ilvl: Int,
    val bullet: Boolean,
)

/** Find hit inside paragraph [id]. */
internal class EHit(val id: Int, val start: Int, val end: Int)

/** Where a paragraph lives: top-level block [top]; inside a table when [row] ≥ 0. */
internal class Loc(val top: Int, val row: Int, val col: Int, val idx: Int)

internal class FocusReq(val id: Int, val sel: TextRange, val seq: Int)

/**
 * All editing logic of the Word editor (no UI): block list with undo/redo, focus/selection, formatting and paragraph
 * commands, insertions, find & replace, saving. Paragraph text offsets never include the editor's zero-width sentinel.
 */
internal class EditorState(val src: EditSource, initial: List<EBlock>) {
    var blocks by mutableStateOf(initial)
        private set
    private var saved: List<EBlock> = initial
    private class Snap(val blocks: List<EBlock>, val focus: Int, val sel: TextRange)
    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private var lastKind = ""
    private var lastId = -1
    private var lastTime = 0L

    var focusId by mutableIntStateOf(-1)
    var selection by mutableStateOf(TextRange.Zero)
    var selectedObject by mutableIntStateOf(-1)
    var pending by mutableStateOf<CFmt?>(null)
    var focusReq by mutableStateOf<FocusReq?>(null); private set
    private var seq = 0

    val dirty: Boolean get() = blocks !== saved
    var saving by mutableStateOf(false); private set
    var savedAt by mutableLongStateOf(0L); private set
    var saveFailed by mutableStateOf(false); private set

    val docx: Boolean get() = src.kind == EditKind.DOCX
    val md: Boolean get() = src.kind == EditKind.MD

    // ------------------------------------------------------------------ looks

    private val lookCache = WeakHashMap<EPara, PLook>()
    private val runCache = HashMap<Long, RunFmt>()

    fun look(p: EPara): PLook {
        synchronized(lookCache) { lookCache[p]?.let { return it } }
        val l = computeLook(p)
        synchronized(lookCache) { lookCache[p] = l }
        return l
    }

    private fun computeLook(p: EPara): PLook {
        val pkg = src.docx
        if (pkg != null) {
            val pl = pkg.session.paraLook(src.writer.pPrXml(p).ifEmpty { null })
            return PLook(pl.para, pl.styleId, pkg.session.kindOf(pl.styleId), pl.numId, pl.ilvl, pl.bullet)
        }
        val rtl = DocxParser.firstStrongRtl(p.text)
        var base = 11f
        var heading = 0
        if (md) {
            val h = Regex("^(#{1,6})\\s").find(p.text)
            if (h != null) { heading = h.groupValues[1].length; base = when (heading) { 1 -> 20f; 2 -> 16f; 3 -> 13.5f; else -> 12f } }
        }
        val para = DocBlock.Para(-1, "", emptyList(), base, 0, rtl, 0f, 0f, 0f, null, null, 0f, if (md && heading > 0) 6f else 0f,
            if (md) 2f else 0f, 1f, heading)
        return PLook(para, null, null, null, 0, false)
    }

    /** Display formatting of format [idx] in paragraph [p]. */
    fun runFmt(p: EPara, look: PLook, idx: Int): RunFmt {
        val pkg = src.docx
        val f = src.fmts[idx]
        if (pkg == null) return RunFmt(sizePt = look.para.basePt, bold = look.para.heading > 0)
        val key = (idx.toLong() shl 20) xor ((look.styleId?.hashCode()?.toLong() ?: 0L) and 0xFFFFF) xor (if (look.para.rtl) 1L shl 62 else 0L)
        synchronized(runCache) { runCache[key]?.let { return it } }
        var r = pkg.session.runFmt(look.styleId, src.writer.rPrXml(idx, null).ifEmpty { null }, look.para.rtl)
        if (look.para.heading > 0) {
            if (f.sizeHalf == null && !pkg.session.styleSetsSize(look.styleId)) r = r.copy(sizePt = look.para.basePt)
            if (f.b == null && !r.bold) r = r.copy(bold = true)
        }
        synchronized(runCache) { runCache[key] = r }
        return r
    }

    /** List marker text per paragraph id (document order; docx numbering or text lists). */
    fun markers(list: List<EBlock>): Map<Int, String> {
        val pkg = src.docx ?: return emptyMap()
        val out = HashMap<Int, String>()
        pkg.session.resetCounters()
        list.forEachPara { p ->
            val l = look(p)
            if (l.numId != null) pkg.session.markerText(l.numId, l.ilvl)?.takeIf { it.isNotEmpty() }?.let { out[p.id] = it }
        }
        return out
    }

    // ------------------------------------------------------------------ locating

    fun locate(id: Int, list: List<EBlock> = blocks): Loc? {
        for ((i, b) in list.withIndex()) when (b) {
            is EPara -> if (b.id == id) return Loc(i, -1, -1, 0)
            is ETable -> for ((r, row) in b.rows.withIndex()) for ((c, cell) in row.cells.withIndex()) {
                val k = cell.paras.indexOfFirst { it.id == id }
                if (k >= 0) return Loc(i, r, c, k)
            }
            else -> {}
        }
        return null
    }

    fun para(id: Int): EPara? {
        val l = locate(id) ?: return null
        return parasAt(blocks, l)[l.idx]
    }

    /** Index of the top-level block that contains paragraph [id]. */
    fun topIndexOf(id: Int): Int = locate(id)?.top ?: blocks.indexOfFirst { it.id == id }

    private fun parasAt(list: List<EBlock>, l: Loc): List<EPara> = when (val b = list[l.top]) {
        is EPara -> listOf(b)
        is ETable -> b.rows[l.row].cells[l.col].paras
        else -> emptyList()
    }

    /** Replaces the paragraph at [l] by [with] (one or more paragraphs; empty only inside cells that keep one). */
    private fun replaceAt(list: List<EBlock>, l: Loc, with: List<EPara>): List<EBlock> {
        val out = ArrayList(list)
        when (val b = list[l.top]) {
            is EPara -> { out.removeAt(l.top); out.addAll(l.top, with) }
            is ETable -> {
                val rows = b.rows.toMutableList()
                val row = rows[l.row]
                val cells = row.cells.toMutableList()
                val cell = cells[l.col]
                val paras = cell.paras.toMutableList()
                paras.removeAt(l.idx); paras.addAll(l.idx, with)
                if (paras.isEmpty()) paras.add(EPara.plain(""))
                cells[l.col] = ECell(cell.head, paras)
                rows[l.row] = ERow(row.head, cells)
                out[l.top] = b.withCells(rows)
            }
            else -> {}
        }
        return out
    }

    // ------------------------------------------------------------------ history

    private fun commit(next: List<EBlock>, kind: String, id: Int) {
        if (next === blocks) return
        val now = SystemClock.uptimeMillis()
        val coalesce = kind == "type" && lastKind == "type" && lastId == id && now - lastTime < 1500
        if (!coalesce) {
            undoStack.addLast(Snap(blocks, focusId, selection))
            if (undoStack.size > 200) undoStack.removeFirst()
            redoStack.clear()
        }
        lastKind = kind; lastId = id; lastTime = now
        blocks = next
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snap(blocks, focusId, selection))
        blocks = s.blocks
        lastKind = ""
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        if (s.focus >= 0 && locate(s.focus) != null) requestFocus(s.focus, s.sel)
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snap(blocks, focusId, selection))
        blocks = s.blocks
        lastKind = ""
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        if (s.focus >= 0 && locate(s.focus) != null) requestFocus(s.focus, s.sel)
    }

    fun requestFocus(id: Int, sel: TextRange) {
        focusReq = FocusReq(id, sel, ++seq)
    }

    // ------------------------------------------------------------------ typing

    private fun inheritFmt(p: EPara, at: Int): Int {
        var k = at - 1
        while (k >= 0 && src.fmts[p.fmts[k]].atom >= 0) k--
        if (k < 0) { k = at; while (k < p.text.length && src.fmts[p.fmts[k]].atom >= 0) k++ }
        if (k >= p.text.length || k < 0) return 0
        val idx = p.fmts[k]
        val f = src.fmts[idx]
        // Typing right after a link doesn't extend it (inside a link it does).
        if (f.link != null && (at >= p.text.length || src.fmts[p.fmts[at]].link != f.link)) return src.fmts.intern(f.copy(link = null))
        return idx
    }

    /** The field of paragraph [id] changed to [newText] (no sentinel) with [sel]. Handles Enter / paste with newlines. */
    fun onText(id: Int, newText0: String, sel: TextRange) {
        val l = locate(id) ?: return
        val p = parasAt(blocks, l)[l.idx]
        val old = p.text
        val newText = if (newText0.indexOf('\r') >= 0) newText0.replace("\r\n", "\n").replace('\r', '\n') else newText0
        if (newText == old) { selection = sel; focusId = id; return }
        var a = 0
        while (a < old.length && a < newText.length && old[a] == newText[a]) a++
        var e = 0
        while (e < old.length - a && e < newText.length - a && old[old.length - 1 - e] == newText[newText.length - 1 - e]) e++
        var ins = newText.substring(a, newText.length - e)
        if (ins.indexOf(ATOM) >= 0) ins = ins.replace(ATOM.toString(), "")
        val oldEnd = old.length - e
        val f = pending?.let { src.fmts.intern(it.copy(atom = -1)) } ?: inheritFmt(p, a)
        val text = old.substring(0, a) + ins + old.substring(oldEnd)
        val fm = IntArray(text.length)
        System.arraycopy(p.fmts, 0, fm, 0, a)
        for (k in ins.indices) fm[a + k] = f
        System.arraycopy(p.fmts, oldEnd, fm, a + ins.length, old.length - oldEnd)

        if (ins.indexOf('\n') < 0) {
            commit(replaceAt(blocks, l, listOf(p.edit(text, fm))), "type", id)
            focusId = id
            selection = TextRange(a + ins.length)
            if (sel.collapsed.not()) selection = sel
            return
        }
        // Enter on an empty list item leaves the list (like Word).
        if (old.isEmpty() && ins == "\n") {
            val lk = look(p)
            if (lk.numId != null) { setList(id, null); return }
        }
        // Split into paragraphs.
        val parts = ArrayList<EPara>()
        var start = 0
        val lk = look(p)
        var idx = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            val t = text.substring(start, end)
            val fms = fm.copyOfRange(start, end)
            if (idx == 0) parts.add(p.edit(t, fms))
            else {
                val headingLike = docx && lk.kind != null && lk.kind != "Normal" && lk.kind != "Quote"
                var props = p.props
                if (headingLike) props = props.copy(style = null)
                parts.add(p.sibling(t, fms, props))
            }
            idx++
            if (nl < 0) break
            start = nl + 1
        }
        if (md) {
            // Continue Markdown lists / quotes on Enter.
            val prefix = Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+|\\s*>\\s?)").find(old)?.value
            if (prefix != null && parts.size == 2 && ins == "\n") {
                if (old.trim() == prefix.trim()) { parts.clear(); parts.add(p.edit("", IntArray(0))) }
                else {
                    val num = Regex("^(\\s*)(\\d+)([.)])").find(prefix)
                    val pre = if (num != null) num.groupValues[1] + (num.groupValues[2].toInt() + 1) + num.groupValues[3] + " " else prefix
                    val second = parts[1]
                    parts[1] = second.edit(pre + second.text, IntArray(pre.length + second.text.length))
                }
            }
        }
        commit(replaceAt(blocks, l, parts), "split", id)
        val last = parts.last()
        val caret = if (parts.size == 1) 0 else (last.text.length - (old.length - oldEnd)).coerceIn(0, last.text.length)
        focusId = last.id
        selection = TextRange(caret)
        requestFocus(last.id, TextRange(caret))
    }

    /** Backspace at the start of paragraph [id]. */
    fun backspaceAtStart(id: Int) {
        val l = locate(id) ?: return
        val p = parasAt(blocks, l)[l.idx]
        if (docx) {
            val lk = look(p)
            if (lk.numId != null) { setList(id, null); return }
            if ((p.props.indLeft ?: 0) > 0) { indent(-1); return }
        }
        if (l.row >= 0) {
            if (l.idx == 0) return
            val prev = parasAt(blocks, l)[l.idx - 1]
            mergeInto(l, Loc(l.top, l.row, l.col, l.idx - 1), prev, p)
            return
        }
        val i = l.top - 1
        if (i < 0) return
        when (val b = blocks[i]) {
            is EPara -> mergeInto(l, Loc(i, -1, -1, 0), b, p)
            is EObject -> if (b.kind != OKind.HIDDEN) {
                val out = ArrayList(blocks); out.removeAt(i)
                commit(out, "delete", id)
                requestFocus(id, TextRange(0))
            }
            else -> {}
        }
    }

    private fun mergeInto(cur: Loc, prevLoc: Loc, prev: EPara, p: EPara) {
        val merged = prev.edit(prev.text + p.text, prev.fmts + p.fmts)
        var list = replaceAt(blocks, cur, emptyList())
        if (cur.row >= 0) {
            // replaceAt on a cell keeps at least one paragraph; the previous one is still at its index
            list = replaceAt(list, prevLoc, listOf(merged))
        } else list = replaceAt(list, prevLoc, listOf(merged))
        commit(list, "merge", prev.id)
        requestFocus(prev.id, TextRange(prev.text.length))
    }

    /** Delete at the end of paragraph [id]: pull the next paragraph in. */
    fun deleteAtEnd(id: Int) {
        val l = locate(id) ?: return
        val p = parasAt(blocks, l)[l.idx]
        if (l.row >= 0) {
            val paras = parasAt(blocks, l)
            if (l.idx + 1 >= paras.size) return
            val next = paras[l.idx + 1]
            var list = replaceAt(blocks, Loc(l.top, l.row, l.col, l.idx + 1), emptyList())
            list = replaceAt(list, l, listOf(p.edit(p.text + next.text, p.fmts + next.fmts)))
            commit(list, "merge", id)
            requestFocus(id, TextRange(p.text.length))
            return
        }
        val n = l.top + 1
        val next = blocks.getOrNull(n) ?: return
        val out = ArrayList(blocks)
        when (next) {
            is EPara -> { out.removeAt(n); out[l.top] = p.edit(p.text + next.text, p.fmts + next.fmts) }
            is EObject -> if (next.kind != OKind.HIDDEN) out.removeAt(n) else return
            else -> return
        }
        commit(out, "merge", id)
        requestFocus(id, TextRange(p.text.length))
    }

    /** Inserts plain text at the caret (soft line break, symbols). */
    fun typeText(s: String) {
        val p = para(focusId) ?: return
        val a = selection.min.coerceIn(0, p.text.length)
        val b = selection.max.coerceIn(0, p.text.length)
        onText(p.id, p.text.substring(0, a) + s + p.text.substring(b), TextRange(a + s.length))
        requestFocus(p.id, TextRange(a + s.length))
    }

    // ------------------------------------------------------------------ character formatting

    /** Direct format at the caret / selection start (pending format first). */
    fun caretFmt(): CFmt {
        pending?.let { return it }
        val p = para(focusId) ?: return CFmt()
        if (p.text.isEmpty()) return CFmt()
        val at = if (selection.collapsed) inheritFmt(p, selection.min.coerceIn(0, p.text.length)) else p.fmts[selection.min.coerceIn(0, p.text.length - 1)]
        return src.fmts[at]
    }

    /** Resolved formatting at the caret (for the ribbon's toggle states). */
    fun caretLook(): RunFmt {
        val p = para(focusId) ?: return RunFmt()
        val lk = look(p)
        return runFmt(p, lk, src.fmts.intern(caretFmt().copy(atom = -1)))
    }

    fun applyChar(t: (CFmt) -> CFmt) {
        val p = para(focusId) ?: return
        if (!docx) return
        if (selection.collapsed) { pending = t(caretFmt()).copy(atom = -1); return }
        val a = selection.min.coerceIn(0, p.text.length)
        val b = selection.max.coerceIn(0, p.text.length)
        val fm = p.fmts.copyOf()
        val memo = HashMap<Int, Int>()
        for (k in a until b) {
            val cur = fm[k]
            if (src.fmts[cur].atom >= 0) continue
            fm[k] = memo.getOrPut(cur) { src.fmts.intern(t(src.fmts[cur])) }
        }
        val l = locate(p.id) ?: return
        commit(replaceAt(blocks, l, listOf(p.edit(p.text, fm))), "fmt", p.id)
        requestFocus(p.id, selection)
    }

    fun toggleBold() { if (md) mdWrap("**") else { val on = !caretLook().bold; applyChar { it.copy(b = on) } } }
    fun toggleItalic() { if (md) mdWrap("*") else { val on = !caretLook().italic; applyChar { it.copy(i = on) } } }
    fun toggleUnderline() { if (md) mdWrap("<u>", "</u>") else { val on = !caretLook().underline; applyChar { it.copy(u = on) } } }
    fun toggleStrike() { if (md) mdWrap("~~") else { val on = !caretLook().strike; applyChar { it.copy(strike = on) } } }
    fun setSize(pt: Float) = applyChar { it.copy(sizeHalf = (pt * 2).toInt().coerceIn(2, 3276)) }
    fun setFont(key: String?) = applyChar { it.copy(font = WordFonts.docxName(key)) }
    fun setColor(hex: String?) = applyChar { it.copy(color = hex) }
    fun setHighlight(name: String?) = applyChar { it.copy(highlight = name) }

    // ------------------------------------------------------------------ paragraph formatting

    private fun applyPara(kind: String, t: (EPara) -> EPara) {
        val p = para(focusId) ?: return
        val l = locate(p.id) ?: return
        val np = t(p)
        if (np === p) return
        commit(replaceAt(blocks, l, listOf(np)), kind, p.id)
        requestFocus(np.id, selection)
    }

    fun setStyle(kind: String) {
        if (md) {
            val prefix = when (kind) { "Title", "Heading1" -> "# "; "Heading2" -> "## "; "Heading3" -> "### "; "Quote" -> "> "; else -> "" }
            mdLinePrefix(Regex("^(#{1,6}\\s+|>\\s?)"), prefix)
            return
        }
        if (!docx) return
        val id = DocxEdit.ensureStyle(src, kind)
        applyPara("pfmt") { it.edit(props = it.props.copy(style = id)) }
    }

    /** jc: "left" (start), "center", "right" (end), "both". */
    fun setAlign(jc: String) { if (docx) applyPara("pfmt") { it.edit(props = it.props.copy(jc = jc)) } }

    fun setRtl(rtl: Boolean) { if (docx) applyPara("pfmt") { it.edit(props = it.props.copy(bidi = rtl)) } }

    /** bullet = true / false (numbered) / null (no list). */
    fun setList(id: Int, bullet: Boolean?) {
        val p = para(id) ?: return
        val l = locate(id) ?: return
        if (md) { focusId = id; mdLinePrefix(Regex("^\\s*([-*+]|\\d+[.)])\\s+"), when (bullet) { true -> "- "; false -> "1. "; null -> "" }); return }
        if (!docx) return
        val lk = look(p)
        val styleNum = src.docx!!.session.styleHasNumbering(lk.styleId)
        val props = when (bullet) {
            null -> p.props.copy(numId = if (styleNum) "0" else null, ilvl = null)
            true -> p.props.copy(numId = DocxEdit.bulletNum(src), ilvl = lk.ilvl.takeIf { lk.numId != null } ?: 0)
            false -> {
                // Join the numbered list right above, else start a new one at 1.
                val prev = parasAt(blocks, l).getOrNull(l.idx - 1) ?: (blocks.getOrNull(l.top - 1) as? EPara)?.takeIf { l.row < 0 }
                val prevLook = prev?.let { look(it) }
                val num = if (prevLook?.numId != null && !prevLook.bullet) prevLook.numId else DocxEdit.newNumberedList(src)
                p.props.copy(numId = num, ilvl = lk.ilvl.takeIf { lk.numId != null } ?: 0)
            }
        }
        commit(replaceAt(blocks, l, listOf(p.edit(props = props))), "pfmt", id)
        requestFocus(id, selection)
    }

    fun toggleList(bullet: Boolean) {
        val p = para(focusId) ?: return
        val on = if (md) Regex(if (bullet) "^\\s*[-*+]\\s" else "^\\s*\\d+[.)]\\s").containsMatchIn(p.text)
        else look(p).let { it.numId != null && it.bullet == bullet }
        setList(p.id, if (on) null else bullet)
    }

    fun indent(dir: Int) {
        val p = para(focusId) ?: return
        if (!docx) {
            if (dir > 0) { mdInsertAtLineStart(if (md) "  " else "    ") }
            else {
                val n = p.text.takeWhile { it == ' ' }.length.coerceAtMost(if (md) 2 else 4)
                if (n > 0) replaceLine(p, p.text.substring(n), -n)
            }
            return
        }
        val lk = look(p)
        applyPara("pfmt") {
            if (lk.numId != null) {
                val lv = (lk.ilvl + dir).coerceIn(0, 8)
                it.edit(props = it.props.copy(numId = lk.numId, ilvl = lv))
            } else {
                val cur = it.props.indLeft ?: (lk.para.indStart * 20).toInt()
                val next = ((cur / 720) + dir).coerceIn(0, 12) * 720
                it.edit(props = it.props.copy(indLeft = if (next == 0 && it.props.indLeft == null) null else next))
            }
        }
    }

    // ------------------------------------------------------------------ markdown helpers

    private fun replaceLine(p: EPara, text: String, caretShift: Int) {
        val l = locate(p.id) ?: return
        commit(replaceAt(blocks, l, listOf(p.edit(text, IntArray(text.length)))), "pfmt", p.id)
        val s = TextRange((selection.start + caretShift).coerceIn(0, text.length), (selection.end + caretShift).coerceIn(0, text.length))
        selection = s
        requestFocus(p.id, s)
    }

    private fun mdLinePrefix(existing: Regex, prefix: String) {
        val p = para(focusId) ?: return
        val m = existing.find(p.text)
        val rest = if (m != null) p.text.substring(m.value.length) else p.text
        val same = m != null && m.value.trim() == prefix.trim()
        val nt = (if (same) "" else prefix) + rest
        replaceLine(p, nt, nt.length - p.text.length)
    }

    private fun mdInsertAtLineStart(s: String) {
        val p = para(focusId) ?: return
        replaceLine(p, s + p.text, s.length)
    }

    private fun mdWrap(open: String, close: String = open) {
        val p = para(focusId) ?: return
        val a = selection.min.coerceIn(0, p.text.length)
        val b = selection.max.coerceIn(0, p.text.length)
        val inner = p.text.substring(a, b)
        val t: String
        val sel: TextRange
        if (a >= open.length && p.text.startsWith(open, a - open.length) && p.text.startsWith(close, b)) {
            t = p.text.substring(0, a - open.length) + inner + p.text.substring(b + close.length)
            sel = TextRange(a - open.length, b - open.length)
        } else {
            t = p.text.substring(0, a) + open + inner + close + p.text.substring(b)
            sel = TextRange(a + open.length, b + open.length)
        }
        val l = locate(p.id) ?: return
        commit(replaceAt(blocks, l, listOf(p.edit(t, IntArray(t.length)))), "fmt", p.id)
        selection = sel
        requestFocus(p.id, sel)
    }

    // ------------------------------------------------------------------ insertions / objects

    /** Inserts [items] after the block holding the caret (or the selected object), then a paragraph to keep typing. */
    fun insertBlocks(items: List<EBlock>) {
        if (items.isEmpty()) return
        val at = when {
            selectedObject >= 0 -> blocks.indexOfFirst { it.id == selectedObject }
            focusId >= 0 -> topIndexOf(focusId)
            else -> -1
        }.let { if (it < 0) blocks.indexOfLast { b -> !(b is EObject && b.kind == OKind.HIDDEN) } else it }
        val out = ArrayList(blocks)
        var pos = at + 1
        // A focused, empty paragraph is replaced by the insertion (like Word inserting on the current line).
        val cur = out.getOrNull(at)
        if (cur is EPara && cur.text.isEmpty() && cur.id == focusId && items.first() !is EPara) { out.removeAt(at); pos = at }
        out.addAll(pos, items)
        val after = pos + items.size
        val next = out.getOrNull(after)
        val focusPara: EPara
        if (next is EPara) focusPara = next
        else { focusPara = EPara.plain(""); out.add(after, focusPara) }
        commit(out, "insert", -1)
        selectedObject = -1
        requestFocus(focusPara.id, TextRange(0))
    }

    fun deleteObject(id: Int) {
        val i = blocks.indexOfFirst { it.id == id }
        if (i < 0) return
        val b = blocks[i]
        if (b is EObject && b.kind == OKind.HIDDEN) return
        val out = ArrayList(blocks)
        out.removeAt(i)
        if (out.none { it is EPara || it is ETable }) out.add(i.coerceAtMost(out.size), EPara.plain(""))
        commit(out, "delete", -1)
        selectedObject = -1
        (out.getOrNull(i) as? EPara ?: out.getOrNull(i - 1) as? EPara)?.let { requestFocus(it.id, TextRange(0)) }
    }

    /** Markdown: a table template / rule / image reference as text lines after the caret. */
    fun insertLines(lines: List<String>) {
        insertBlocks(lines.map { EPara.plain(it) })
    }

    // ------------------------------------------------------------------ find & replace

    fun findAll(q: String, list: List<EBlock> = blocks): List<EHit> {
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<EHit>()
        list.forEachPara { p ->
            var i = p.text.indexOf(q, ignoreCase = true)
            while (i >= 0 && out.size < 5000) { out.add(EHit(p.id, i, i + q.length)); i = p.text.indexOf(q, i + q.length, ignoreCase = true) }
        }
        return out
    }

    private fun replaceIn(p: EPara, ranges: List<EHit>, with: String): EPara {
        val sb = StringBuilder()
        val fm = ArrayList<Int>()
        var pos = 0
        for (h in ranges.sortedBy { it.start }) {
            if (h.start < pos || h.end > p.text.length) continue
            for (k in pos until h.start) { sb.append(p.text[k]); fm.add(p.fmts[k]) }
            // Keep atoms that sat inside the match; the replacement takes the first character's format.
            val f = (h.start until h.end).firstOrNull { src.fmts[p.fmts[it]].atom < 0 }?.let { p.fmts[it] } ?: inheritFmt(p, h.start)
            for (c in with) { sb.append(if (c == '\n') SOFT_BREAK else c); fm.add(f) }
            for (k in h.start until h.end) if (p.text[k] == ATOM) { sb.append(ATOM); fm.add(p.fmts[k]) }
            pos = h.end
        }
        for (k in pos until p.text.length) { sb.append(p.text[k]); fm.add(p.fmts[k]) }
        return p.edit(sb.toString(), fm.toIntArray())
    }

    fun replace(hit: EHit, with: String) {
        val p = para(hit.id) ?: return
        if (hit.end > p.text.length) return
        val l = locate(p.id) ?: return
        commit(replaceAt(blocks, l, listOf(replaceIn(p, listOf(hit), with))), "replace", -1)
    }

    /** Replaces every match; returns how many. */
    fun replaceAll(q: String, with: String): Int {
        val hits = findAll(q).groupBy { it.id }
        if (hits.isEmpty()) return 0
        var list = blocks
        var n = 0
        for ((id, hs) in hits) {
            val l = locate(id, list) ?: continue
            val p = parasAt(list, l)[l.idx]
            list = replaceAt(list, l, listOf(replaceIn(p, hs, with)))
            n += hs.size
        }
        commit(list, "replace", -1)
        return n
    }

    // ------------------------------------------------------------------ saving

    /** Saves the current blocks if they changed. [then] runs on the main thread with success. */
    fun save(then: ((Boolean) -> Unit)? = null) {
        val snap = blocks
        if (snap === saved) { then?.invoke(true); return }
        saving = true
        WordSaver.scope.launch {
            val ok = WordSaver.lock.withLock { runCatching { DocxEdit.save(src, snap) }.isSuccess }
            withContext(Dispatchers.Main) {
                saving = false
                if (ok) { saved = snap; savedAt = System.currentTimeMillis(); saveFailed = false } else saveFailed = true
                then?.invoke(ok)
            }
        }
    }

    /** Fire-and-forget save that doesn't touch Compose state (screen being disposed). */
    fun saveDetached() {
        val snap = blocks
        if (snap === saved) return
        saved = snap
        WordSaver.scope.launch { WordSaver.lock.withLock { runCatching { DocxEdit.save(src, snap) } } }
    }
}
