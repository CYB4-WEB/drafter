package com.daftar.app.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftar.app.R
import com.daftar.app.ai.AiPrefs
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.TextInputDialog
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.card
import com.daftar.app.ui.pane
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Screen state for one quiz: the loaded quiz, grading jobs and errors. */
private class QuizHolder {
    var quiz by mutableStateOf<Quiz?>(null)
    var loaded by mutableStateOf(false)
    /** Questions being graded by Gemini. */
    var grading by mutableStateOf(emptySet<Int>())
    var submitting by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var retry: (() -> Unit)? = null
    var job: Job? = null
}

@Composable
internal fun QuizScreenImpl(id: String) {
    val c = D.c
    val scope = rememberCoroutineScope()
    val h = remember(id) { QuizHolder() }
    LaunchedEffect(id) { h.quiz = QuizStore.get(id); h.loaded = true }
    var listMode by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val compact = LocalWidthClass.current == WidthClass.Compact
    val oneAtATime = !(listMode ?: !compact)
    var index by rememberSaveable(id) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf<(() -> Unit)?>(null) }

    BackHandler { h.job?.cancel(); pane.back() }

    val quiz = h.quiz
    if (quiz == null) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            ViewerTopBar(stringResource(R.string.quiz_title), onBack = { pane.back() })
            if (h.loaded) EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.quiz_not_found))
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
        }
        return
    }

    fun update(q: Quiz) { h.quiz = q; scope.launch { QuizStore.save(q) } }
    fun setAnswer(i: Int, a: QuizAnswer) { h.quiz?.let { q -> update(q.copy(answers = List(q.questions.size) { k -> if (k == i) a else q.answer(k) }, updated = System.currentTimeMillis())) } }

    /** Runs a Gemini call after the one-time privacy notice. */
    fun withPrivacy(block: () -> Unit) { if (AiPrefs.privacyAccepted) block() else privacy = block }

    fun gradeText(indices: List<Int>, then: (Quiz) -> Unit = {}) {
        val q0 = h.quiz ?: return
        if (indices.isEmpty()) { then(q0); return }
        h.error = null
        h.grading = h.grading + indices
        h.job = scope.launch {
            try {
                val res = QuizAi.grade(q0, indices)
                val cur = h.quiz ?: return@launch
                val ans = List(cur.questions.size) { k ->
                    val a = cur.answer(k)
                    res[k]?.let { (s, fb) -> a.copy(checked = true, score = s, feedback = fb) } ?: a
                }
                val nq = cur.copy(answers = ans, updated = System.currentTimeMillis())
                update(nq)
                then(nq)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                h.error = e
                h.retry = { withPrivacy { gradeText(indices, then) } }
            } finally { h.grading = h.grading - indices.toSet(); h.submitting = false }
        }
    }

    fun check(i: Int) {
        val q = h.quiz ?: return
        val qq = q.questions[i]
        if (qq.local) setAnswer(i, gradeLocal(qq, q.answer(i)))
        else withPrivacy { gradeText(listOf(i)) }
    }

    fun finish() {
        val q = h.quiz ?: return
        // local questions now; the rest in one Gemini call
        val local = List(q.questions.size) { k -> val a = q.answer(k); if (q.questions[k].local && !a.checked) gradeLocal(q.questions[k], a) else a }
        val q1 = q.copy(answers = local)
        h.quiz = q1
        val pending = q1.questions.indices.filter { !q1.questions[it].local && !q1.answer(it).checked }
        val close: (Quiz) -> Unit = { done ->
            val fin = done.copy(finished = true, results = done.results + QuizResult(System.currentTimeMillis(), done.percent), updated = System.currentTimeMillis())
            update(fin)
        }
        if (pending.none { q1.answer(it).text.isNotBlank() }) {
            // nothing for Gemini to read: blank answers score 0
            val ans = List(q1.questions.size) { k -> if (k in pending) q1.answer(k).copy(checked = true, score = 0) else q1.answer(k) }
            close(q1.copy(answers = ans))
        } else {
            h.submitting = true
            withPrivacy { gradeText(pending, close) }
        }
    }

    fun retake() { h.job?.cancel(); h.error = null; update(quiz.fresh()); index = 0 }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ViewerTopBar(quiz.title, onBack = { h.job?.cancel(); pane.back() }, onTitleClick = { rename = true }) {
            if (!quiz.finished) IconButton(onClick = { listMode = oneAtATime }) {
                Icon(if (oneAtATime) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.ViewCarousel,
                    stringResource(if (oneAtATime) R.string.quiz_show_all else R.string.quiz_one_by_one), tint = c.ink)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = c.ink) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.quiz_retake)) }, { menu = false; retake() }, leadingIcon = { Icon(Icons.Rounded.Replay, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.quiz_rename)) }, { menu = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.quiz_delete), color = c.danger) }, { menu = false; delete = true },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.danger) })
                }
            }
        }
        val err = h.error
        val errorCard: @Composable () -> Unit = {
            if (err != null) AiErrorCard(err, onRetry = h.retry?.let { r -> { h.error = null; r() } }, onDismiss = { h.error = null },
                modifier = Modifier.padding(bottom = 12.dp))
        }
        when {
            quiz.finished -> Results(quiz, onRetake = { retake() })
            oneAtATime -> OneByOne(quiz, index.coerceIn(0, quiz.questions.lastIndex), { index = it }, h, errorCard,
                onAnswer = ::setAnswer, onCheck = ::check, onFinish = ::finish)
            else -> AllQuestions(quiz, h, errorCard, onAnswer = ::setAnswer, onCheck = ::check, onFinish = ::finish)
        }
    }

    if (rename) TextInputDialog(stringResource(R.string.quiz_rename), quiz.title, stringResource(R.string.quiz_save), onDismiss = { rename = false }) { t ->
        rename = false; update(quiz.copy(title = t))
    }
    if (delete) ConfirmDialog(stringResource(R.string.quiz_delete_title), stringResource(R.string.quiz_delete_text), stringResource(R.string.quiz_delete), danger = true,
        onDismiss = { delete = false }) {
        delete = false; h.job?.cancel()
        scope.launch { QuizStore.delete(quiz.id) }
        pane.back()
    }
    privacy?.let { block -> AiPrivacyDialog(onDismiss = { privacy = null; h.submitting = false }) { privacy = null; block() } }
}

