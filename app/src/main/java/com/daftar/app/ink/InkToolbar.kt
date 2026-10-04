package com.daftar.app.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Prefs
import com.daftar.app.ui.theme.D
import kotlin.math.PI
import kotlin.math.sin

internal val PenColors = listOf(
    0xFF1F2937, 0xFF3B82F6, 0xFFE5484D, 0xFF2F9E6E, 0xFF8B6CE0, 0xFFF59E42,
).map { it.toInt() }
internal val MoreColors = listOf(
    0xFF000000, 0xFF4B5563, 0xFF9CA3AF, 0xFF1E40AF, 0xFF0EA5E9, 0xFF14B8A6,
    0xFF15803D, 0xFF84CC16, 0xFFEAB308, 0xFFF97316, 0xFFB91C1C, 0xFFDB2777,
    0xFF7C3AED, 0xFF92400E, 0xFFFFFFFF,
).map { it.toInt() }
internal val HlColors = listOf(0x66F2C94C, 0x664CC38A, 0x6660A5FA, 0x66F472B6, 0x66F59E42).map { it.toInt() }

/** Toolbar state, observable from Compose and remembered across sessions through [InkPrefs]. */
internal class InkToolState {
    var tool by mutableIntStateOf(InkPrefs.tool.let { if (it == Tool.LASER) Tool.PEN else it })
        private set
    var penStyle by mutableIntStateOf(InkPrefs.penStyle)
        private set
    var penColor by mutableIntStateOf(InkPrefs.penColor)
        private set
    var penWidth by mutableFloatStateOf(InkPrefs.penWidth(InkPrefs.penStyle))
        private set
    var hlColor by mutableIntStateOf(InkPrefs.hlColor)
        private set
    var hlWidth by mutableFloatStateOf(InkPrefs.hlWidth)
        private set
    var eraserRadius by mutableFloatStateOf(InkPrefs.eraserRadius)
        private set
    var tapeColor by mutableIntStateOf(InkPrefs.tapeColor)
        private set
    var tapeWidth by mutableFloatStateOf(InkPrefs.tapeWidth)
        private set

    fun selectTool(t: Int) { tool = t; InkPrefs.tool = t }

    fun selectStyle(s: Int) {
        penStyle = s; penWidth = InkPrefs.penWidth(s); InkPrefs.penStyle = s
        if (tool != Tool.PEN) selectTool(Tool.PEN)
    }

    /** Colour for the current tool; picking a colour in a non-drawing tool switches back to the pen. */
    fun pickColor(col: Int) {
        when (tool) {
            Tool.HIGHLIGHTER -> { hlColor = (col and 0x00FFFFFF) or 0x66000000; InkPrefs.hlColor = hlColor }
            Tool.TAPE -> { tapeColor = col or 0xFF000000.toInt(); InkPrefs.tapeColor = tapeColor }
            else -> {
                penColor = col; InkPrefs.penColor = col
                if (tool == Tool.ERASER || tool == Tool.LASSO || tool == Tool.HAND || tool == Tool.LASER) selectTool(Tool.PEN)
            }
        }
    }

    val hasWidth get() = tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.ERASER || tool == Tool.TAPE || tool == Tool.SHAPE
    val hasColors get() = tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.TAPE || tool == Tool.SHAPE || tool == Tool.TEXT

    val width: Float get() = when (tool) {
        Tool.HIGHLIGHTER -> hlWidth
        Tool.ERASER -> eraserRadius
        Tool.TAPE -> tapeWidth
        else -> penWidth
    }

    fun setWidth(w: Float) {
        val v = (w * 10f).toInt() / 10f
        when (tool) {
            Tool.HIGHLIGHTER -> { hlWidth = v; InkPrefs.hlWidth = v }
            Tool.ERASER -> { eraserRadius = v; InkPrefs.eraserRadius = v }
            Tool.TAPE -> { tapeWidth = v; InkPrefs.tapeWidth = v }
            else -> { penWidth = v; InkPrefs.putPenWidth(penStyle, v) }
        }
    }

