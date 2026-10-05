package com.daftar.app.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.daftar.app.R
import com.daftar.app.ui.theme.D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sticker / stamp catalogue and vector artwork (flat, Daftar colours, no emoji). Everything is drawn with paths and
 * text, so stickers are crisp at any zoom and stay vectors in PDF exports. Thread-safe (per-thread paints): the editor,
 * tile/thumbnail threads and exports all draw through [draw].
 */
object InkStickers {
    // Daftar palette
    private const val GREEN = 0xFF2F9E6E.toInt()
    private const val RED = 0xFFE5484D.toInt()
    private const val CORAL = 0xFFF26D5B.toInt()
    private const val SAND = 0xFFF2C94C.toInt()
    private const val ORANGE = 0xFFF59E42.toInt()
    private const val BLUE = 0xFF3B82F6.toInt()
    private const val PURPLE = 0xFF8B6CE0.toInt()
    private const val INDIGO = 0xFF5B6EE8.toInt()
    private const val TEAL = 0xFF2EB5B0.toInt()

    val symbols = listOf("check", "cross", "star", "exclaim", "question")
    val stamps = listOf("important", "exam", "review", "done", "ask")
    val arrows = listOf("arrow_r", "arrow_l", "arrow_u", "arrow_d", "arrow_ur", "arrow_curve")
    val numbers = (1..9).map { "num$it" }
    val all = symbols + stamps + arrows + numbers

    fun isStamp(kind: String) = kind in stamps

    fun defaultColor(kind: String): Int = when (kind) {
        "check", "done" -> GREEN
        "cross", "exam" -> RED
        "important" -> CORAL
        "star" -> SAND
        "exclaim" -> ORANGE
        "question", "ask" -> BLUE
        "review" -> PURPLE
        else -> if (kind.startsWith("num")) INDIGO else TEAL
    }

    /** Label of a text stamp in the current language (stored in the item when placed). */
    fun stampLabel(kind: String): Int = when (kind) {
        "important" -> R.string.ink5_stamp_important
        "exam" -> R.string.ink5_stamp_exam
        "review" -> R.string.ink5_stamp_review
        "done" -> R.string.ink5_stamp_done
        else -> R.string.ink5_stamp_question
    }

    /** Natural size in page points for [kind] (text stamps: width from the label). */
    fun naturalSize(kind: String, text: String): Pair<Float, Float> = when {
        isStamp(kind) -> {
            val h = 34f
            val tp = textTL.get()!!
            tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = h * 0.46f; tp.letterSpacing = 0.04f
            val w = tp.measureText(text) + h * 0.9f
            tp.letterSpacing = 0f
            w.coerceAtLeast(h * 1.6f) to h
        }
        kind.startsWith("num") -> 30f to 30f
        kind.startsWith("arrow") -> 48f to 48f
        else -> 36f to 36f
    }

