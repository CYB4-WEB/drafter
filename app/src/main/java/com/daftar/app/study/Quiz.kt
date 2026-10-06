package com.daftar.app.study

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import com.daftar.app.R
import com.daftar.app.ai.Gemini
import com.daftar.app.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

// =====================================================================================
// Model
// =====================================================================================

/** Question types (stored as strings so old files stay readable if types are added). */
object QType {
    const val MCQ = "mcq"
    const val TF = "tf"
    const val SHORT = "short"
    const val ESSAY = "essay"
    val all = listOf(MCQ, TF, SHORT, ESSAY)
}

/**
 * One question. MCQ: [options] + [correct] indices (several = "select all that apply").
 * TF: [correct] = [0] for true, [1] for false. Short / essay: [answer] = model answer / grading rubric.
 */
@Serializable
data class QuizQuestion(
    val type: String,
    val question: String,
    val options: List<String> = emptyList(),
    val correct: List<Int> = emptyList(),
    val answer: String = "",
    val explanation: String = "",
) {
    val multi get() = type == QType.MCQ && correct.size > 1
    /** Graded on the device (MCQ / TF) — the rest is graded by Gemini. */
    val local get() = type == QType.MCQ || type == QType.TF
}

/** The user's answer; [score] 0..100 once [checked]. */
@Serializable
data class QuizAnswer(
    val selected: List<Int> = emptyList(),
    val text: String = "",
    val checked: Boolean = false,
    val score: Int = 0,
    val feedback: String = "",
) {
    fun isBlank(q: QuizQuestion) = if (q.local) selected.isEmpty() else text.isBlank()
}

@Serializable
data class QuizResult(val at: Long, val percent: Int)

@Serializable
data class Quiz(
    val id: String,
    val title: String,
    val created: Long,
    val updated: Long = created,
    /** Library file the questions came from ("" = only the selection image). */
    val sourcePath: String = "",
    val sourceName: String = "",
    /** 1-based inclusive page range of [sourcePath] ("" = whole file / not paged). */
    val pages: String = "",
    val fromSelection: Boolean = false,
    val difficulty: String = "",
    val language: String = "",
    val instructions: String = "",
    val questions: List<QuizQuestion> = emptyList(),
    /** Current attempt, parallel to [questions]. */
    val answers: List<QuizAnswer> = emptyList(),
    val finished: Boolean = false,
    /** Finished attempts (newest last). */
    val results: List<QuizResult> = emptyList(),
) {
    fun answer(i: Int) = answers.getOrNull(i) ?: QuizAnswer()
    val percent: Int get() = if (questions.isEmpty()) 0 else (questions.indices.sumOf { answer(it).score } / questions.size)
    val answeredCount get() = questions.indices.count { !answer(it).isBlank(questions[it]) }
    val inProgress get() = !finished && answers.any { !it.selected.isEmpty() || it.text.isNotBlank() }
    fun fresh() = copy(answers = questions.map { QuizAnswer() }, finished = false, updated = System.currentTimeMillis())
}

/** Local grade of an MCQ / TF answer: all-or-nothing (the exact set of correct options). */
fun gradeLocal(q: QuizQuestion, a: QuizAnswer): QuizAnswer {
    val ok = a.selected.toSet() == q.correct.toSet() && a.selected.isNotEmpty()
    return a.copy(checked = true, score = if (ok) 100 else 0, feedback = "")
}

// =====================================================================================
// Store: filesDir/study/quizzes/<id>.json
// =====================================================================================

object QuizStore {
    var version by mutableIntStateOf(0)
        private set
    private val lock = Mutex()
    private val cache = HashMap<String, Quiz>()
    private var listed = false
    private val dir get() = File(studyDir, "quizzes").apply { mkdirs() }
    private fun fileOf(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}.json")

    fun newId(): String = "q${System.currentTimeMillis()}_${(Math.random() * 1e4).toInt()}"