    val range: ClosedFloatingPointRange<Float> get() = when (tool) {
        Tool.HIGHLIGHTER -> 4f..48f
        Tool.ERASER -> 4f..48f
        Tool.TAPE -> 8f..80f
        else -> 0.5f..24f
    }

    val presets: List<Float> get() = when (tool) {
        Tool.HIGHLIGHTER -> listOf(10f, 16f, 24f)
        Tool.ERASER -> listOf(8f, 16f, 32f)
        Tool.TAPE -> listOf(16f, 28f, 44f)
        else -> when (penStyle) {
            PenStyle.BRUSH -> listOf(3f, 5f, 9f)
            PenStyle.MARKER -> listOf(4f, 6f, 10f)
            PenStyle.FOUNTAIN -> listOf(1.6f, 2.6f, 4.5f)
            else -> listOf(1.2f, 2.2f, 4f)
        }
    }

    val currentColor: Int get() = when (tool) {
        Tool.HIGHLIGHTER -> hlColor
        Tool.TAPE -> tapeColor
        else -> penColor
    }
}

internal fun penStyleIcon(s: Int): ImageVector = when (s) {
    PenStyle.FOUNTAIN -> Icons.Rounded.HistoryEdu
    PenStyle.PENCIL -> Icons.Rounded.Draw
    PenStyle.BRUSH -> Icons.Rounded.Brush
    PenStyle.MARKER -> Icons.Rounded.FormatPaint
    else -> Icons.Rounded.Edit
}

internal fun penStyleName(s: Int): Int = when (s) {
    PenStyle.FOUNTAIN -> R.string.ink_pen_fountain
    PenStyle.PENCIL -> R.string.ink_pen_pencil
    PenStyle.BRUSH -> R.string.ink_pen_brush
    PenStyle.MARKER -> R.string.ink_pen_marker
    else -> R.string.ink_pen_ball
}

private fun fmtWidth(w: Float) = if (w < 10f) "%.1f".format(w) else w.toInt().toString()

/**
 * Editor toolbar in three groups: tools | style + colours + size bar | insert.
 * One row on wide screens; on narrow screens / split panes two rows (tools + insert, then style), each scrolls horizontally.
 */
