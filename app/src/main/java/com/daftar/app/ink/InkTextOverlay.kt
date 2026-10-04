package com.daftar.app.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AColor
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.theme.D
import kotlin.math.abs

/** What the Compose layer needs to show the format bar for the selected / edited text box. */
internal class TextBoxUi(val page: Int, val item: TextItem, val editing: Boolean)

/**
 * The on-canvas text editor: a native [EditText] that sits in the same [FrameLayout] as the [InkView] and is placed
 * exactly over the text box being edited. It uses the same Typeface, size (page points × zoom), wrap width, no font
 * padding, simple line breaking and first-strong text direction as the `StaticLayout` that draws committed text, so
 * typing looks like the final ink-layer text (Arabic / RTL included). The view only gets new translation values while
 * scrolling (no layout pass); size and width are re-applied when the zoom or the box changes.
 */
@SuppressLint("ViewConstructor")
internal class TextOverlay(ctx: Context, private val host: InkView) {

    /** Notified on every text / caret change (the host keeps the caret above the keyboard and redraws the frame). */
    var onEdited: (() -> Unit)? = null

    val edit: EditText = object : EditText(ctx) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onEdited?.let { post(it) }
        }
    }.apply {
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        if (Build.VERSION.SDK_INT >= 28) isFallbackLineSpacing = false
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        // never the full-screen extract editor in landscape: the user must see the page while typing
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        minHeight = 0; minimumHeight = 0
        highlightColor = 0x553B82F6
        setHintTextColor(0x886B7280.toInt())
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { onEdited?.let { post(it) } }
        })
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> host.invalidate() }
    }

    val showing get() = edit.visibility == View.VISIBLE
    private var item: TextItem? = null
    private var appliedScale = -1f
    private var appliedKey: Any? = null

    /** Shows the editor for [t] (its text becomes the editable content) and opens the keyboard. */
    fun show(t: TextItem, hint: String) {
        item = t
        appliedKey = null
        edit.hint = hint
        edit.setText(t.text)
        edit.setSelection(edit.text.length)
        apply(t, force = true)
        edit.visibility = View.VISIBLE
        edit.requestFocus()
        edit.post {
            edit.requestFocus()
            (edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Applies new formatting (font, size, colour, width, alignment) without touching the typed text. */
    fun apply(t: TextItem, force: Boolean = false) {
        item = t
        val key = listOf(t.font, t.bold, t.size, t.color, t.w, t.align)
        val s = host.zoomScale
        if (!force && key == appliedKey && s == appliedScale) return
        appliedKey = key; appliedScale = s
        edit.typeface = InkRender.typeface(t.font, t.bold)
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.size * s)
        edit.setTextColor(if (AColor.alpha(t.color) == 0) t.color or 0xFF000000.toInt() else t.color)
        edit.gravity = Gravity.TOP or when (t.align) {
            TextItem.ALIGN_CENTER -> Gravity.CENTER_HORIZONTAL
            TextItem.ALIGN_END -> Gravity.END
            else -> Gravity.START
        }
        val lp = edit.layoutParams as FrameLayout.LayoutParams
        val w = (t.w.coerceAtLeast(20f) * s).toInt().coerceAtLeast(8)
        if (lp.width != w) { lp.width = w; edit.layoutParams = lp }
    }

    /** Places the editor at screen position ([x], [y]) px; re-applies size when the zoom changed. */
    fun place(x: Float, y: Float) {
        val t = item ?: return
        if (host.zoomScale != appliedScale) apply(t)
        if (edit.translationX != x) edit.translationX = x
        if (edit.translationY != y) edit.translationY = y
    }

    /** Height of the edited text in page points (at least one line). */
    fun heightPt(): Float {
        val t = item ?: return 0f
        val s = host.zoomScale
        val h = edit.layout?.height ?: 0
        return if (h > 0 && s > 0f) h / s else t.size * 1.35f
    }

    /** Caret line (top, bottom) in px relative to the editor's top, or null before the first layout. */
    fun caretLine(): Pair<Float, Float>? {
        val l = edit.layout ?: return null
        val off = edit.selectionEnd.coerceIn(0, edit.text.length)
        val line = l.getLineForOffset(off)
        return l.getLineTop(line).toFloat() to l.getLineBottom(line).toFloat()
    }

    fun text(): String = edit.text.toString()

    fun hide() {
        if (!showing) return
        (edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edit.windowToken, 0)
        edit.clearFocus()
        edit.visibility = View.GONE
        item = null
    }
}

// =====================================================================================
// Format bar & long-press menu
// =====================================================================================

private val SizeSteps = listOf(6f, 8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 22f, 24f, 28f, 32f, 36f, 40f, 48f, 56f, 64f, 72f, 96f, 120f, 160f, 200f)

private fun nextSize(cur: Float, up: Boolean): Float =
    if (up) SizeSteps.firstOrNull { it > cur + 0.01f } ?: (cur * 1.25f).coerceAtMost(400f)
    else SizeSteps.lastOrNull { it < cur - 0.01f } ?: (cur / 1.25f).coerceAtLeast(4f)

private fun fmtSize(s: Float) = if (abs(s - s.toInt()) < 0.05f) s.toInt().toString() else "%.1f".format(s)

/**
 * Small floating bar for the selected / edited text box: font, size −/+ with value, bold, colour, alignment, duplicate,
 * delete, done. Every change goes through [onFormat] (applied live to the box; one undo step per change).
 */
@Composable
internal fun TextFormatBar(
    modifier: Modifier,
    ui: TextBoxUi,
    onFormat: ((TextItem) -> TextItem) -> Unit,
    onMoreColors: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val c = D.c
    val t = ui.item
    var fonts by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    Row(
        modifier.widthIn(max = 720.dp).background(c.surface, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceAlt).clickable { fonts = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(FontLabels[t.font] ?: t.font, fontFamily = fontFamilyOf(t.font), color = c.ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 130.dp))
                Icon(Icons.Rounded.ExpandMore, stringResource(R.string.ink_font), tint = c.muted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(fonts, { fonts = false }) {
                InkRender.fonts.forEach { f ->
                    DropdownMenuItem(
                        { Text(FontLabels[f] ?: f, fontFamily = fontFamilyOf(f), color = if (f == t.font) c.accent else c.ink) },
                        { fonts = false; onFormat { it.copy(font = f) } },
                        trailingIcon = if (f == t.font) ({ Icon(Icons.Rounded.Check, null, tint = c.accent) }) else null,
                    )
                }
            }
        }
        IconButton(onClick = { onFormat { it.copy(size = nextSize(it.size, false)) } }) {
            Icon(Icons.Rounded.Remove, stringResource(R.string.ink_text_smaller), tint = c.ink)
        }
        Text(fmtSize(t.size), color = c.ink, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            modifier = Modifier.widthIn(min = 28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onFormat { it.copy(size = nextSize(it.size, true)) } }) {
            Icon(Icons.Rounded.Add, stringResource(R.string.ink_text_bigger), tint = c.ink)
        }
        Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(c.line))
        IconButton(onClick = { onFormat { it.copy(bold = !it.bold) } }) {
            Icon(Icons.Rounded.FormatBold, stringResource(R.string.ink_bold), tint = if (t.bold) c.accent else c.ink,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (t.bold) c.accent.copy(alpha = 0.12f) else Color.Transparent).padding(2.dp))
        }
        Box {
            IconButton(onClick = { colors = true }) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color(t.color or 0xFF000000.toInt())).border(1.dp, c.line, CircleShape))
            }
            DropdownMenu(colors, { colors = false }) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PenColors.forEach { col ->
                        Box(Modifier.padding(3.dp).size(30.dp).clip(CircleShape).border(2.dp, if (col == t.color) c.accent else Color.Transparent, CircleShape)
                            .clickable { colors = false; onFormat { it.copy(color = col) } }.padding(3.dp).clip(CircleShape).background(Color(col)))
                    }
                    IconButton(onClick = { colors = false; onMoreColors() }) { Icon(Icons.Rounded.Palette, stringResource(R.string.ink_more_colors), tint = c.ink) }
                }
            }
        }
        val alignIcon = when (t.align) {
            TextItem.ALIGN_CENTER -> Icons.Rounded.FormatAlignCenter
            TextItem.ALIGN_END -> Icons.AutoMirrored.Rounded.FormatAlignRight
            else -> Icons.AutoMirrored.Rounded.FormatAlignLeft
        }
        val alignLabel = when (t.align) {
            TextItem.ALIGN_CENTER -> R.string.ink_align_center
            TextItem.ALIGN_END -> R.string.ink_align_end
            else -> R.string.ink_align_start
        }
        IconButton(onClick = { onFormat { it.copy(align = (it.align + 1) % 3) } }) { Icon(alignIcon, stringResource(alignLabel), tint = c.ink) }
        Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(c.line))
        IconButton(onClick = onDuplicate) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.duplicate), tint = c.ink) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = c.danger) }
        IconButton(onClick = onDone) { Icon(Icons.Rounded.Check, stringResource(R.string.done), tint = c.accent) }
    }
}

/** Long-press menu of a text box, anchored at the press position ([x], [y] px inside the canvas box). */
@Composable
internal fun TextBoxMenu(x: Float, y: Float, onDismiss: () -> Unit, onEdit: () -> Unit, onEditDialog: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val c = D.c
    val dens = LocalDensity.current
    Box(Modifier.offset(x = with(dens) { x.toDp() }, y = with(dens) { y.toDp() }).size(1.dp)) {
        DropdownMenu(true, onDismiss, offset = DpOffset(0.dp, 0.dp)) {
            DropdownMenuItem({ Text(stringResource(R.string.ink_text_edit_here)) }, onEdit, leadingIcon = { Icon(Icons.Rounded.EditNote, null, tint = c.muted) })
            DropdownMenuItem({ Text(stringResource(R.string.ink_text_edit_dialog)) }, onEditDialog, leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = c.muted) })
            DropdownMenuItem({ Text(stringResource(R.string.duplicate)) }, onDuplicate, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = c.muted) })
            DropdownMenuItem({ Text(stringResource(R.string.delete), color = c.danger) }, onDelete, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.danger) })
        }
    }
}