    // ---- drawing ----
    private val fillTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val lineTL = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    }
    private val textTL = ThreadLocal.withInitial { TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    private val pathTL = ThreadLocal.withInitial { Path() }
    private val rectTL = ThreadLocal.withInitial { RectF() }

    /** Draws a placed sticker (page coordinates). */
    fun draw(c: Canvas, s: StickerItem) {
        c.save()
        c.translate(s.x + s.w / 2f, s.y + s.h / 2f)
        if (s.rot != 0f) c.rotate(s.rot)
        drawCentered(c, s.kind, s.text, s.color, s.w, s.h)
        c.restore()
    }

    /** Artwork of [kind] centred on the canvas origin in a [w] × [h] box. */
    fun drawCentered(c: Canvas, kind: String, text: String, color: Int, w: Float, h: Float) {
        val f = fillTL.get()!!; val ln = lineTL.get()!!
        val r = min(w, h) / 2f
        when {
            kind == "check" -> { badge(c, r, color); ln.color = WHITE; ln.strokeWidth = r * 0.2f
                val p = path(); p.moveTo(-0.42f * r, 0.02f * r); p.lineTo(-0.12f * r, 0.32f * r); p.lineTo(0.44f * r, -0.3f * r); c.drawPath(p, ln) }
            kind == "cross" -> { badge(c, r, color); ln.color = WHITE; ln.strokeWidth = r * 0.2f
                val k = 0.34f * r; c.drawLine(-k, -k, k, k, ln); c.drawLine(k, -k, -k, k, ln) }
            kind == "exclaim" -> { badge(c, r, color); ln.color = WHITE; ln.strokeWidth = r * 0.22f
                c.drawLine(0f, -0.48f * r, 0f, 0.1f * r, ln); f.color = WHITE; c.drawCircle(0f, 0.44f * r, r * 0.13f, f) }
            kind == "question" -> { badge(c, r, color); glyph(c, "?", r * 1.25f, WHITE) }
            kind.startsWith("num") -> { badge(c, r, color); glyph(c, kind.removePrefix("num"), r * 1.12f, WHITE) }
            kind == "star" -> star(c, r, color)
            kind == "arrow_curve" -> curvedArrow(c, w, h, color)
            kind.startsWith("arrow") -> {
                val deg = when (kind) { "arrow_l" -> 180f; "arrow_u" -> -90f; "arrow_d" -> 90f; "arrow_ur" -> -45f; else -> 0f }
                c.save(); c.rotate(deg)
                val s = if (kind == "arrow_ur") min(w, h) * 0.86f else min(w, h)
                blockArrow(c, s, color)
                c.restore()
            }
            isStamp(kind) -> stamp(c, text, w, h, color)
        }
    }

    private const val WHITE = 0xFFFFFFFF.toInt()

    private fun path(): Path = pathTL.get()!!.also { it.rewind() }

    private fun badge(c: Canvas, r: Float, color: Int) {
        val f = fillTL.get()!!
        f.color = color or 0xFF000000.toInt()
        c.drawCircle(0f, 0f, r, f)
    }

    private fun glyph(c: Canvas, s: String, size: Float, color: Int) {
        val tp = textTL.get()!!
        tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = size; tp.color = color; tp.letterSpacing = 0f
        c.drawText(s, 0f, -(tp.ascent() + tp.descent()) / 2f, tp)
    }

    private fun star(c: Canvas, r: Float, color: Int) {
        val p = path()
        // unit star spans x ±0.951, y −1 … 0.809: centre it vertically
        val oy = (1f - 0.809f) / 2f * r
        for (k in 0 until 10) {
            val a = Math.toRadians(-90.0 + k * 36.0)
            val rr = if (k % 2 == 0) r * 0.98f else r * 0.42f
            val x = (cos(a) * rr).toFloat(); val y = (sin(a) * rr).toFloat() + oy
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        val f = fillTL.get()!!
        f.color = color or 0xFF000000.toInt()
        c.drawPath(p, f)
        // softened corners in the same colour (flat)
        val ln = lineTL.get()!!
        ln.color = color or 0xFF000000.toInt(); ln.strokeWidth = r * 0.12f
        c.drawPath(p, ln)
    }

    /** Right-pointing block arrow in a [s] × [s] box centred at the origin. */
    private fun blockArrow(c: Canvas, s: Float, color: Int) {
        val p = path()
        val hx = s / 2f
        val shaft = s * 0.13f; val head = s * 0.36f; val neck = s * 0.06f
        p.moveTo(-hx, -shaft); p.lineTo(neck, -shaft); p.lineTo(neck, -head); p.lineTo(hx, 0f)
        p.lineTo(neck, head); p.lineTo(neck, shaft); p.lineTo(-hx, shaft); p.close()
        val f = fillTL.get()!!
        f.color = color or 0xFF000000.toInt()
        c.drawPath(p, f)
        val ln = lineTL.get()!!
        ln.color = f.color; ln.strokeWidth = s * 0.05f
        c.drawPath(p, ln)
    }

    private fun curvedArrow(c: Canvas, w: Float, h: Float, color: Int) {
        val s = min(w, h)
        val x0 = -0.42f * s; val y0 = 0.30f * s
        val cx = -0.18f * s; val cy = -0.42f * s
        val x1 = 0.20f * s; val y1 = -0.12f * s
        val ln = lineTL.get()!!
        ln.color = color or 0xFF000000.toInt(); ln.strokeWidth = s * 0.13f
        val p = path(); p.moveTo(x0, y0); p.quadTo(cx, cy, x1, y1)
        c.drawPath(p, ln)
        // head along the end tangent
        val dx = x1 - cx; val dy = y1 - cy
        val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
        val ux = dx / l; val uy = dy / l
        val tipX = x1 + ux * s * 0.26f; val tipY = y1 + uy * s * 0.26f
        val bw = s * 0.21f
        val h2 = path()
        h2.moveTo(tipX, tipY); h2.lineTo(x1 - uy * bw, y1 + ux * bw); h2.lineTo(x1 + uy * bw, y1 - ux * bw); h2.close()
        val f = fillTL.get()!!
        f.color = ln.color
        c.drawPath(h2, f)
        ln.strokeWidth = s * 0.04f
        c.drawPath(h2, ln)
    }

    /** Rubber-stamp label: tinted fill, double border, bold label in the stamp colour. */
    private fun stamp(c: Canvas, text: String, w: Float, h: Float, color: Int) {
        val r = rectTL.get()!!
        r.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        val f = fillTL.get()!!
        f.color = (color and 0x00FFFFFF) or 0x24000000
        val rad = h * 0.22f
        c.drawRoundRect(r, rad, rad, f)
        val ln = lineTL.get()!!
        ln.color = color or 0xFF000000.toInt(); ln.strokeWidth = h * 0.065f
        r.inset(ln.strokeWidth / 2f, ln.strokeWidth / 2f)
        c.drawRoundRect(r, rad, rad, ln)
        ln.strokeWidth = h * 0.025f
        r.inset(h * 0.09f, h * 0.09f)
        c.drawRoundRect(r, rad * 0.6f, rad * 0.6f, ln)
        val tp = textTL.get()!!
        tp.typeface = Typeface.DEFAULT_BOLD; tp.color = color or 0xFF000000.toInt()
        tp.textSize = h * 0.46f; tp.letterSpacing = 0.04f
        // shrink to fit when the box was squeezed
        val avail = w - h * 0.7f
        val tw = tp.measureText(text)
        if (tw > avail && tw > 0f) tp.textSize *= (avail / tw).coerceAtLeast(0.3f)
        c.drawText(text, 0f, -(tp.ascent() + tp.descent()) / 2f, tp)
        tp.letterSpacing = 0f
    }

    /** Angle in degrees of the vector (dx, dy) — used by rotation snapping elsewhere. */
    internal fun angleOf(dx: Float, dy: Float) = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
}

/** One sticker preview drawn with the real artwork (crisp, vector). */
@Composable
private fun StickerPreview(kind: String, text: String, size: Dp, onClick: () -> Unit) {
    val c = D.c
    val (nw, nh) = remember(kind, text) { InkStickers.naturalSize(kind, text) }
    val boxW = if (InkStickers.isStamp(kind)) size * 2 else size
    Box(
        Modifier.padding(4.dp).size(boxW, size).clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt)
            .clickable(onClick = onClick).semantics { contentDescription = text.ifEmpty { kind } },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val k = min(size.width / nw, size.height / nh)
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                nc.save()
                nc.translate(size.width / 2f, size.height / 2f)
                nc.scale(k, k)
                InkStickers.drawCentered(nc, kind, text, InkStickers.defaultColor(kind), nw, nh)
                nc.restore()
            }
        }
    }
}

