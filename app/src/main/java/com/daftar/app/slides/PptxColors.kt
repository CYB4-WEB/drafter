package com.daftar.app.slides

import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/** Theme colour scheme + the master's (or slide override's) colour mapping (bg1 -> lt1, tx1 -> dk1 ...). */
class ColorCtx(private val scheme: Map<String, Int>, private val map: Map<String, String>) {
    fun scheme(name: String, ph: Int?): Int {
        if (name == "phClr") return ph ?: 0xFF000000.toInt()
        val key = map[name] ?: DEFAULT_MAP[name] ?: name
        return scheme[key] ?: scheme[name] ?: DEFAULT_SCHEME[key] ?: 0xFF000000.toInt()
    }

    fun withOverride(ovr: XNode?): ColorCtx {
        val m = ovr?.child("overrideClrMapping") ?: return this
        val nm = HashMap(map)
        for (k in CLR_MAP_KEYS) m.attr(k)?.let { nm[k] = it }
        return ColorCtx(scheme, nm)
    }

    companion object {
        val DEFAULT_MAP = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
    }
}

private val COLOR_ELEMENTS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")

/** Colour without scheme context (theme definitions): srgbClr / sysClr lastClr. */
fun simpleColor(n: XNode): Int? = when (n.name) {
    "srgbClr" -> hex(n.attr("val"))
    "sysClr" -> hex(n.attr("lastClr")) ?: if (n.attr("val") == "window") 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    else -> null
}?.let { applyMods(it, n) }

private fun hex(s: String?): Int? = s?.trim()?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }

/** Resolve a colour element (srgbClr, schemeClr ...) including its modifiers. */
fun colorOf(n: XNode, cc: ColorCtx, ph: Int?): Int? {
    val base: Int = when (n.name) {
        "srgbClr" -> hex(n.attr("val"))
        "schemeClr" -> n.attr("val")?.let { cc.scheme(it, ph) }
        "sysClr" -> hex(n.attr("lastClr")) ?: if (n.attr("val") == "window") 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        "prstClr" -> PRESET_COLORS[n.attr("val")?.lowercase()] ?: 0xFF000000.toInt()
        "scrgbClr" -> {
            fun ch(a: String) = ((n.int(a) ?: 0) / 100000f * 255f).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch("r") shl 16) or (ch("g") shl 8) or ch("b")
        }
        "hslClr" -> {
            val hsl = floatArrayOf((n.int("hue") ?: 0) / 60000f, (n.int("sat") ?: 0) / 100000f, (n.int("lum") ?: 0) / 100000f)
            ColorUtils.HSLToColor(hsl)
        }
        else -> null
    } ?: return null
    return applyMods(base, n)
}

/** First colour child of a container (solidFill, buClr, fgClr, fillRef ...). */
fun colorIn(container: XNode?, cc: ColorCtx, ph: Int?): Int? {
    container ?: return null
    for (c in container.children) if (c.name in COLOR_ELEMENTS) return colorOf(c, cc, ph)
    return null
}

private fun applyMods(color: Int, n: XNode): Int {
    if (n.children.isEmpty()) return color
    var a = (color ushr 24) / 255f
    var r = (color shr 16) and 0xFF
    var g = (color shr 8) and 0xFF
    var b = color and 0xFF
    val hsl = FloatArray(3)
    for (m in n.children) {
        val v = (m.int("val") ?: continue) / 100000f
        when (m.name) {
            "lumMod", "lumOff", "satMod", "satOff", "hueOff", "hueMod" -> {
                ColorUtils.RGBToHSL(r, g, b, hsl)
                when (m.name) {
                    "lumMod" -> hsl[2] = (hsl[2] * v).coerceIn(0f, 1f)
                    "lumOff" -> hsl[2] = (hsl[2] + v).coerceIn(0f, 1f)
                    "satMod" -> hsl[1] = (hsl[1] * v).coerceIn(0f, 1f)
                    "satOff" -> hsl[1] = (hsl[1] + v).coerceIn(0f, 1f)
                    "hueOff" -> hsl[0] = ((hsl[0] + (m.int("val") ?: 0) / 60000f) % 360f + 360f) % 360f
                    "hueMod" -> hsl[0] = (hsl[0] * v) % 360f
                }
                val c = ColorUtils.HSLToColor(hsl)
                r = (c shr 16) and 0xFF; g = (c shr 8) and 0xFF; b = c and 0xFF
            }
            "tint" -> {
                r = (255 - (255 - r) * v).roundToInt().coerceIn(0, 255)
                g = (255 - (255 - g) * v).roundToInt().coerceIn(0, 255)
                b = (255 - (255 - b) * v).roundToInt().coerceIn(0, 255)
            }
            "shade" -> {
                r = (r * v).roundToInt().coerceIn(0, 255)
                g = (g * v).roundToInt().coerceIn(0, 255)
                b = (b * v).roundToInt().coerceIn(0, 255)
            }
            "alpha" -> a = v.coerceIn(0f, 1f)
            "alphaMod" -> a = (a * v).coerceIn(0f, 1f)
            "alphaOff" -> a = (a + v).coerceIn(0f, 1f)
        }
    }
    for (m in n.children) when (m.name) {
        "gray" -> { val l = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt(); r = l; g = l; b = l }
        "inv" -> { r = 255 - r; g = 255 - g; b = 255 - b }
    }
    return ((a * 255).roundToInt() shl 24) or (r shl 16) or (g shl 8) or b
}

