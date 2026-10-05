package com.daftar.app.grades

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.study.subjectMeta
import com.daftar.app.ui.Chip
import com.daftar.app.ui.FolderGlyph
import com.daftar.app.ui.card
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.FolderPalette
import com.daftar.app.ui.theme.folderColor
import java.io.File

// ---------- colours ----------

/** Badge colour by grade family (A green, B blue, C sand, D orange, F coral) — flat palette from DESIGN.md. */
fun letterColor(letter: String): Color = when (normLetter(letter).uppercase().firstOrNull()) {
    'A' -> FolderPalette[4]
    'B' -> FolderPalette[6]
    'C' -> FolderPalette[2]
    'D' -> FolderPalette[1]
    'E', 'F' -> FolderPalette[0]
    'P' -> FolderPalette[5]
    else -> FolderPalette[11]
}

/** Readable text colour for a tinted badge. */
@Composable
private fun onTint(col: Color): Color = if (D.c.dark) lerp(col, Color.White, 0.25f) else lerp(col, Color.Black, 0.35f)

/** Subject folder colour for a course (slate when unlinked or the folder is gone). */
fun courseColor(co: Course): Color =
    if (co.subject.isNotEmpty() && File(co.subject).isDirectory) folderColor(subjectMeta(co.subject).color) else FolderPalette[11]

@Composable
fun LetterBadge(letter: String?, size: Dp = 44.dp, muted: Boolean = false) {
    val c = D.c
    val col = if (letter == null) c.muted else letterColor(letter)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(if (letter == null) c.surfaceAlt else col.copy(alpha = if (muted) 0.08f else 0.16f))
            .then(if (muted && letter != null) Modifier.border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(12.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter?.let { showLetter(it) } ?: "—",
            style = if (size >= 52.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = if (letter == null) c.muted else onTint(col), maxLines = 1,
        )
    }
}

// ---------- summary ----------

@Composable
internal fun scaleName(id: String, d: GradesData): String = when (id) {
    Scales.SA50 -> stringResource(R.string.grades_scale_sa50)
    Scales.SA40 -> stringResource(R.string.grades_scale_sa40)
    Scales.CUSTOM -> stringResource(R.string.grades_scale_custom)
    else -> stringResource(if (d.usAPlus433) R.string.grades_scale_us433 else R.string.grades_scale_us40)
}

@Composable
fun SummaryCard(data: GradesData, scale: GradeScale, stacked: Boolean, onScale: () -> Unit) {
    val c = D.c
    val cum = cumulativeGpa(data, scale)
    val points = data.terms.mapNotNull { termGpa(it, scale).value }
    Column(Modifier.fillMaxWidth().card(c).padding(16.dp)) {
        @Composable
        fun Numbers(m: Modifier) = Column(m) {
            Text(stringResource(R.string.grades_cumulative), style = MaterialTheme.typography.labelLarge, color = c.muted)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmtGpa(cum.value), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.grades_of_max, fmtGpa(scale.max)), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                stringResource(R.string.grades_credits_n, fmtNum(cum.credits)) + " · " + pluralStringResource(R.plurals.grades_n_courses, cum.courses, cum.courses),
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
            Spacer(Modifier.height(10.dp))
            Chip(scaleName(data.scaleId, data), false, onScale, leading = Icons.Rounded.Tune)
        }
        if (stacked) {
            Numbers(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Trend(points, scale.max, Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Numbers(Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Trend(points, scale.max, Modifier.weight(1.3f))
            }
        }
    }
}

@Composable
private fun Trend(points: List<Double>, max: Double, modifier: Modifier) {
    val c = D.c
    Column(modifier) {
        Text(stringResource(R.string.grades_trend), style = MaterialTheme.typography.labelMedium, color = c.muted)
        Spacer(Modifier.height(6.dp))
        if (points.size < 2) {
            Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt).padding(12.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.grades_trend_need_two), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        } else {
            TrendChart(points, max, Modifier.fillMaxWidth().height(88.dp))
        }
    }
}

/** Flat line chart of term GPAs (oldest → newest; mirrored in RTL so time reads in the text direction). */
@Composable
fun TrendChart(points: List<Double>, max: Double, modifier: Modifier) {
    val c = D.c
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val desc = points.joinToString(" → ") { fmtGpa(it) }
    Canvas(modifier.semantics { contentDescription = desc }) {
        val pad = 6.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val lo = (points.min() - 0.5).coerceAtLeast(0.0)
        val hi = max.coerceAtLeast(points.max())
        val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
        for (k in 0..2) {
            val y = pad + h * k / 2f
            drawLine(c.line, Offset(pad, y), Offset(pad + w, y), strokeWidth = 1.dp.toPx())
        }
        val pts = points.mapIndexed { i, v ->
            val fx = i / (points.size - 1).toFloat()
            val x = pad + w * (if (rtl) 1f - fx else fx)
            Offset(x, pad + h * (1f - ((v - lo) / span).toFloat()))
        }
        val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        drawPath(path, c.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        pts.forEachIndexed { i, p ->
            val last = i == pts.lastIndex
            drawCircle(c.surface, if (last) 5.dp.toPx() else 4.dp.toPx(), p)
            drawCircle(c.accent, if (last) 4.dp.toPx() else 2.5.dp.toPx(), p)
        }
    }
}

// ---------- terms & courses ----------

@Composable
fun TermHeader(t: Term, scale: GradeScale, canUp: Boolean, canDown: Boolean, onAddCourse: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onMove: (Boolean) -> Unit) {
    val c = D.c
    val g = termGpa(t, scale)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(t.name, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.grades_term_gpa_line, fmtGpa(g.value), fmtNum(g.credits)),
                style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1,
            )
        }
        IconButton(onClick = onAddCourse) { Icon(Icons.Rounded.Add, stringResource(R.string.grades_add_course), tint = c.muted) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.grades_more), tint = c.muted) }
            DropdownMenu(menu, { menu = false }, containerColor = c.surface) {
                DropdownMenuItem({ Text(stringResource(R.string.grades_rename_term)) }, { menu = false; onEdit() },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                if (canUp) DropdownMenuItem({ Text(stringResource(R.string.grades_move_up)) }, { menu = false; onMove(true) },
                    leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) })
                if (canDown) DropdownMenuItem({ Text(stringResource(R.string.grades_move_down)) }, { menu = false; onMove(false) },
                    leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) })
                DropdownMenuItem({ Text(stringResource(R.string.delete), color = c.danger) }, { menu = false; onDelete() },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.danger) })
            }
        }
    }
}

