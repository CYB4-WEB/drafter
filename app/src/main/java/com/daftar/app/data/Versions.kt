package com.daftar.app.data

import java.io.File

/**
 * STUB — owned by files-agent. Note version history.
 * The note editor calls [capture] right after it saves a note (off the main thread); files-agent decides retention
 * (e.g. one snapshot per 10 min of editing, keep last 30 / 30 days) and builds the "Version history" UI.
 */
object Versions {
    fun capture(f: File) {}
}
