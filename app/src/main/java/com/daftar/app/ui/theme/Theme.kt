package com.daftar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftar.app.data.Prefs

@Immutable
data class DaftarColors(
    val bg: Color, val surface: Color, val surfaceAlt: Color, val line: Color,
    val ink: Color, val muted: Color, val accent: Color, val onAccent: Color, val danger: Color,
    val dark: Boolean,
)

private val Light = DaftarColors(
    bg = Color(0xFFF7F6F2), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFE9E7E1), line = Color(0xFFE4E2DC),
    ink = Color(0xFF1F2937), muted = Color(0xFF6B7280), accent = Color(0xFF3B82F6), onAccent = Color.White,
    danger = Color(0xFFB3261E), dark = false,
)
private val Dark = DaftarColors(
    bg = Color(0xFF14161A), surface = Color(0xFF1C1F24), surfaceAlt = Color(0xFF262A31), line = Color(0xFF2E333B),
    ink = Color(0xFFE8EAED), muted = Color(0xFF9CA3AF), accent = Color(0xFF60A5FA), onAccent = Color(0xFF0B1B33),
    danger = Color(0xFFF2B8B5), dark = true,
)

/** The 12 folder colours from DESIGN.md. Index is stored in FolderMeta.color. */
val FolderPalette = listOf(
    Color(0xFFF26D5B), Color(0xFFF59E42), Color(0xFFF2C94C), Color(0xFF9BC53D),
    Color(0xFF4CC38A), Color(0xFF2EB5B0), Color(0xFF3B82F6), Color(0xFF5B6EE8),
    Color(0xFF8B6CE0), Color(0xFFE56BA6), Color(0xFFB08968), Color(0xFF7C8796),
)
fun folderColor(i: Int) = FolderPalette[i.mod(FolderPalette.size)]

val LocalColors = staticCompositionLocalOf { Light }

object D {
    val c: DaftarColors @Composable get() = LocalColors.current
    val gutter = 16.dp
    val gutterWide = 24.dp
    val cardRadius = RoundedCornerShape(16.dp)
    val chipRadius = RoundedCornerShape(12.dp)
}

private val type = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun DaftarTheme(content: @Composable () -> Unit) {
    val dark = when (Prefs.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val c = if (dark) Dark else Light
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.surfaceAlt, onPrimaryContainer = c.ink,
        secondary = c.accent, secondaryContainer = c.surfaceAlt, onSecondaryContainer = c.ink,
        background = c.bg, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.muted, outline = c.line, outlineVariant = c.line,
        surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surfaceAlt, surfaceContainerLowest = c.surface, surfaceTint = Color.Transparent,
        error = c.danger,
    )
    CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(
            colorScheme = scheme, typography = type,
            shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
            content = content,
        )
    }
}