/** "CS101 · 3 credits · 87.5% so far" */
@Composable
private fun courseLine(co: Course, scale: GradeScale): String {
    val parts = ArrayList<String>()
    if (co.code.isNotBlank()) parts.add(co.code)
    parts.add(stringResource(R.string.grades_credits_n, fmtNum(co.credits)))
    when {
        co.passFail -> parts.add(stringResource(R.string.grades_pf) + " · " + stringResource(if (co.passed) R.string.grades_passed else R.string.grades_failed))
        co.letterMode -> co.letter.takeIf { it.isNotBlank() }?.let { l ->
            scale.band(l)?.let { parts.add(stringResource(R.string.grades_points_n, fmtNum(it.points))) }
                ?: parts.add(stringResource(R.string.grades_not_on_scale))
        }
        else -> {
            val st = standing(co)
            if (st.pct == null) parts.add(stringResource(R.string.grades_nothing_graded))
            else parts.add(if (st.complete) fmtPct(st.pct) else stringResource(R.string.grades_pct_so_far, fmtPct(st.pct)))
        }
    }
    return parts.joinToString(" · ")
}

@Composable
fun CourseCard(co: Course, scale: GradeScale, selected: Boolean, onClick: () -> Unit) {
    val c = D.c
    val band = courseBand(co, scale)
    val inProgress = !co.letterMode && !co.passFail && !standing(co).complete
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) c.accent.copy(alpha = 0.08f) else c.surface)
            .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (co.subject.isNotEmpty() && File(co.subject).isDirectory) {
            val m = subjectMeta(co.subject)
            FolderGlyph(m.color, m.icon, 36.dp)
        } else {
            Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(courseColor(co)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(co.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(courseLine(co, scale), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (co.passFail) LetterBadge(stringResource(if (co.passed) R.string.grades_p else R.string.grades_f_short))
        else LetterBadge(band?.letter, muted = inProgress)
    }
}

// ---------- detail ----------

@Composable
fun CourseDetail(
    co: Course, termName: String, scale: GradeScale, gutter: Dp,
    onEdit: () -> Unit, onDelete: () -> Unit, onAddAssessment: () -> Unit, onEditAssessment: (Assessment) -> Unit,
) {
    val c = D.c
    val st = standing(co)
    val band = courseBand(co, scale)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter, vertical = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(Modifier.fillMaxWidth().card(c).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (co.subject.isNotEmpty() && File(co.subject).isDirectory) {
                val m = subjectMeta(co.subject)
                FolderGlyph(m.color, m.icon, 44.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(co.name, style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    termName.takeIf { it.isNotBlank() }, co.code.takeIf { it.isNotBlank() },
                    stringResource(R.string.grades_credits_n, fmtNum(co.credits)),
                    if (co.subject.isNotEmpty()) File(co.subject).name else null,
                ).joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, stringResource(R.string.grades_edit_course), tint = c.muted) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = c.muted) }
        }

        // How the grade is decided
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.grades_mode_assess), !co.letterMode, { GradeBook.updateCourse(co.id) { it.copy(letterMode = false) } }, leading = Icons.Rounded.Functions)
            Chip(stringResource(R.string.grades_mode_letter), co.letterMode, { GradeBook.updateCourse(co.id) { it.copy(letterMode = true) } }, leading = Icons.Rounded.Grade)
        }

        if (co.passFail) {
            Column(Modifier.fillMaxWidth().card(c).padding(16.dp)) {
                Text(stringResource(R.string.grades_pf_note), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.grades_passed), co.passed, { GradeBook.updateCourse(co.id) { it.copy(passed = true) } }, tint = FolderPalette[4])
                    Chip(stringResource(R.string.grades_failed), !co.passed, { GradeBook.updateCourse(co.id) { it.copy(passed = false) } }, tint = FolderPalette[0])
                }
            }
        }

        if (co.letterMode) {
            if (!co.passFail) LetterPicker(co, scale)
        } else {
            StandingCard(co, st, band, scale)
            AssessmentsCard(co, st, onAddAssessment, onEditAssessment)
            if (!co.passFail) NeededCard(co, st, scale)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LetterPicker(co: Course, scale: GradeScale) {
    val c = D.c
    Column(Modifier.fillMaxWidth().card(c).padding(16.dp)) {
        Text(stringResource(R.string.grades_letter), style = MaterialTheme.typography.titleSmall, color = c.ink)
        Spacer(Modifier.height(10.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            scale.bands.forEach { b ->
                val sel = sameLetter(b.letter, co.letter)
                Chip(showLetter(b.letter) + "  " + fmtNum(b.points), sel, {
                    GradeBook.updateCourse(co.id) { it.copy(letter = if (sel) "" else normLetter(b.letter)) }
                }, tint = letterColor(b.letter))
            }
        }
        if (co.letter.isNotBlank() && scale.band(co.letter) == null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.grades_letter_missing, showLetter(co.letter)), style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
    }
}

@Composable
private fun StandingCard(co: Course, st: Standing, band: GradeBand?, scale: GradeScale) {
    val c = D.c
    Row(Modifier.fillMaxWidth().card(c).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (st.complete) R.string.grades_final_result else R.string.grades_standing), style = MaterialTheme.typography.labelLarge, color = c.muted)
            if (st.pct == null) {
                Text(stringResource(R.string.grades_nothing_graded), style = MaterialTheme.typography.titleMedium, color = c.ink)
            } else {
                Text(fmtPct(st.pct), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = c.ink)
                val graded = if (st.totalWeight > 0) st.gradedWeight / st.totalWeight * 100 else 0.0
                Text(
                    if (st.complete) stringResource(R.string.grades_points_n, fmtNum(band?.points ?: 0.0))
                    else stringResource(R.string.grades_graded_share, fmtPct(graded)),
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
                if (!st.complete && !co.passFail) {
                    // Points if nothing else were earned (worst case) — helps judge risk.
                    val worst = if (st.totalWeight > 0) st.earned / st.totalWeight * 100 else 0.0
                    Text(stringResource(R.string.grades_worst_case, fmtPct(worst), scale.bandFor(worst)?.letter?.let { showLetter(it) } ?: "—"),
                        style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
        }
        if (!co.passFail) LetterBadge(band?.letter, 60.dp, muted = !st.complete)
    }
}

@Composable
private fun AssessmentsCard(co: Course, st: Standing, onAdd: () -> Unit, onEdit: (Assessment) -> Unit) {
    val c = D.c
    Column(Modifier.fillMaxWidth().card(c).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.grades_assessments), style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) {
                Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.grades_add_assessment), color = c.accent)
            }
        }
        if (co.assessments.isEmpty()) {
            Text(stringResource(R.string.grades_no_assessments), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            val names = listOf(
                stringResource(R.string.grades_tpl_midterm) to 30.0, stringResource(R.string.grades_tpl_final) to 40.0,
                stringResource(R.string.grades_tpl_labs) to 20.0, stringResource(R.string.grades_tpl_quiz) to 10.0,
            )
            TextButton(onClick = {
                val base = GradeBook.newId()
                GradeBook.setAssessments(co.id, names.mapIndexed { i, (n, w) -> Assessment(base + i, n, w) })
            }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Rounded.AutoAwesomeMosaic, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.grades_template), color = c.accent)
            }
            return@Column
        }
        co.assessments.forEachIndexed { i, a ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(c.line))
            Row(Modifier.fillMaxWidth().clickable { onEdit(a) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.grades_weight_n, fmtPct(a.weight)), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                val p = a.pct
                if (p == null) {
                    Text(stringResource(R.string.grades_not_graded), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                } else Column(horizontalAlignment = Alignment.End) {
                    Text(fmtPct(p), style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(stringResource(R.string.grades_score_of, fmtNum(a.score ?: 0.0), fmtNum(a.outOf)), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
        }
        val total = st.totalWeight
        val ok = kotlin.math.abs(total - 100.0) < 0.01
        Text(
            if (ok) stringResource(R.string.grades_weight_total, fmtPct(total)) else stringResource(R.string.grades_weight_warn, fmtPct(total)),
            style = MaterialTheme.typography.bodySmall, color = if (ok) c.muted else FolderPalette[1],
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun NeededCard(co: Course, st: Standing, scale: GradeScale) {
    val c = D.c
    Column(Modifier.fillMaxWidth().card(c).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Calculate, null, tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.grades_calc), style = MaterialTheme.typography.titleSmall, color = c.ink)
        }
        Spacer(Modifier.height(6.dp))
        if (st.totalWeight <= 0) {
            Text(stringResource(R.string.grades_calc_need_assess), style = MaterialTheme.typography.bodyMedium, color = c.muted)
            return@Column
        }
        if (st.complete) {
            Text(stringResource(R.string.grades_final_done), style = MaterialTheme.typography.bodyMedium, color = c.muted)
            return@Column
        }
        val left = co.assessments.filter { it.weight > 0 && it.pct == null }
        val remainingShare = fmtPct(st.remainingWeight / st.totalWeight * 100)
        Text(
            if (left.size == 1) stringResource(R.string.grades_calc_on_one, left[0].name, remainingShare)
            else stringResource(R.string.grades_calc_on_rest, remainingShare),
            style = MaterialTheme.typography.bodyMedium, color = c.muted,
        )
        Spacer(Modifier.height(8.dp))
        val targets = scale.bands.filter { it.minPct > 0 }
        targets.forEachIndexed { i, b ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            val n = needed(co, b.minPct)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LetterBadge(b.letter, 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.grades_target_min, fmtPct(b.minPct)), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
                when (n) {
                    Need.Secured -> Text(stringResource(R.string.grades_secured), style = MaterialTheme.typography.titleSmall, color = FolderPalette[4])
                    is Need.Unreachable -> Text(stringResource(R.string.grades_unreachable), style = MaterialTheme.typography.titleSmall, color = c.muted)
                    is Need.Score -> Text(stringResource(R.string.grades_need_pct, fmtPct(kotlin.math.ceil(n.pct * 10) / 10)), style = MaterialTheme.typography.titleSmall, color = c.ink)
                    Need.Final -> Text("—", color = c.muted)
                }
            }
        }
    }
}
