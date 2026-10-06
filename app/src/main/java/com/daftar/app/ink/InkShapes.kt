package com.daftar.app.ink

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.daftar.app.R
import com.daftar.app.ui.theme.D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Shape library. Every shape is ONE [Tool.SHAPE] stroke (a polyline; tables and arrow heads retrace their own edges, which
 * is invisible for one stroked path), so the eraser, lasso, undo, tiles, thumbnails and PDF export (vector paths) all work
 * unchanged. [Stroke.shape] remembers the kind so the shape can be edited with handles:
 * box kinds by their bounding box corners, line kinds (line / arrow / double arrow) by their two end points, which are
 * always the stroke's first two points.
 */
object InkShapes {
    const val RECT = "rect"
    const val ROUND = "round"
    const val ELLIPSE = "ellipse"
    const val TRIANGLE = "triangle"
    const val LINE = "line"
    const val ARROW = "arrow"
    const val DARROW = "darrow"
    const val STAR = "star"
    const val BUBBLE = "bubble"
    const val TABLE = "table"
    val all = listOf(RECT, ROUND, ELLIPSE, TRIANGLE, LINE, ARROW, DARROW, STAR, BUBBLE, TABLE)

    fun base(shape: String) = shape.substringBefore(':')
    fun isLine(shape: String) = base(shape).let { it == LINE || it == ARROW || it == DARROW }
    fun isBox(shape: String) = shape.isNotEmpty() && !isLine(shape) && base(shape) in all

    fun table(rows: Int, cols: Int) = "$TABLE:${rows.coerceIn(1, 20)}:${cols.coerceIn(1, 12)}"
    fun tableDims(shape: String): Pair<Int, Int>? {
        if (base(shape) != TABLE) return null
        val p = shape.split(':')
        val r = p.getOrNull(1)?.toIntOrNull() ?: return null
        val c = p.getOrNull(2)?.toIntOrNull() ?: return null
        return r.coerceIn(1, 20) to c.coerceIn(1, 12)
    }

    fun labelOf(shape: String): Int = when (base(shape)) {
        RECT -> R.string.ink5_shape_rect
        ROUND -> R.string.ink5_shape_round
        ELLIPSE -> R.string.ink5_shape_ellipse
        TRIANGLE -> R.string.ink5_shape_triangle
        LINE -> R.string.ink5_shape_line
        ARROW -> R.string.ink5_shape_arrow
        DARROW -> R.string.ink5_shape_darrow
        STAR -> R.string.ink5_shape_star
        BUBBLE -> R.string.ink5_shape_bubble
        else -> R.string.ink5_shape_table
    }

    /** Default size (page points) when placed: box kinds w × h, line kinds the length (h = 0). */
    fun defaultSize(shape: String): Pair<Float, Float> = when (base(shape)) {
        LINE, ARROW, DARROW -> 180f to 0f
        TABLE -> tableDims(shape)!!.let { (r, c) -> min(420f, c * 90f) to r * 34f }
        BUBBLE -> 170f to 120f
        RECT, ROUND -> 180f to 120f
        else -> 140f to 140f
    }

    /**
     * The shape as a stroke. Box kinds: ([x0],[y0])–([x1],[y1]) is the box (any corner order); line kinds: start and end.
     */
    fun build(shape: String, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float): Stroke =
        Stroke(Tool.SHAPE, color, width, points(shape, x0, y0, x1, y1, width), shape = shape)

    private class Pts {
        var a = FloatArray(96); var n = 0
        fun add(x: Float, y: Float) {
            if (n + 3 > a.size) a = a.copyOf(a.size * 2)
            a[n++] = x; a[n++] = y; a[n++] = -1f
        }
        fun out() = a.copyOf(n)
    }