/**
 * Sticker tray (Insert → Stickers): recently used row, then symbols, text stamps (current language), arrows and numbered
 * circles. [onPick] gets the kind and the label to store (text stamps only).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StickerTray(onDismiss: () -> Unit, onPick: (kind: String, text: String) -> Unit) {
    val c = D.c
    val recent = remember { InkPrefs.recentStickers }
    val labels = InkStickers.stamps.associateWith { stringResource(InkStickers.stampLabel(it)) }
    fun text(k: String) = labels[k] ?: ""
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().background(c.surface, RoundedCornerShape(24.dp))
                .border(1.dp, c.line, RoundedCornerShape(24.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ink5_stickers), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.muted) }
            }
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                @Composable
                fun Section(title: Int) = Text(stringResource(title), style = MaterialTheme.typography.labelMedium, color = c.muted,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
                if (recent.isNotEmpty()) {
                    Section(R.string.ink5_recent)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        recent.forEach { k -> StickerPreview(k, text(k), 52.dp) { onPick(k, text(k)) } }
                    }
                }
                Section(R.string.ink5_symbols)
                FlowRow { InkStickers.symbols.forEach { k -> StickerPreview(k, "", 52.dp) { onPick(k, "") } } }
                Section(R.string.ink5_stamps)
                FlowRow { InkStickers.stamps.forEach { k -> StickerPreview(k, text(k), 52.dp) { onPick(k, text(k)) } } }
                Section(R.string.ink5_arrows)
                FlowRow { InkStickers.arrows.forEach { k -> StickerPreview(k, "", 52.dp) { onPick(k, "") } } }
                Section(R.string.ink5_numbers)
                FlowRow { InkStickers.numbers.forEach { k -> StickerPreview(k, "", 52.dp) { onPick(k, "") } } }
            }
        }
    }
}
