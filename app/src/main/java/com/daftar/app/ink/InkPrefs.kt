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
}
