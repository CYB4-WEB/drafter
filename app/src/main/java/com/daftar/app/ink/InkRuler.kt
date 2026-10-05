package com.daftar.app.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * On-screen ruler / straightedge (Samsung Notes / GoodNotes style). It lives in **screen space** (stays where it was put
 * while the page is panned or zoomed) and is never saved. Ticks are in page units at the current zoom: centimetres and
 * millimetres on the top edge, inches (1/8") on the bottom edge, so it measures what is drawn. One finger on the body
 * moves it, two fingers move + rotate it (angle snaps to multiples of 15° — 0/15/30/45/…/90 — with a haptic tick).
 * Pen / highlighter strokes that start near a long edge are projected onto that edge (see [edgeNear], [project]).
 * All drawing objects are preallocated (no allocation per frame); label strings are cached.
 */
internal class InkRuler(private val density: Float) {
    var on = false
    var placed = false
    var cx = 0f; private set
    var cy = 0f; private set
    /** Angle in degrees (after snapping). */
    var angle = 0f; private set
    private var cosA = 1f
    private var sinA = 0f
    /** Length in px (set from the view size). */
    var length = 1200f; private set
    val thick get() = 72f * density

    fun place(w: Int, h: Int) {
        length = max(w, h) * 1.15f
        cx = w / 2f; cy = h * 0.42f
        setAngle(0f)
        placed = true
    }

    /** Keeps the ruler reachable after a resize (rotation, split). */
    fun fit(w: Int, h: Int) {
        if (!placed) return
        length = max(w, h) * 1.15f
        cx = cx.coerceIn(0f, w.toFloat()); cy = cy.coerceIn(0f, h.toFloat())
    }

    private fun setAngle(a: Float) {
        var v = a % 360f
        if (v < 0f) v += 360f
        angle = v
        val r = Math.toRadians(v.toDouble())
        cosA = cos(r).toFloat(); sinA = sin(r).toFloat()
    }

    fun localX(x: Float, y: Float) = (x - cx) * cosA + (y - cy) * sinA
    fun localY(x: Float, y: Float) = -(x - cx) * sinA + (y - cy) * cosA

    fun hitBody(x: Float, y: Float): Boolean =
        on && placed && abs(localX(x, y)) <= length / 2f && abs(localY(x, y)) <= thick / 2f

    /** −1 = near the top edge, +1 = near the bottom edge, 0 = neither (screen px point). */
    fun edgeNear(x: Float, y: Float): Int {
        if (!on || !placed) return 0
        if (abs(localX(x, y)) > length / 2f) return 0
        val ly = localY(x, y)
        val half = thick / 2f
        val out = 30f * density; val inside = 14f * density
        if (ly >= half - inside && ly <= half + out) return 1
        if (ly <= -half + inside && ly >= -half - out) return -1
        return 0
    }

    /** Position along the ruler (local x, px) of a screen point, clamped to the ruler. */
    fun along(x: Float, y: Float) = localX(x, y).coerceIn(-length / 2f, length / 2f)

    /** Screen point at local ([t], side·(thick/2 + [off])) → [out] (x, y). */
    fun project(t: Float, side: Int, off: Float, out: FloatArray) {
        val ly = side * (thick / 2f + off)
        out[0] = cx + t * cosA - ly * sinA
        out[1] = cy + t * sinA + ly * cosA
    }

    // ---- gestures ----
    private var gx = 0f; private var gy = 0f
    private var a0 = 0f; private var ang0 = 0f
    private var mx0 = 0f; private var my0 = 0f; private var cx0 = 0f; private var cy0 = 0f
    private var snapped = Float.NaN

    fun beginDrag(x: Float, y: Float) { gx = x; gy = y }
    fun drag(x: Float, y: Float) { cx += x - gx; cy += y - gy; gx = x; gy = y }

    fun beginTwo(x0: Float, y0: Float, x1: Float, y1: Float) {
        a0 = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        ang0 = angle
        mx0 = (x0 + x1) / 2f; my0 = (y0 + y1) / 2f; cx0 = cx; cy0 = cy
        snapped = Float.NaN
    }

    /** Two-finger move + rotate. Returns true when the angle just snapped to a new 15° step (haptic tick). */
    fun two(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val a = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        val raw = ang0 + (a - a0)
        val step = (raw / 15f).roundToInt() * 15f
        val snap = abs(raw - step) < SNAP_DEG
        val target = if (snap) step else raw
        setAngle(target)
        // rotate the start centre around the start midpoint by the applied delta, then follow the midpoint
        val d = Math.toRadians((target - ang0).toDouble())
        val c = cos(d).toFloat(); val s = sin(d).toFloat()
        val rx = cx0 - mx0; val ry = cy0 - my0
        val mx = (x0 + x1) / 2f; val my = (y0 + y1) / 2f
        cx = mx + rx * c - ry * s; cy = my + rx * s + ry * c
        val tick = snap && (snapped.isNaN() || snapped != step)
        snapped = if (snap) step else Float.NaN
        return tick
    }

    // ---- drawing ----
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lines = FloatArray(1024)
    private val numbers = arrayOfNulls<String>(1000)
    private val degrees = arrayOfNulls<String>(181)
    private fun num(i: Int): String = if (i in 0..999) numbers[i] ?: i.toString().also { numbers[i] = it } else i.toString()

    /** [pxPerPt] = current zoom (screen px per page point); [dark] = night paper / dark page. */
    fun draw(c: Canvas, pxPerPt: Float, dark: Boolean) {
        if (!on || !placed) return
        val half = thick / 2f; val hl = length / 2f
        c.save()
        c.translate(cx, cy); c.rotate(angle)
        body.color = if (dark) 0xB82A2E35.toInt() else 0xB8F4F3EF.toInt()
        rect.set(-hl, -half, hl, half)
        val rad = 8f * density
        c.drawRoundRect(rect, rad, rad, body)
        edge.color = if (dark) 0xCC6B7280.toInt() else 0xCC9CA3AF.toInt(); edge.strokeWidth = 1f * density
        c.drawRoundRect(rect, rad, rad, edge)
        val ink = if (dark) 0xFFE8EAED.toInt() else 0xFF1F2937.toInt()
        tick.color = ink; label.color = ink
        tick.strokeWidth = max(1f, 0.9f * density)
        label.textSize = 10f * density
        val x0 = -hl + 12f * density
        val span = length - 24f * density
        // top edge: cm / mm
        scale(c, x0, span, pxPerPt * 72f / 25.4f, 10, -half, 1f, CM_LENS, 5)
        // bottom edge: inches in 1/8
        scale(c, x0, span, pxPerPt * 72f / 8f, 8, half, -1f, IN_LENS, 4)
        // live angle in a pill at the centre (0…180°)
        var shown = angle % 180f
        if (shown < 0f) shown += 180f
        val deg = shown.roundToInt() % 180
        val s = degrees[deg] ?: "$deg°".also { degrees[deg] = it }
        label.textSize = 12f * density
        val tw = label.measureText(s)
        rect.set(-tw / 2f - 8f * density, -10f * density, tw / 2f + 8f * density, 10f * density)
        pill.color = if (dark) 0xFF1C1F24.toInt() else 0xFFFFFFFF.toInt()
        c.drawRoundRect(rect, 10f * density, 10f * density, pill)
        c.drawRoundRect(rect, 10f * density, 10f * density, edge)
        label.color = if (snapped.isNaN()) ink else 0xFF3B82F6.toInt()
        c.drawText(s, 0f, -(label.ascent() + label.descent()) / 2f, label)
        c.restore()
    }

    /**
     * Ticks along one edge. [unitPx] = px per smallest unit, [per] = units per labelled major, [y] = edge, [dir] = +1
     * inward when the edge is the top one. [lens] = tick lengths in dp for unit / half / major (inch adds 1/4).
     */
    private fun scale(c: Canvas, x0: Float, span: Float, unitPx: Float, per: Int, y: Float, dir: Float, lens: IntArray, minLabelDp: Int) {
        if (unitPx <= 0f) return
        // level of detail: thin out units closer than 4 px
        var step = 1
        while (unitPx * step < 4f * density && step < per) step *= 2
        if (step > per) step = per
        var majorEvery = 1
        while (unitPx * per * majorEvery < (minLabelDp * 6f) * density) majorEvery *= 2
        val count = floor(span / unitPx).toInt()
        var n = 0
        var i = 0
        while (i <= count) {
            val major = i % per == 0
            if (major || i % step == 0) {
                val len = when {
                    major -> lens[lens.size - 1]
                    per == 8 && i % 4 == 0 -> lens[2]
                    per == 8 && i % 2 == 0 -> lens[1]
                    per == 10 && i % 5 == 0 -> lens[1]
                    else -> lens[0]
                } * density
                if (n + 4 > lines.size) lines = lines.copyOf(lines.size * 2)
                val x = x0 + i * unitPx
                lines[n++] = x; lines[n++] = y; lines[n++] = x; lines[n++] = y + dir * len
                if (major && (i / per) % majorEvery == 0) {
                    val ly = y + dir * (lens[lens.size - 1] * density + 3f * density) + (if (dir > 0) -label.ascent() else -label.descent())
                    label.textSize = 10f * density
                    c.drawText(num(i / per), x, ly, label)
                }
            }
            i++
            if (i > 20000) break
        }
        if (n > 0) c.drawLines(lines, 0, n, tick)
    }

    companion object {
        private const val SNAP_DEG = 2.5f
        private val CM_LENS = intArrayOf(5, 8, 13)
        private val IN_LENS = intArrayOf(4, 6, 9, 13)
    }
}
