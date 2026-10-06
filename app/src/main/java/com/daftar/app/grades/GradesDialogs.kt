package com.daftar.app.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.planner.EventType
import com.daftar.app.planner.Planner
import com.daftar.app.study.rememberSubjects
import com.daftar.app.study.subjectOf
import com.daftar.app.ui.Chip
import com.daftar.app.ui.studyIcon
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.folderColor
import com.daftar.app.ui.toast
import java.io.File

/** Shared dialog shell: flat surface, 24dp radius, scrollable body. */
@Composable
private fun GDialogShell(
    title: String, onDismiss: () -> Unit, confirm: String, confirmEnabled: Boolean = true, onConfirm: () -> Unit,
    extra: (@Composable () -> Unit)? = null, body: @Composable ColumnScope.() -> Unit,
) {
    val c = D.c
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = c.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm, color = if (confirmEnabled) c.accent else c.muted) }
        },
        dismissButton = {
            Row {
                extra?.invoke()
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = c.muted) }
            }
        },
    )
}

@Composable
private fun NumField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: Boolean = false) {
    OutlinedTextField(
        value, onValue, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true, isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

@Composable
private fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = D.c.muted)

// ---------- term ----------

@Composable
fun TermDialog(term: Term?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(term?.name ?: "") }
    GDialogShell(
        stringResource(if (term == null) R.string.grades_new_term else R.string.grades_rename_term), onDismiss,
        stringResource(R.string.save), confirmEnabled = name.isNotBlank(), onConfirm = { onSave(name.trim()) },
    ) {
        OutlinedTextField(
            name, { name = it }, label = { Text(stringResource(R.string.grades_term_name)) },
            placeholder = { Text(stringResource(R.string.grades_term_hint)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------- course ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseDialog(termId: Long?, course: Course?, scale: GradeScale, onDismiss: () -> Unit, onSave: (Long, Course) -> Unit) {
    val c = D.c
    val terms = GradeBook.data.terms
    val subjects = rememberSubjects()
    var name by remember { mutableStateOf(course?.name ?: "") }
    var code by remember { mutableStateOf(course?.code ?: "") }
    var credits by remember { mutableStateOf(course?.let { fmtNum(it.credits) } ?: "3") }
    var subject by remember { mutableStateOf(course?.subject ?: "") }
    var passFail by remember { mutableStateOf(course?.passFail ?: false) }
    var letterMode by remember { mutableStateOf(course?.letterMode ?: false) }
    var letter by remember { mutableStateOf(course?.letter ?: "") }
    var term by remember { mutableStateOf(termId ?: terms.lastOrNull()?.id ?: -1L) }
    val cr = parseNum(credits)
    val crOk = cr != null && cr >= 0 && cr <= 60
    val effectiveName = name.trim().ifBlank { subjects.firstOrNull { it.path == subject }?.name ?: "" }
    val canSave = effectiveName.isNotBlank() && crOk && terms.any { it.id == term }

    GDialogShell(
        stringResource(if (course == null) R.string.grades_new_course else R.string.grades_edit_course), onDismiss,
        stringResource(R.string.save), confirmEnabled = canSave,
        onConfirm = {
            val base = course ?: Course(GradeBook.newId(), effectiveName)
            onSave(term, base.copy(name = effectiveName, code = code.trim(), credits = cr ?: 0.0, subject = subject,
                passFail = passFail, letterMode = letterMode, letter = if (letterMode) normLetter(letter) else base.letter))
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.grades_course_name)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.grades_course_code), maxLines = 1) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.weight(1f))
            NumField(credits, { credits = it }, stringResource(R.string.grades_credits), Modifier.weight(1f), error = !crOk)
        }
        if (terms.size > 1) {
            SectionLabel(stringResource(R.string.grades_term))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                terms.forEach { t -> Chip(t.name, t.id == term, { term = t.id }) }
            }
        }
        SectionLabel(stringResource(R.string.grades_subject))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.grades_no_subject), subject.isEmpty(), { subject = "" })
            subjects.forEach { s ->
                Chip(s.name, subject == s.path, { subject = s.path }, leading = studyIcon(s.meta.icon), tint = folderColor(s.meta.color))
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { passFail = !passFail }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.grades_pass_fail), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
            Switch(passFail, { passFail = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
        }
        if (!passFail) {
            SectionLabel(stringResource(R.string.grades_how))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.grades_mode_assess), !letterMode, { letterMode = false }, leading = Icons.Rounded.Functions)
                Chip(stringResource(R.string.grades_mode_letter), letterMode, { letterMode = true }, leading = Icons.Rounded.Grade)
            }
            if (letterMode) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scale.bands.forEach { b ->
                        Chip(showLetter(b.letter), sameLetter(b.letter, letter), { letter = b.letter }, tint = letterColor(b.letter))
                    }
                }
            }
        }
    }
}

