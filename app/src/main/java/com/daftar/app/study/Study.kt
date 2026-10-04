package com.daftar.app.study

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import java.io.File

/** Study hub: flashcard decks per subject + focus (Pomodoro) timer + study stats. */
@Composable
fun StudyScreen() = StudyScreenImpl()

/** Spaced-repetition review of due cards ([deck] = subject folder path, null = all). */
@Composable
fun ReviewScreen(deck: String?) = ReviewScreenImpl(deck)

/**
 * Called by the note editor's lasso menu ("Make flashcard").
 * [front] = image of the lassoed content (the caller keeps ownership; it is not recycled here),
 * [recognizedText] = handwriting/typed text if available, [source] = the note/PDF file.
 * Shows a dialog to confirm the front, type/draw the back, pick the deck (defaults to the source's subject folder) and save.
 */
@Composable
fun MakeFlashcardDialog(source: File, front: Bitmap, recognizedText: String?, onDismiss: () -> Unit) =
    MakeFlashcardDialog(source, front, recognizedText, page = -1, onDismiss = onDismiss)

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
