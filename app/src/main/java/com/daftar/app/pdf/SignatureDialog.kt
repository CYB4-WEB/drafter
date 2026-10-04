package com.daftar.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import androidx.compose.ui.graphics.Path as ComposePath

/** The user's saved signature: a transparent PNG in app-private storage. */
object SignatureStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "pdf_signature.png")

    fun exists(ctx: Context) = file(ctx).exists()

    fun load(ctx: Context): Bitmap? = runCatching { BitmapFactory.decodeFile(file(ctx).path) }.getOrNull()

    fun save(ctx: Context, b: Bitmap) {
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
    }

    fun clear(ctx: Context) { file(ctx).delete() }
}

private val SignColors = listOf(0xFF111827.toInt(), 0xFF1E40AF.toInt())

/**
 * Sign: shows the saved signature (Place / Draw new / Delete) or a drawing pad. [onPlace] receives a transparent
 * bitmap that the caller inserts with `EditorController.addImage` (it floats selected so it can be moved and resized).
 */
@Composable
fun SignatureDialog(onDismiss: () -> Unit, onPlace: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<Bitmap?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        saved = withContext(Dispatchers.IO) { SignatureStore.load(ctx) }
        drawing = saved == null
        loaded = true
    }
    if (!loaded) return

    if (!drawing && saved != null) {
        val b = saved!!
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pdf_signature)) },
            text = {
                Box(
                    Modifier.fillMaxWidth().height(160.dp).background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, D.c.line, RoundedCornerShape(12.dp)).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(remember(b) { b.asImageBitmap() }, stringResource(R.string.pdf_signature), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            },
            confirmButton = { TextButton(onClick = { onPlace(b) }) { Text(stringResource(R.string.pdf_sign_place)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch { withContext(Dispatchers.IO) { SignatureStore.clear(ctx) }; saved = null; drawing = true }
                    }) { Text(stringResource(R.string.pdf_sign_delete), color = D.c.danger) }
                    TextButton(onClick = { drawing = true }) { Text(stringResource(R.string.pdf_sign_redraw)) }
                }
            },
        )
        return
    }

    SignaturePad(
        onDismiss = { if (saved != null) drawing = false else onDismiss() },
        onDone = { bmp ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { SignatureStore.save(ctx, bmp) }.onFailure { Log.e("Signature", "save", it) }.isSuccess }
                if (ok) saved = bmp
                onPlace(bmp)
            }
        },
    )
}

@Composable
private fun SignaturePad(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var color by remember { mutableIntStateOf(SignColors[0]) }
    val widthPx = with(LocalDensity.current) { 3.dp.toPx() }
    val c = D.c

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_sign_draw_title)) },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color.White).border(1.dp, c.line, RoundedCornerShape(12.dp)),
                ) {
                    if (strokes.isEmpty() && current.isEmpty()) Text(
                        stringResource(R.string.pdf_sign_here), color = Color(0xFF9CA3AF), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 52.dp),
                    )
                    androidx.compose.foundation.Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                current.clear(); current.add(down.position)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    current.add(ch.position); ch.consume()
                                }
                                if (current.isNotEmpty()) strokes.add(current.toList())
                                current.clear()
                            }
                        },
                    ) {
                        // signing line
                        val y = size.height * 0.72f
                        drawLine(Color(0xFFD1D5DB), Offset(16.dp.toPx(), y), Offset(size.width - 16.dp.toPx(), y), strokeWidth = 1.dp.toPx())
                        val st = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val col = Color(color)
                        for (s in strokes) drawPath(composePath(s), col, style = st)
                        if (current.isNotEmpty()) drawPath(composePath(current), col, style = st)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SignColors.forEach { col ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).border(2.dp, if (col == color) c.accent else Color.Transparent, CircleShape)
                                .clickable { color = col }.padding(6.dp).clip(CircleShape).background(Color(col)),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { strokes.clear(); current.clear() }, enabled = strokes.isNotEmpty()) {
                        Text(stringResource(R.string.pdf_sign_clear))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                renderSignature(strokes.toList(), color, widthPx)?.let(onDone)
            }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.pdf_sign_save_place)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun composePath(pts: List<Offset>): ComposePath {
    val p = ComposePath()
    if (pts.isEmpty()) return p
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) { p.lineTo(pts[0].x + 0.1f, pts[0].y); return p }
    for (i in 1 until pts.size) {
        val a = pts[i - 1]; val b = pts[i]
        p.quadraticTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }
    p.lineTo(pts.last().x, pts.last().y)
    return p
}

/** Crops the strokes to their bounds (+ margin) on a transparent bitmap, long side ≤ 1600 px. */
private fun renderSignature(strokes: List<List<Offset>>, color: Int, widthPx: Float): Bitmap? {
    val all = strokes.flatten()
    if (all.isEmpty()) return null
    val pad = widthPx * 3
    val l = all.minOf { it.x } - pad; val t = all.minOf { it.y } - pad
    val r = all.maxOf { it.x } + pad; val b = all.maxOf { it.y } + pad
    val w = max(1f, r - l); val h = max(1f, b - t)
    val long = max(w, h)
    val s = when { long > 1600f -> 1600f / long; long < 400f -> 2f; else -> 1f }
    val bmp = Bitmap.createBitmap((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    bmp.eraseColor(android.graphics.Color.TRANSPARENT)
    val cv = Canvas(bmp)
    cv.scale(s, s); cv.translate(-l, -t)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = widthPx; this.color = color
    }
    for (st in strokes) {
        val p = Path()
        p.moveTo(st[0].x, st[0].y)
        if (st.size == 1) p.lineTo(st[0].x + 0.1f, st[0].y)
        for (i in 1 until st.size) {
            val a = st[i - 1]; val c = st[i]
            p.quadTo(a.x, a.y, (a.x + c.x) / 2f, (a.y + c.y) / 2f)
        }
        if (st.size > 1) p.lineTo(st.last().x, st.last().y)
        cv.drawPath(p, paint)
    }
    return bmp
}