// =====================================================================================
// Modes
// =====================================================================================

@Composable
private fun SourceLine(quiz: Quiz) {
    val c = D.c
    val parts = buildList {
        quizSourceLabel(quiz)?.let { add(it) }
        if (quiz.fromSelection) add(stringResource(R.string.quiz_selection))
        add(pluralStringResource(R.plurals.quiz_n_questions, quiz.questions.size, quiz.questions.size))
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun OneByOne(
    quiz: Quiz, index: Int, onIndex: (Int) -> Unit, h: QuizHolder, errorCard: @Composable () -> Unit,
    onAnswer: (Int, QuizAnswer) -> Unit, onCheck: (Int) -> Unit, onFinish: () -> Unit,
) {
    val c = D.c
    val n = quiz.questions.size
    val q = quiz.questions[index]
    val a = quiz.answer(index)
    val scroll = rememberScrollState()
    LaunchedEffect(index) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator({ (index + 1f) / n }, Modifier.fillMaxWidth().height(3.dp), color = c.accent, trackColor = c.surfaceAlt)
        Column(Modifier.weight(1f).verticalScroll(scroll).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = gutter(), vertical = 16.dp)) {
                SourceLine(quiz)
                Text(stringResource(R.string.quiz_q_of, index + 1, n), style = MaterialTheme.typography.labelLarge, color = c.muted)
                Spacer(Modifier.height(8.dp))
                QuestionCard(index, q, a, grading = index in h.grading, onAnswer = { onAnswer(index, it) })
                Spacer(Modifier.height(12.dp))
                errorCard()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(horizontal = gutter(), vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onIndex(index - 1) }, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.quiz_prev))
            }
            Spacer(Modifier.weight(1f))
            val busy = index in h.grading || h.submitting
            if (!a.checked) {
                TextButton(onClick = { onCheck(index) }, enabled = !a.isBlank(q) && !busy) { Text(stringResource(R.string.quiz_check)) }
            }
            if (index < n - 1) Button(onClick = { onIndex(index + 1) }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                Text(stringResource(R.string.quiz_next)); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
            } else FinishButton(quiz, h, onFinish)
        }
    }
}

@Composable
private fun AllQuestions(
    quiz: Quiz, h: QuizHolder, errorCard: @Composable () -> Unit,
    onAnswer: (Int, QuizAnswer) -> Unit, onCheck: (Int) -> Unit, onFinish: () -> Unit,
) {
    val c = D.c
    val state = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = state, horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = gutter())) { SourceLine(quiz) } }
        itemsIndexed(quiz.questions, key = { i, _ -> i }) { i, q ->
            val a = quiz.answer(i)
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = gutter()).padding(bottom = 12.dp)) {
                QuestionCard(i, q, a, grading = i in h.grading, onAnswer = { onAnswer(i, it) }, number = true)
                if (!a.checked) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onCheck(i) }, enabled = !a.isBlank(q) && i !in h.grading && !h.submitting) { Text(stringResource(R.string.quiz_check)) }
                }
            }
        }
        item {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = gutter()).navigationBarsPadding()) {
                errorCard()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.quiz_answered, quiz.answeredCount, quiz.questions.size), style = MaterialTheme.typography.bodyMedium,
                        color = c.muted, modifier = Modifier.weight(1f))
                    FinishButton(quiz, h, onFinish)
                }
            }
        }
    }
}