// ---------- assessment ----------

@Composable
fun AssessmentDialog(a: Assessment?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Assessment) -> Unit) {
    val c = D.c
    var name by remember { mutableStateOf(a?.name ?: "") }
    var weight by remember { mutableStateOf(a?.let { fmtNum(it.weight) } ?: "") }
    var score by remember { mutableStateOf(a?.score?.let { fmtNum(it) } ?: "") }
    var outOf by remember { mutableStateOf(a?.let { fmtNum(it.outOf) } ?: "100") }
    val w = parseNum(weight)
    val o = parseNum(outOf)
    val s = if (score.isBlank()) null else parseNum(score)
    val wOk = w != null && w >= 0 && w <= 100
    val oOk = o != null && o > 0
    val sOk = score.isBlank() || (s != null && s >= 0 && (o == null || s <= o * 2))
    GDialogShell(
        stringResource(if (a == null) R.string.grades_new_assessment else R.string.grades_edit_assessment), onDismiss,
        stringResource(R.string.save), confirmEnabled = name.isNotBlank() && wOk && oOk && sOk,
        onConfirm = {
            val base = a ?: Assessment(GradeBook.newId(), name.trim(), w ?: 0.0)
            onSave(base.copy(name = name.trim(), weight = w ?: 0.0, score = s, outOf = o ?: 100.0))
        },
        extra = onDelete?.let { del -> { TextButton(onClick = del) { Text(stringResource(R.string.delete), color = c.danger) } } },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.grades_assess_name)) },
            placeholder = { Text(stringResource(R.string.grades_tpl_midterm)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
        NumField(weight, { weight = it }, stringResource(R.string.grades_weight), Modifier.fillMaxWidth(), error = weight.isNotBlank() && !wOk)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumField(score, { score = it }, stringResource(R.string.grades_score), Modifier.weight(1f), error = !sOk)
            Text("/", color = c.muted)
            NumField(outOf, { outOf = it }, stringResource(R.string.grades_out_of), Modifier.weight(1f), error = !oOk)
        }
        Text(
            if (s != null && o != null && o > 0) fmtPct(s / o * 100) else stringResource(R.string.grades_score_hint),
            style = MaterialTheme.typography.bodySmall, color = c.muted,
        )
    }
}

// ---------- scale ----------

private class BandRow(letter: String, min: String, points: String) {
    var letter by mutableStateOf(letter)
    var min by mutableStateOf(min)
    var points by mutableStateOf(points)
}

