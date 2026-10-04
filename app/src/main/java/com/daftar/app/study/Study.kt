package com.daftar.app.study

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import java.io.File

/** STUB — owned by study-agent. Study hub: flashcard decks per subject + focus (Pomodoro) timer + study stats. */
@Composable
fun StudyScreen() {}

/** STUB — owned by study-agent. Spaced-repetition review of due cards ([deck] = subject folder path, null = all). */
@Composable
fun ReviewScreen(deck: String?) {}

/**
 * STUB — owned by study-agent; called by the note editor's lasso menu ("Make flashcard").
 * [front] = image of the lassoed content, [recognizedText] = handwriting/typed text if available, [source] = the note/PDF file.
 * Shows a dialog to confirm the front, type/draw the back, pick the deck (defaults to the source's subject folder) and save.
 */
@Composable
fun MakeFlashcardDialog(source: File, front: Bitmap, recognizedText: String?, onDismiss: () -> Unit) {}