@Composable
private fun FinishButton(quiz: Quiz, h: QuizHolder, onFinish: () -> Unit) {
    val c = D.c
    var confirm by remember { mutableStateOf(false) }
    val busy = h.submitting || h.grading.isNotEmpty()
    Button(onClick = { if (quiz.answeredCount < quiz.questions.size) confirm = true else onFinish() }, enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
        if (h.submitting) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onAccent); Spacer(Modifier.width(8.dp)) }
        Text(stringResource(if (h.submitting) R.string.quiz_grading else R.string.quiz_finish))
    }
    if (confirm) ConfirmDialog(stringResource(R.string.quiz_finish), stringResource(R.string.quiz_unanswered, quiz.questions.size - quiz.answeredCount),
        stringResource(R.string.quiz_finish), onDismiss = { confirm = false }) { confirm = false; onFinish() }
}

// =====================================================================================
// Question
// =====================================================================================

@Composable
private fun QuestionCard(i: Int, q: QuizQuestion, a: QuizAnswer, grading: Boolean, onAnswer: (QuizAnswer) -> Unit, number: Boolean = false, review: Boolean = false) {
    val c = D.c
    val locked = a.checked || grading || review
    Column(Modifier.fillMaxWidth().card(c).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (number) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = c.accent,
                    modifier = Modifier.background(c.accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(if (q.multi) stringResource(R.string.quiz_select_all) else typeName(q.type), style = MaterialTheme.typography.labelMedium, color = c.muted,
                modifier = Modifier.weight(1f))
            if (a.checked) StatusPill(a.score)
        }
        Spacer(Modifier.height(10.dp))
        SelectionContainer { Text(q.question, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = c.ink) }
        if (q.source.isNotBlank()) Text(stringResource(R.string.quiz_question_source, q.source), style = MaterialTheme.typography.bodySmall,
            color = c.muted, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(14.dp))
        when (q.type) {
            QType.MCQ, QType.TF -> {
                val opts = if (q.type == QType.TF) listOf(stringResource(R.string.quiz_true), stringResource(R.string.quiz_false)) else q.options
                opts.forEachIndexed { k, label ->
                    val sel = k in a.selected
                    OptionRow(label, sel, q.multi, reveal = a.checked, correct = k in q.correct, enabled = !locked) {
                        onAnswer(a.copy(selected = if (q.multi) (if (sel) a.selected - k else (a.selected + k).sorted()) else listOf(k)))
                    }
                }
            }
            else -> {
                OutlinedTextField(
                    a.text, { onAnswer(a.copy(text = it.take(if (q.type == QType.ESSAY) 8000 else 1000))) }, Modifier.fillMaxWidth(),
                    readOnly = locked, minLines = if (q.type == QType.ESSAY) 6 else 2, maxLines = if (q.type == QType.ESSAY) 20 else 6,
                    placeholder = { Text(stringResource(R.string.quiz_your_answer), color = c.muted) },
                )
            }
        }
        if (grading) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.quiz_grading), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        if (a.checked || review) Feedback(q, a)
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, multi: Boolean, reveal: Boolean, correct: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = D.c
    val tint = when {
        reveal && correct -> OkColor
        reveal && selected -> c.danger
        selected -> c.accent
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(tint?.copy(alpha = 0.10f) ?: Color.Transparent)
            .border(1.dp, tint ?: c.line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) Checkbox(selected, { onClick() }, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = tint ?: c.accent, disabledCheckedColor = tint ?: c.muted))
        else RadioButton(selected, onClick, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = tint ?: c.accent, disabledSelectedColor = tint ?: c.muted))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (reveal && correct) Icon(Icons.Rounded.CheckCircle, null, tint = OkColor, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
        else if (reveal && selected) Icon(Icons.Rounded.Cancel, null, tint = c.danger, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
    }
}

