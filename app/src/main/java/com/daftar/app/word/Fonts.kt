package com.daftar.app.word

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat
import com.daftar.app.R

/**
 * Font families a document run can use (keys of [RunFmt.font]) for the reader, the print layout, the PDF and the editor.
 * The bundled Arabic faces (Cairo, Amiri, PakType Tehreer) are loaded from res/font; [init] gives the print/PDF engine
 * (which has no Context) access to them — WordScreen calls it before laying out pages.
 */
internal object WordFonts {
    @Volatile private var app: Context? = null
    private val faces = HashMap<String, Typeface>()

    fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }

    /** Keys offered by the editor's font picker, in order (null = default sans). */
    val keys: List<String?> = listOf(null, "serif", "mono", "cairo", "amiri", "tehreer")

    /** Font name written into w:rFonts for a key. */
    fun docxName(key: String?): String = when (key) {
        "serif" -> "Times New Roman"
        "mono" -> "Courier New"
        "cairo" -> "Cairo"
        "amiri" -> "Amiri"
        "tehreer" -> "PakType Tehreer"
        else -> "Arial"
    }

    /** Label shown in the picker (font names are not translated; Arabic faces carry their Arabic name too). */
    fun label(key: String?): String = when (key) {
        "serif" -> "Serif"
        "mono" -> "Mono"
        "cairo" -> "Cairo القاهرة"
        "amiri" -> "Amiri أميري"
        "tehreer" -> "Tehreer تحرير"
        else -> "Sans"
    }

    private fun resId(key: String): Int? = when (key) {
        "cairo" -> R.font.cairo
        "amiri" -> R.font.amiri
        "tehreer" -> R.font.tehreer
        else -> null
    }

    fun typeface(key: String?): Typeface? = when (key) {
        null -> null
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        else -> synchronized(faces) {
            faces[key] ?: run {
                val ctx = app ?: return null
                val id = resId(key) ?: return null
                runCatching { ResourcesCompat.getFont(ctx, id) }.getOrNull()?.also { faces[key] = it }
            }
        }
    }

    private val cairo by lazy { FontFamily(Font(R.font.cairo)) }
    private val amiri by lazy { FontFamily(Font(R.font.amiri)) }
    private val tehreer by lazy { FontFamily(Font(R.font.tehreer)) }

    fun family(key: String?): FontFamily? = when (key) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "cairo" -> cairo
        "amiri" -> amiri
        "tehreer" -> tehreer
        else -> null
    }
}

/** Sets a typeface while keeping the bold/italic already applied (synthesised when the face lacks them). */
internal class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
    private fun apply(tp: TextPaint) {
        val style = tp.typeface?.style ?: 0
        val tf = Typeface.create(face, style)
        val fake = style and tf.style.inv()
        if (fake and Typeface.BOLD != 0) tp.isFakeBoldText = true
        if (fake and Typeface.ITALIC != 0) tp.textSkewX = -0.25f
        tp.typeface = tf
    }
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    override fun updateDrawState(tp: TextPaint) = apply(tp)
}
