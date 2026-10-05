package com.daftar.app.grades

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.Fab
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.card
import com.daftar.app.ui.pane
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.launch

/** Which add/edit dialog is showing. */
private sealed class GDialog {
    data class TermEdit(val term: Term?) : GDialog()
    data class CourseEdit(val termId: Long?, val course: Course?) : GDialog()
    data class AssessmentEdit(val courseId: Long, val a: Assessment?) : GDialog()
    data object Scale : GDialog()
    data class DeleteTerm(val term: Term) : GDialog()
    data class DeleteCourse(val course: Course) : GDialog()
    data class GradeExam(val eventId: Long) : GDialog()
}

/** GPA / grades tracker: terms → courses → assessments; list + detail (two panes when wide). */
@Composable
fun GradesScreen() {
    val ctx = LocalContext.current
    remember { GradeBook.init(ctx); true }
    val c = D.c
    @Suppress("UNUSED_VARIABLE") val v = GradeBook.version
    val data = GradeBook.data
    val scale = GradeBook.scale
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<GDialog?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val compact = LocalWidthClass.current == WidthClass.Compact
    val gutter = if (compact) D.gutter else D.gutterWide

    // "Grade it" from a past planner exam.
    val pending = GradeBook.pendingExam
    LaunchedEffect(pending) {
        if (pending != null) { GradeBook.pendingExam = null; dialog = GDialog.GradeExam(pending) }
    }
    val course = selected?.let { GradeBook.course(it) }
    LaunchedEffect(course == null) { if (course == null) selected = null }

    val deletedMsg = stringResource(R.string.grades_deleted)
    val undo = stringResource(R.string.grades_undo)

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val twoPane = maxWidth >= 760.dp
        val detailOnly = !twoPane && course != null
        BackHandler(enabled = detailOnly) { selected = null }

        Column(Modifier.fillMaxSize()) {
            ViewerTopBar(
                if (detailOnly) course!!.name else stringResource(R.string.grades_title),
                onBack = { if (detailOnly) selected = null else pane.back() },
            ) {
                IconButton(onClick = { dialog = GDialog.Scale }) {
                    Icon(Icons.Rounded.Tune, stringResource(R.string.grades_scale), tint = c.muted)
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (!detailOnly) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        TermsList(
                            data, scale, selected, gutter, compactSummary = compact || twoPane,
                            onCourse = { selected = it.id },
                            onAddTerm = { dialog = GDialog.TermEdit(null) },
                            onEditTerm = { dialog = GDialog.TermEdit(it) },
                            onDeleteTerm = { dialog = GDialog.DeleteTerm(it) },
                            onAddCourse = { dialog = GDialog.CourseEdit(it.id, null) },
                            onScale = { dialog = GDialog.Scale },
                        )
                    }
                }
                if (twoPane) Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                if (twoPane || detailOnly) {
                    Box(Modifier.weight(if (twoPane) 1.15f else 1f).fillMaxHeight()) {
                        if (course != null) {
                            CourseDetail(
                                course, GradeBook.termOf(course.id)?.name.orEmpty(), scale, gutter,
                                onEdit = { dialog = GDialog.CourseEdit(GradeBook.termOf(course.id)?.id, course) },
                                onDelete = { dialog = GDialog.DeleteCourse(course) },
                                onAddAssessment = { dialog = GDialog.AssessmentEdit(course.id, null) },
                                onEditAssessment = { dialog = GDialog.AssessmentEdit(course.id, it) },
                            )
                        } else {
                            EmptyState(Icons.Rounded.School, stringResource(R.string.grades_select_course), Modifier.align(Alignment.Center).padding(24.dp))
                        }
                    }
                }
            }
        }
        if (!detailOnly) {
            Fab({
                dialog = if (data.terms.isEmpty()) GDialog.TermEdit(null)
                else GDialog.CourseEdit(GradeBook.termOf(selected ?: -1)?.id ?: data.terms.last().id, null)
            }, Modifier.align(Alignment.BottomEnd).padding(24.dp).navigationBarsPadding())
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }

    when (val d = dialog) {
        null -> {}
        is GDialog.TermEdit -> TermDialog(d.term, onDismiss = { dialog = null }) { name ->
            val t = d.term?.copy(name = name) ?: Term(GradeBook.newId(), name)
            GradeBook.upsertTerm(t)
            dialog = null
        }
        is GDialog.CourseEdit -> CourseDialog(d.termId, d.course, scale, onDismiss = { dialog = null }) { termId, newCourse ->
            GradeBook.upsertCourse(termId, newCourse)
            selected = newCourse.id
            dialog = null
        }
        is GDialog.AssessmentEdit -> AssessmentDialog(d.a, onDismiss = { dialog = null },
            onDelete = if (d.a != null) ({ GradeBook.deleteAssessment(d.courseId, d.a.id); dialog = null }) else null) { a ->
            GradeBook.upsertAssessment(d.courseId, a)
            dialog = null
        }
        GDialog.Scale -> ScaleDialog(data, onDismiss = { dialog = null }) { id, a433, custom ->
            GradeBook.setScale(id, a433, custom)
            dialog = null
        }
        is GDialog.DeleteTerm -> ConfirmDialog(
            stringResource(R.string.grades_delete_term), stringResource(R.string.grades_delete_term_msg, d.term.name),
            stringResource(R.string.delete), danger = true, onDismiss = { dialog = null },
        ) {
            val idx = data.terms.indexOfFirst { it.id == d.term.id }
            GradeBook.deleteTerm(d.term.id)
            dialog = null
            scope.launch {
                if (snack.showSnackbar(deletedMsg, undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) GradeBook.restoreTerm(d.term, idx)
            }
        }
        is GDialog.DeleteCourse -> ConfirmDialog(
            stringResource(R.string.grades_delete_course), stringResource(R.string.grades_delete_course_msg, d.course.name),
            stringResource(R.string.delete), danger = true, onDismiss = { dialog = null },
        ) {
            val termId = GradeBook.termOf(d.course.id)?.id
            GradeBook.deleteCourse(d.course.id)
            selected = null
            dialog = null
            if (termId != null) scope.launch {
                if (snack.showSnackbar(deletedMsg, undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) GradeBook.upsertCourse(termId, d.course)
            }
        }
        is GDialog.GradeExam -> GradeExamDialog(d.eventId, onDismiss = { dialog = null }) { courseId ->
            selected = courseId
            dialog = null
        }
    }
}

@Composable
private fun TermsList(
    data: GradesData, scale: GradeScale, selected: Long?, gutter: androidx.compose.ui.unit.Dp, compactSummary: Boolean,
    onCourse: (Course) -> Unit, onAddTerm: () -> Unit, onEditTerm: (Term) -> Unit, onDeleteTerm: (Term) -> Unit,
    onAddCourse: (Term) -> Unit, onScale: () -> Unit,
) {
    val c = D.c
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gutter, end = gutter, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "summary") { SummaryCard(data, scale, stacked = compactSummary, onScale = onScale) }
        if (data.terms.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().card(c)) {
                    EmptyState(Icons.Rounded.School, stringResource(R.string.grades_empty)) {
                        Button(onClick = onAddTerm, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                            Text(stringResource(R.string.grades_add_term))
                        }
                    }
                }
            }
        }
        // Newest term first (terms are stored oldest → newest for the trend chart).
        val terms = data.terms.reversed()
        terms.forEachIndexed { i, t ->
            item(key = "t${t.id}") {
                TermHeader(
                    t, scale, canUp = i > 0, canDown = i < terms.lastIndex,
                    onAddCourse = { onAddCourse(t) }, onEdit = { onEditTerm(t) }, onDelete = { onDeleteTerm(t) },
                    // the list is reversed: "up" on screen = later in the stored order
                    onMove = { up -> GradeBook.moveTerm(t.id, if (up) 1 else -1) },
                )
            }
            if (t.courses.isEmpty()) item(key = "e${t.id}") {
                Text(stringResource(R.string.grades_empty_term), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            }
            items(t.courses, key = { "c${it.id}" }) { co -> CourseCard(co, scale, co.id == selected) { onCourse(co) } }
        }
        if (data.terms.isNotEmpty()) item(key = "addterm") {
            TextButton(onClick = onAddTerm) {
                Icon(Icons.Rounded.Add, null, tint = c.accent)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.grades_add_term), color = c.accent)
            }
        }
    }
}
