package com.daftar.app.convert

import androidx.compose.runtime.Composable
import java.io.File

/** STUB — owned by convert-agent. Converter hub screen (top-level tab). [path] optionally preselects a source file. */
@Composable
fun ConvertScreen(path: String?) {}

/** STUB — owned by convert-agent. Bottom sheet listing every conversion available for [file]; viewers show it from their "Convert" action. */
@Composable
fun ConvertSheet(file: File, onDismiss: () -> Unit) {}
