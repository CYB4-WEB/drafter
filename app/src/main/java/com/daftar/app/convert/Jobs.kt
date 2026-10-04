package com.daftar.app.convert

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daftar.app.R
import com.daftar.app.data.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** One conversion run. All fields are Compose state, so the hub and the sheet can observe it. */
class ConvertJob internal constructor(
    val conv: Conv,
    val sources: List<Src>,
    val options: ConvOptions,
    val outDir: File,
) {
    sealed class State {
        data object Running : State()
        data class Done(val out: ConvOutput) : State()
        data class Failed(val message: String) : State()
        data object Cancelled : State()
    }

    val id: Long = nextId++
    val started: Long = System.currentTimeMillis()
    var state: State by mutableStateOf(State.Running)
        internal set
    var done by mutableIntStateOf(0)
        internal set
    var total by mutableIntStateOf(0)
        internal set
    internal var job: Job? = null

    val running: Boolean get() = state is State.Running

    /** 0..1, or null while the total is unknown. */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    fun cancel() {
        if (state !is State.Running) return
        state = State.Cancelled
        job?.cancel()
    }

    private companion object { var nextId = 1L }
}

/**
 * Runs conversions in an application-wide scope, so a job keeps going when the sheet or the hub is closed.
 * Keeps an in-memory list of recent runs (newest first) and toasts "Saved to …" when a run finishes.
 */
object ConvertJobs {
    private const val MAX_RECENT = 20
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val recent = mutableStateListOf<ConvertJob>()

    fun start(ctx: Context, conv: Conv, sources: List<Src>, options: ConvOptions, outDir: File): ConvertJob {
        val app = ctx.applicationContext
        val j = ConvertJob(conv, sources, options, outDir)
        recent.add(0, j)
        while (recent.size > MAX_RECENT) {
            val idx = recent.indexOfLast { !it.running }
            if (idx < 0) break
            recent.removeAt(idx)
        }
        j.job = scope.launch {
            try {
                val out = Engines.run(app, conv, sources, options, outDir) { d, t -> j.done = d; j.total = t }
                Storage.touch()
                if (j.state is ConvertJob.State.Running) {
                    j.state = ConvertJob.State.Done(out)
                    Toast.makeText(app, app.getString(R.string.saved_to, displayPath(app, out.folder)), Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                j.state = ConvertJob.State.Cancelled
                Storage.touch()
            } catch (e: ConvertException) {
                j.state = ConvertJob.State.Failed(runCatching { app.getString(e.msg, *e.args) }.getOrDefault(app.getString(R.string.convert_err_failed)))
                Storage.touch()
            } catch (t: Throwable) {
                Log.e("Convert", "job failed", t)
                j.state = ConvertJob.State.Failed(app.getString(R.string.convert_err_failed))
                Storage.touch()
            }
        }
        return j
    }

    fun remove(j: ConvertJob) {
        if (!j.running) recent.remove(j)
    }

    fun clearFinished() {
        recent.removeAll { !it.running }
    }
}

/** "Files › Math › Lecture 3" — a library folder as the student sees it. */
fun displayPath(ctx: Context, dir: File): String {
    val crumbs = Storage.crumbs(dir)
    if (crumbs.isEmpty()) return dir.name
    return crumbs.joinToString(" › ") { if (Storage.isRoot(it)) ctx.getString(R.string.files) else it.name }
}

/** Human readable size: "820 KB", "12.4 MB". */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), if (mb < 10) "%.1f MB" else "%.0f MB", mb)
    return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
}
