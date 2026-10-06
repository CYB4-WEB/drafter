package com.daftar.app.study

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daftar.app.ai.Gemini
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The New-quiz draft + the generation job. Lives in the process (not in a composable) so it survives rotation,
 * pane moves and split-screen changes; the New-quiz screen only observes it.
 */
object QuizRequests {
    enum class Stage { IDLE, READING, WRITING, SAVING }

    // ---------------- draft ----------------
    /** Library files / folders to build the quiz from (in the order shown). */
    val sources = mutableStateListOf<QuizSource>()
    /** The lasso selection as a JPEG part (not a Bitmap: nothing large stays in RAM). */
    var image by mutableStateOf<Gemini.Part.Blob?>(null)
        private set
    var imageLoading by mutableStateOf(false)
        private set
    var options by mutableStateOf(QuizOptions())
    /** Bumped by [prepare] so the screen can reset its local UI state. */
    var draftVersion by mutableIntStateOf(0)
        private set

    // ---------------- job ----------------
    var stage by mutableStateOf(Stage.IDLE)
        private set
    /** While [Stage.READING]: file n of total and its name. */
    var readIndex by mutableIntStateOf(0)
        private set
    var readTotal by mutableIntStateOf(0)
        private set
    var readName by mutableStateOf("")
        private set
    var error by mutableStateOf<Throwable?>(null)
    /** Id of the quiz just created; the screen opens it and clears this. */
    var done by mutableStateOf<String?>(null)
    val busy get() = stage != Stage.IDLE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var imageJob: Job? = null

    /**
     * Prefills the New-quiz screen; the caller then does `pane.push(Screen.NewQuiz)`.
     * [pages] is 0-based inclusive (null = the whole file). [image] stays owned by the caller (a copy is encoded
     * in the background, so the caller may recycle it right after this returns).
     */
    fun prepare(file: File?, pages: IntRange?, image: Bitmap?, instructions: String = "") {
        cancel()
        imageJob?.cancel()
        error = null
        done = null
        sources.clear()
        if (file != null) {
            val a = pages?.first?.coerceAtLeast(0) ?: 0
            sources += QuizSource(file, folder = file.isDirectory, whole = pages == null || file.isDirectory, from = a, to = pages?.last?.coerceAtLeast(a) ?: 0)
        }
        this.image = null
        options = options.copy(instructions = instructions)
        draftVersion++
        if (image != null && !image.isRecycled) {
            val copy = runCatching { image.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
            imageLoading = true
            imageJob = scope.launch {
                try { this@QuizRequests.image = withContext(Dispatchers.Default) { Gemini.Part.image(copy, maxSide = 1600) } }
                catch (_: Throwable) { }
                finally { copy.recycle(); imageLoading = false }
            }
        } else imageLoading = false
    }

    fun clearImage() { imageJob?.cancel(); image = null; imageLoading = false }

    val hasSource get() = sources.isNotEmpty() || image != null

    /** Adds files / folders that are not in the list yet. */
    fun add(files: List<File>, folder: Boolean) {
        files.forEach { f -> if (sources.none { it.file.absolutePath == f.absolutePath }) sources += QuizSource(f, folder = folder) }
    }

    fun update(i: Int, s: QuizSource) { if (i in sources.indices) sources[i] = s }

    /** Starts generating (no-op while running). Result: [done] = new quiz id, or [error]. */
    fun generate(fallbackTitle: String) {
        if (job?.isActive == true) return
        error = null
        done = null
        val o = options
        val srcs = sources.toList()
        val img = image
        job = scope.launch {
            try {
                val parts = ArrayList<Gemini.Part>()
                if (img != null) {
                    parts += Gemini.Part.text("A part of the student's notes they selected (image):")
                    parts += img
                }
                var infos: List<QuizSourceInfo> = emptyList()
                if (srcs.isNotEmpty()) {
                    stage = Stage.READING
                    readIndex = 0; readTotal = 0; readName = ""
                    val built = QuizContext.build(srcs) { i, n, name -> readIndex = i; readTotal = n; readName = name }
                    parts += built.parts
                    infos = built.infos
                }
                if (parts.isEmpty()) {
                    if (o.instructions.isBlank()) throw QuizException(com.daftar.app.R.string.quiz_err_no_source)
                    parts += Gemini.Part.text("No study material is attached: write the quiz on the topic described in the instructions, using reliable, well-established knowledge.")
                }
                stage = Stage.WRITING
                val (title, qs) = QuizAi.generate(parts, o)
                stage = Stage.SAVING
                val now = System.currentTimeMillis()
                val quiz = Quiz(
                    id = QuizStore.newId(),
                    title = title.ifBlank { srcs.singleOrNull()?.file?.nameWithoutExtension ?: fallbackTitle },
                    created = now, updated = now,
                    // single-source fields kept for older readers; [sources] holds everything
                    sourcePath = infos.singleOrNull()?.takeIf { !it.folder }?.path ?: "",
                    sourceName = infos.firstOrNull()?.name ?: "", pages = infos.singleOrNull()?.pages ?: "",
                    sources = infos,
                    fromSelection = img != null, difficulty = o.difficulty, language = o.language, instructions = o.instructions.trim(),
                    questions = qs, answers = qs.map { QuizAnswer() },
                )
                QuizStore.save(quiz)
                done = quiz.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
            } finally {
                if (job === coroutineContext[Job]) stage = Stage.IDLE
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        stage = Stage.IDLE
    }
}