private val PRESET_COLORS = mapOf(
    "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(), "green" to 0xFF008000.toInt(),
    "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(), "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
    "orange" to 0xFFFFA500.toInt(), "purple" to 0xFF800080.toInt(), "navy" to 0xFF000080.toInt(), "silver" to 0xFFC0C0C0.toInt(),
    "maroon" to 0xFF800000.toInt(), "teal" to 0xFF008080.toInt(), "lime" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(),
    "magenta" to 0xFFFF00FF.toInt(), "darkgray" to 0xFFA9A9A9.toInt(), "lightgray" to 0xFFD3D3D3.toInt(),
    "darkblue" to 0xFF00008B.toInt(), "darkred" to 0xFF8B0000.toInt(), "darkgreen" to 0xFF006400.toInt(),
)

/** A resolved fill. Image fills carry the zip entry name of their media. */
sealed class Fill {
    data object None : Fill()
    data class Solid(val color: Int) : Fill()
    class Grad(val colors: IntArray, val pos: FloatArray, val angleDeg: Float, val radial: Boolean) : Fill()
    class Image(val media: String, val srcRect: XNode?, val alpha: Float, val tile: Boolean) : Fill()
}

private val FILL_ELEMENTS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")

/** Find and resolve a fill child of [container] (spPr, bgPr, tcPr, a fill style entry...). Null = not specified. */
fun fillIn(container: XNode?, part: Part?, cc: ColorCtx, ph: Int?, groupFill: Fill?): Fill? {
    container ?: return null
    for (c in container.children) if (c.name in FILL_ELEMENTS) return fillOf(c, part, cc, ph, groupFill)
    return null
}

fun fillOf(n: XNode, part: Part?, cc: ColorCtx, ph: Int?, groupFill: Fill?): Fill? = when (n.name) {
    "noFill" -> Fill.None
    "solidFill" -> colorIn(n, cc, ph)?.let { Fill.Solid(it) } ?: Fill.None
    "pattFill" -> (colorIn(n.child("fgClr"), cc, ph) ?: colorIn(n.child("bgClr"), cc, ph))?.let { Fill.Solid(it) } ?: Fill.None
    "grpFill" -> groupFill ?: Fill.None
    "gradFill" -> {
        val stops = n.child("gsLst")?.children("gs")
            ?.mapNotNull { gs -> colorIn(gs, cc, ph)?.let { (gs.int("pos") ?: 0) / 100000f to it } }
            ?.sortedBy { it.first } ?: emptyList()
        when {
            stops.isEmpty() -> Fill.None
            stops.size == 1 -> Fill.Solid(stops[0].second)
            else -> Fill.Grad(
                stops.map { it.second }.toIntArray(), stops.map { it.first.coerceIn(0f, 1f) }.toFloatArray(),
                (n.child("lin")?.int("ang") ?: 5400000) / 60000f, n.child("path") != null,
            )
        }
    }
    "blipFill" -> {
        val blip = n.child("blip")
        val target = part?.rel(blip?.attr("r:embed"))?.target
        if (target == null) Fill.None else {
            val alpha = blip?.child("alphaModFix")?.int("amt")?.let { it / 100000f } ?: 1f
            Fill.Image(target, n.child("srcRect"), alpha, n.child("tile") != null)
        }
    }
    else -> null
}
