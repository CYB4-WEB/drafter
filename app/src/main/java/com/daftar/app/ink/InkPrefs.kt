package com.daftar.app.ink

import android.content.Context
import android.content.SharedPreferences

/**
 * Last tool settings, remembered across sessions (own `ink_prefs` file so it never collides with app settings):
 * tool, pen style, pen colour, width per pen style, highlighter colour/width, eraser radius, tape colour/thickness.
 */
internal object InkPrefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("ink_prefs", Context.MODE_PRIVATE)
    }

    /** Default width per pen style (page points). */
    fun defaultPenWidth(style: Int) = when (style) {
        PenStyle.FOUNTAIN -> 2.6f
        PenStyle.PENCIL -> 2f
        PenStyle.BRUSH -> 5f
        PenStyle.MARKER -> 6f
        else -> 2.2f
    }

    private fun int(key: String, def: Int) = sp?.getInt(key, def) ?: def
    private fun float(key: String, def: Float) = sp?.getFloat(key, def) ?: def
    private fun put(key: String, v: Int) { sp?.edit()?.putInt(key, v)?.apply() }
    private fun put(key: String, v: Float) { sp?.edit()?.putFloat(key, v)?.apply() }

    var tool: Int
        get() = int("tool", Tool.PEN)
        set(v) = put("tool", v)
    var penStyle: Int
        get() = int("penStyle", PenStyle.BALL).let { if (it in PenStyle.all) it else PenStyle.BALL }
        set(v) = put("penStyle", v)
    var penColor: Int
        get() = int("penColor", 0xFF1F2937.toInt())
        set(v) = put("penColor", v)
    fun penWidth(style: Int) = float("penWidth_$style", defaultPenWidth(style))
    fun putPenWidth(style: Int, w: Float) = put("penWidth_$style", w)
    var hlColor: Int
        get() = int("hlColor", 0x66F2C94C)
        set(v) = put("hlColor", v)
    var hlWidth: Float
        get() = float("hlWidth", 16f)
        set(v) = put("hlWidth", v)
    var eraserRadius: Float
        get() = float("eraserRadius", 12f)
        set(v) = put("eraserRadius", v)
    var tapeColor: Int
        get() = int("tapeColor", InkRender.tapeColors[0])
        set(v) = put("tapeColor", v)
    var tapeWidth: Float
        get() = float("tapeWidth", 26f)
        set(v) = put("tapeWidth", v)
    /** Math helper: answers expressions ending with "=" (on by default). */
    var mathHelper: Boolean
        get() = sp?.getBoolean("mathHelper", true) ?: true
        set(v) { sp?.edit()?.putBoolean("mathHelper", v)?.apply() }
    /** Last text box formatting (used for new boxes). */
    var textFont: String
        get() = sp?.getString("textFont", "sans")?.takeIf { it in InkRender.fonts } ?: "sans"
        set(v) { sp?.edit()?.putString("textFont", v)?.apply() }
    var textSize: Float
        get() = float("textSize", 16f).coerceIn(4f, 400f)
        set(v) = put("textSize", v)
    var textBold: Boolean
        get() = sp?.getBoolean("textBold", false) ?: false
        set(v) { sp?.edit()?.putBoolean("textBold", v)?.apply() }
    /** Recently used stickers (kinds, newest first, at most 8). */
    val recentStickers: List<String>
        get() = sp?.getString("recentStickers", "")?.split(',')?.filter { it in InkStickers.all } ?: emptyList()
    fun useSticker(kind: String) {
        val l = (listOf(kind) + recentStickers.filter { it != kind }).take(8)
        sp?.edit()?.putString("recentStickers", l.joinToString(","))?.apply()
    }
    /** Two-page (book spread) view for paged notes and PDF / slides, in landscape on wide windows. */
    var twoPages: Boolean
        get() = sp?.getBoolean("twoPages", false) ?: false
        set(v) { sp?.edit()?.putBoolean("twoPages", v)?.apply() }
    /** Two-page view: the first page (cover) stands alone. */
    var coverAlone: Boolean
        get() = sp?.getBoolean("coverAlone", true) ?: true
        set(v) { sp?.edit()?.putBoolean("coverAlone", v)?.apply() }
    /** Night paper for notes (on-screen only). */
    var nightPaper: Boolean
        get() = sp?.getBoolean("nightPaper", false) ?: false
        set(v) { sp?.edit()?.putBoolean("nightPaper", v)?.apply() }
}
