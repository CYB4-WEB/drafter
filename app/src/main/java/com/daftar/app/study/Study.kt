package com.daftar.app.study

import androidx.compose.runtime.Composable
import java.io.File

/** Study hub: AI practice quizzes + focus (Pomodoro) timer + focus stats. */
@Composable
fun StudyScreen() = StudyScreenImpl()

/** Create an AI practice quiz (prefilled by [QuizRequests.prepare]). */
@Composable
fun NewQuizScreen() = NewQuizScreenImpl()

/** Take / review the saved quiz [id]. */
@Composable
fun QuizScreen(id: String) = QuizScreenImpl(id)

/**
 * "Open in note" at a page: [requestPage] is set right before the source opens; the note/PDF viewer calls
 * [consumePage] once it has loaded that file and scrolls to the returned page (0-based) when non-null.
 */
object StudyLinks {
    private var pending: Pair<String, Int>? = null

    fun requestPage(f: File, page: Int) { pending = f.absolutePath to page }

    fun consumePage(f: File): Int? {
        val p = pending ?: return null
        if (p.first != f.absolutePath) return null
        pending = null
        return p.second
    }
}