    fun points(shape: String, x0: Float, y0: Float, x1: Float, y1: Float, width: Float): FloatArray {
        val p = Pts()
        if (isLine(shape)) {
            p.add(x0, y0); p.add(x1, y1)
            val len = hypot(x1 - x0, y1 - y0)
            if (len < 1e-3f) return p.out()
            val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
            val hl = min(max(10f, width * 4.5f), len * 0.4f)
            val ca = cos(0.5); val sa = sin(0.5)        // ≈ 28.6°
            fun head(tx: Float, ty: Float, dx: Float, dy: Float) {
                // wings: the direction back from the tip, rotated ±28.6°
                val bx = -dx; val by = -dy
                val w1x = (bx * ca - by * sa).toFloat(); val w1y = (bx * sa + by * ca).toFloat()
                val w2x = (bx * ca + by * sa).toFloat(); val w2y = (-bx * sa + by * ca).toFloat()
                p.add(tx + w1x * hl, ty + w1y * hl); p.add(tx, ty); p.add(tx + w2x * hl, ty + w2y * hl)
            }
            when (base(shape)) {
                ARROW -> head(x1, y1, ux, uy)
                DARROW -> { head(x1, y1, ux, uy); p.add(x1, y1); p.add(x0, y0); head(x0, y0, -ux, -uy) }
            }
            return p.out()
        }
        val l = min(x0, x1); val r = max(x0, x1); val t = min(y0, y1); val b = max(y0, y1)
        val w = r - l; val h = b - t
        when (base(shape)) {
            RECT -> { p.add(l, t); p.add(r, t); p.add(r, b); p.add(l, b); p.add(l, t) }
            ROUND -> roundRect(p, l, t, r, b, min(w, h) * 0.18f, -1f, 0f, 0f)
            ELLIPSE -> {
                val cx = (l + r) / 2f; val cy = (t + b) / 2f
                for (k in 0..72) {
                    val a = -PI / 2 + k * 2 * PI / 72
                    p.add(cx + (cos(a) * w / 2).toFloat(), cy + (sin(a) * h / 2).toFloat())
                }
            }
            TRIANGLE -> { val cx = (l + r) / 2f; p.add(cx, t); p.add(r, b); p.add(l, b); p.add(cx, t) }
            STAR -> {
                // unit star spans x ±0.951, y −1 … 0.809 → stretched to the box
                for (k in 0..10) {
                    val a = -PI / 2 + k * PI / 5
                    val rr = if (k % 2 == 0) 1.0 else 0.42
                    val ux = (cos(a) * rr).toFloat(); val uy = (sin(a) * rr).toFloat()
                    p.add(l + (ux + 0.951f) / 1.902f * w, t + (uy + 1f) / 1.809f * h)
                }
            }
            BUBBLE -> {
                val bb = t + h * 0.78f
                roundRect(p, l, t, r, bb, min(w, bb - t) * 0.18f, l + w * 0.42f, l + w * 0.12f, b, tail2 = l + w * 0.24f)
            }
            TABLE -> {
                val (rows, cols) = tableDims(shape) ?: (3 to 3)
                p.add(l, t); p.add(r, t); p.add(r, b); p.add(l, b); p.add(l, t)
                // inner horizontal lines, zig-zag along the side edges (retraced)
                var atLeft = true
                for (i in 1 until rows) {
                    val y = t + h * i / rows
                    if (atLeft) { p.add(l, y); p.add(r, y) } else { p.add(r, y); p.add(l, y) }
                    atLeft = !atLeft
                }
                // to the bottom corner on the current side, then verticals zig-zag along top / bottom
                val sx = if (atLeft) l else r
                p.add(sx, b)
                var atBottom = true
                for (j in 1 until cols) {
                    val x = if (atLeft) l + w * j / cols else r - w * j / cols
                    if (atBottom) { p.add(x, b); p.add(x, t) } else { p.add(x, t); p.add(x, b) }
                    atBottom = !atBottom
                }
            }
            else -> { p.add(l, t); p.add(r, t); p.add(r, b); p.add(l, b); p.add(l, t) }
        }
        return p.out()
    }

    /**
     * Rounded rectangle, clockwise from the top-left corner's end. When [tailA] ≥ 0 the bottom edge gets a speech tail:
     * … → ([tailA], b) → tip ([tailTipX], [tailTipY]) → ([tail2], b) → …
     */
    private fun roundRect(p: Pts, l: Float, t: Float, r: Float, b: Float, rad0: Float, tailA: Float, tailTipX: Float, tailTipY: Float, tail2: Float = 0f) {
        val rad = max(0f, rad0)
        fun arc(cx: Float, cy: Float, from: Double) {
            for (k in 0..6) { val a = from + k * (PI / 2) / 6; p.add(cx + (cos(a) * rad).toFloat(), cy + (sin(a) * rad).toFloat()) }
        }
        arc(l + rad, t + rad, PI)                 // top-left
        arc(r - rad, t + rad, -PI / 2)            // top-right
        arc(r - rad, b - rad, 0.0)                // bottom-right
        if (tailA >= 0f) { p.add(tailA, b); p.add(tailTipX, tailTipY); p.add(tail2, b) }
        arc(l + rad, b - rad, PI / 2)             // bottom-left
        p.add(l, t + rad)
    }