@Composable
internal fun InkToolbar(
    st: InkToolState,
    showAddPage: Boolean,
    onToolChanged: () -> Unit,
    onMoreColors: () -> Unit,
    onImage: () -> Unit,
    onLink: () -> Unit,
    onDictate: () -> Unit,
    onAddPage: () -> Unit,
) {
    val c = D.c
    val big = Prefs.largeControls
    val btn = if (big) 48.dp else 42.dp
    val icon = if (big) 26.dp else 22.dp
    var stylePopup by remember { mutableIntStateOf(0) }   // 0 closed, 1 from the pen button, 2 from the style chip

    @Composable
    fun ToolRow(content: @Composable RowScope.() -> Unit) {
        Row(
            Modifier.padding(horizontal = 8.dp)
                .background(c.surface, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }

    @Composable
    fun RowScope.Tools() {
        Box {
            ToolButton(penStyleIcon(st.penStyle), stringResource(R.string.ink_tool_pen), st.tool == Tool.PEN, btn, icon) {
                if (st.tool == Tool.PEN) stylePopup = 1 else { st.selectTool(Tool.PEN); onToolChanged() }
            }
            PenStyleMenu(stylePopup == 1, st, onDismiss = { stylePopup = 0 }) { st.selectStyle(it); stylePopup = 0; onToolChanged() }
        }
        listOf(
            Triple(Tool.HIGHLIGHTER, Icons.Rounded.BorderColor, R.string.ink_tool_highlighter),
            Triple(Tool.ERASER, Icons.Rounded.CleaningServices, R.string.ink_tool_eraser),
            Triple(Tool.LASSO, Icons.Rounded.Gesture, R.string.ink_tool_lasso),
            Triple(Tool.TEXT, Icons.Rounded.TextFields, R.string.ink_tool_text),
            Triple(Tool.SHAPE, Icons.Rounded.Category, R.string.ink_tool_shape),
            Triple(Tool.TAPE, Icons.Rounded.VisibilityOff, R.string.ink_tool_tape),
            Triple(Tool.LASER, Icons.Rounded.AdsClick, R.string.ink_tool_laser),
            Triple(Tool.HAND, Icons.Rounded.PanTool, R.string.ink_tool_hand),
        ).forEach { (t, ic, label) ->
            ToolButton(ic, stringResource(label), st.tool == t, btn, icon) { st.selectTool(t); onToolChanged() }
        }
    }

    @Composable
    fun RowScope.Insert() {
        ToolButton(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.ink_insert_image), false, btn, icon, onImage)
        ToolButton(Icons.Rounded.AddLink, stringResource(R.string.ink_insert_link), false, btn, icon, onLink)
        ToolButton(Icons.Rounded.KeyboardVoice, stringResource(R.string.ink_dictate), false, btn, icon, onDictate)
        if (showAddPage) ToolButton(Icons.Rounded.NoteAdd, stringResource(R.string.ink_add_page), false, btn, icon, onAddPage)
    }

    @Composable
    fun RowScope.Style(wide: Boolean) {
        if (st.tool == Tool.PEN) {
            Box {
                Row(
                    Modifier.padding(horizontal = 2.dp).height(btn - 6.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt)
                        .clickable { stylePopup = 2 }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(penStyleIcon(st.penStyle), null, tint = c.ink, modifier = Modifier.size(icon - 4.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(penStyleName(st.penStyle)), style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 1)
                    Icon(Icons.Rounded.ExpandMore, stringResource(R.string.ink_pen_type), tint = c.muted, modifier = Modifier.size(18.dp))
                }
                PenStyleMenu(stylePopup == 2, st, onDismiss = { stylePopup = 0 }) { st.selectStyle(it); stylePopup = 0; onToolChanged() }
            }
            Spacer(Modifier.width(4.dp))
        }
        if (st.hasColors) {
            val colors = when (st.tool) {
                Tool.HIGHLIGHTER -> HlColors
                Tool.TAPE -> InkRender.tapeColors
                else -> PenColors
            }
            val cur = st.currentColor
            colors.forEach { col ->
                val sel = cur == col
                Box(
                    Modifier.padding(horizontal = 2.dp).size(if (big) 34.dp else 30.dp).clip(CircleShape)
                        .border(2.dp, if (sel) c.accent else Color.Transparent, CircleShape)
                        .clickable { st.pickColor(col); onToolChanged() }.padding(4.dp).clip(CircleShape).background(Color(col or 0xFF000000.toInt()))
                        .border(1.dp, c.line, CircleShape),
                )
            }
            if (st.tool != Tool.TAPE) ToolButton(Icons.Rounded.Palette, stringResource(R.string.ink_more_colors), false, btn, icon, onMoreColors)
        }
        if (st.hasWidth) {
            if (st.hasColors) Divider()
            SizeBar(st, wide, big, onToolChanged)
        }
        if (!st.hasColors && !st.hasWidth) {
            val hint = when (st.tool) {
                Tool.LASSO -> R.string.ink_hint_lasso
                Tool.LASER -> R.string.ink_hint_laser
                else -> R.string.ink_hint_hand
            }
            Text(stringResource(hint), style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp))
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().background(c.bg).padding(vertical = 6.dp)) {
        val wide = maxWidth >= 1000.dp
        if (wide) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ToolRow { Tools(); Divider(); Style(true); Divider(); Insert() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ToolRow { Tools(); Divider(); Insert() }
                Spacer(Modifier.height(6.dp))
                ToolRow { Style(false) }
            }
        }
    }
}

@Composable
private fun SizeBar(st: InkToolState, wide: Boolean, big: Boolean, onChanged: () -> Unit) {
    val c = D.c
    val range = st.range
    val w = st.width.coerceIn(range.start, range.endInclusive)
    st.presets.forEachIndexed { i, p ->
        val sel = abs1(w - p) < 0.05f
        Box(
            Modifier.padding(horizontal = 1.dp).size(if (big) 40.dp else 34.dp).clip(RoundedCornerShape(10.dp))
                .background(if (sel) c.accent.copy(alpha = 0.12f) else Color.Transparent).clickable { st.setWidth(p); onChanged() },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size((5 + i * 5).dp).clip(CircleShape).background(if (sel) c.accent else c.muted)) }
    }
    Spacer(Modifier.width(6.dp))
    // live preview of the size in the current colour
    val frac = (w - range.start) / (range.endInclusive - range.start)
    val d: Dp = (4f + 20f * frac).dp
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when (st.tool) {
            Tool.ERASER -> Box(Modifier.size(d).border(1.5.dp, c.muted, CircleShape))
            Tool.TAPE -> Box(Modifier.width(26.dp).height((3f + 13f * frac).dp).clip(RoundedCornerShape(3.dp)).background(Color(st.tapeColor)))
            else -> Box(Modifier.size(d).clip(CircleShape).background(Color(st.currentColor)))
        }
    }
    Slider(
        value = w, onValueChange = { st.setWidth(it); onChanged() }, valueRange = range,
        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line),
        modifier = Modifier.width(if (wide) 170.dp else 140.dp).padding(horizontal = 6.dp),
    )
    Text(fmtWidth(w), style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 1, modifier = Modifier.width(34.dp))
}