@Composable
private fun StatusPill(score: Int) {
    val c = D.c
    val (col, txt) = when {
        score >= 100 -> OkColor to stringResource(R.string.quiz_correct)
        score > 0 -> PartColor to stringResource(R.string.quiz_partly, score)
        else -> c.danger to stringResource(R.string.quiz_wrong)
    }
    Text(txt, style = MaterialTheme.typography.labelMedium, color = col,
        modifier = Modifier.background(col.copy(alpha = 0.13f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun Feedback(q: QuizQuestion, a: QuizAnswer) {
    val c = D.c
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(c.surfaceAlt.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp)) {
        SelectionContainer {
            Column {
                if (a.feedback.isNotBlank()) {
                    Text(stringResource(R.string.quiz_feedback), style = MaterialTheme.typography.labelLarge, color = c.ink)
                    Text(a.feedback, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    Spacer(Modifier.height(8.dp))
                }
                val correct = when (q.type) {
                    QType.TF -> stringResource(if (q.correct.firstOrNull() == 0) R.string.quiz_true else R.string.quiz_false)
                    QType.MCQ -> q.correct.mapNotNull { q.options.getOrNull(it) }.joinToString(" · ")
                    else -> q.answer
                }
                if (a.score < 100 || !q.local) {
                    Text(stringResource(if (q.local) R.string.quiz_correct_answer else R.string.quiz_model_answer), style = MaterialTheme.typography.labelLarge, color = c.ink)
                    Text(correct, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    Spacer(Modifier.height(8.dp))
                }
                if (q.explanation.isNotBlank()) {
                    Text(stringResource(R.string.quiz_explanation), style = MaterialTheme.typography.labelLarge, color = c.ink)
                    Text(q.explanation, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            }
        }
    }
}

// =====================================================================================
// Results
// =====================================================================================

@Composable
private fun Results(quiz: Quiz, onRetake: () -> Unit) {
    val c = D.c
    val pct = quiz.percent
    val right = quiz.questions.indices.count { quiz.answer(it).score >= 100 }
    val col = when { pct >= 80 -> OkColor; pct >= 50 -> PartColor; else -> c.danger }
    var wrongOnly by rememberSaveable { mutableStateOf(false) }
    val shown = quiz.questions.indices.filter { !wrongOnly || quiz.answer(it).score < 100 }
    LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = gutter())) {
                SourceLine(quiz)
                Column(Modifier.fillMaxWidth().card(c).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$pct%", style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, fontWeight = FontWeight.SemiBold), color = col)
                    Text(stringResource(R.string.quiz_score_line, right, quiz.questions.size), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    Text(stringResource(when { pct >= 90 -> R.string.quiz_cheer_great; pct >= 60 -> R.string.quiz_cheer_good; else -> R.string.quiz_cheer_keep }),
                        style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    if (quiz.results.size > 1) {
                        Text(stringResource(R.string.quiz_attempts, quiz.results.joinToString("  ·  ") { "${it.percent}%" }.let { s -> if (quiz.results.size > 6) "…  " + quiz.results.takeLast(6).joinToString("  ·  ") { "${it.percent}%" } else s }),
                            style = MaterialTheme.typography.bodySmall, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetake, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                        Icon(Icons.Rounded.Replay, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.quiz_retake))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.quiz_review_answers), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                    if (right < quiz.questions.size) com.daftar.app.ui.Chip(stringResource(R.string.quiz_mistakes_only), wrongOnly, { wrongOnly = !wrongOnly })
                }
            }
        }
        items(shown.size, key = { shown[it] }) { k ->
            val i = shown[k]
            Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = gutter()).padding(bottom = 12.dp)) {
                QuestionCard(i, quiz.questions[i], quiz.answer(i), grading = false, onAnswer = {}, number = true, review = true)
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
    }
}

/** "Lecture.pdf (p. 3–5)", "Lecture.pdf, Chapter 2 +3" — null when the quiz has no file source. Works for pre-v3.6 quizzes. */
@Composable
internal fun quizSourceLabel(q: Quiz): String? {
    if (q.sources.isEmpty()) return q.sourceName.takeIf { it.isNotEmpty() }?.let { n ->
        if (q.pages.isNotEmpty()) stringResource(R.string.quiz_source_pages, n, q.pages) else n
    }
    val names = q.sources.map { s -> if (s.pages.isNotEmpty()) stringResource(R.string.quiz_source_pages, s.name, s.pages) else s.name }
    return if (names.size <= 2) names.joinToString(", ") else stringResource(R.string.quiz_more_sources, names.take(2).joinToString(", "), names.size - 2)
}

/** True when the quiz has exactly one source file and it still exists ("Open source file"). */
internal fun quizSourceExists(q: Quiz) = q.sourcePath.isNotEmpty() && File(q.sourcePath).exists()
