package com.daftar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.ui.theme.D

// Daftar folder mark, traced from the brand sheet (160×160 artboard; the mark spans x 22..136, y 23.5..118).
private val backPath = PathParser().parsePathString(
    "M24 38C24 31.3 29.3 26 36 26L124 26C130.7 26 136 31.3 136 38L136 106C136 112.7 130.7 118 124 118L36 118C29.3 118 24 112.7 24 106Z"
).toPath()
private val frontPath = PathParser().parsePathString(
    "M22 46C22 41.5 25.5 37.8 30 37.2L126 24.2C131.2 23.5 136 27.5 136 32.8L136 106C136 112.7 130.7 118 124 118L34 118C27.3 118 22 112.7 22 106Z"
).toPath()
private val sheetPath = PathParser().parsePathString("M32 38L128 25C132 24.5 134.5 27 134.5 30L134.5 38L32 50Z").toPath()

/** The Daftar logo mark (light/dark variants follow the theme). */
@Composable
fun DaftarLogo(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val dark = D.c.dark
    val back = if (dark) Color(0xFF1D4ED8) else Color(0xFF2563EB)
    val front = Color(0xFF3B82F6)
    val sheet = if (dark) Color(0xFF93C5FD) else Color(0xFF60A5FA)
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 118f
        val dx = (this.size.width - 114f * s) / 2f - 22f * s
        val dy = (this.size.height - 94.5f * s) / 2f - 23.5f * s
        translate(dx, dy) {
            scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                drawPath(backPath, back)
                drawPath(frontPath, front)
                drawPath(sheetPath, sheet, alpha = 0.92f)
            }
        }
    }
}