private fun abs1(v: Float) = if (v < 0f) -v else v

/** Pen type popup: a real rendered sample stroke + name per style. */
@Composable
private fun PenStyleMenu(open: Boolean, st: InkToolState, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val c = D.c
    DropdownMenu(open, onDismiss) {
        Text(stringResource(R.string.ink_pen_type), style = MaterialTheme.typography.labelMedium, color = c.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        PenStyle.all.forEach { s ->
            val sel = s == st.penStyle
            Row(
                Modifier.fillMaxWidth().widthIn(min = 230.dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onPick(s) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(penStyleIcon(s), null, tint = if (sel) c.accent else c.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(penStyleName(s)), color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                StylePreview(s, st.penColor)
            }
        }
    }
}

@Composable
private fun StylePreview(style: Int, color: Int) {
    val stroke = remember(style, color) { sampleStroke(style, color) }
    Canvas(Modifier.size(76.dp, 26.dp)) {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save(); nc.scale(density, density)
            InkRender.drawStroke(nc, stroke)
            nc.restore()
        }
    }
}

/** A short wave with a pressure swell, in a 76 × 26 box. */
private fun sampleStroke(style: Int, color: Int): Stroke {
    val n = 48
    val pts = FloatArray(n * 3)
    for (i in 0 until n) {
        val t = i / (n - 1f)
        pts[i * 3] = 6f + t * 64f
        pts[i * 3 + 1] = 13f + 6f * sin(t * 2f * PI.toFloat())
        pts[i * 3 + 2] = 0.25f + 0.7f * sin(t * PI.toFloat())
    }
    val w = when (style) {
        PenStyle.MARKER -> 5f
        PenStyle.BRUSH -> 4f
        PenStyle.FOUNTAIN -> 3.2f
        else -> 2.2f
    }
    return Stroke(Tool.PEN, color or 0xFF000000.toInt(), w, pts, style = style)
}

@Composable
internal fun Divider() = Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(D.c.line))

@Composable
internal fun ToolButton(icon: ImageVector, label: String, selected: Boolean, size: Dp = 42.dp, iconSize: Dp = 22.dp, onClick: () -> Unit) {
    val c = D.c
    Box(
        Modifier.padding(horizontal = 1.dp).size(size).clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (selected) c.accent else c.ink, modifier = Modifier.size(iconSize)) }
}