    /** Raw box of the stroke's points (no pen-width padding). */
    fun boxOf(s: Stroke): RectF {
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i + 1 < s.pts.size) { r.union(s.pts[i], s.pts[i + 1]); i += 3 }
        return r
    }

    /** Rebuilt with a new box / new end points (handles). */
    fun rebuilt(s: Stroke, x0: Float, y0: Float, x1: Float, y1: Float): Stroke =
        Stroke(Tool.SHAPE, s.color, s.width, points(s.shape, x0, y0, x1, y1, s.width), s.rec, s.t, s.style, s.shape)

    /** Cell (page coords) of a table stroke under ([x], [y]), or null. */
    fun cellAt(s: Stroke, x: Float, y: Float): RectF? {
        val (rows, cols) = tableDims(s.shape) ?: return null
        val b = boxOf(s)
        if (!b.contains(x, y)) return null
        val ci = ((x - b.left) / b.width() * cols).toInt().coerceIn(0, cols - 1)
        val ri = ((y - b.top) / b.height() * rows).toInt().coerceIn(0, rows - 1)
        val cw = b.width() / cols; val ch = b.height() / rows
        return RectF(b.left + ci * cw, b.top + ri * ch, b.left + (ci + 1) * cw, b.top + (ri + 1) * ch)
    }

    /** True when the stroke's first two points look like an upright box start (handles regenerate an upright box). */
    fun upright(s: Stroke): Boolean {
        if (!isBox(s.shape) || s.pts.size < 6) return false
        val b = boxOf(s)
        return b.width() > 0.5f && b.height() > 0.5f
    }

    internal fun angle(dx: Float, dy: Float) = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    internal fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}

/** Small preview of a shape (same points as the real one). */
@Composable
private fun ShapePreview(shape: String, selected: Boolean, onClick: () -> Unit) {
    val c = D.c
    val label = stringResource(InkShapes.labelOf(shape))
    val pts = remember(shape) {
        val s = if (InkShapes.base(shape) == InkShapes.TABLE) InkShapes.table(3, 3) else shape
        if (InkShapes.isLine(s)) InkShapes.points(s, 6f, 34f, 50f, 10f, 3f) else InkShapes.points(s, 6f, 10f, 50f, 46f, 3f)
    }
    Column(
        Modifier.padding(4.dp).width(76.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surfaceAlt)
            .clickable(onClick = onClick).padding(vertical = 8.dp).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val col = c.ink
        Canvas(Modifier.size(44.dp)) {
            val k = size.width / 56f
            val path = Path()
            var i = 0
            while (i + 1 < pts.size) {
                val o = Offset(pts[i] * k, pts[i + 1] * k)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                i += 3
            }
            drawPath(path, col, style = DrawStroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
    }
}

/** Shape tray (Insert → Shapes). A table asks for rows × columns first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShapeTray(onDismiss: () -> Unit, current: String? = null, withAuto: Boolean = false, onPick: (String) -> Unit) {
    val c = D.c
    var tableMode by remember { mutableStateOf(current != null && InkShapes.base(current) == InkShapes.TABLE) }
    val dims = current?.let { InkShapes.tableDims(it) }
    var rows by remember { mutableIntStateOf(dims?.first ?: 3) }
    var cols by remember { mutableIntStateOf(dims?.second ?: 3) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().background(c.surface, RoundedCornerShape(24.dp))
                .border(1.dp, c.line, RoundedCornerShape(24.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ink5_shapes), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.muted) }
            }
            FlowRow {
                InkShapes.all.forEach { s ->
                    val on = if (s == InkShapes.TABLE) tableMode else current == s
                    ShapePreview(s, on) {
                        if (s == InkShapes.TABLE) tableMode = true else onPick(s)
                    }
                }
                if (withAuto) {
                    // Auto: draw freehand, the shape is recognized
                    val label = stringResource(R.string.ink5_shape_auto)
                    Column(
                        Modifier.padding(4.dp).width(160.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (current == "") c.accent.copy(alpha = 0.12f) else c.surfaceAlt)
                            .clickable { onPick("") }.padding(8.dp).semantics { contentDescription = label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.Gesture, null, tint = c.ink, modifier = Modifier.size(44.dp).padding(6.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 2)
                    }
                }
            }
            if (withAuto) Text(stringResource(R.string.ink5_shape_hint), style = MaterialTheme.typography.bodySmall, color = c.muted,
                modifier = Modifier.padding(4.dp))
            if (tableMode) {
                Spacer(Modifier.height(8.dp))
                @Composable
                fun Stepper(label: Int, v: Int, range: IntRange, set: (Int) -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label), color = c.ink, modifier = Modifier.width(96.dp))
                        IconButton(onClick = { set((v - 1).coerceIn(range)) }) { Icon(Icons.Rounded.Remove, null, tint = c.ink) }
                        Text("$v", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
                        IconButton(onClick = { set((v + 1).coerceIn(range)) }) { Icon(Icons.Rounded.Add, null, tint = c.ink) }
                    }
                }
                Stepper(R.string.ink5_rows, rows, 1..20) { rows = it }
                Stepper(R.string.ink5_cols, cols, 1..12) { cols = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onPick(InkShapes.table(rows, cols)) }) { Text(stringResource(R.string.ink_insert), color = c.accent) }
                }
            }
        }
    }
}
