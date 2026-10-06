package com.daftar.app.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.theme.D

/** Full palette: 13 hue columns × 5 shades (light → dark) + a grey row. */
private val PaletteHues: List<List<Long>> = listOf(
    listOf(0xFFFCA5A5, 0xFFEF4444, 0xFFDC2626, 0xFFB91C1C, 0xFF7F1D1D),   // red
    listOf(0xFFFDBA74, 0xFFF97316, 0xFFEA580C, 0xFFC2410C, 0xFF7C2D12),   // orange
    listOf(0xFFFCD34D, 0xFFF59E0B, 0xFFD97706, 0xFFB45309, 0xFF78350F),   // amber
    listOf(0xFFFDE047, 0xFFEAB308, 0xFFCA8A04, 0xFFA16207, 0xFF713F12),   // yellow
    listOf(0xFFBEF264, 0xFF84CC16, 0xFF65A30D, 0xFF4D7C0F, 0xFF365314),   // lime
    listOf(0xFF86EFAC, 0xFF22C55E, 0xFF16A34A, 0xFF15803D, 0xFF14532D),   // green
    listOf(0xFF5EEAD4, 0xFF14B8A6, 0xFF0D9488, 0xFF0F766E, 0xFF134E4A),   // teal
    listOf(0xFF67E8F9, 0xFF06B6D4, 0xFF0891B2, 0xFF0E7490, 0xFF164E63),   // cyan
    listOf(0xFF93C5FD, 0xFF3B82F6, 0xFF2563EB, 0xFF1D4ED8, 0xFF1E3A8A),   // blue
    listOf(0xFFA5B4FC, 0xFF6366F1, 0xFF4F46E5, 0xFF4338CA, 0xFF312E81),   // indigo
    listOf(0xFFD8B4FE, 0xFFA855F7, 0xFF9333EA, 0xFF7E22CE, 0xFF581C87),   // purple
    listOf(0xFFF9A8D4, 0xFFEC4899, 0xFFDB2777, 0xFFBE185D, 0xFF831843),   // pink
    listOf(0xFFD6B89C, 0xFFA1785A, 0xFF8B5E3C, 0xFF6B4226, 0xFF4A2C17),   // brown
)
private val PaletteGreys = listOf(0xFFFFFFFF, 0xFFE5E7EB, 0xFFD1D5DB, 0xFF9CA3AF, 0xFF6B7280, 0xFF4B5563, 0xFF374151, 0xFF1F2937, 0xFF111827, 0xFF000000)

/**
 * Colour picker: recent colours, a 70-colour palette and a custom HSV picker with hex input.
 * [current] preselects; [onPick] receives an opaque ARGB colour (the caller applies highlighter alpha etc.).
 */
@Composable
internal fun ColorPickerDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val c = D.c
    var tab by remember { mutableIntStateOf(0) }
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(current or 0xFF000000.toInt(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val custom = android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v))
    var hex by remember { mutableStateOf("") }
    LaunchedEffect(custom) { hex = "%06X".format(custom and 0xFFFFFF) }
    fun pick(col: Int) { val o = col or 0xFF000000.toInt(); InkPrefs.addRecentColor(o); onPick(o) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ink_more_colors)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = c.accent) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.ink_palette)) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.ink_custom_color)) })
                }
                Spacer(Modifier.height(12.dp))
                if (InkPrefs.recentColors.isNotEmpty()) {
                    Text(stringResource(R.string.ink_recent_colors), style = MaterialTheme.typography.labelMedium, color = c.muted)
                    Spacer(Modifier.height(6.dp))
                    Row { InkPrefs.recentColors.forEach { col -> Swatch(col, 30.dp, col == (current or 0xFF000000.toInt())) { pick(col) } } }
                    Spacer(Modifier.height(12.dp))
                }
                if (tab == 0) BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val full = maxWidth
                    val cell = (full / PaletteHues.size).coerceAtMost(40.dp)
                    Column {
                        for (shade in 0 until 5) Row {
                            PaletteHues.forEach { hue -> val col = hue[shade].toInt(); Swatch(col, cell, col == (current or 0xFF000000.toInt())) { pick(col) } }
                        }
                        Spacer(Modifier.height(6.dp))
                        val g = (full / PaletteGreys.size).coerceAtMost(40.dp)
                        Row { PaletteGreys.forEach { gl -> val col = gl.toInt(); Swatch(col, g, col == (current or 0xFF000000.toInt())) { pick(col) } } }
                    }
                } else Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(custom)).border(1.dp, c.line, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            hex, { t ->
                                hex = t.filter { it.isLetterOrDigit() }.take(6).uppercase()
                                if (hex.length == 6) hex.toLongOrNull(16)?.let { rgb ->
                                    val a = FloatArray(3); android.graphics.Color.colorToHSV((rgb or 0xFF000000L).toInt(), a); h = a[0]; sat = a[1]; v = a[2]
                                }
                            },
                            prefix = { Text("#") }, singleLine = true, label = { Text(stringResource(R.string.ink_hex)) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.width(150.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GradientSlider(stringResource(R.string.ink_hue), h / 360f, (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }) { h = it * 360f }
                    GradientSlider(stringResource(R.string.ink_saturation), sat,
                        listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 0f, v))), Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v))))) { sat = it }
                    GradientSlider(stringResource(R.string.ink_brightness), v,
                        listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, 1f))))) { v = it }
                }
            }
        },
        confirmButton = { if (tab == 1) TextButton(onClick = { pick(custom) }) { Text(stringResource(R.string.ink_use_color)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Swatch(col: Int, size: androidx.compose.ui.unit.Dp, selected: Boolean, onClick: () -> Unit) {
    val c = D.c
    Box(Modifier.size(size).padding(3.dp).clip(CircleShape).background(Color(col)).border(1.dp, c.line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = if (androidx.core.graphics.ColorUtils.calculateLuminance(col) > 0.6) Color.Black else Color.White,
            modifier = Modifier.size(size * 0.5f))
    }
}

/** A slider drawn over a gradient strip showing what the value does. */
@Composable
private fun GradientSlider(label: String, value: Float, colors: List<Color>, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = D.c.muted)
    Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(colors)))
        Slider(value, onChange, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
    }
}