    /** All quizzes, newest first. */
    suspend fun all(): List<Quiz> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!listed) {
                listed = true
                dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                    val id = f.name.removeSuffix(".json")
                    if (id !in cache) runCatching { json.decodeFromString<Quiz>(f.readText()) }.getOrNull()?.let { cache[it.id] = it }
                }
            }
            cache.values.sortedByDescending { it.updated }
        }
    }

    suspend fun get(id: String): Quiz? = withContext(Dispatchers.IO) {
        lock.withLock {
            cache[id] ?: runCatching { json.decodeFromString<Quiz>(fileOf(id).readText()) }.getOrNull()?.also { cache[id] = it }
        }
    }

    suspend fun save(q: Quiz) = withContext(Dispatchers.IO) {
        lock.withLock {
            cache[q.id] = q
            atomicWrite(fileOf(q.id), json.encodeToString(q))
        }
        withContext(Dispatchers.Main) { version++ }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock { cache.remove(id); fileOf(id).delete() }
        withContext(Dispatchers.Main) { version++ }
    }

    suspend fun rename(id: String, title: String) { get(id)?.let { save(it.copy(title = title.trim().ifBlank { it.title })) } }
}

/** Saved quizzes (newest first), reloaded when the store changes. Loads off the main thread. */
@Composable
fun rememberQuizList(): List<Quiz> {
    val v = QuizStore.version
    val list by produceState(emptyList<Quiz>(), v) { value = QuizStore.all() }
    return list
}

// =====================================================================================
// Gemini: generate + grade
// =====================================================================================

/** Options chosen on the New-quiz screen. */
data class QuizOptions(
    val count: Int = 10,
    val types: Set<String> = setOf(QType.MCQ, QType.TF),
    /** "easy" | "medium" | "hard" | "mixed" */
    val difficulty: String = "medium",
    /** "auto" | "ar" | "en" */
    val language: String = "auto",
    val instructions: String = "",
)

/** User-presentable failure that is not a [Gemini.AiException] (e.g. the reply was not a usable quiz). */
class QuizException(val res: Int) : Exception()

internal object QuizAi {
    private val typeNames = mapOf(
        QType.MCQ to "multiple choice (4 options, usually exactly one correct; use several correct options only if the instructions ask for \"select all\")",
        QType.TF to "true/false",
        QType.SHORT to "short answer (a word, number or one or two sentences)",
        QType.ESSAY to "essay / long answer (explain, compare, prove, solve with steps)",
    )

    private fun str(desc: String? = null) = buildJsonObject { put("type", "STRING"); if (desc != null) put("description", desc) }