@Composable
fun ScaleDialog(data: GradesData, onDismiss: () -> Unit, onSave: (String, Boolean, GradeScale?) -> Unit) {
    val c = D.c
    var id by remember { mutableStateOf(data.scaleId) }
    var a433 by remember { mutableStateOf(data.usAPlus433) }
    val rows = remember {
        val src = data.custom?.takeIf { it.bands.isNotEmpty() } ?: Scales.active(data)
        mutableStateListOf<BandRow>().apply { src.sorted().bands.forEach { add(BandRow(it.letter, fmtNum(it.minPct), fmtNum(it.points))) } }
    }
    fun loadFrom(s: GradeScale) { rows.clear(); s.bands.forEach { rows.add(BandRow(it.letter, fmtNum(it.minPct), fmtNum(it.points))) } }

    val parsed = rows.map { Triple(normLetter(it.letter), parseNum(it.min), parseNum(it.points)) }
    val customOk = parsed.isNotEmpty() && parsed.all { (l, m, p) -> l.isNotBlank() && m != null && m in 0.0..100.0 && p != null && p >= 0 } &&
        parsed.map { it.first.uppercase() }.distinct().size == parsed.size
    val canSave = id != Scales.CUSTOM || customOk

    GDialogShell(
        stringResource(R.string.grades_scale), onDismiss, stringResource(R.string.save), confirmEnabled = canSave,
        onConfirm = {
            val custom = if (customOk) GradeScale(Scales.CUSTOM, parsed.map { (l, m, p) -> GradeBand(l, m!!, p!!) }, parsed.maxOf { it.third!! }).sorted() else data.custom
            onSave(id, a433, custom)
        },
    ) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Scales.US40, Scales.SA50, Scales.SA40, Scales.CUSTOM).forEach { sid ->
                Chip(scaleName(sid, data.copy(usAPlus433 = a433)), id == sid, {
                    if (sid == Scales.CUSTOM && id != Scales.CUSTOM && data.custom == null) loadFrom(Scales.preset(id, a433))
                    id = sid
                })
            }
        }
        if (id == Scales.US40) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { a433 = !a433 }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.grades_aplus_433), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
                Switch(a433, { a433 = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
            }
        }
        // Header row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.grades_col_letter), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.grades_col_min), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.grades_col_points), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f))
            if (id == Scales.CUSTOM) Spacer(Modifier.width(40.dp))
        }
        if (id != Scales.CUSTOM) {
            val s = Scales.preset(id, a433)
            s.bands.forEach { b ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { LetterBadge(b.letter, 32.dp) }
                    Text("≥ " + fmtPct(b.minPct), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
                    Text(fmtNum(b.points), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
                }
            }
            TextButton(onClick = { loadFrom(s); id = Scales.CUSTOM }) {
                Icon(Icons.Rounded.Edit, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.grades_scale_customize), color = c.accent)
            }
        } else {
            rows.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(r.letter, { r.letter = it.take(4) }, singleLine = true, isError = r.letter.isBlank(), modifier = Modifier.weight(1f))
                    OutlinedTextField(r.min, { r.min = it }, singleLine = true, isError = parseNum(r.min)?.let { it !in 0.0..100.0 } ?: true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(r.points, { r.points = it }, singleLine = true, isError = parseNum(r.points)?.let { it < 0 } ?: true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    IconButton(onClick = { rows.removeAt(i) }, enabled = rows.size > 1, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.delete), tint = c.muted)
                    }
                }
            }
            TextButton(onClick = { rows.add(BandRow("", "", "")) }) {
                Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.grades_add_band), color = c.accent)
            }
            if (!customOk) Text(stringResource(R.string.grades_scale_invalid), style = MaterialTheme.typography.bodySmall, color = c.danger)
            else Text(stringResource(R.string.grades_scale_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

// ---------- grade a planner exam ----------

/**
 * "Grade it" from a past planner exam: pick (or create) the course and the component, enter the score.
 * The component remembers the exam (Assessment.eventId), so grading the same exam again edits it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GradeExamDialog(eventId: Long, onDismiss: () -> Unit, onDone: (Long) -> Unit) {
    val c = D.c
    val ctx = LocalContext.current
    val ev = remember(eventId) { Planner.get(eventId) }
    if (ev == null || ev.type != EventType.EXAM) { LaunchedEffect(Unit) { onDismiss() }; return }
    val data = GradeBook.data
    val all = remember(GradeBook.version) { data.terms.flatMap { t -> t.courses.map { t to it } } }
    val already = remember { GradeBook.gradedFrom(eventId) }
    val evSubject = remember(ev) { if (ev.folder.isNotEmpty()) subjectOf(File(ev.folder)) else "" }
    val guess = remember {
        already?.first?.id
            ?: all.firstOrNull { evSubject.isNotEmpty() && it.second.subject == evSubject }?.second?.id
            ?: all.firstOrNull { (_, co) -> ev.title.contains(co.name, true) || (co.code.isNotBlank() && ev.title.contains(co.code, true)) }?.second?.id
            ?: all.lastOrNull()?.second?.id
            ?: -1L
    }
    var courseId by remember { mutableStateOf(guess) }
    var newCourseName by remember { mutableStateOf(if (evSubject.isNotEmpty()) File(evSubject).name else ev.title) }
    val course = all.firstOrNull { it.second.id == courseId }?.second
    val ungraded = course?.assessments?.filter { it.pct == null || it.id == already?.second?.id }.orEmpty()
    var compId by remember { mutableStateOf(already?.second?.id ?: -1L) }
    LaunchedEffect(courseId) {
        if (already?.first?.id != courseId) {
            // best guess: a component whose name appears in the exam title, else the first ungraded one
            compId = ungraded.firstOrNull { ev.title.contains(it.name, true) }?.id ?: ungraded.firstOrNull()?.id ?: -1L
        }
    }
    var compName by remember { mutableStateOf(ev.title) }
    var weight by remember { mutableStateOf("") }
    var score by remember { mutableStateOf(already?.second?.score?.let { fmtNum(it) } ?: "") }
    var outOf by remember { mutableStateOf(already?.second?.outOf?.let { fmtNum(it) } ?: "100") }
    val s = parseNum(score); val o = parseNum(outOf); val w = parseNum(weight)
    val newComp = course == null || compId == -1L
    val ok = s != null && s >= 0 && o != null && o > 0 &&
        (course != null || newCourseName.isNotBlank()) && (!newComp || (compName.isNotBlank() && w != null && w > 0 && w <= 100))
    val savedMsg = stringResource(R.string.grades_saved)
    val defaultTerm = stringResource(R.string.grades_default_term)

    GDialogShell(
        stringResource(R.string.grades_grade_exam), onDismiss, stringResource(R.string.save), confirmEnabled = ok,
        onConfirm = {
            var target = course
            if (target == null) {
                val termId = data.terms.lastOrNull()?.id ?: Term(GradeBook.newId(), defaultTerm).also { GradeBook.upsertTerm(it) }.id
                target = Course(GradeBook.newId(), newCourseName.trim(), subject = evSubject)
                GradeBook.upsertCourse(termId, target)
            }
            val existing = target.assessments.firstOrNull { it.id == compId }
            val a = existing?.copy(score = s, outOf = o!!, eventId = eventId)
                ?: Assessment(GradeBook.newId(), compName.trim(), w ?: 0.0, s, o!!, eventId)
            GradeBook.upsertAssessment(target.id, a)
            toast(ctx, savedMsg)
            onDone(target.id)
        },
    ) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Quiz, null, tint = com.daftar.app.planner.typeColor(EventType.EXAM))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(ev.title, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(com.daftar.app.planner.fmtDate(ctx, Planner.dateOf(ev.start)), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
        SectionLabel(stringResource(R.string.grades_exam_course))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            all.forEach { (t, co) ->
                Chip(if (data.terms.size > 1) "${co.name} · ${t.name}" else co.name, co.id == courseId, { courseId = co.id },
                    tint = courseColor(co))
            }
            Chip(stringResource(R.string.grades_new_course), courseId == -1L, { courseId = -1L }, leading = Icons.Rounded.Add)
        }
        if (course == null) {
            OutlinedTextField(newCourseName, { newCourseName = it }, label = { Text(stringResource(R.string.grades_course_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
        }
        SectionLabel(stringResource(R.string.grades_exam_component))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ungraded.forEach { a -> Chip("${a.name} · ${fmtPct(a.weight)}", a.id == compId, { compId = a.id }) }
            Chip(stringResource(R.string.grades_new_component), newComp, { compId = -1L }, leading = Icons.Rounded.Add)
        }
        if (newComp) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(compName, { compName = it }, label = { Text(stringResource(R.string.grades_assess_name)) }, singleLine = true,
                    modifier = Modifier.weight(1.6f))
                NumField(weight, { weight = it }, stringResource(R.string.grades_weight), Modifier.weight(1f), error = weight.isNotBlank() && (w == null || w <= 0 || w > 100))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumField(score, { score = it }, stringResource(R.string.grades_score), Modifier.weight(1f), error = score.isNotBlank() && (s == null || s < 0))
            Text("/", color = c.muted)
            NumField(outOf, { outOf = it }, stringResource(R.string.grades_out_of), Modifier.weight(1f), error = o == null || o <= 0)
        }
    }
}
