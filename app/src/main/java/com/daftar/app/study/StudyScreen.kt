package com.daftar.app.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ai.AiPrefs
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.Screen
import com.daftar.app.ui.SectionTitle
import com.daftar.app.ui.TextInputDialog
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.card
import com.daftar.app.ui.pane
import com.daftar.app.ui.rememberTickingNow
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun gutter() = if (LocalWidthClass.current == WidthClass.Compact) D.gutter else D.gutterWide

@Composable
internal fun StudyScreenImpl() {
    val c = D.c
    val expanded = LocalWidthClass.current == WidthClass.Expanded
    val now = rememberTickingNow(60_000)
    val quizzes = rememberQuizList()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { StudyCleanup.runOnce() } }
    val minutesToday = remember(StudyLog.version, now / DAY_MS) { StudyLog.minutesToday() }
    val sub = buildList {
        if (quizzes.isNotEmpty()) add(pluralStringResource(R.plurals.quiz_n_quizzes, quizzes.size, quizzes.size))
        add(stringResource(R.string.study_focus_today, minutesToday))
    }.joinToString(" · ")

    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = gutter()).padding(top = 20.dp, bottom = 8.dp)) {
            Text(stringResource(R.string.study_title), style = MaterialTheme.typography.displaySmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        Column(Modifier.padding(horizontal = gutter())) {
            if (expanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.6f)) { QuizColumn(quizzes) }
                    Column(Modifier.weight(1f)) { com.daftar.app.grades.GradesEntryCard(); FocusSection(); StatsSection() }
                }
            } else {
                QuizColumn(quizzes)
                com.daftar.app.grades.GradesEntryCard()
                FocusSection()
                StatsSection()
            }
            Spacer(Modifier.navigationBarsPadding().height(48.dp))
        }
    }
}

@Composable
private fun QuizColumn(quizzes: List<Quiz>) {
    val c = D.c
    Spacer(Modifier.height(8.dp))
    if (!AiPrefs.hasKey) { NoKeyCard(); Spacer(Modifier.height(12.dp)) }
    // prominent "New quiz"
    Row(Modifier.fillMaxWidth().card(c).clip(RoundedCornerShape(16.dp)).clickable { newQuiz() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Rounded.AutoAwesome, c.accent, 48)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.quiz_practice), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(stringResource(R.string.quiz_practice_desc), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = { newQuiz() }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.quiz_new))
        }
    }
    SectionTitle(stringResource(R.string.quiz_saved))
    if (quizzes.isEmpty()) {
        Box(Modifier.fillMaxWidth().card(c)) { EmptyState(Icons.Rounded.Quiz, stringResource(R.string.quiz_none)) }
        return
    }
    Column(Modifier.fillMaxWidth().card(c).padding(4.dp)) {
        quizzes.forEachIndexed { i, q ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(c.line))
            QuizRow(q)
        }
    }
}

private fun newQuiz() { QuizRequests.prepare(null, null, null); pane.push(Screen.NewQuiz) }

@Composable
private fun QuizRow(q: Quiz) {
    val c = D.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val last = q.results.lastOrNull()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { pane.push(Screen.Quiz(q.id)) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Rounded.Quiz, c.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(q.title, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val info = buildList {
                add(pluralStringResource(R.plurals.quiz_n_questions, q.questions.size, q.questions.size))
                if (q.sourceName.isNotEmpty()) add(if (q.pages.isNotEmpty()) stringResource(R.string.quiz_source_pages, q.sourceName, q.pages) else q.sourceName)
                else if (q.fromSelection) add(stringResource(R.string.quiz_selection))
            }.joinToString(" · ")
            Text(info, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            q.inProgress -> Text(stringResource(R.string.quiz_in_progress), style = MaterialTheme.typography.labelMedium, color = c.accent)
            last != null -> Text("${last.percent}%", style = MaterialTheme.typography.labelLarge,
                color = when { last.percent >= 80 -> OkColor; last.percent >= 50 -> PartColor; else -> c.danger })
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = c.muted) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.quiz_open)) }, { menu = false; pane.push(Screen.Quiz(q.id)) },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) })
                DropdownMenuItem({ Text(stringResource(R.string.quiz_retake)) }, {
                    menu = false
                    scope.launch { QuizStore.save(q.fresh()); pane.push(Screen.Quiz(q.id)) }
                }, leadingIcon = { Icon(Icons.Rounded.Replay, null) })
                if (quizSourceExists(q)) DropdownMenuItem({ Text(stringResource(R.string.quiz_open_source)) }, {
                    menu = false; pane.open(ctx, File(q.sourcePath))
                }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) })
                DropdownMenuItem({ Text(stringResource(R.string.quiz_rename)) }, { menu = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                DropdownMenuItem({ Text(stringResource(R.string.quiz_delete), color = c.danger) }, { menu = false; delete = true },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.danger) })
            }
        }
    }
    if (rename) TextInputDialog(stringResource(R.string.quiz_rename), q.title, stringResource(R.string.quiz_save), onDismiss = { rename = false }) { t ->
        rename = false; scope.launch { QuizStore.rename(q.id, t) }
    }
    if (delete) ConfirmDialog(stringResource(R.string.quiz_delete_title), stringResource(R.string.quiz_delete_text), stringResource(R.string.quiz_delete), danger = true,
        onDismiss = { delete = false }) { delete = false; scope.launch { QuizStore.delete(q.id) } }
}