    /** Strict schema for the generated quiz. */
    private val quizSchema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            put("title", str("Short title of the quiz (max 60 characters), in the quiz language"))
            putJsonObject("questions") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("type") {
                            put("type", "STRING")
                            putJsonArray("enum") { QType.all.forEach { add(JsonPrimitive(it)) } }
                        }
                        put("question", str("The question text. Plain text; formulas in plain notation"))
                        putJsonObject("options") {
                            put("type", "ARRAY"); put("description", "mcq: the answer options (no letters/numbers in front). Empty for other types")
                            putJsonObject("items") { put("type", "STRING") }
                        }
                        putJsonObject("correct") {
                            put("type", "ARRAY"); put("description", "mcq: 0-based indices of the correct options. tf: [0] if the statement is true, [1] if false. Empty for short/essay")
                            putJsonObject("items") { put("type", "INTEGER") }
                        }
                        put("answer", str("short/essay: a model answer and the key points needed for full marks. mcq/tf: the correct answer in words"))
                        put("explanation", str("Why the correct answer is correct (and why common wrong answers are wrong), 1-3 sentences"))
                    }
                    putJsonArray("required") { listOf("type", "question", "options", "correct", "answer", "explanation").forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("title")); add(JsonPrimitive("questions")) }
    }

    private val gradeSchema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("results") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("index") { put("type", "INTEGER") }
                        putJsonObject("score") { put("type", "INTEGER"); put("description", "0-100") }
                        put("feedback", str("What was right, what is missing or wrong, 1-3 sentences, addressed to the student"))
                    }
                    putJsonArray("required") { add(JsonPrimitive("index")); add(JsonPrimitive("score")); add(JsonPrimitive("feedback")) }
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("results")) }
    }

    @Serializable private data class GenOut(val title: String = "", val questions: List<QuizQuestion> = emptyList())
    @Serializable private data class GradeItem(val index: Int = -1, val score: Int = 0, val feedback: String = "")
    @Serializable private data class GradeOut(val results: List<GradeItem> = emptyList())

    private fun languageLine(lang: String) = when (lang) {
        "ar" -> "Write the whole quiz (title, questions, options, answers, explanations) in Modern Standard Arabic."
        "en" -> "Write the whole quiz (title, questions, options, answers, explanations) in English."
        else -> "Write the quiz in the language the user's instructions are written in; if there are no instructions, use the main language of the source material (Arabic or English)."
    }

    private fun system(o: QuizOptions) = """
        You are an experienced teacher writing a practice quiz for a student from their own study material.
        Rules:
        - Base every question on the provided material (images and/or page text). Do not invent facts that are not supported by it; general knowledge needed to understand it is fine.
        - Write exactly ${o.count} questions unless the material is clearly too small, then as many good ones as it supports (at least 1).
        - Allowed question types: ${o.types.joinToString("; ") { typeNames[it] ?: it }}. Mix them sensibly unless the instructions say otherwise.
        - Difficulty: ${when (o.difficulty) { "easy" -> "easy (recall and basic understanding)"; "hard" -> "hard (application, analysis, multi-step problems, tricky distractors)"; "mixed" -> "mixed, from easy to hard"; else -> "medium (understanding and application)" }}.
        - Multiple-choice distractors must be plausible; only one option correct unless stated; never "all of the above" tricks.
        - True/false: a clear statement that is definitely true or definitely false according to the material.
        - ${languageLine(o.language)}
        - The user's own instructions below take priority over these defaults (count, topic, types, language), but always return the JSON format.
        - Plain text only (no Markdown); write math in plain notation such as x^2, sqrt(x), a/b.
    """.trimIndent()

    /** Calls Gemini and returns validated questions + a title. Throws [Gemini.AiException] / [QuizException]. */
    suspend fun generate(source: List<Gemini.Part>, o: QuizOptions): Pair<String, List<QuizQuestion>> {
        val ask = buildString {
            append("Make the practice quiz from the material above.")
            if (o.instructions.isNotBlank()) append("\n\nThe student's instructions: ").append(o.instructions.trim())
        }
        val reply = Gemini.generate(
            listOf(Gemini.Msg("user", source + Gemini.Part.text(ask))),
            system = system(o), jsonSchema = quizSchema, temperature = 0.6,
        )
        val out = runCatching { json.decodeFromString<GenOut>(cleanJson(reply.text)) }.getOrElse { throw QuizException(R.string.quiz_err_format) }
        val qs = out.questions.mapNotNull { validate(it) }
        if (qs.isEmpty()) throw QuizException(R.string.quiz_err_empty)
        return out.title.trim().take(80) to qs
    }

    /** Normalises a generated question, or null when it can't be used. */
    private fun validate(q: QuizQuestion): QuizQuestion? {
        val text = q.question.trim()
        if (text.isEmpty()) return null
        return when (q.type.lowercase()) {
            QType.MCQ -> {
                val opts = q.options.map { it.trim() }.filter { it.isNotEmpty() }
                val corr = q.correct.filter { it in opts.indices }.distinct().sorted()
                if (opts.size < 2 || corr.isEmpty() || opts.size != q.options.size) null
                else q.copy(type = QType.MCQ, question = text, options = opts, correct = corr)
            }
            QType.TF -> {
                val c = q.correct.firstOrNull()?.takeIf { it == 0 || it == 1 }
                    ?: when (q.answer.trim().lowercase()) { "true", "صح", "صحيح", "صواب" -> 0; "false", "خطأ", "خاطئ" -> 1; else -> null }
                    ?: return null
                q.copy(type = QType.TF, question = text, options = emptyList(), correct = listOf(c))
            }
            QType.SHORT, QType.ESSAY -> if (q.answer.isBlank()) null else q.copy(type = q.type.lowercase(), question = text, options = emptyList(), correct = emptyList())
            else -> null
        }
    }

    /**
     * Grades the short/essay answers at [indices] in one call. Returns index → (score 0..100, feedback).
     * Blank answers get 0 without asking Gemini.
     */
    suspend fun grade(quiz: Quiz, indices: List<Int>): Map<Int, Pair<Int, String>> {
        val ask = indices.filter { !quiz.answer(it).text.isBlank() }
        val out = HashMap<Int, Pair<Int, String>>()
        indices.filter { it !in ask }.forEach { out[it] = 0 to "" }
        if (ask.isEmpty()) return out
        val prompt = buildString {
            append("Grade these answers from a student's practice quiz.\n")
            ask.forEach { i ->
                val q = quiz.questions[i]
                append("\n### index ").append(i).append(" (").append(if (q.type == QType.ESSAY) "essay" else "short answer").append(")\n")
                append("Question: ").append(q.question).append('\n')
                append("Model answer / key points: ").append(q.answer).append('\n')
                append("Student's answer: ").append(quiz.answer(i).text.trim()).append('\n')
            }
        }
        val system = """
            You are a fair, encouraging teacher grading a practice quiz.
            - Give each answer a score from 0 to 100 against the model answer / key points: full marks for a correct answer in other words; partial credit for partly correct answers; 0 for wrong or empty answers.
            - Short answers: be strict about the core fact but ignore spelling, wording and formatting. Essays: grade on the key points, correctness and reasoning.
            - Feedback: 1-3 sentences, addressed to the student, saying what is right and what is missing or wrong. Write the feedback in the language of the question.
            - Return one result per index given, using the same index numbers. Plain text only.
        """.trimIndent()
        val reply = Gemini.generate(listOf(Gemini.Msg.user(Gemini.Part.text(prompt))), system = system, jsonSchema = gradeSchema, temperature = 0.2)
        val res = runCatching { json.decodeFromString<GradeOut>(cleanJson(reply.text)) }.getOrElse { throw QuizException(R.string.quiz_err_format) }
        res.results.forEach { r -> if (r.index in ask) out[r.index] = r.score.coerceIn(0, 100) to r.feedback.trim() }
        if (ask.any { it !in out }) throw QuizException(R.string.quiz_err_format)
        return out
    }

    private fun cleanJson(t: String): String {
        val s = t.trim()
        if (!s.startsWith("```")) return s
        return s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
}

/** Friendly, localized message for any failure of a Gemini call (never shows raw server text). */
fun quizErrorText(ctx: Context, e: Throwable): String = ctx.getString(
    when (e) {
        is Gemini.AiException -> when (e.kind) {
            Gemini.AiException.Kind.NO_KEY -> R.string.quiz_err_no_key
            Gemini.AiException.Kind.BAD_KEY -> R.string.quiz_err_bad_key
            Gemini.AiException.Kind.QUOTA -> R.string.quiz_err_quota
            Gemini.AiException.Kind.NETWORK -> R.string.quiz_err_network
            Gemini.AiException.Kind.BLOCKED -> R.string.quiz_err_blocked
            Gemini.AiException.Kind.SERVER -> R.string.quiz_err_server
        }
        is QuizException -> e.res
        is OutOfMemoryError -> R.string.quiz_err_memory
        else -> R.string.quiz_err_generic
    },
)

/** True when the failure is fixed in Settings → AI (show an "Open Settings" button). */
fun isKeyError(e: Throwable?) = e is Gemini.AiException && (e.kind == Gemini.AiException.Kind.NO_KEY || e.kind == Gemini.AiException.Kind.BAD_KEY)

