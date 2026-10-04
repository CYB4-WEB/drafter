package com.daftar.app.ui.workspace

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.daftar.app.Route
import com.daftar.app.data.Storage
import com.daftar.app.ui.Screen
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.screenFor
import java.io.File

private var entryIds = 0L

/** One screen in a pane / window back stack with a stable key for its saved UI state. */
class PaneEntry internal constructor(val screen: Screen) {
    val key: String = "e" + (++entryIds)
}

/**
 * A back stack of screens used by split panes and separate windows. Each entry keeps its saveable UI state
 * (scroll positions…) while it is covered and drops it when popped.
 */
class EntryStack(initial: Screen) {
    val entries = mutableStateListOf(PaneEntry(initial))
    internal val dropped = ArrayList<String>()

    val current: Screen get() = entries.last().screen
    val size: Int get() = entries.size
    val screens: List<Screen> get() = entries.map { it.screen }

    fun push(s: Screen) { entries.add(PaneEntry(s)) }
    fun pop(): Boolean {
        if (entries.size <= 1) return false
        dropped += entries.removeAt(entries.lastIndex).key
        return true
    }
    fun replace(s: Screen) { dropped += entries.last().key; entries[entries.lastIndex] = PaneEntry(s) }
    fun reset(s: Screen) { entries.forEach { dropped += it.key }; entries.clear(); entries.add(PaneEntry(s)) }
    fun popTo(s: Screen): Boolean {
        val i = entries.indexOfLast { it.screen == s }
        if (i < 0) return false
        while (entries.lastIndex > i) pop()
        return true
    }

    /** Opens [f] in its viewer on this stack (or hands it to another app when Daftar can't show it). */
    fun open(ctx: Context, f: File) {
        val s = screenFor(f)
        if (s == null) { openExternally(ctx, f); return }
        if (!f.isDirectory) Storage.opened(f)
        push(s)
    }
}

/** Shows the top screen of [stack], restoring its saved UI state when the user comes back to it. */
@Composable
fun EntryStackContent(stack: EntryStack) {
    val holder = rememberSaveableStateHolder()
    val top = stack.entries.last()
    SideEffect {
        if (stack.dropped.isNotEmpty()) {
            stack.dropped.forEach { if (it != top.key) holder.removeState(it) }
            stack.dropped.clear()
        }
    }
    holder.SaveableStateProvider(top.key) { Route(top.screen) }
}

/** Gives a pane its own back dispatcher so screen BackHandlers inside it only react when that pane is active. */
internal class PaneBackOwner(override val onBackPressedDispatcher: OnBackPressedDispatcher, private val owner: LifecycleOwner) : OnBackPressedDispatcherOwner {
    override val lifecycle: Lifecycle get() = owner.lifecycle
}
